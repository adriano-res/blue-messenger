package com.bluemessenger.domain.ports.transport.model

import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.primitives.MessageId

/**
 * Closed hierarchy of immutable application-level wire packets exchanged over [BluetoothTransport].
 *
 * All byte array payloads are defensively copied on construction and access to preserve
 * immutability across domain and transport boundaries.
 */
sealed interface Packet

/**
 * Key-exchange handshake message carrying a protocol [step] identifier and defensive-copied
 * Noise/X25519 handshake [payload] bytes.
 */
class HandshakePacket(
    val step: Byte,
    payload: ByteArray,
) : Packet {
    private val rawPayload: ByteArray = payload.copyOf()

    /**
     * Returns an isolated defensive copy of the handshake payload bytes.
     */
    val payload: ByteArray
        get() = rawPayload.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakePacket) return false
        return step == other.step && rawPayload.contentEquals(other.rawPayload)
    }

    override fun hashCode(): Int {
        var result = step.hashCode()
        result = HASH_MULTIPLIER * result + rawPayload.contentHashCode()
        return result
    }

    override fun toString(): String = "HandshakePacket(step=$step, payloadSizeBytes=${rawPayload.size})"

    private companion object {
        private const val HASH_MULTIPLIER: Int = 31
    }
}

/**
 * End-to-end encrypted message packet carrying the ratchet [sequenceNumber], cryptographic
 * [salt] bytes, and AEAD [ciphertext] bytes.
 */
class EncryptedPayloadPacket(
    val sequenceNumber: Long,
    salt: ByteArray,
    ciphertext: ByteArray,
) : Packet {
    private val rawSalt: ByteArray = salt.copyOf()
    private val rawCiphertext: ByteArray = ciphertext.copyOf()

    /**
     * Returns an isolated defensive copy of the cryptographic salt bytes.
     */
    val salt: ByteArray
        get() = rawSalt.copyOf()

    /**
     * Returns an isolated defensive copy of the AEAD ciphertext bytes.
     */
    val ciphertext: ByteArray
        get() = rawCiphertext.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedPayloadPacket) return false
        return sequenceNumber == other.sequenceNumber &&
            rawSalt.contentEquals(other.rawSalt) &&
            rawCiphertext.contentEquals(other.rawCiphertext)
    }

    override fun hashCode(): Int {
        var result = sequenceNumber.hashCode()
        result = HASH_MULTIPLIER * result + rawSalt.contentHashCode()
        result = HASH_MULTIPLIER * result + rawCiphertext.contentHashCode()
        return result
    }

    override fun toString(): String =
        "EncryptedPayloadPacket(sequenceNumber=$sequenceNumber, " +
            "saltSizeBytes=${rawSalt.size}, ciphertextSizeBytes=${rawCiphertext.size})"

    private companion object {
        private const val HASH_MULTIPLIER: Int = 31
    }
}

/**
 * Delivery acknowledgment packet confirming receipt of the message identified by [messageId].
 */
data class AckPacket(
    val messageId: MessageId,
) : Packet

/**
 * Connection liveness probe and response packets carrying a Unix epoch [timestamp] in
 * milliseconds.
 */
sealed interface KeepAlivePacket : Packet {
    val timestamp: Long

    /**
     * Keep-alive probe packet sent periodically to verify link health.
     */
    data class Ping(
        override val timestamp: Long,
    ) : KeepAlivePacket

    /**
     * Keep-alive response packet acknowledging a received [Ping].
     */
    data class Pong(
        override val timestamp: Long,
    ) : KeepAlivePacket
}
