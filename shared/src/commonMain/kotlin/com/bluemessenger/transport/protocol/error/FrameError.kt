package com.bluemessenger.transport.protocol.error

import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.transport.protocol.constants.FrameConstants
import com.bluemessenger.transport.protocol.util.PROTOCOL_HEX_RADIX
import com.bluemessenger.transport.protocol.util.formatByteHex

/**
 * Typed failures produced during binary frame encoding or stream decoding.
 */
sealed interface FrameError {
    val message: String

    /**
     * Leading frame byte did not match [FrameConstants.FRAME_MAGIC_BYTE].
     */
    data class CorruptedMagicByte(
        val actualByte: Byte,
        val expectedByte: Byte = FrameConstants.FRAME_MAGIC_BYTE,
    ) : FrameError {
        override val message: String =
            "Invalid frame magic byte: expected 0x${formatByteHex(expectedByte)}, " +
                "got 0x${formatByteHex(actualByte)}."
    }

    /**
     * Frame length prefix was negative.
     */
    data class InvalidPayloadLength(
        val length: Int,
    ) : FrameError {
        override val message: String = "Invalid negative frame payload length: $length."
    }

    /**
     * Frame payload length exceeded [FrameConstants.MAX_FRAME_PAYLOAD_BYTES].
     */
    data class PayloadTooLarge(
        val length: Int,
        val maxAllowedBytes: Int = FrameConstants.MAX_FRAME_PAYLOAD_BYTES,
    ) : FrameError {
        override val message: String =
            "Frame payload length $length exceeds maximum allowed $maxAllowedBytes bytes."
    }

    /**
     * Frame CRC32 trailer did not match the computed header and payload checksum.
     */
    data class CrcMismatch(
        val expectedChecksum: Long,
        val actualChecksum: Long,
    ) : FrameError {
        override val message: String =
            "Frame CRC32 mismatch: expected 0x${expectedChecksum.toString(PROTOCOL_HEX_RADIX)}, " +
                "got 0x${actualChecksum.toString(PROTOCOL_HEX_RADIX)}."
    }
}

/**
 * Exception cause preserving the granular [FrameError] beneath a domain [CorruptedFrameError].
 */
class FrameErrorException(
    val frameError: FrameError,
) : Exception(frameError.message)

/**
 * Converts this [FrameError] into a failed [Result] carrying [DomainException] with
 * [CorruptedFrameError] and [FrameErrorException] as its cause.
 */
fun <T> FrameError.asFailure(): Result<T> = CorruptedFrameError(message).asFailure(cause = FrameErrorException(this))

/**
 * Extracts the underlying [FrameError] from a [Throwable] returned by framing operations, if any.
 */
fun Throwable.toFrameErrorOrNull(): FrameError? {
    return (this as? FrameErrorException)?.frameError ?: (cause as? FrameErrorException)?.frameError
}
