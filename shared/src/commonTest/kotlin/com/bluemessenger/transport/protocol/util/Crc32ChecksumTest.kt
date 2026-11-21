package com.bluemessenger.transport.protocol.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Crc32ChecksumTest {
    @Test
    fun `empty byte array produces zero checksum`() {
        assertEquals(0x0000_0000L, Crc32Checksum.compute(ByteArray(0)))
    }

    @Test
    fun `standard IEEE 802_3 check vector matches expected value`() {
        val vector = "123456789".encodeToByteArray()
        assertEquals(0xCBF4_3926L, Crc32Checksum.compute(vector))
        assertTrue(Crc32Checksum.verify(vector, 0xCBF4_3926L))
    }

    @Test
    fun `single byte known vectors match IEEE 802_3 specification`() {
        assertEquals(0xD202_EF8DL, Crc32Checksum.compute(byteArrayOf(0x00)))
        assertEquals(0xFF00_0000L, Crc32Checksum.compute(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `slice computation matches standalone subarray checksum`() {
        val full = byteArrayOf(0x01, 0x02) + "123456789".encodeToByteArray() + byteArrayOf(0x03, 0x04)
        assertEquals(0xCBF4_3926L, Crc32Checksum.compute(full, offset = 2, length = 9))
        assertTrue(Crc32Checksum.verify(full, 0xCBF4_3926L, offset = 2, length = 9))
    }

    @Test
    fun `verify detects single bit flip corruption`() {
        val original = "BlueMessenger-CRC32".encodeToByteArray()
        val expected = Crc32Checksum.compute(original)

        val corrupted = original.copyOf()
        corrupted[5] = (corrupted[5].toInt() xor 0x01).toByte()

        assertFalse(Crc32Checksum.verify(corrupted, expected))
    }

    @Test
    fun `invalid slice bounds fail fast with IllegalArgumentException`() {
        val data = byteArrayOf(1, 2, 3, 4)
        assertFailsWith<IllegalArgumentException> {
            Crc32Checksum.compute(data, offset = -1, length = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            Crc32Checksum.compute(data, offset = 2, length = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            Crc32Checksum.compute(data, offset = 2, length = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            Crc32Checksum.compute(data, offset = Int.MAX_VALUE, length = 1)
        }
    }
}
