package com.bluemessenger.domain.usecase.message

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.constants.SessionConstants
import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.InvalidInputError
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

            val isPeerSessionActive =
                isPeerSessionActive(peerId, conversationId).getOrElse {
                    return@withContext Result.failure(it)
                }
            if (!isPeerSessionActive) {
                logger.info(
                    tag = TAG,
                    message = "Queued outgoing message as PENDING while peer session is inactive.",
                    context =
                        mapOf(
                            "messageId" to message.id.toString(),
                            "conversationId" to conversationId.toString(),
                            "transportState" to bluetoothTransport.sessionState.value.name,
                        ),
                )
                return@withContext Result.success(message)
            }

            transmitAndScheduleAckTimeout(peerId, message, ackTimeoutScope)
        }

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
                conversation?.state == SessionState.SECURE_ACTIVE && bluetoothTransport.hasOpenTransportLink()
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
    }
}
