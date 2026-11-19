package com.bluemessenger.protocol.util

/**
 * Pure Kotlin Multiplatform IEEE 802.3 CRC32 checksum calculator and verifier.
 *
 * Uses the reflected polynomial `0xEDB88320` with a precomputed 256-entry lookup table.
 * Stateless and thread-safe.
 */
object Crc32Checksum {
    private const val CRC32_POLYNOMIAL: Long = 0xEDB88320L
    private const val CRC32_INITIAL_VALUE: Long = 0xFFFF_FFFFL
    private const val CRC32_FINAL_XOR: Long = 0xFFFF_FFFFL
    private const val TABLE_SIZE: Int = 256

    private val lookupTable: LongArray =
        LongArray(TABLE_SIZE) { entry ->
            var crc = entry.toLong()
            repeat(PROTOCOL_BITS_PER_BYTE) {
                crc =
                    if ((crc and 1L) != 0L) {
                        (crc ushr 1) xor CRC32_POLYNOMIAL
                    } else {
                        crc ushr 1
                    }
            }
            crc and PROTOCOL_UINT32_MASK
        }

    /**
     * Computes the unsigned 32-bit IEEE 802.3 CRC32 checksum over [data] from [offset] for
     * [length] bytes, returned in the lower 32 bits of a non-negative [Long].
     */
    fun compute(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): Long {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "Invalid slice bounds: offset=$offset, length=$length, size=${data.size}."
        }
        var crc = CRC32_INITIAL_VALUE
        val end = offset + length
        for (index in offset until end) {
            val tableIndex =
                ((crc xor (data[index].toLong() and PROTOCOL_LONG_BYTE_MASK)) and PROTOCOL_LONG_BYTE_MASK).toInt()
            crc = (crc ushr PROTOCOL_BITS_PER_BYTE) xor lookupTable[tableIndex]
        }
        return (crc xor CRC32_FINAL_XOR) and PROTOCOL_UINT32_MASK
    }

    /**
     * Returns `true` when the CRC32 checksum computed over the slice matches [expectedChecksum].
     */
    fun verify(
        data: ByteArray,
        expectedChecksum: Long,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): Boolean = compute(data, offset, length) == (expectedChecksum and PROTOCOL_UINT32_MASK)
}
