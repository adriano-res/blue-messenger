package com.bluemessenger.crypto.model

import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.DecryptionFailedError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.primitives.PublicKey

/**
 * Immutable 40-byte Double Ratchet message header encoded as
 * `[dhPublicKey (32B) | previousChainLength (4B BE) | messageNumber (4B BE)]`.
 *
 * Thread safety: Immutable value object.
 *
 * Error handling: [decode] fails fast with [CorruptedFrameError] if truncated or
 * [DecryptionFailedError] if counters are malformed.
 */
class RatchetHeader(
    val dhPublicKey: PublicKey,
    val previousChainLength: Int,
    val messageNumber: Int,
) {
    init {
        require(previousChainLength >= 0) {
            "Previous chain length must be non-negative, got $previousChainLength."
        }
        require(messageNumber >= 0) {
            "Message number must be non-negative, got $messageNumber."
        }
    }

    /**
     * Encodes this header into its canonical 40-byte big-endian representation.
     */
    fun encode(): ByteArray {
        val output = ByteArray(CryptoConstants.RATCHET_HEADER_SIZE_BYTES)
        val keyBytes = dhPublicKey.toByteArray()
        keyBytes.copyInto(output, destinationOffset = 0)
        writeIntBigEndian(output, CryptoConstants.X25519_KEY_LENGTH_BYTES, previousChainLength)
        writeIntBigEndian(output, MESSAGE_NUMBER_OFFSET, messageNumber)
        return output
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RatchetHeader) return false
        return dhPublicKey == other.dhPublicKey &&
            previousChainLength == other.previousChainLength &&
            messageNumber == other.messageNumber
    }

    override fun hashCode(): Int {
        var result = dhPublicKey.hashCode()
        result = HASH_MULTIPLIER * result + previousChainLength
        result = HASH_MULTIPLIER * result + messageNumber
        return result
    }

    override fun toString(): String =
        "RatchetHeader(dhPublicKey=$dhPublicKey, " +
            "previousChainLength=$previousChainLength, messageNumber=$messageNumber)"

    companion object {
        private const val HASH_MULTIPLIER: Int = 31
        private const val MESSAGE_NUMBER_OFFSET: Int =
            CryptoConstants.X25519_KEY_LENGTH_BYTES + CryptoConstants.RATCHET_COUNTER_SIZE_BYTES

        /**
         * Decodes a [RatchetHeader] from the first 40 bytes of [bytes].
         */
        fun decode(bytes: ByteArray): Result<RatchetHeader> {
            if (bytes.size < CryptoConstants.RATCHET_HEADER_SIZE_BYTES) {
                return CorruptedFrameError(
                    "Ratchet header requires ${CryptoConstants.RATCHET_HEADER_SIZE_BYTES} bytes, got ${bytes.size}.",
                ).asFailure()
            }
            val prevLength = readIntBigEndian(bytes, CryptoConstants.X25519_KEY_LENGTH_BYTES)
            val msgNumber = readIntBigEndian(bytes, MESSAGE_NUMBER_OFFSET)
            return if (prevLength < 0 || msgNumber < 0) {
                DecryptionFailedError("Ratchet header contains negative chain counter.").asFailure()
            } else {
                val dhKeyBytes = bytes.copyOfRange(0, CryptoConstants.X25519_KEY_LENGTH_BYTES)
                PublicKey.fromBytes(dhKeyBytes).map { publicKey ->
                    RatchetHeader(
                        dhPublicKey = publicKey,
                        previousChainLength = prevLength,
                        messageNumber = msgNumber,
                    )
                }
            }
        }

        private fun writeIntBigEndian(
            destination: ByteArray,
            offset: Int,
            value: Int,
        ) {
            for (byteIndex in 0 until CryptoConstants.RATCHET_COUNTER_SIZE_BYTES) {
                val shift = (CryptoConstants.RATCHET_COUNTER_SIZE_BYTES - 1 - byteIndex) * CryptoConstants.BITS_PER_BYTE
                destination[offset + byteIndex] = ((value ushr shift) and CryptoConstants.BYTE_MASK).toByte()
            }
        }

        private fun readIntBigEndian(
            source: ByteArray,
            offset: Int,
        ): Int {
            var value = 0
            for (byteIndex in 0 until CryptoConstants.RATCHET_COUNTER_SIZE_BYTES) {
                value =
                    (value shl CryptoConstants.BITS_PER_BYTE) or
                    (source[offset + byteIndex].toInt() and CryptoConstants.BYTE_MASK)
            }
            return value
        }
    }
}
