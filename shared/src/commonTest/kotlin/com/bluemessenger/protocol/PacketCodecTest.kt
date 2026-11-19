package com.bluemessenger.protocol

import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.protocol.constants.PacketConstants
import com.bluemessenger.protocol.error.PacketError
import com.bluemessenger.protocol.error.toPacketErrorOrNull
import com.bluemessenger.protocol.model.AckPacket
import com.bluemessenger.protocol.model.EncryptedPayloadPacket
import com.bluemessenger.protocol.model.HandshakePacket
import com.bluemessenger.protocol.model.KeepAlivePacket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PacketCodecTest {
    @Test
    fun `handshake packet round trip preserves step and payload`() {
        val packets =
            listOf(
                HandshakePacket(step = 1, payload = ByteArray(0)),
                HandshakePacket(step = 2, payload = ByteArray(48) { index -> (index * 3).toByte() }),
                HandshakePacket(step = 3, payload = byteArrayOf(0x01, 0x7F, 0x80.toByte(), 0xFF.toByte())),
            )

        for (packet in packets) {
            val encoded = PacketCodec.encode(packet)
            assertEquals(PacketConstants.PACKET_TYPE_HANDSHAKE, encoded[0])
            val decoded = PacketCodec.decode(encoded).getOrThrow()
            assertEquals(packet, decoded)
            assertEquals(packet.hashCode(), decoded.hashCode())
        }
    }

    @Test
    fun `encrypted payload packet round trip preserves sequence number, salt, and ciphertext`() {
        val packets =
            listOf(
                EncryptedPayloadPacket(
                    sequenceNumber = 0L,
                    salt = ByteArray(0),
                    ciphertext = ByteArray(0),
                ),
                EncryptedPayloadPacket(
                    sequenceNumber = 42L,
                    salt = ByteArray(16) { index -> (index + 1).toByte() },
                    ciphertext = "CiphertextWithPoly1305Tag".encodeToByteArray(),
                ),
                EncryptedPayloadPacket(
                    sequenceNumber = Long.MAX_VALUE,
                    salt = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
                    ciphertext = ByteArray(64) { 0x55 },
                ),
            )

        for (packet in packets) {
            val encoded = PacketCodec.encode(packet)
            assertEquals(PacketConstants.PACKET_TYPE_ENCRYPTED_PAYLOAD, encoded[0])
            val decoded = PacketCodec.decode(encoded).getOrThrow()
            assertEquals(packet, decoded)
            assertEquals(packet.hashCode(), decoded.hashCode())
        }
    }

    @Test
    fun `ack packet encodes as exact 17 bytes and round trips UUID deterministically`() {
        val uuid = Uuid.fromLongs(0x0123_4567_89AB_CDEFL, -0x0123_4567_89AB_CDEFL)
        val generatedId = MessageId.generate(Instant(1_700_000_000_000L))

        for (messageId in listOf(MessageId(uuid), generatedId)) {
            val packet = AckPacket(messageId)
            val encoded = PacketCodec.encode(packet)
            assertEquals(PacketConstants.ACK_PACKET_SIZE_BYTES, encoded.size)
            assertEquals(17, encoded.size)
            assertEquals(PacketConstants.PACKET_TYPE_ACK, encoded[0])

            val decoded = PacketCodec.decode(encoded).getOrThrow()
            assertEquals(packet, decoded)
        }

        val deterministicPacket = AckPacket(MessageId(uuid))
        val deterministicBytes = PacketCodec.encode(deterministicPacket)
        assertEquals(0x01.toByte(), deterministicBytes[1])
        assertEquals(0xEF.toByte(), deterministicBytes[8])
    }

    @Test
    fun `keep alive ping and pong encode as exact 9 bytes and round trip timestamp`() {
        val timestamps = listOf(0L, 1_700_000_123_456L, Long.MAX_VALUE, Long.MIN_VALUE)
        for (timestamp in timestamps) {
            val ping = KeepAlivePacket.Ping(timestamp)
            val pingEncoded = PacketCodec.encode(ping)
            assertEquals(PacketConstants.KEEP_ALIVE_PACKET_SIZE_BYTES, pingEncoded.size)
            assertEquals(9, pingEncoded.size)
            assertEquals(PacketConstants.PACKET_TYPE_KEEP_ALIVE_PING, pingEncoded[0])
            assertEquals(ping, PacketCodec.decode(pingEncoded).getOrThrow())

            val pong = KeepAlivePacket.Pong(timestamp)
            val pongEncoded = PacketCodec.encode(pong)
            assertEquals(PacketConstants.KEEP_ALIVE_PACKET_SIZE_BYTES, pongEncoded.size)
            assertEquals(9, pongEncoded.size)
            assertEquals(PacketConstants.PACKET_TYPE_KEEP_ALIVE_PONG, pongEncoded[0])
            assertEquals(pong, PacketCodec.decode(pongEncoded).getOrThrow())
        }
    }

    @Test
    fun `packet models enforce defensive immutability and value equality`() {
        val rawPayload = byteArrayOf(1, 2, 3, 4)
        val handshake = HandshakePacket(step = 1, payload = rawPayload)
        rawPayload[0] = 99
        assertContentEquals(byteArrayOf(1, 2, 3, 4), handshake.payload)
        val exportedHandshakePayload = handshake.payload
        exportedHandshakePayload[1] = 88
        assertContentEquals(byteArrayOf(1, 2, 3, 4), handshake.payload)
        assertNotEquals(handshake, HandshakePacket(step = 2, payload = byteArrayOf(1, 2, 3, 4)))
        assertNotEquals(handshake, HandshakePacket(step = 1, payload = byteArrayOf(1, 2, 3, 5)))

        val rawSalt = byteArrayOf(10, 20)
        val rawCipher = byteArrayOf(30, 40, 50)
        val encrypted = EncryptedPayloadPacket(sequenceNumber = 5L, salt = rawSalt, ciphertext = rawCipher)
        rawSalt[0] = 0
        rawCipher[0] = 0
        assertContentEquals(byteArrayOf(10, 20), encrypted.salt)
        assertContentEquals(byteArrayOf(30, 40, 50), encrypted.ciphertext)
        encrypted.salt[1] = 99
        encrypted.ciphertext[2] = 99
        assertContentEquals(byteArrayOf(10, 20), encrypted.salt)
        assertContentEquals(byteArrayOf(30, 40, 50), encrypted.ciphertext)
        assertTrue(!encrypted.toString().contains("30"))
    }

    @Test
    fun `decode rejects empty input and unknown opcodes fail-fast`() {
        val emptyResult = PacketCodec.decode(ByteArray(0))
        assertTrue(emptyResult.isFailure)
        assertIs<CorruptedFrameError>(emptyResult.exceptionOrNull()!!.toDomainErrorOrNull())
        assertIs<PacketError.TruncatedPacket>(emptyResult.exceptionOrNull()!!.toPacketErrorOrNull())

        for (unknownOpcode in listOf(0x00.toByte(), 0x06.toByte(), 0x7F.toByte(), 0xFF.toByte())) {
            val result = PacketCodec.decode(byteArrayOf(unknownOpcode, 0x01, 0x02))
            assertTrue(result.isFailure)
            assertIs<CorruptedFrameError>(result.exceptionOrNull()!!.toDomainErrorOrNull())
            val error = assertIs<PacketError.UnknownPacketType>(result.exceptionOrNull()!!.toPacketErrorOrNull())
            assertEquals(unknownOpcode, error.packetType)
        }
    }

    @Test
    fun `decode rejects truncated, negative-length, and trailing-bytes handshake packets`() {
        val valid = PacketCodec.encode(HandshakePacket(step = 2, payload = byteArrayOf(1, 2, 3, 4)))

        for (truncatedSize in 1 until valid.size) {
            val result = PacketCodec.decode(valid.copyOfRange(0, truncatedSize))
            assertTrue(result.isFailure, "Expected failure for truncated size $truncatedSize.")
            assertIs<CorruptedFrameError>(result.exceptionOrNull()!!.toDomainErrorOrNull())
            assertIs<PacketError.TruncatedPacket>(result.exceptionOrNull()!!.toPacketErrorOrNull())
        }

        val trailing = valid + byteArrayOf(0x00)
        val trailingResult = PacketCodec.decode(trailing)
        assertTrue(trailingResult.isFailure)
        val trailingError =
            assertIs<PacketError.TrailingBytes>(trailingResult.exceptionOrNull()!!.toPacketErrorOrNull())
        assertEquals(1, trailingError.remainingBytes)

        val negativeLength =
            byteArrayOf(
                PacketConstants.PACKET_TYPE_HANDSHAKE,
                0x01,
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
            )
        val negativeResult = PacketCodec.decode(negativeLength)
        assertTrue(negativeResult.isFailure)
        assertIs<PacketError.InvalidFieldLength>(negativeResult.exceptionOrNull()!!.toPacketErrorOrNull())
    }

    @Test
    fun `decode rejects truncated, negative-length, overflow-length, and trailing-bytes encrypted packets`() {
        val valid =
            PacketCodec.encode(
                EncryptedPayloadPacket(
                    sequenceNumber = 9L,
                    salt = byteArrayOf(1, 2, 3),
                    ciphertext = byteArrayOf(4, 5, 6, 7),
                ),
            )

        for (truncatedSize in 1 until valid.size) {
            val result = PacketCodec.decode(valid.copyOfRange(0, truncatedSize))
            assertTrue(result.isFailure, "Expected failure for truncated size $truncatedSize.")
            assertIs<CorruptedFrameError>(result.exceptionOrNull()!!.toDomainErrorOrNull())
            assertIs<PacketError.TruncatedPacket>(result.exceptionOrNull()!!.toPacketErrorOrNull())
        }

        val trailingResult = PacketCodec.decode(valid + byteArrayOf(0x01, 0x02))
        assertTrue(trailingResult.isFailure)
        val trailingError =
            assertIs<PacketError.TrailingBytes>(trailingResult.exceptionOrNull()!!.toPacketErrorOrNull())
        assertEquals(2, trailingError.remainingBytes)

        val negativeSaltLength = valid.copyOf()
        negativeSaltLength[9] = 0xFF.toByte()
        negativeSaltLength[10] = 0xFF.toByte()
        negativeSaltLength[11] = 0xFF.toByte()
        negativeSaltLength[12] = 0xFF.toByte()
        assertIs<PacketError.InvalidFieldLength>(
            PacketCodec.decode(negativeSaltLength).exceptionOrNull()!!.toPacketErrorOrNull(),
        )

        val overflowSaltLength = valid.copyOf()
        overflowSaltLength[9] = 0x7F
        overflowSaltLength[10] = 0xFF.toByte()
        overflowSaltLength[11] = 0xFF.toByte()
        overflowSaltLength[12] = 0xFF.toByte()
        assertIs<PacketError.TruncatedPacket>(
            PacketCodec.decode(overflowSaltLength).exceptionOrNull()!!.toPacketErrorOrNull(),
        )
    }

    @Test
    fun `decode rejects truncated or oversized ack and keep-alive packets`() {
        val validAck = PacketCodec.encode(AckPacket(MessageId.generate(Instant(1000L))))
        for (size in 1 until validAck.size) {
            assertIs<PacketError.TruncatedPacket>(
                PacketCodec.decode(validAck.copyOfRange(0, size)).exceptionOrNull()!!.toPacketErrorOrNull(),
            )
        }
        assertIs<PacketError.TrailingBytes>(
            PacketCodec.decode(validAck + byteArrayOf(0x01)).exceptionOrNull()!!.toPacketErrorOrNull(),
        )

        val validPing = PacketCodec.encode(KeepAlivePacket.Ping(1234L))
        val validPong = PacketCodec.encode(KeepAlivePacket.Pong(1234L))
        for (packetBytes in listOf(validPing, validPong)) {
            for (size in 1 until packetBytes.size) {
                assertIs<PacketError.TruncatedPacket>(
                    PacketCodec.decode(packetBytes.copyOfRange(0, size)).exceptionOrNull()!!.toPacketErrorOrNull(),
                )
            }
            assertIs<PacketError.TrailingBytes>(
                PacketCodec.decode(packetBytes + byteArrayOf(0x01)).exceptionOrNull()!!.toPacketErrorOrNull(),
            )
        }
    }
}
