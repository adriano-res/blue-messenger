package com.bluemessenger.domain.primitives

/**
 * Immutable cryptographic public key wrapper backed by defensive byte copies.
 */
class PublicKey private constructor(
    private val rawBytes: ByteArray,
) {
    val sizeBytes: Int
        get() = rawBytes.size

    /**
     * Returns an isolated defensive copy of the raw key bytes.
     */
    fun toByteArray(): ByteArray = rawBytes.copyOf()

    /**
     * Encodes the public key bytes as a lowercase hexadecimal string.
     */
    fun toHex(): String {
        val builder = StringBuilder(rawBytes.size * HEX_CHARS_PER_BYTE)
        for (byte in rawBytes) {
            val unsigned = byte.toInt() and BYTE_MASK
            builder.append(HEX_DIGITS[unsigned ushr HIGH_NIBBLE_SHIFT])
            builder.append(HEX_DIGITS[unsigned and LOW_NIBBLE_MASK])
        }
        return builder.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PublicKey) return false
        return rawBytes.contentEquals(other.rawBytes)
    }

    override fun hashCode(): Int = rawBytes.contentHashCode()

    override fun toString(): String = "PublicKey(sizeBytes=$sizeBytes)"

    companion object {
        const val EXPECTED_KEY_LENGTH_BYTES: Int = 32
        private const val HEX_CHARS_PER_BYTE: Int = 2
        private const val HEX_RADIX: Int = 16
        private const val BYTE_MASK: Int = 0xFF
        private const val HIGH_NIBBLE_SHIFT: Int = 4
        private const val LOW_NIBBLE_MASK: Int = 0x0F
        private const val HEX_DIGITS: String = "0123456789abcdef"

        /**
         * Creates a [PublicKey] from raw bytes, validating the canonical 32-byte key length.
         */
        fun fromBytes(bytes: ByteArray): Result<PublicKey> {
            if (bytes.size != EXPECTED_KEY_LENGTH_BYTES) {
                return Result.failure(
                    IllegalArgumentException(
                        "PublicKey must be $EXPECTED_KEY_LENGTH_BYTES bytes, got ${bytes.size}.",
                    ),
                )
            }
            return Result.success(PublicKey(bytes.copyOf()))
        }

        /**
         * Parses a hexadecimal string into a [PublicKey], validating length and hex characters.
         */
        fun fromHex(hex: String): Result<PublicKey> {
            val trimmed = hex.trim()
            val expectedHexLength = EXPECTED_KEY_LENGTH_BYTES * HEX_CHARS_PER_BYTE
            return when {
                trimmed.length != expectedHexLength ->
                    Result.failure(
                        IllegalArgumentException(
                            "PublicKey hex must have length $expectedHexLength, got ${trimmed.length}.",
                        ),
                    )
                trimmed.any { it.digitToIntOrNull(HEX_RADIX) == null } ->
                    Result.failure(IllegalArgumentException("Invalid hexadecimal public key."))
                else -> {
                    val bytes =
                        ByteArray(EXPECTED_KEY_LENGTH_BYTES) { index ->
                            val start = index * HEX_CHARS_PER_BYTE
                            val highNibble = trimmed[start].digitToInt(HEX_RADIX)
                            val lowNibble = trimmed[start + 1].digitToInt(HEX_RADIX)
                            ((highNibble shl HIGH_NIBBLE_SHIFT) or lowNibble).toByte()
                        }
                    Result.success(PublicKey(bytes))
                }
            }
        }
    }
}
