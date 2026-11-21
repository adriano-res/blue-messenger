package com.bluemessenger.transport.protocol

import com.bluemessenger.transport.protocol.constants.FrameConstants
import com.bluemessenger.transport.protocol.error.FrameError
import com.bluemessenger.transport.protocol.error.asFailure
import com.bluemessenger.transport.protocol.util.Crc32Checksum
import com.bluemessenger.transport.protocol.util.readIntBigEndian
import com.bluemessenger.transport.protocol.util.readUInt32BigEndian

/**
 * Stateful stream-oriented binary frame decoder supporting fragmented chunks (including
 * byte-by-byte reads) and multiple concatenated frames in a single chunk.
 *
 * Enforces fail-fast rejection of invalid magic bytes, negative lengths, lengths exceeding
 * [FrameConstants.MAX_FRAME_PAYLOAD_BYTES], and CRC32 mismatches before allocating payload
 * buffers.
 *
 * Thread safety: Not thread-safe. Each incoming stream must be owned by a single sequential
 * reader or guarded by external synchronization.
 */
class FrameDecoder {
    private var buffer: ByteArray = ByteArray(FrameConstants.MAX_FRAME_SIZE_BYTES)
    private var bufferedBytes: Int = 0

    /**
     * Appends [chunk] to the internal stream accumulator and extracts all complete, CRC-validated
     * frame payloads currently available.
     *
     * On any framing violation, resets the internal buffer and returns `Result.failure` carrying
     * `DomainException` with `CorruptedFrameError` and a granular [FrameError] cause.
     */
    fun feed(chunk: ByteArray): Result<List<ByteArray>> {
        if (chunk.isEmpty()) {
            return Result.success(emptyList())
        }
        val preCheckError = validateLeadingHeaderBeforeAppend(chunk)
        return if (preCheckError != null) {
            reset()
            preCheckError.asFailure()
        } else {
            appendToBuffer(chunk)
            extractAvailableFrames()
        }
    }

    /**
     * Discards any partially buffered bytes and shrinks the accumulator if oversized.
     */
    fun reset() {
        buffer.fill(0)
        bufferedBytes = 0
        if (buffer.size > FrameConstants.MAX_FRAME_SIZE_BYTES * BUFFER_SHRINK_FACTOR) {
            buffer = ByteArray(FrameConstants.MAX_FRAME_SIZE_BYTES)
        }
    }

    private fun validateLeadingHeaderBeforeAppend(chunk: ByteArray): FrameError? =
        when {
            bufferedBytes == 0 && chunk[0] != FrameConstants.FRAME_MAGIC_BYTE ->
                FrameError.CorruptedMagicByte(chunk[0])
            bufferedBytes < FrameConstants.FRAME_HEADER_SIZE_BYTES &&
                chunk.size >= FrameConstants.FRAME_HEADER_SIZE_BYTES - bufferedBytes ->
                validateAssembledHeader(chunk)
            else -> null
        }

    private fun validateAssembledHeader(chunk: ByteArray): FrameError? {
        val header = ByteArray(FrameConstants.FRAME_HEADER_SIZE_BYTES)
        buffer.copyInto(header, destinationOffset = 0, startIndex = 0, endIndex = bufferedBytes)
        val remainingHeaderBytes = FrameConstants.FRAME_HEADER_SIZE_BYTES - bufferedBytes
        chunk.copyInto(header, destinationOffset = bufferedBytes, startIndex = 0, endIndex = remainingHeaderBytes)
        return if (header[0] != FrameConstants.FRAME_MAGIC_BYTE) {
            FrameError.CorruptedMagicByte(header[0])
        } else {
            validateFramePayloadLength(header.readIntBigEndian(FrameConstants.FRAME_MAGIC_BYTE_SIZE_BYTES))
        }
    }

    private fun appendToBuffer(chunk: ByteArray) {
        val requiredCapacity = bufferedBytes + chunk.size
        if (requiredCapacity > buffer.size) {
            val newCapacity = maxOf(buffer.size * BUFFER_GROWTH_FACTOR, requiredCapacity)
            buffer = buffer.copyOf(newCapacity)
        }
        chunk.copyInto(buffer, destinationOffset = bufferedBytes)
        bufferedBytes = requiredCapacity
    }

    private fun extractAvailableFrames(): Result<List<ByteArray>> {
        val frames = mutableListOf<ByteArray>()
        var cursor = 0
        var failureError: FrameError? = null

        while (bufferedBytes - cursor >= FrameConstants.FRAME_MAGIC_BYTE_SIZE_BYTES && failureError == null) {
            when (val step = decodeSingleFrameAt(cursor)) {
                is FrameStep.NeedMoreData -> break
                is FrameStep.Invalid -> failureError = step.error
                is FrameStep.Decoded -> {
                    frames.add(step.payload)
                    cursor += step.consumedBytes
                }
            }
        }

        return if (failureError != null) {
            reset()
            failureError.asFailure()
        } else {
            compactBuffer(cursor)
            Result.success(frames.toList())
        }
    }

    private fun decodeSingleFrameAt(cursor: Int): FrameStep {
        val available = bufferedBytes - cursor
        val magicByte = buffer[cursor]
        return when {
            magicByte != FrameConstants.FRAME_MAGIC_BYTE ->
                FrameStep.Invalid(FrameError.CorruptedMagicByte(magicByte))
            available < FrameConstants.FRAME_HEADER_SIZE_BYTES ->
                FrameStep.NeedMoreData
            else -> decodeFrameWithCompleteHeader(cursor, available)
        }
    }

    private fun decodeFrameWithCompleteHeader(
        cursor: Int,
        available: Int,
    ): FrameStep {
        val payloadLength = buffer.readIntBigEndian(cursor + FrameConstants.FRAME_MAGIC_BYTE_SIZE_BYTES)
        val lengthError = validateFramePayloadLength(payloadLength)
        return when {
            lengthError != null -> FrameStep.Invalid(lengthError)
            available < FrameConstants.FRAME_OVERHEAD_BYTES + payloadLength -> FrameStep.NeedMoreData
            else -> verifyAndExtractPayload(cursor, payloadLength)
        }
    }

    private fun verifyAndExtractPayload(
        cursor: Int,
        payloadLength: Int,
    ): FrameStep {
        val signedDataLength = FrameConstants.FRAME_HEADER_SIZE_BYTES + payloadLength
        val expectedChecksum = buffer.readUInt32BigEndian(cursor + signedDataLength)
        val actualChecksum = Crc32Checksum.compute(buffer, offset = cursor, length = signedDataLength)
        return if (actualChecksum != expectedChecksum) {
            FrameStep.Invalid(
                FrameError.CrcMismatch(
                    expectedChecksum = expectedChecksum,
                    actualChecksum = actualChecksum,
                ),
            )
        } else {
            val payloadStart = cursor + FrameConstants.FRAME_HEADER_SIZE_BYTES
            val payload = buffer.copyOfRange(payloadStart, payloadStart + payloadLength)
            FrameStep.Decoded(
                payload = payload,
                consumedBytes = FrameConstants.FRAME_OVERHEAD_BYTES + payloadLength,
            )
        }
    }

    private fun compactBuffer(consumedBytes: Int) {
        if (consumedBytes == 0) {
            return
        }
        val remaining = bufferedBytes - consumedBytes
        if (remaining > 0) {
            buffer.copyInto(
                destination = buffer,
                destinationOffset = 0,
                startIndex = consumedBytes,
                endIndex = bufferedBytes,
            )
        }
        buffer.fill(0, fromIndex = remaining, toIndex = bufferedBytes)
        bufferedBytes = remaining
        if (buffer.size > FrameConstants.MAX_FRAME_SIZE_BYTES * BUFFER_SHRINK_FACTOR &&
            bufferedBytes <= FrameConstants.MAX_FRAME_SIZE_BYTES
        ) {
            buffer = buffer.copyOf(FrameConstants.MAX_FRAME_SIZE_BYTES)
        }
    }

    private sealed interface FrameStep {
        data object NeedMoreData : FrameStep

        data class Invalid(
            val error: FrameError,
        ) : FrameStep

        class Decoded(
            val payload: ByteArray,
            val consumedBytes: Int,
        ) : FrameStep
    }

    private companion object {
        private const val BUFFER_GROWTH_FACTOR: Int = 2
        private const val BUFFER_SHRINK_FACTOR: Int = 4
    }
}
