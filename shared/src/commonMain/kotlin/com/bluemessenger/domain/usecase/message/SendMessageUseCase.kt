package com.bluemessenger.domain.usecase.message

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.constants.SessionConstants
import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.InvalidInputError
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.storage.MessageRepository
import com.bluemessenger.domain.ports.storage.PeerRepository
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.usecase.message.util.EncryptedPacketSender
import com.bluemessenger.domain.usecase.message.util.hasOpenTransportLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Persists, encrypts, and transmits outgoing text messages, queueing them as `PENDING` when the
 * peer's session or transport link is offline and scheduling optional ACK delivery timeout checks.
 *
 * Thread safety: Delegates sequence allocation and encryption to the shared thread-safe
 * [EncryptedPacketSender] and executes storage and transport writes on [CoroutineDispatchers.io].
 *
 * Failure behavior: Fails fast with [InvalidInputError] for blank plaintext. When both the peer's
 * persisted conversation is `SECURE_ACTIVE` and the transport link is open, encryption or
 * transport errors mark the persisted message `FAILED` and return [Result.failure].
 */
@Suppress("LongParameterList")
class SendMessageUseCase(
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val bluetoothTransport: BluetoothTransport,
    private val packetSender: EncryptedPacketSender,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val clock: () -> Instant,
) {
    /**
     * Sends [plaintext] in [conversationId] to [peerId], or queues it as `PENDING` if offline.
     *
     * When [ackTimeoutScope] is provided and the message transitions to `SENT`, schedules an
     * asynchronous check after [SessionConstants.ACK_TIMEOUT_MS] that marks unacknowledged `SENT`
     * messages as `FAILED`.
     */
    suspend operator fun invoke(
        peerId: PeerId,
        conversationId: ConversationId,
        plaintext: String,
        ackTimeoutScope: CoroutineScope? = null,
    ): Result<Message> =
        withContext(dispatchers.io) {
            if (plaintext.isBlank()) {
                logger.warn(
                    tag = TAG,
                    message = "Rejected outgoing message with blank plaintext.",
                    context = mapOf("conversationId" to conversationId.toString()),
                )
                return@withContext InvalidInputError("Message plaintext must not be blank.").asFailure()
            }

            val message =
                persistPendingMessage(conversationId, plaintext).getOrElse {
                    return@withContext Result.failure(it)
                }

            deliverOrQueue(peerId, message, ackTimeoutScope)
        }

    /**
     * Retries a `FAILED` outgoing message identified by [messageId], transitioning it back to
     * `PENDING` while preserving its [MessageId] for end-to-end idempotency, and transmitting it
     * immediately if [peerId] has an active session.
     */
    suspend fun retryFailedMessage(
        peerId: PeerId,
        messageId: MessageId,
        ackTimeoutScope: CoroutineScope? = null,
    ): Result<Message> =
        withContext(dispatchers.io) {
            val existing =
                messageRepository.getById(messageId).getOrElse { error ->
                    logger.error(
                        tag = TAG,
                        message = "Failed to load message for retry.",
                        throwable = error,
                        context = mapOf("messageId" to messageId.toString()),
                    )
                    return@withContext Result.failure(error)
                } ?: return@withContext StorageError("Message $messageId was not found.").asFailure()

            if (existing.direction != MessageDirection.OUTGOING || existing.status != MessageStatus.FAILED) {
                logger.warn(
                    tag = TAG,
                    message = "Rejected retry for message in invalid direction or status.",
                    context =
                        mapOf(
                            "messageId" to messageId.toString(),
                            "direction" to existing.direction.name,
                            "status" to existing.status.name,
                        ),
                )
                return@withContext InvalidStateTransitionError(
                    entity = ENTITY_MESSAGE,
                    fromState = existing.status.name,
                    toState = MessageStatus.PENDING.name,
                    message = "Only FAILED outgoing messages can be retried.",
                ).asFailure()
            }

            verifyConversationOwnership(peerId, existing).onFailure {
                return@withContext Result.failure(it)
            }

            messageRepository.updateStatus(existing.id, MessageStatus.PENDING).getOrElse { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to transition FAILED message back to PENDING.",
                    throwable = error,
                    context = mapOf("messageId" to messageId.toString()),
                )
                return@withContext Result.failure(error)
            }

            val pendingMessage = existing.copy(status = MessageStatus.PENDING, deliveredAt = null)
            deliverOrQueue(peerId, pendingMessage, ackTimeoutScope)
        }

    private suspend fun verifyConversationOwnership(
        peerId: PeerId,
        message: Message,
    ): Result<Unit> =
        peerRepository.getConversationByPeer(peerId).fold(
            onSuccess = { conversation ->
                if (conversation?.id == message.conversationId) {
                    Result.success(Unit)
                } else {
                    logger.warn(
                        tag = TAG,
                        message = "Rejected retry for message belonging to a different peer conversation.",
                        context =
                            mapOf(
                                "peerId" to peerId.toString(),
                                "messageId" to message.id.toString(),
                                "expectedConversationId" to message.conversationId.toString(),
                                "actualConversationId" to (conversation?.id?.toString() ?: STATE_NONE),
                            ),
                    )
                    InvalidStateTransitionError(
                        entity = ENTITY_MESSAGE,
                        fromState = message.status.name,
                        toState = MessageStatus.PENDING.name,
                        message = "Message ${message.id} does not belong to peer $peerId.",
                    ).asFailure()
                }
            },
            onFailure = { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to verify peer conversation ownership before message retry.",
                    throwable = error,
                    context =
                        mapOf(
                            "peerId" to peerId.toString(),
                            "messageId" to message.id.toString(),
                        ),
                )
                Result.failure(error)
            },
        )

    private suspend fun deliverOrQueue(
        peerId: PeerId,
        message: Message,
        ackTimeoutScope: CoroutineScope?,
    ): Result<Message> =
        isPeerSessionActive(peerId, message.conversationId).fold(
            onSuccess = { isPeerSessionActive ->
                if (!isPeerSessionActive) {
                    logger.info(
                        tag = TAG,
                        message = "Queued outgoing message as PENDING while peer session is inactive.",
                        context =
                            mapOf(
                                "messageId" to message.id.toString(),
                                "conversationId" to message.conversationId.toString(),
                                "transportState" to bluetoothTransport.sessionState.value.name,
                            ),
                    )
                    Result.success(message)
                } else {
                    transmitAndScheduleAckTimeout(peerId, message, ackTimeoutScope)
                }
            },
            onFailure = { Result.failure(it) },
        )

    private suspend fun persistPendingMessage(
        conversationId: ConversationId,
        plaintext: String,
    ): Result<Message> {
        val now = clock()
        val message =
            Message(
                id = MessageId.generate(now),
                conversationId = conversationId,
                plaintext = plaintext,
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.PENDING,
                sentAt = now,
            )
        return messageRepository.saveIdempotent(message)
            .onFailure { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to persist outgoing message.",
                    throwable = error,
                    context =
                        mapOf(
                            "messageId" to message.id.toString(),
                            "conversationId" to conversationId.toString(),
                        ),
                )
            }.map { message }
    }

    private suspend fun isPeerSessionActive(
        peerId: PeerId,
        conversationId: ConversationId,
    ): Result<Boolean> =
        peerRepository.getConversationByPeer(peerId)
            .onFailure { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to load peer conversation state before sending message.",
                    throwable = error,
                    context =
                        mapOf(
                            "peerId" to peerId.toString(),
                            "conversationId" to conversationId.toString(),
                        ),
                )
            }.map { conversation ->
                conversation != null &&
                    conversation.id == conversationId &&
                    conversation.state == SessionState.SECURE_ACTIVE &&
                    bluetoothTransport.hasOpenTransportLink()
            }

    private suspend fun transmitAndScheduleAckTimeout(
        peerId: PeerId,
        message: Message,
        ackTimeoutScope: CoroutineScope?,
    ): Result<Message> =
        packetSender.encryptAndSend(peerId, message).fold(
            onSuccess = {
                markSentAndScheduleAckTimeout(message, ackTimeoutScope)
            },
            onFailure = { error ->
                logger.warn(
                    tag = TAG,
                    message = "Failed to encrypt or send outgoing message; marking FAILED.",
                    throwable = error,
                    context =
                        mapOf(
                            "messageId" to message.id.toString(),
                            "conversationId" to message.conversationId.toString(),
                        ),
                )
                messageRepository.updateStatus(message.id, MessageStatus.FAILED).onFailure { updateError ->
                    logger.error(
                        tag = TAG,
                        message = "Failed to update outgoing message status to FAILED.",
                        throwable = updateError,
                        context = mapOf("messageId" to message.id.toString()),
                    )
                }
                Result.failure(error)
            },
        )

    private suspend fun markSentAndScheduleAckTimeout(
        message: Message,
        ackTimeoutScope: CoroutineScope?,
    ): Result<Message> =
        messageRepository.updateStatus(message.id, MessageStatus.SENT).fold(
            onSuccess = {
                val sentMessage = message.copy(status = MessageStatus.SENT)
                if (ackTimeoutScope != null) {
                    scheduleAckTimeout(ackTimeoutScope, sentMessage)
                }
                Result.success(sentMessage)
            },
            onFailure = { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to transition transmitted message to SENT.",
                    throwable = error,
                    context = mapOf("messageId" to message.id.toString()),
                )
                Result.failure(error)
            },
        )

    private fun scheduleAckTimeout(
        scope: CoroutineScope,
        message: Message,
    ) {
        scope.launch(dispatchers.default) {
            delay(SessionConstants.ACK_TIMEOUT_MS)
            messageRepository.getById(message.id)
                .onSuccess { current ->
                    if (current != null && current.status == MessageStatus.SENT) {
                        logger.warn(
                            tag = TAG,
                            message = "Delivery ACK timed out; marking message FAILED.",
                            context =
                                mapOf(
                                    "messageId" to message.id.toString(),
                                    "conversationId" to message.conversationId.toString(),
                                ),
                        )
                        messageRepository.updateStatus(message.id, MessageStatus.FAILED)
                            .onFailure { updateError ->
                                logger.error(
                                    tag = TAG,
                                    message = "Failed to mark timed-out message as FAILED.",
                                    throwable = updateError,
                                    context = mapOf("messageId" to message.id.toString()),
                                )
                            }
                    }
                }.onFailure { lookupError ->
                    logger.error(
                        tag = TAG,
                        message = "Failed to verify message status on ACK timeout.",
                        throwable = lookupError,
                        context = mapOf("messageId" to message.id.toString()),
                    )
                }
        }
    }

    private companion object {
        private const val TAG: String = "SendMessageUseCase"
        private const val ENTITY_MESSAGE: String = "Message"
        private const val STATE_NONE: String = "NONE"
    }
}
