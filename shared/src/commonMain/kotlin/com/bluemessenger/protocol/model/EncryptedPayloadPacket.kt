package com.bluemessenger.protocol.model

/**
 * End-to-end encrypted message packet carrying the ratchet [sequenceNumber], cryptographic
 * salt/header bytes, and AEAD ciphertext bytes.
 *
 * All byte arrays are defensively copied on construction and access to enforce strict immutability.
 */
class EncryptedPayloadPacket(
    val sequenceNumber: Long,
    salt: ByteArray,
    ciphertext: ByteArray,
) : Packet {
    private val rawSalt: ByteArray = salt.copyOf()
    private val rawCiphertext: ByteArray = ciphertext.copyOf()

    /**
     * Returns an isolated defensive copy of the salt bytes.
     */
    val salt: ByteArray
        get() = rawSalt.copyOf()

    /**
     * Returns an isolated defensive copy of the ciphertext bytes.
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
