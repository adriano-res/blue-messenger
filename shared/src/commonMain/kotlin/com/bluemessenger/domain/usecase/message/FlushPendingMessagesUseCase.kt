package com.bluemessenger.domain.usecase.message

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.storage.MessageRepository
import com.bluemessenger.domain.ports.storage.PeerRepository
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.usecase.message.util.EncryptedPacketSender
import com.bluemessenger.domain.usecase.message.util.hasOpenTransportLink
import kotlinx.coroutines.withContext

/**
 * Sequentially encrypts and transmits queued `PENDING` outgoing messages once a peer's session
 * reaches [SessionState.SECURE_ACTIVE] over an open transport link.
 *
 * Thread safety: Shares [EncryptedPacketSender] for monotonic sequence allocation and executes
 * flush operations on [CoroutineDispatchers.io].
 *
 * Failure behavior: Fails fast with [TransportDisconnectedError] if the peer's conversation is not
 * `SECURE_ACTIVE` or the transport link is closed. Stops on the first encryption or transport
 * failure, leaving unsent messages in `PENDING` status for a subsequent flush.
 */
class FlushPendingMessagesUseCase(
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val bluetoothTransport: BluetoothTransport,
    private val packetSender: EncryptedPacketSender,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
) {
    /**
     * Flushes all `PENDING` outgoing messages in [conversationId] to [peerId] in chronological order,
     * returning the count of messages transitioned to `SENT`.
     */
    suspend operator fun invoke(
        peerId: PeerId,
        conversationId: ConversationId,
    ): Result<Int> =
        withContext(dispatchers.io) {
            val conversation =
                peerRepository.getConversationByPeer(peerId).getOrElse { error ->
                    logger.error(
                        tag = TAG,
                        message = "Failed to load peer conversation state before flushing pending messages.",
                        throwable = error,
                        context =
                            mapOf(
                                "peerId" to peerId.toString(),
                                "conversationId" to conversationId.toString(),
                            ),
                    )
                    return@withContext Result.failure(error)
                }

            if (conversation?.state != SessionState.SECURE_ACTIVE || !bluetoothTransport.hasOpenTransportLink()) {
                logger.warn(
                    tag = TAG,
                    message = "Cannot flush pending messages while peer session is not SECURE_ACTIVE.",
                    context =
                        mapOf(
                            "peerId" to peerId.toString(),
                            "conversationId" to conversationId.toString(),
                            "peerState" to (conversation?.state?.name ?: STATE_NONE),
                            "transportState" to bluetoothTransport.sessionState.value.name,
                        ),
                )
                return@withContext TransportDisconnectedError(
                    "Cannot flush pending messages when peer session is ${conversation?.state ?: STATE_NONE} " +
                        "and transport is ${bluetoothTransport.sessionState.value}.",
                ).asFailure()
            }

            val candidates =
                messageRepository.getPendingOrFailedOutgoing(conversationId)
                    .getOrElse { error ->
                        logger.error(
                            tag = TAG,
                            message = "Failed to load pending messages for flush.",
                            throwable = error,
                            context = mapOf("conversationId" to conversationId.toString()),
                        )
                        return@withContext Result.failure(error)
                    }

            val pendingMessages =
                candidates
                    .filter { it.status == MessageStatus.PENDING }
                    .sortedBy { it.sentAt }

            flushSequentially(peerId, conversationId, pendingMessages)
        }

    private suspend fun flushSequentially(
        peerId: PeerId,
        conversationId: ConversationId,
        pendingMessages: List<Message>,
    ): Result<Int> {
        var sentCount = 0
        for (message in pendingMessages) {
            val sendOutcome = sendSinglePendingMessage(peerId, message)
            sendOutcome.onFailure { error ->
                logger.warn(
                    tag = TAG,
                    message = "Stopped pending message flush after transmission failure.",
                    throwable = error,
                    context =
                        mapOf(
                            "peerId" to peerId.toString(),
                            "conversationId" to conversationId.toString(),
                            "messageId" to message.id.toString(),
                            "flushedCount" to sentCount.toString(),
                        ),
                )
                return Result.failure(error)
            }
            sentCount += 1
        }
        logger.info(
            tag = TAG,
            message = "Completed pending message flush.",
            context =
                mapOf(
                    "peerId" to peerId.toString(),
                    "conversationId" to conversationId.toString(),
                    "flushedCount" to sentCount.toString(),
                ),
        )
        return Result.success(sentCount)
    }

    private suspend fun sendSinglePendingMessage(
        peerId: PeerId,
        message: Message,
    ): Result<Unit> =
        packetSender.encryptAndSend(peerId, message).fold(
            onSuccess = { messageRepository.updateStatus(message.id, MessageStatus.SENT) },
            onFailure = { Result.failure(it) },
        )

    private companion object {
        private const val TAG: String = "FlushPendingMessagesUseCase"
        private const val STATE_NONE: String = "NONE"
    }
}
