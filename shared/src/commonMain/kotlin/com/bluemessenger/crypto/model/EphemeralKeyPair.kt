package com.bluemessenger.crypto.model

import com.bluemessenger.crypto.CryptoEngine
import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.crypto.util.ByteArrayZeroizer
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.primitives.PublicKey
import kotlin.concurrent.Volatile

/**
 * Ephemeral X25519 Diffie-Hellman key pair supporting explicit zeroization and low-order checks.
 *
 * Thread safety: [zeroize] visibility is guarded by `@Volatile`; callers sharing an instance
 * across coroutines must synchronize key agreement against destruction.
 *
 * Error handling: Fails fast with [InvalidStateTransitionError] after [zeroize], and rejects
 * low-order peer keys producing all-zero shared secrets with `DecryptionFailedError`.
 */
class EphemeralKeyPair private constructor(
    private val privateKeyBytes: ByteArray,
    val publicKey: PublicKey,
) {
    @Volatile
    private var isDestroyed: Boolean = false

    /**
     * Computes an X25519 shared secret with [peerPublicKey], rejecting low-order points.
     */
    fun computeSharedSecret(peerPublicKey: PublicKey): Result<ByteArray> {
        if (isDestroyed) {
            return InvalidStateTransitionError(
                entity = ENTITY_NAME,
                fromState = STATE_DESTROYED,
                toState = ACTION_AGREE,
            ).asFailure()
        }
        val peerBytes = peerPublicKey.toByteArray()
        return CryptoEngine.x25519SharedSecret(privateKeyBytes, peerBytes)
    }

    /**
     * Permanently erases the ephemeral private key bytes from memory.
     */
    fun zeroize() {
        isDestroyed = true
        ByteArrayZeroizer.zeroize(privateKeyBytes)
    }

    override fun toString(): String = "EphemeralKeyPair(publicKey=$publicKey, isDestroyed=$isDestroyed)"

    companion object {
        private const val ENTITY_NAME: String = "EphemeralKeyPair"
        private const val STATE_DESTROYED: String = "DESTROYED"
        private const val ACTION_AGREE: String = "AGREE"

        /**
         * Generates a fresh random X25519 ephemeral key pair.
         */
        fun generate(): EphemeralKeyPair {
            val privateKey = CryptoEngine.generateX25519PrivateKey()
            val publicKeyBytes = CryptoEngine.deriveX25519PublicKey(privateKey)
            val publicKey = PublicKey.fromBytes(publicKeyBytes).getOrThrow()
            return EphemeralKeyPair(privateKey, publicKey)
        }

        /**
         * Reconstructs an [EphemeralKeyPair] from a defensive copy of [privateKey].
         */
        fun fromPrivateKey(privateKey: ByteArray): Result<EphemeralKeyPair> {
            if (privateKey.size != CryptoConstants.X25519_KEY_LENGTH_BYTES) {
                return Result.failure(
                    IllegalArgumentException(
                        "X25519 private key must be ${CryptoConstants.X25519_KEY_LENGTH_BYTES} bytes, " +
                            "got ${privateKey.size}.",
                    ),
                )
            }
            val copiedPrivate = privateKey.copyOf()
            val publicKeyBytes = CryptoEngine.deriveX25519PublicKey(copiedPrivate)
            val publicKey = PublicKey.fromBytes(publicKeyBytes).getOrThrow()
            return Result.success(EphemeralKeyPair(copiedPrivate, publicKey))
        }
    }
}
