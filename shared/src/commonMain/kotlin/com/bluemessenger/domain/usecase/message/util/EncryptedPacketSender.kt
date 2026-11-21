package com.bluemessenger.domain.usecase.message.util

import com.bluemessenger.domain.constants.SessionConstants
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.ports.crypto.CryptoService
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.ports.transport.model.EncryptedPayloadPacket
import com.bluemessenger.domain.primitives.PeerId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Concrete outgoing encrypted-packet transmitter shared by live messaging and pending-queue
 * flushing so [EncryptedPayloadPacket.sequenceNumber] values remain strictly monotonic and
 * non-colliding within a session.
 *
 * Thread safety: Serializes sequence allocation via a [Mutex].
 *
 * Failure behavior: Propagates encryption or transport send failures fail-fast via [Result.failure].
 */
class EncryptedPacketSender(
    private val cryptoService: CryptoService,
    private val bluetoothTransport: BluetoothTransport,
) {
    private val sequenceMutex: Mutex = Mutex()
    private var nextSequenceNumber: Long = SessionConstants.INITIAL_OUTGOING_SEQUENCE

    /**
     * Encrypts [message] for [peerId] using the next monotonic sequence number as AAD and sends
     * the resulting [EncryptedPayloadPacket] over [BluetoothTransport].
     */
    suspend fun encryptAndSend(
        peerId: PeerId,
        message: Message,
    ): Result<Unit> {
        val sequenceNumber = sequenceMutex.withLock { nextSequenceNumber++ }
        val payloadBytes = MessagePayloadCodec.encode(message.id, message.sentAt, message.plaintext)
        val aad = MessagePayloadCodec.encodeSequenceNumberAad(sequenceNumber)
        return cryptoService.encrypt(peerId, payloadBytes, associatedData = aad).fold(
            onSuccess = { ciphertext ->
                bluetoothTransport.send(
                    EncryptedPayloadPacket(
                        sequenceNumber = sequenceNumber,
                        salt = ByteArray(0),
                        ciphertext = ciphertext,
                    ),
                )
            },
            onFailure = { Result.failure(it) },
        )
    }
}

internal fun BluetoothTransport.hasOpenTransportLink(): Boolean {
    val state = sessionState.value
    return state == SessionState.SECURE_ACTIVE || state == SessionState.HANDSHAKING
}
