package com.bluemessenger.domain.usecase.message

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.ports.crypto.CryptoService
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.storage.MessageRepository
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.ports.transport.model.AckPacket
import com.bluemessenger.domain.ports.transport.model.EncryptedPayloadPacket
import com.bluemessenger.domain.ports.transport.model.HandshakePacket
import com.bluemessenger.domain.ports.transport.model.KeepAlivePacket
import com.bluemessenger.domain.ports.transport.model.Packet
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.usecase.message.util.DecodedMessagePayload
import com.bluemessenger.domain.usecase.message.util.MessagePayloadCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Observes decoded incoming [Packet] values from [BluetoothTransport.incomingPackets] on a managed
 * [CoroutineScope], dispatching encrypted chat messages, delivery ACKs, and Keep-Alive
 * `PING`/`PONG` packets.
 *
 * Thread safety: Collects [BluetoothTransport.incomingPackets] on [CoroutineDispatchers.io] and
 * preserves structured concurrency without using `GlobalScope`.
 *
 * Failure behavior: Drops unauthenticated or corrupted message payloads after structured logging so
 * a single bad packet does not terminate the stream, logs stream exceptions via `.catch` while
 * always rethrowing [CancellationException], and enforces message idempotency by re-sending ACKs
 * for duplicates without duplicating database rows.
 */
class ObserveIncomingFramesUseCase(
    private val bluetoothTransport: BluetoothTransport,
    private val cryptoService: CryptoService,
    private val messageRepository: MessageRepository,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val clock: () -> Instant,
) {
    /**
     * Starts collecting incoming packets for [peerId] and [conversationId] inside [scope].
     */
    operator fun invoke(
        scope: CoroutineScope,
        peerId: PeerId,
        conversationId: ConversationId,
        onPongReceived: suspend (Long) -> Unit = {},
    ): Job =
        scope.launch(dispatchers.io) {
            bluetoothTransport.incomingPackets
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    logger.error(
                        tag = TAG,
                        message = "Incoming Bluetooth packet stream failed.",
                        throwable = cause,
                        context =
                            mapOf(
                                "peerId" to peerId.toString(),
                                "conversationId" to conversationId.toString(),
                            ),
                    )
                }.collect { packet ->
                    dispatchPacket(packet, peerId, conversationId, onPongReceived)
                }
        }

    private suspend fun dispatchPacket(
        packet: Packet,
        peerId: PeerId,
        conversationId: ConversationId,
        onPongReceived: suspend (Long) -> Unit,
    ) {
        when (packet) {
            is EncryptedPayloadPacket ->
                handleEncryptedPayload(packet, peerId, conversationId)
            is AckPacket ->
                handleAck(packet, conversationId)
            is KeepAlivePacket.Ping ->
                handlePing(packet, peerId)
            is KeepAlivePacket.Pong ->
                onPongReceived(packet.timestamp)
            is HandshakePacket ->
                logger.debug(
                    tag = TAG,
                    message = "Ignored handshake packet while observing active session.",
                    context =
                        mapOf(
                            "peerId" to peerId.toString(),
                            "step" to packet.step.toString(),
                        ),
                )
        }
    }

    private suspend fun handleEncryptedPayload(
        packet: EncryptedPayloadPacket,
        peerId: PeerId,
        conversationId: ConversationId,
    ) {
        val aad = MessagePayloadCodec.encodeSequenceNumberAad(packet.sequenceNumber)
        val decrypted =
            cryptoService.decrypt(peerId, packet.ciphertext, associatedData = aad)
                .getOrElse { error ->
                    logger.error(
                        tag = TAG,
                        message = "Failed to decrypt or authenticate incoming message packet.",
                        throwable = error,
                        context =
                            mapOf(
                                "peerId" to peerId.toString(),
                                "sequenceNumber" to packet.sequenceNumber.toString(),
                            ),
                    )
                    return
                }

        val payload =
            MessagePayloadCodec.decode(decrypted).getOrElse { error ->
                logger.warn(
                    tag = TAG,
                    message = "Dropped corrupted decrypted message payload.",
                    throwable = error,
                    context = mapOf("peerId" to peerId.toString()),
                )
                return
            }

        persistAndAckIncomingMessage(payload, peerId, conversationId)
    }

    private suspend fun persistAndAckIncomingMessage(
        payload: DecodedMessagePayload,
        peerId: PeerId,
        conversationId: ConversationId,
    ) {
        val now = clock()
        val incomingMessage =
            Message(
                id = payload.messageId,
                conversationId = conversationId,
                plaintext = payload.plaintext,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                sentAt = payload.sentAt,
                deliveredAt = maxOf(now, payload.sentAt),
            )

        messageRepository.saveIdempotent(incomingMessage).fold(
            onSuccess = { inserted ->
                if (!inserted) {
                    logger.debug(
                        tag = TAG,
                        message = "Duplicate incoming message received; re-sending ACK.",
                        context = mapOf("messageId" to payload.messageId.toString()),
                    )
                }
                sendAck(payload.messageId, peerId)
            },
            onFailure = { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to persist incoming message; skipping ACK.",
                    throwable = error,
                    context =
                        mapOf(
                            "messageId" to payload.messageId.toString(),
                            "conversationId" to conversationId.toString(),
                        ),
                )
            },
        )
    }

    private suspend fun sendAck(
        messageId: MessageId,
        peerId: PeerId,
    ) {
        bluetoothTransport.send(AckPacket(messageId)).onFailure { error ->
            logger.warn(
                tag = TAG,
                message = "Failed to transmit delivery ACK packet.",
                throwable = error,
                context =
                    mapOf(
                        "peerId" to peerId.toString(),
                        "messageId" to messageId.toString(),
                    ),
            )
        }
    }

    private suspend fun handleAck(
        packet: AckPacket,
        conversationId: ConversationId,
    ) {
        val message =
            messageRepository.getById(packet.messageId).getOrElse { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to load message for incoming ACK.",
                    throwable = error,
                    context = mapOf("messageId" to packet.messageId.toString()),
                )
                return
            }
        when {
            message == null ->
                logger.warn(
                    tag = TAG,
                    message = "Received ACK for unknown message.",
                    context = mapOf("messageId" to packet.messageId.toString()),
                )
            message.conversationId != conversationId || message.direction != MessageDirection.OUTGOING ->
                logger.warn(
                    tag = TAG,
                    message = "Ignored ACK for message outside active conversation or direction.",
                    context =
                        mapOf(
                            "messageId" to message.id.toString(),
                            "expectedConversationId" to conversationId.toString(),
                            "actualConversationId" to message.conversationId.toString(),
                            "direction" to message.direction.name,
                        ),
                )
            message.status == MessageStatus.DELIVERED ->
                logger.debug(
                    tag = TAG,
                    message = "Ignored duplicate ACK for already delivered message.",
                    context = mapOf("messageId" to packet.messageId.toString()),
                )
            message.status == MessageStatus.SENT -> markMessageDelivered(message)
            else ->
                logger.warn(
                    tag = TAG,
                    message = "Ignored ACK for message in unexpected status.",
                    context =
                        mapOf(
                            "messageId" to message.id.toString(),
                            "status" to message.status.name,
                        ),
                )
        }
    }

    private suspend fun markMessageDelivered(message: Message) {
        val deliveredAt = maxOf(clock(), message.sentAt)
        messageRepository.updateStatus(message.id, MessageStatus.DELIVERED, deliveredAt = deliveredAt)
            .onFailure { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to mark acknowledged message as DELIVERED.",
                    throwable = error,
                    context = mapOf("messageId" to message.id.toString()),
                )
            }
    }

    private suspend fun handlePing(
        packet: KeepAlivePacket.Ping,
        peerId: PeerId,
    ) {
        bluetoothTransport.send(KeepAlivePacket.Pong(packet.timestamp)).onFailure { error ->
            logger.warn(
                tag = TAG,
                message = "Failed to transmit Keep-Alive PONG reply.",
                throwable = error,
                context =
                    mapOf(
                        "peerId" to peerId.toString(),
                        "timestamp" to packet.timestamp.toString(),
                    ),
            )
        }
    }

    private companion object {
        private const val TAG: String = "ObserveIncomingFramesUseCase"
    }
}
