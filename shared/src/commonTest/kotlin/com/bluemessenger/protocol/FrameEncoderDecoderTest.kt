package com.bluemessenger.protocol

import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.protocol.constants.FrameConstants
import com.bluemessenger.protocol.error.FrameError
import com.bluemessenger.protocol.error.toFrameErrorOrNull
import com.bluemessenger.protocol.model.AckPacket
import com.bluemessenger.protocol.model.EncryptedPayloadPacket
import com.bluemessenger.protocol.model.HandshakePacket
import com.bluemessenger.protocol.model.KeepAlivePacket
import com.bluemessenger.protocol.util.Crc32Checksum
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameEncoderDecoderTest {
    @Test
    fun `constants match canonical protocol specification`() {
        assertEquals(0x42.toByte(), FrameConstants.FRAME_MAGIC_BYTE)
        assertEquals(1024, FrameConstants.MAX_FRAME_PAYLOAD_BYTES)
        assertEquals(4, FrameConstants.CRC32_CHECKSUM_SIZE_BYTES)
        assertEquals(4, FrameConstants.FRAME_LENGTH_PREFIX_BYTES)
    }

    @Test
    fun `round trip succeeds for empty, typical, and max boundary payloads`() {
        val payloads =
            listOf(
                ByteArray(0),
                "Hello Bluetooth RFCOMM!".encodeToByteArray(),
                ByteArray(FrameConstants.MAX_FRAME_PAYLOAD_BYTES) { index -> (index % 251).toByte() },
            )

        for (payload in payloads) {
            val encoded = FrameEncoder.encode(payload).getOrThrow()
            assertEquals(FrameConstants.FRAME_OVERHEAD_BYTES + payload.size, encoded.size)
            assertEquals(FrameConstants.FRAME_MAGIC_BYTE, encoded[0])

            val decoder = FrameDecoder()
            val decoded = decoder.feed(encoded).getOrThrow()
            assertEquals(1, decoded.size)
            assertContentEquals(payload, decoded.first())
        }
    }

    @Test
    fun `encoder writes explicit Big-Endian length and trailing CRC32`() {
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        val encoded = FrameEncoder.encode(payload).getOrThrow()

        assertEquals(0x42.toByte(), encoded[0])
        assertEquals(0x00.toByte(), encoded[1])
        assertEquals(0x00.toByte(), encoded[2])
        assertEquals(0x00.toByte(), encoded[3])
        assertEquals(0x03.toByte(), encoded[4])
        assertContentEquals(payload, encoded.copyOfRange(5, 8))

        val expectedCrc = Crc32Checksum.compute(encoded, offset = 0, length = 8)
        val actualCrc =
            ((encoded[8].toLong() and 0xFFL) shl 24) or
                ((encoded[9].toLong() and 0xFFL) shl 16) or
                ((encoded[10].toLong() and 0xFFL) shl 8) or
                (encoded[11].toLong() and 0xFFL)
        assertEquals(expectedCrc, actualCrc)
    }

    @Test
    fun `decoder handles byte-by-byte fragmented stream`() {
        val payload = "Fragmented RFCOMM stream payload".encodeToByteArray()
        val encoded = FrameEncoder.encode(payload).getOrThrow()
        val decoder = FrameDecoder()

        val collected = mutableListOf<ByteArray>()
        for (index in encoded.indices) {
            val chunk = byteArrayOf(encoded[index])
            val decoded = decoder.feed(chunk).getOrThrow()
            if (index < encoded.lastIndex) {
                assertTrue(decoded.isEmpty(), "Expected no complete frame at byte index $index.")
            } else {
                collected.addAll(decoded)
            }
        }

        assertEquals(1, collected.size)
        assertContentEquals(payload, collected.first())
    }

    @Test
    fun `decoder extracts multiple concatenated frames and preserves trailing partial frame`() {
        val payload1 = "First frame".encodeToByteArray()
        val payload2 = "Second frame".encodeToByteArray()
        val payload3 = "Third partial frame".encodeToByteArray()

        val frame1 = FrameEncoder.encode(payload1).getOrThrow()
        val frame2 = FrameEncoder.encode(payload2).getOrThrow()
        val frame3 = FrameEncoder.encode(payload3).getOrThrow()

        val splitPoint = 7
        val firstChunk = frame1 + frame2 + frame3.copyOfRange(0, splitPoint)
        val secondChunk = frame3.copyOfRange(splitPoint, frame3.size)

        val decoder = FrameDecoder()
        val firstBatch = decoder.feed(firstChunk).getOrThrow()
        assertEquals(2, firstBatch.size)
        assertContentEquals(payload1, firstBatch[0])
        assertContentEquals(payload2, firstBatch[1])

        val emptyBatch = decoder.feed(ByteArray(0)).getOrThrow()
        assertTrue(emptyBatch.isEmpty())

        val secondBatch = decoder.feed(secondChunk).getOrThrow()
        assertEquals(1, secondBatch.size)
        assertContentEquals(payload3, secondBatch[0])
    }

    @Test
    fun `encoder rejects payload larger than MAX_FRAME_PAYLOAD_BYTES fail-fast`() {
        val oversized = ByteArray(FrameConstants.MAX_FRAME_PAYLOAD_BYTES + 1)
        val result = FrameEncoder.encode(oversized)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()!!
        assertIs<CorruptedFrameError>(exception.toDomainErrorOrNull())
        val frameError = assertIs<FrameError.PayloadTooLarge>(exception.toFrameErrorOrNull())
        assertEquals(FrameConstants.MAX_FRAME_PAYLOAD_BYTES + 1, frameError.length)
        assertEquals(FrameConstants.MAX_FRAME_PAYLOAD_BYTES, frameError.maxAllowedBytes)
    }

    @Test
    fun `decoder rejects invalid magic byte immediately without waiting for header`() {
        val decoder = FrameDecoder()
        val result = decoder.feed(byteArrayOf(0x99.toByte()))

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()!!
        assertIs<CorruptedFrameError>(exception.toDomainErrorOrNull())
        val frameError = assertIs<FrameError.CorruptedMagicByte>(exception.toFrameErrorOrNull())
        assertEquals(0x99.toByte(), frameError.actualByte)
    }

    @Test
    fun `decoder rejects oversized and negative lengths fail-fast before payload arrives`() {
        val oversizedHeader =
            byteArrayOf(
                FrameConstants.FRAME_MAGIC_BYTE,
                0x00,
                0x00,
                0x04,
                0x01,
            )
        val oversizedDecoder = FrameDecoder()
        val oversizedResult = oversizedDecoder.feed(oversizedHeader)
        assertTrue(oversizedResult.isFailure)
        assertIs<CorruptedFrameError>(oversizedResult.exceptionOrNull()!!.toDomainErrorOrNull())
        val tooLargeError =
            assertIs<FrameError.PayloadTooLarge>(oversizedResult.exceptionOrNull()!!.toFrameErrorOrNull())
        assertEquals(1025, tooLargeError.length)

        val fragmentedOversizedDecoder = FrameDecoder()
        val firstFragment =
            fragmentedOversizedDecoder.feed(byteArrayOf(FrameConstants.FRAME_MAGIC_BYTE, 0x7F)).getOrThrow()
        assertTrue(firstFragment.isEmpty())
        val hugeLengthResult =
            fragmentedOversizedDecoder.feed(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        assertTrue(hugeLengthResult.isFailure)
        assertIs<FrameError.PayloadTooLarge>(hugeLengthResult.exceptionOrNull()!!.toFrameErrorOrNull())

        val negativeHeader =
            byteArrayOf(
                FrameConstants.FRAME_MAGIC_BYTE,
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
            )
        val negativeDecoder = FrameDecoder()
        val negativeResult = negativeDecoder.feed(negativeHeader)
        assertTrue(negativeResult.isFailure)
        assertIs<CorruptedFrameError>(negativeResult.exceptionOrNull()!!.toDomainErrorOrNull())
        val invalidLength =
            assertIs<FrameError.InvalidPayloadLength>(negativeResult.exceptionOrNull()!!.toFrameErrorOrNull())
        assertEquals(-1, invalidLength.length)
    }

    @Test
    fun `decoder detects bit flips in payload and trailer via CRC32`() {
        val payload = "Tamper detection payload".encodeToByteArray()
        val encoded = FrameEncoder.encode(payload).getOrThrow()

        val payloadCorrupted = encoded.copyOf()
        payloadCorrupted[FrameConstants.FRAME_HEADER_SIZE_BYTES + 2] =
            (payloadCorrupted[FrameConstants.FRAME_HEADER_SIZE_BYTES + 2].toInt() xor 0x01).toByte()

        val payloadDecoder = FrameDecoder()
        val payloadResult = payloadDecoder.feed(payloadCorrupted)
        assertTrue(payloadResult.isFailure)
        assertIs<CorruptedFrameError>(payloadResult.exceptionOrNull()!!.toDomainErrorOrNull())
        assertIs<FrameError.CrcMismatch>(payloadResult.exceptionOrNull()!!.toFrameErrorOrNull())

        val trailerCorrupted = encoded.copyOf()
        trailerCorrupted[trailerCorrupted.lastIndex] =
            (trailerCorrupted[trailerCorrupted.lastIndex].toInt() xor 0x80).toByte()

        val trailerDecoder = FrameDecoder()
        val trailerResult = trailerDecoder.feed(trailerCorrupted)
        assertTrue(trailerResult.isFailure)
        assertIs<CorruptedFrameError>(trailerResult.exceptionOrNull()!!.toDomainErrorOrNull())
        assertIs<FrameError.CrcMismatch>(trailerResult.exceptionOrNull()!!.toFrameErrorOrNull())
    }

    @Test
    fun `decoder reset discards buffered partial bytes`() {
        val decoder = FrameDecoder()
        val frame = FrameEncoder.encode("Valid after reset".encodeToByteArray()).getOrThrow()

        assertTrue(decoder.feed(byteArrayOf(FrameConstants.FRAME_MAGIC_BYTE, 0x00, 0x00)).getOrThrow().isEmpty())
        decoder.reset()

        val decoded = decoder.feed(frame).getOrThrow()
        assertEquals(1, decoded.size)
        assertContentEquals("Valid after reset".encodeToByteArray(), decoded.first())
    }

    @Test
    fun `framed packets round trip seamlessly across PacketCodec and FrameEncoderDecoder`() {
        val packets =
            listOf(
                HandshakePacket(step = 1, payload = ByteArray(32) { it.toByte() }),
                EncryptedPayloadPacket(
                    sequenceNumber = 7L,
                    salt = ByteArray(12) { (it + 10).toByte() },
                    ciphertext = "Encrypted hello".encodeToByteArray(),
                ),
                AckPacket(MessageId.generate(Instant(1_700_000_000_000L))),
                KeepAlivePacket.Ping(timestamp = 1_700_000_001_000L),
                KeepAlivePacket.Pong(timestamp = 1_700_000_001_500L),
            )

        val streamBytes =
            packets.fold(ByteArray(0)) { acc, packet ->
                acc + FrameEncoder.encode(PacketCodec.encode(packet)).getOrThrow()
            }

        val decoder = FrameDecoder()
        val rawFrames = decoder.feed(streamBytes).getOrThrow()
        assertEquals(packets.size, rawFrames.size)

        val decodedPackets = rawFrames.map { framePayload -> PacketCodec.decode(framePayload).getOrThrow() }
        assertEquals(packets, decodedPackets)
    }
}
