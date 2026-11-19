package com.bluemessenger.protocol.constants

/**
 * Wire protocol framing constants shared across all platforms for length-prefixed Bluetooth
 * RFCOMM framing and CRC32 integrity verification.
 */
object FrameConstants {
    const val FRAME_MAGIC_BYTE: Byte = 0x42.toByte()
    const val FRAME_MAGIC_BYTE_SIZE_BYTES: Int = 1
    const val FRAME_LENGTH_PREFIX_BYTES: Int = 4
    const val CRC32_CHECKSUM_SIZE_BYTES: Int = 4
    const val MAX_FRAME_PAYLOAD_BYTES: Int = 1024
    const val FRAME_HEADER_SIZE_BYTES: Int = FRAME_MAGIC_BYTE_SIZE_BYTES + FRAME_LENGTH_PREFIX_BYTES
    const val FRAME_TRAILER_SIZE_BYTES: Int = CRC32_CHECKSUM_SIZE_BYTES
    const val FRAME_OVERHEAD_BYTES: Int = FRAME_HEADER_SIZE_BYTES + FRAME_TRAILER_SIZE_BYTES
    const val MAX_FRAME_SIZE_BYTES: Int = FRAME_OVERHEAD_BYTES + MAX_FRAME_PAYLOAD_BYTES
}
