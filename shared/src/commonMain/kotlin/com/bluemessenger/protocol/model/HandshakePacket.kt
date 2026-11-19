package com.bluemessenger.protocol.model

/**
 * Key-exchange handshake message carrying a protocol [step] identifier and defensive-copied
 * Noise/X25519 handshake payload bytes.
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
