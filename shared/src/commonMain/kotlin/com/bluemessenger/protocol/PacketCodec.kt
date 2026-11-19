package com.bluemessenger.protocol

import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.protocol.constants.PacketConstants
import com.bluemessenger.protocol.error.PacketError
import com.bluemessenger.protocol.error.asFailure
import com.bluemessenger.protocol.model.AckPacket
import com.bluemessenger.protocol.model.EncryptedPayloadPacket
import com.bluemessenger.protocol.model.HandshakePacket
import com.bluemessenger.protocol.model.KeepAlivePacket
import com.bluemessenger.protocol.model.Packet
import com.bluemessenger.protocol.util.readIntBigEndian
import com.bluemessenger.protocol.util.readLongBigEndian
import com.bluemessenger.protocol.util.writeIntBigEndian
import com.bluemessenger.protocol.util.writeLongBigEndian
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pure positional Big-Endian binary codec for all [Packet] subtypes.
 *
 * Wire layouts:
 * - [HandshakePacket]: `[0x01 (1B)] [step (1B)] [payloadLen (4B)] [payload (NB)]`
 * - [EncryptedPayloadPacket]:
 *   `[0x02 (1B)] [sequenceNumber (8B)] [saltLen (4B)] [salt] [ciphertextLen (4B)] [ciphertext]`
 * - [AckPacket]: `[0x03 (1B)] [messageIdMsb (8B)] [messageIdLsb (8B)]` (17 bytes exact)
 * - [KeepAlivePacket.Ping]: `[0x04 (1B)] [timestamp (8B)]` (9 bytes exact)
 * - [KeepAlivePacket.Pong]: `[0x05 (1B)] [timestamp (8B)]` (9 bytes exact)
 *
 * Stateless and thread-safe.
 */
@OptIn(ExperimentalUuidApi::class)
object PacketCodec {
    private const val FIELD_PAYLOAD: String = "payload"
    private const val FIELD_SALT: String = "salt"
    private const val FIELD_CIPHERTEXT: String = "ciphertext"

    /**
     * Serializes [packet] into its deterministic Big-Endian binary wire representation.
     */
    fun encode(packet: Packet): ByteArray =
        when (packet) {
            is HandshakePacket -> encodeHandshake(packet)
            is EncryptedPayloadPacket -> encodeEncryptedPayload(packet)
            is AckPacket -> encodeAck(packet)
            is KeepAlivePacket.Ping -> encodeKeepAlive(PacketConstants.PACKET_TYPE_KEEP_ALIVE_PING, packet.timestamp)
            is KeepAlivePacket.Pong -> encodeKeepAlive(PacketConstants.PACKET_TYPE_KEEP_ALIVE_PONG, packet.timestamp)
        }

    /**
     * Deserializes a [Packet] from [bytes], failing fast with [Result.failure] carrying
     * [DomainException] (`CorruptedFrameError` and [PacketError]) on empty input, unknown
     * opcodes, negative lengths, truncated fields, or trailing bytes.
     */
    fun decode(bytes: ByteArray): Result<Packet> =
        when {
            bytes.isEmpty() ->
                PacketError.TruncatedPacket(
                    expectedMinBytes = PacketConstants.PACKET_TYPE_SIZE_BYTES,
                    actualBytes = 0,
                ).asFailure()
            else ->
                when (val packetType = bytes[0]) {
                    PacketConstants.PACKET_TYPE_HANDSHAKE -> decodeHandshake(bytes)
                    PacketConstants.PACKET_TYPE_ENCRYPTED_PAYLOAD -> decodeEncryptedPayload(bytes)
                    PacketConstants.PACKET_TYPE_ACK -> decodeAck(bytes)
                    PacketConstants.PACKET_TYPE_KEEP_ALIVE_PING -> decodeKeepAlive(bytes, isPing = true)
                    PacketConstants.PACKET_TYPE_KEEP_ALIVE_PONG -> decodeKeepAlive(bytes, isPing = false)
                    else -> PacketError.UnknownPacketType(packetType).asFailure()
                }
        }

    private fun encodeHandshake(packet: HandshakePacket): ByteArray {
        val payload = packet.payload
        val output = ByteArray(PacketConstants.HANDSHAKE_MIN_BYTES + payload.size)
        output[0] = PacketConstants.PACKET_TYPE_HANDSHAKE
        output[PacketConstants.PACKET_TYPE_SIZE_BYTES] = packet.step
        val lengthOffset = PacketConstants.PACKET_TYPE_SIZE_BYTES + PacketConstants.HANDSHAKE_STEP_SIZE_BYTES
        output.writeIntBigEndian(lengthOffset, payload.size)
        payload.copyInto(output, destinationOffset = PacketConstants.HANDSHAKE_MIN_BYTES)
        return output
    }

    private fun encodeEncryptedPayload(packet: EncryptedPayloadPacket): ByteArray {
        val salt = packet.salt
        val ciphertext = packet.ciphertext
        val output = ByteArray(PacketConstants.ENCRYPTED_PAYLOAD_MIN_BYTES + salt.size + ciphertext.size)
        var cursor = 0
        output[cursor] = PacketConstants.PACKET_TYPE_ENCRYPTED_PAYLOAD
        cursor += PacketConstants.PACKET_TYPE_SIZE_BYTES
        output.writeLongBigEndian(cursor, packet.sequenceNumber)
        cursor += PacketConstants.SEQUENCE_NUMBER_SIZE_BYTES
        output.writeIntBigEndian(cursor, salt.size)
        cursor += PacketConstants.LENGTH_FIELD_SIZE_BYTES
        salt.copyInto(output, destinationOffset = cursor)
        cursor += salt.size
        output.writeIntBigEndian(cursor, ciphertext.size)
        cursor += PacketConstants.LENGTH_FIELD_SIZE_BYTES
        ciphertext.copyInto(output, destinationOffset = cursor)
        return output
    }

    private fun encodeAck(packet: AckPacket): ByteArray {
        val output = ByteArray(PacketConstants.ACK_PACKET_SIZE_BYTES)
        output[0] = PacketConstants.PACKET_TYPE_ACK
        packet.messageId.value.toLongs { mostSignificantBits, leastSignificantBits ->
            output.writeLongBigEndian(PacketConstants.PACKET_TYPE_SIZE_BYTES, mostSignificantBits)
            output.writeLongBigEndian(
                PacketConstants.PACKET_TYPE_SIZE_BYTES + PacketConstants.UUID_HALF_SIZE_BYTES,
                leastSignificantBits,
            )
        }
        return output
    }

    private fun encodeKeepAlive(
        packetType: Byte,
        timestamp: Long,
    ): ByteArray {
        val output = ByteArray(PacketConstants.KEEP_ALIVE_PACKET_SIZE_BYTES)
        output[0] = packetType
        output.writeLongBigEndian(PacketConstants.PACKET_TYPE_SIZE_BYTES, timestamp)
        return output
    }

    private fun decodeHandshake(bytes: ByteArray): Result<HandshakePacket> {
        if (bytes.size < PacketConstants.HANDSHAKE_MIN_BYTES) {
            return PacketError.TruncatedPacket(
                expectedMinBytes = PacketConstants.HANDSHAKE_MIN_BYTES,
                actualBytes = bytes.size,
            ).asFailure()
        }
        val step = bytes[PacketConstants.PACKET_TYPE_SIZE_BYTES]
        val lengthOffset = PacketConstants.PACKET_TYPE_SIZE_BYTES + PacketConstants.HANDSHAKE_STEP_SIZE_BYTES
        return when (val slice = readLengthPrefixedPacketSlice(bytes, lengthOffset, FIELD_PAYLOAD)) {
            is PacketSliceRead.Failure -> slice.error.asFailure()
            is PacketSliceRead.Success ->
                if (slice.nextOffset != bytes.size) {
                    PacketError.TrailingBytes(bytes.size - slice.nextOffset).asFailure()
                } else {
                    Result.success(HandshakePacket(step = step, payload = slice.value))
                }
        }
    }

    private fun decodeEncryptedPayload(bytes: ByteArray): Result<EncryptedPayloadPacket> {
        if (bytes.size < PacketConstants.ENCRYPTED_PAYLOAD_MIN_BYTES) {
            return PacketError.TruncatedPacket(
                expectedMinBytes = PacketConstants.ENCRYPTED_PAYLOAD_MIN_BYTES,
                actualBytes = bytes.size,
            ).asFailure()
        }
        val sequenceNumber = bytes.readLongBigEndian(PacketConstants.PACKET_TYPE_SIZE_BYTES)
        val saltOffset = PacketConstants.PACKET_TYPE_SIZE_BYTES + PacketConstants.SEQUENCE_NUMBER_SIZE_BYTES
        return when (val saltSlice = readLengthPrefixedPacketSlice(bytes, saltOffset, FIELD_SALT)) {
            is PacketSliceRead.Failure -> saltSlice.error.asFailure()
            is PacketSliceRead.Success ->
                when (val cipherSlice = readLengthPrefixedPacketSlice(bytes, saltSlice.nextOffset, FIELD_CIPHERTEXT)) {
                    is PacketSliceRead.Failure -> cipherSlice.error.asFailure()
                    is PacketSliceRead.Success ->
                        if (cipherSlice.nextOffset != bytes.size) {
                            PacketError.TrailingBytes(bytes.size - cipherSlice.nextOffset).asFailure()
                        } else {
                            Result.success(
                                EncryptedPayloadPacket(
                                    sequenceNumber = sequenceNumber,
                                    salt = saltSlice.value,
                                    ciphertext = cipherSlice.value,
                                ),
                            )
                        }
                }
        }
    }

    private fun decodeAck(bytes: ByteArray): Result<AckPacket> =
        when {
            bytes.size < PacketConstants.ACK_PACKET_SIZE_BYTES ->
                PacketError.TruncatedPacket(
                    expectedMinBytes = PacketConstants.ACK_PACKET_SIZE_BYTES,
                    actualBytes = bytes.size,
                ).asFailure()
            bytes.size > PacketConstants.ACK_PACKET_SIZE_BYTES ->
                PacketError.TrailingBytes(bytes.size - PacketConstants.ACK_PACKET_SIZE_BYTES).asFailure()
            else -> {
                val mostSignificantBits = bytes.readLongBigEndian(PacketConstants.PACKET_TYPE_SIZE_BYTES)
                val leastSignificantBits =
                    bytes.readLongBigEndian(
                        PacketConstants.PACKET_TYPE_SIZE_BYTES + PacketConstants.UUID_HALF_SIZE_BYTES,
                    )
                Result.success(AckPacket(MessageId(Uuid.fromLongs(mostSignificantBits, leastSignificantBits))))
            }
        }

    private fun decodeKeepAlive(
        bytes: ByteArray,
        isPing: Boolean,
    ): Result<KeepAlivePacket> =
        when {
            bytes.size < PacketConstants.KEEP_ALIVE_PACKET_SIZE_BYTES ->
                PacketError.TruncatedPacket(
                    expectedMinBytes = PacketConstants.KEEP_ALIVE_PACKET_SIZE_BYTES,
                    actualBytes = bytes.size,
                ).asFailure()
            bytes.size > PacketConstants.KEEP_ALIVE_PACKET_SIZE_BYTES ->
                PacketError.TrailingBytes(bytes.size - PacketConstants.KEEP_ALIVE_PACKET_SIZE_BYTES).asFailure()
            else -> {
                val timestamp = bytes.readLongBigEndian(PacketConstants.PACKET_TYPE_SIZE_BYTES)
                val packet =
                    if (isPing) {
                        KeepAlivePacket.Ping(timestamp)
                    } else {
                        KeepAlivePacket.Pong(timestamp)
                    }
                Result.success(packet)
            }
        }
}

private sealed interface PacketSliceRead {
    class Success(
        val value: ByteArray,
        val nextOffset: Int,
    ) : PacketSliceRead

    data class Failure(
        val error: PacketError,
    ) : PacketSliceRead
}

private fun readLengthPrefixedPacketSlice(
    bytes: ByteArray,
    offset: Int,
    fieldName: String,
): PacketSliceRead {
    if (offset > bytes.size - PacketConstants.LENGTH_FIELD_SIZE_BYTES) {
        return PacketSliceRead.Failure(
            PacketError.TruncatedPacket(
                expectedMinBytes = offset + PacketConstants.LENGTH_FIELD_SIZE_BYTES,
                actualBytes = bytes.size,
            ),
        )
    }
    val length = bytes.readIntBigEndian(offset)
    val dataStart = offset + PacketConstants.LENGTH_FIELD_SIZE_BYTES
    val remaining = bytes.size - dataStart
    return when {
        length < 0 ->
            PacketSliceRead.Failure(PacketError.InvalidFieldLength(fieldName, length))
        length > remaining -> {
            val cappedExpectedBytes =
                (dataStart.toLong() + length.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            PacketSliceRead.Failure(
                PacketError.TruncatedPacket(
                    expectedMinBytes = cappedExpectedBytes,
                    actualBytes = bytes.size,
                ),
            )
        }
        else ->
            PacketSliceRead.Success(
                value = bytes.copyOfRange(dataStart, dataStart + length),
                nextOffset = dataStart + length,
            )
    }
}
