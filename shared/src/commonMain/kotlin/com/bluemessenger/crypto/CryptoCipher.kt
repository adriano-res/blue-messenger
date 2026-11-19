package com.bluemessenger.crypto

import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.domain.error.DecryptionFailedError
import com.bluemessenger.domain.error.asFailure

/**
 * Stateless AEAD cipher implementing ChaCha20-Poly1305 with strict key, nonce, and tag validation.
 *
 * Thread safety: Stateless and safe to call concurrently across threads and coroutines.
 *
 * Error handling: Fails fast with [DecryptionFailedError] on invalid key/nonce length, truncated
 * ciphertext, or Poly1305 tag/associated-data verification failure.
 */
object CryptoCipher {
    /**
     * Encrypts [plaintext] under [key] and [nonce], authenticating [associatedData].
     */
    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): Result<ByteArray> =
        validateKeyAndNonce(key, nonce)?.asFailure()
            ?: Result.success(CryptoEngine.chaCha20Poly1305Encrypt(key, nonce, plaintext, associatedData))

    /**
     * Decrypts [ciphertextWithTag] under [key] and [nonce], verifying [associatedData] and MAC tag.
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): Result<ByteArray> {
        val validationError =
            validateKeyAndNonce(key, nonce)
                ?: if (ciphertextWithTag.size < CryptoConstants.TAG_LENGTH_BYTES) {
                    DecryptionFailedError(
                        "Ciphertext shorter than ${CryptoConstants.TAG_LENGTH_BYTES}-byte Poly1305 tag: " +
                            "${ciphertextWithTag.size} bytes.",
                    )
                } else {
                    null
                }
        return validationError?.asFailure()
            ?: CryptoEngine.chaCha20Poly1305Decrypt(key, nonce, ciphertextWithTag, associatedData)
    }

    private fun validateKeyAndNonce(
        key: ByteArray,
        nonce: ByteArray,
    ): DecryptionFailedError? =
        when {
            key.size != CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES ->
                DecryptionFailedError(
                    "ChaCha20-Poly1305 key must be ${CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES} bytes, " +
                        "got ${key.size}.",
                )
            nonce.size != CryptoConstants.NONCE_LENGTH_BYTES ->
                DecryptionFailedError(
                    "ChaCha20-Poly1305 nonce must be ${CryptoConstants.NONCE_LENGTH_BYTES} bytes, got ${nonce.size}.",
                )
            else -> null
        }
}
