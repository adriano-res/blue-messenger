package com.bluemessenger.crypto

import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.crypto.util.ByteArrayZeroizer
import com.bluemessenger.domain.error.DecryptionFailedError
import com.bluemessenger.domain.error.asFailure
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.bouncycastle.util.Arrays
import java.security.SecureRandom

/**
 * Shared JVM/Android implementation (`actual`) of `commonMain`'s `expect object CryptoEngine`
 * (`shared/src/commonMain/kotlin/com/bluemessenger/crypto/CryptoEngine.kt`), backed by
 * BouncyCastle's Lightweight API (`bcprov-jdk18on`) without registering a global JCA provider.
 *
 * Linked to both `androidTarget` and `jvm` at compile time via `jvmCommonMain` in
 * `shared/build.gradle.kts` (`applyDefaultHierarchyTemplate`).
 */
internal actual object CryptoEngine {
    private val secureRandom: SecureRandom = SecureRandom()
    private val allZeroSharedSecret: ByteArray = ByteArray(CryptoConstants.X25519_KEY_LENGTH_BYTES)

    actual fun randomBytes(length: Int): ByteArray {
        require(length >= 0) { "Random byte count must be non-negative, got $length." }
        return ByteArray(length).also(secureRandom::nextBytes)
    }

    actual fun generateX25519PrivateKey(): ByteArray =
        ByteArray(CryptoConstants.X25519_KEY_LENGTH_BYTES).also { key ->
            X25519.generatePrivateKey(secureRandom, key)
        }

    actual fun deriveX25519PublicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == CryptoConstants.X25519_KEY_LENGTH_BYTES) {
            "X25519 private key must be ${CryptoConstants.X25519_KEY_LENGTH_BYTES} bytes."
        }
        return ByteArray(CryptoConstants.X25519_KEY_LENGTH_BYTES).also { publicKey ->
            X25519.generatePublicKey(privateKey, 0, publicKey, 0)
        }
    }

    actual fun x25519SharedSecret(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): Result<ByteArray> {
        val result: Result<ByteArray>
        if (
            privateKey.size != CryptoConstants.X25519_KEY_LENGTH_BYTES ||
            peerPublicKey.size != CryptoConstants.X25519_KEY_LENGTH_BYTES
        ) {
            result = DecryptionFailedError("Invalid X25519 key length for shared secret.").asFailure()
        } else {
            val sharedSecret = ByteArray(CryptoConstants.X25519_KEY_LENGTH_BYTES)
            val nonZeroAgreement = X25519.calculateAgreement(privateKey, 0, peerPublicKey, 0, sharedSecret, 0)
            result =
                if (!nonZeroAgreement || Arrays.constantTimeAreEqual(sharedSecret, allZeroSharedSecret)) {
                    ByteArrayZeroizer.zeroize(sharedSecret)
                    DecryptionFailedError("X25519 shared secret rejected: low-order or all-zero output.").asFailure()
                } else {
                    Result.success(sharedSecret)
                }
        }
        return result
    }

    actual fun generateEd25519PrivateKey(): ByteArray =
        ByteArray(CryptoConstants.ED25519_PRIVATE_KEY_LENGTH_BYTES).also { key ->
            Ed25519.generatePrivateKey(secureRandom, key)
        }

    actual fun deriveEd25519PublicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == CryptoConstants.ED25519_PRIVATE_KEY_LENGTH_BYTES) {
            "Ed25519 private key must be ${CryptoConstants.ED25519_PRIVATE_KEY_LENGTH_BYTES} bytes."
        }
        return ByteArray(CryptoConstants.ED25519_PUBLIC_KEY_LENGTH_BYTES).also { publicKey ->
            Ed25519.generatePublicKey(privateKey, 0, publicKey, 0)
        }
    }

    actual fun ed25519Sign(
        privateKey: ByteArray,
        message: ByteArray,
    ): ByteArray {
        require(privateKey.size == CryptoConstants.ED25519_PRIVATE_KEY_LENGTH_BYTES) {
            "Ed25519 private key must be ${CryptoConstants.ED25519_PRIVATE_KEY_LENGTH_BYTES} bytes."
        }
        return ByteArray(CryptoConstants.ED25519_SIGNATURE_LENGTH_BYTES).also { signature ->
            Ed25519.sign(privateKey, 0, message, 0, message.size, signature, 0)
        }
    }

    actual fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean =
        if (
            publicKey.size != CryptoConstants.ED25519_PUBLIC_KEY_LENGTH_BYTES ||
            signature.size != CryptoConstants.ED25519_SIGNATURE_LENGTH_BYTES
        ) {
            false
        } else {
            Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
        }

    actual fun constantTimeEquals(
        first: ByteArray,
        second: ByteArray,
    ): Boolean = Arrays.constantTimeAreEqual(first, second)

    actual fun sha256(data: ByteArray): ByteArray {
        val digest = SHA256Digest()
        digest.update(data, 0, data.size)
        return ByteArray(CryptoConstants.SHA256_DIGEST_LENGTH_BYTES).also { output ->
            digest.doFinal(output, 0)
        }
    }

    actual fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(key))
        hmac.update(data, 0, data.size)
        return ByteArray(CryptoConstants.SHA256_DIGEST_LENGTH_BYTES).also { output ->
            hmac.doFinal(output, 0)
        }
    }

    actual fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray {
        require(outputLength >= 0) { "HKDF output length must be non-negative, got $outputLength." }
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))
        return ByteArray(outputLength).also { output ->
            hkdf.generateBytes(output, 0, outputLength)
        }
    }

    actual fun chaCha20Poly1305Encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        val parameters = AEADParameters(KeyParameter(key), CryptoConstants.TAG_LENGTH_BITS, nonce, associatedData)
        cipher.init(true, parameters)
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val processed = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        val finalized = cipher.doFinal(output, processed)
        val totalLength = processed + finalized
        return if (totalLength == output.size) output else output.copyOf(totalLength)
    }

    actual fun chaCha20Poly1305Decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        associatedData: ByteArray,
    ): Result<ByteArray> =
        try {
            val cipher = ChaCha20Poly1305()
            val parameters = AEADParameters(KeyParameter(key), CryptoConstants.TAG_LENGTH_BITS, nonce, associatedData)
            cipher.init(false, parameters)
            val output = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
            val processed = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, output, 0)
            val finalized = cipher.doFinal(output, processed)
            val totalLength = processed + finalized
            val plaintext = if (totalLength == output.size) output else output.copyOf(totalLength)
            Result.success(plaintext)
        } catch (exception: InvalidCipherTextException) {
            DecryptionFailedError("ChaCha20-Poly1305 authentication failed.").asFailure(exception)
        }
}
