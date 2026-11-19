package com.bluemessenger.protocol.util

internal const val PROTOCOL_BYTE_MASK: Int = 0xFF
internal const val PROTOCOL_LONG_BYTE_MASK: Long = 0xFFL
internal const val PROTOCOL_UINT32_MASK: Long = 0xFFFF_FFFFL
internal const val PROTOCOL_BITS_PER_BYTE: Int = 8
internal const val PROTOCOL_INT_SIZE_BYTES: Int = 4
internal const val PROTOCOL_LONG_SIZE_BYTES: Int = 8
internal const val PROTOCOL_HEX_RADIX: Int = 16
internal const val PROTOCOL_BYTE_HEX_LENGTH: Int = 2
internal const val PROTOCOL_HEX_PAD_CHAR: Char = '0'

internal fun formatByteHex(value: Byte): String =
    (value.toInt() and PROTOCOL_BYTE_MASK)
        .toString(PROTOCOL_HEX_RADIX)
        .padStart(PROTOCOL_BYTE_HEX_LENGTH, PROTOCOL_HEX_PAD_CHAR)

internal fun ByteArray.writeIntBigEndian(
    offset: Int,
    value: Int,
) {
    for (byteIndex in 0 until PROTOCOL_INT_SIZE_BYTES) {
        val shift = (PROTOCOL_INT_SIZE_BYTES - 1 - byteIndex) * PROTOCOL_BITS_PER_BYTE
        this[offset + byteIndex] = ((value ushr shift) and PROTOCOL_BYTE_MASK).toByte()
    }
}

internal fun ByteArray.readIntBigEndian(offset: Int): Int {
    var result = 0
    for (byteIndex in 0 until PROTOCOL_INT_SIZE_BYTES) {
        result = (result shl PROTOCOL_BITS_PER_BYTE) or (this[offset + byteIndex].toInt() and PROTOCOL_BYTE_MASK)
    }
    return result
}

internal fun ByteArray.writeUInt32BigEndian(
    offset: Int,
    value: Long,
) {
    writeIntBigEndian(offset, (value and PROTOCOL_UINT32_MASK).toInt())
}

internal fun ByteArray.readUInt32BigEndian(offset: Int): Long {
    return readIntBigEndian(offset).toLong() and PROTOCOL_UINT32_MASK
}

internal fun ByteArray.writeLongBigEndian(
    offset: Int,
    value: Long,
) {
    for (byteIndex in 0 until PROTOCOL_LONG_SIZE_BYTES) {
        val shift = (PROTOCOL_LONG_SIZE_BYTES - 1 - byteIndex) * PROTOCOL_BITS_PER_BYTE
        this[offset + byteIndex] = ((value ushr shift) and PROTOCOL_LONG_BYTE_MASK).toByte()
    }
}

internal fun ByteArray.readLongBigEndian(offset: Int): Long {
    var result = 0L
    for (byteIndex in 0 until PROTOCOL_LONG_SIZE_BYTES) {
        result =
            (result shl PROTOCOL_BITS_PER_BYTE) or
            (this[offset + byteIndex].toLong() and PROTOCOL_LONG_BYTE_MASK)
    }
    return result
}
