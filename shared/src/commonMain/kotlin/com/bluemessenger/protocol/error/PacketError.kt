package com.bluemessenger.protocol.error

import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.protocol.util.formatByteHex

/**
 * Typed failures produced when decoding malformed or truncated binary packets.
 */
sealed interface PacketError {
    val message: String

    /**
     * Packet opcode byte did not match any known packet type constant.
     */
    data class UnknownPacketType(
        val packetType: Byte,
    ) : PacketError {
        override val message: String =
            "Unknown packet type opcode: 0x${formatByteHex(packetType)}."
    }

    /**
     * Packet buffer ended before all required header or declared field bytes could be read.
     */
    data class TruncatedPacket(
        val expectedMinBytes: Int,
        val actualBytes: Int,
    ) : PacketError {
        override val message: String =
            "Truncated packet: expected at least $expectedMinBytes bytes, got $actualBytes."
    }

    /**
     * Declared variable-length field size was negative.
     */
    data class InvalidFieldLength(
        val fieldName: String,
        val length: Int,
    ) : PacketError {
        override val message: String =
            "Invalid negative length $length for packet field '$fieldName'."
    }

    /**
     * Packet buffer contained unexpected trailing bytes after the complete packet structure.
     */
    data class TrailingBytes(
        val remainingBytes: Int,
    ) : PacketError {
        override val message: String =
            "Malformed packet with $remainingBytes unexpected trailing bytes."
    }
}

/**
 * Exception cause preserving the granular [PacketError] beneath a domain [CorruptedFrameError].
 */
class PacketErrorException(
    val packetError: PacketError,
) : Exception(packetError.message)

/**
 * Converts this [PacketError] into a failed [Result] carrying [DomainException] with
 * [CorruptedFrameError] and [PacketErrorException] as its cause.
 */
fun <T> PacketError.asFailure(): Result<T> = CorruptedFrameError(message).asFailure(cause = PacketErrorException(this))

/**
 * Extracts the underlying [PacketError] from a [Throwable] returned by packet decoding, if any.
 */
fun Throwable.toPacketErrorOrNull(): PacketError? =
    (this as? PacketErrorException)?.packetError ?: (cause as? PacketErrorException)?.packetError
