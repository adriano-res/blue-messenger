package com.bluemessenger.protocol

import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.protocol.constants.FrameConstants
import com.bluemessenger.protocol.error.FrameError
import com.bluemessenger.protocol.error.asFailure
import com.bluemessenger.protocol.util.Crc32Checksum
import com.bluemessenger.protocol.util.writeIntBigEndian
import com.bluemessenger.protocol.util.writeUInt32BigEndian

/**
 * Encodes binary payloads into deterministic length-prefixed wire frames.
 *
 * Wire layout:
 * `[MAGIC_BYTE (1B)] [LENGTH (4B, Big-Endian)] [PAYLOAD (NB)] [CRC32 (4B, Big-Endian)]`
 * where `CRC32` covers `MAGIC_BYTE || LENGTH || PAYLOAD`.
 *
 * Stateless and thread-safe.
 */
object FrameEncoder {
    /**
     * Frames [payload] when its length does not exceed [FrameConstants.MAX_FRAME_PAYLOAD_BYTES],
     * or fails fast with [Result.failure] carrying [DomainException] (`CorruptedFrameError` /
     * [FrameError.PayloadTooLarge]) before allocating an output buffer.
     */
    fun encode(payload: ByteArray): Result<ByteArray> {
        validateFramePayloadLength(payload.size)?.let { return it.asFailure() }
        val totalSize = FrameConstants.FRAME_OVERHEAD_BYTES + payload.size
        val frame = ByteArray(totalSize)
        frame[0] = FrameConstants.FRAME_MAGIC_BYTE
        frame.writeIntBigEndian(FrameConstants.FRAME_MAGIC_BYTE_SIZE_BYTES, payload.size)
        payload.copyInto(frame, destinationOffset = FrameConstants.FRAME_HEADER_SIZE_BYTES)
        val checksumOffset = FrameConstants.FRAME_HEADER_SIZE_BYTES + payload.size
        val checksum = Crc32Checksum.compute(frame, offset = 0, length = checksumOffset)
        frame.writeUInt32BigEndian(checksumOffset, checksum)
        return Result.success(frame)
    }
}

internal fun validateFramePayloadLength(payloadLength: Int): FrameError? =
    when {
        payloadLength < 0 -> FrameError.InvalidPayloadLength(payloadLength)
        payloadLength > FrameConstants.MAX_FRAME_PAYLOAD_BYTES -> FrameError.PayloadTooLarge(payloadLength)
        else -> null
    }
