package com.bluemessenger.crypto

import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.domain.error.DecryptionFailedError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CryptoCipherTest {
    @Test
    fun `matches RFC 8439 section 2_8_2 AEAD test vector`() {
        val key =
            hexToBytes(
                "808182838485868788898a8b8c8d8e8f" +
                    "909192939495969798999a9b9c9d9e9f",
            )
        val nonce = hexToBytes("070000004041424344454647")
        val aad = hexToBytes("50515253c0c1c2c3c4c5c6c7")
        val plaintext =
            (
                "Ladies and Gentlemen of the class of '99: " +
                    "If I could offer you only one tip for the future, sunscreen would be it."
            ).encodeToByteArray()
        val expectedCiphertext =
            hexToBytes(
                "d31a8d34648e60db7b86afbc53ef7ec2" +
                    "a4aded51296e08fea9e2b5a736ee62d6" +
                    "3dbea45e8ca9671282fafb69da92728b" +
                    "1a71de0a9e060b2905d6a5b67ecd3b36" +
                    "92ddbd7f2d778b8c9803aee328091b58" +
                    "fab324e4fad675945585808b4831d7bc" +
                    "3ff4def08e4b7a9de576d26586cec64b" +
                    "6116",
            )
        val expectedTag = hexToBytes("1ae10b594f09e26a7e902ecbd0600691")
        val expectedCombined = expectedCiphertext + expectedTag

        val encrypted = CryptoCipher.encrypt(key, nonce, plaintext, aad).getOrThrow()
        assertContentEquals(expectedCombined, encrypted)

        val decrypted = CryptoCipher.decrypt(key, nonce, encrypted, aad).getOrThrow()
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `round-trips empty and arbitrary payloads with associated data`() {
        val key = CryptoEngine.randomBytes(CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES)
        val nonce = CryptoEngine.randomBytes(CryptoConstants.NONCE_LENGTH_BYTES)
        val aad = "frame-header-v1".encodeToByteArray()

        val emptyCiphertext = CryptoCipher.encrypt(key, nonce, ByteArray(0), aad).getOrThrow()
        assertEquals(CryptoConstants.TAG_LENGTH_BYTES, emptyCiphertext.size)
        assertContentEquals(
            ByteArray(0),
            CryptoCipher.decrypt(key, nonce, emptyCiphertext, aad).getOrThrow(),
        )

        val message = "Hello Bluetooth E2EE!".encodeToByteArray()
        val ciphertext = CryptoCipher.encrypt(key, nonce, message, aad).getOrThrow()
        assertEquals(message.size + CryptoConstants.TAG_LENGTH_BYTES, ciphertext.size)
        assertContentEquals(
            message,
            CryptoCipher.decrypt(key, nonce, ciphertext, aad).getOrThrow(),
        )
    }

    @Test
    fun `single bit-flip in ciphertext tag nonce or AD fails with DecryptionFailedError`() {
        val key = CryptoEngine.randomBytes(CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES)
        val nonce = CryptoEngine.randomBytes(CryptoConstants.NONCE_LENGTH_BYTES)
        val aad = "associated-metadata".encodeToByteArray()
        val plaintext = "Confidential message payload".encodeToByteArray()
        val encrypted = CryptoCipher.encrypt(key, nonce, plaintext, aad).getOrThrow()

        val tamperedCiphertext = encrypted.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        assertDecryptionFailed(CryptoCipher.decrypt(key, nonce, tamperedCiphertext, aad))

        val tamperedTag =
            encrypted.copyOf().apply {
                val tagIndex = size - 1
                this[tagIndex] = (this[tagIndex].toInt() xor 0x01).toByte()
            }
        assertDecryptionFailed(CryptoCipher.decrypt(key, nonce, tamperedTag, aad))

        val tamperedNonce = nonce.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        assertDecryptionFailed(CryptoCipher.decrypt(key, tamperedNonce, encrypted, aad))

        val tamperedAad = aad.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        assertDecryptionFailed(CryptoCipher.decrypt(key, nonce, encrypted, tamperedAad))
    }

    @Test
    fun `rejects invalid key nonce or truncated ciphertext fail-fast`() {
        val validKey = ByteArray(CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES)
        val validNonce = ByteArray(CryptoConstants.NONCE_LENGTH_BYTES)
        val validPlaintext = "data".encodeToByteArray()

        assertDecryptionFailed(
            CryptoCipher.encrypt(
                ByteArray(CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES - 1),
                validNonce,
                validPlaintext,
            ),
        )
        assertDecryptionFailed(
            CryptoCipher.encrypt(validKey, ByteArray(CryptoConstants.NONCE_LENGTH_BYTES - 1), validPlaintext),
        )
        assertDecryptionFailed(
            CryptoCipher.decrypt(validKey, validNonce, ByteArray(CryptoConstants.TAG_LENGTH_BYTES - 1)),
        )
    }

    private fun assertDecryptionFailed(result: Result<ByteArray>) {
        assertTrue(result.isFailure)
        assertIs<DecryptionFailedError>(result.exceptionOrNull()?.toDomainErrorOrNull())
    }

    private fun hexToBytes(hex: String): ByteArray {
        check(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { index ->
            val offset = index * 2
            hex.substring(offset, offset + 2).toInt(16).toByte()
        }
    }
}
