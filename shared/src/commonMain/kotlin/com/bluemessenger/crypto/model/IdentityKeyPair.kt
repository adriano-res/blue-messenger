package com.bluemessenger.crypto.model

import com.bluemessenger.crypto.CryptoEngine
import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.crypto.util.ByteArrayZeroizer
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.primitives.PublicKey
import kotlin.concurrent.Volatile

/**
 * Long-term Ed25519 identity key pair supporting explicit in-memory zeroization.
 *
 * Thread safety: [zeroize] visibility is guarded by `@Volatile`; callers sharing an instance
 * across coroutines must synchronize signing against destruction.
 *
 * Error handling: Calling [sign] after [zeroize] fails fast with [InvalidStateTransitionError].
 */
class IdentityKeyPair private constructor(
    private val privateKeyBytes: ByteArray,
    val publicKey: PublicKey,
) {
    @Volatile
    private var isDestroyed: Boolean = false

    /**
     * Signs [data] with the private Ed25519 key unless this key pair has been zeroized.
     */
    fun sign(data: ByteArray): Result<ByteArray> {
        if (isDestroyed) {
            return InvalidStateTransitionError(
                entity = ENTITY_NAME,
                fromState = STATE_DESTROYED,
                toState = ACTION_SIGN,
            ).asFailure()
        }
        return Result.success(CryptoEngine.ed25519Sign(privateKeyBytes, data))
    }

    /**
     * Verifies an Ed25519 [signature] over [data] against [publicKey].
     */
    fun verify(
        data: ByteArray,
        signature: ByteArray,
    ): Boolean = verify(publicKey, data, signature)

    /**
     * Permanently erases the private key bytes from memory.
     */
    fun zeroize() {
        isDestroyed = true
        ByteArrayZeroizer.zeroize(privateKeyBytes)
    }

    override fun toString(): String = "IdentityKeyPair(publicKey=$publicKey, isDestroyed=$isDestroyed)"

    companion object {
        private const val ENTITY_NAME: String = "IdentityKeyPair"
        private const val STATE_DESTROYED: String = "DESTROYED"
        private const val ACTION_SIGN: String = "SIGN"

        /**
         * Generates a fresh random Ed25519 identity key pair.
         */
        fun generate(): IdentityKeyPair {
            val privateKey = CryptoEngine.generateEd25519PrivateKey()
            val publicKeyBytes = CryptoEngine.deriveEd25519PublicKey(privateKey)
            val publicKey = PublicKey.fromBytes(publicKeyBytes).getOrThrow()
            return IdentityKeyPair(privateKey, publicKey)
        }

        /**
         * Reconstructs an [IdentityKeyPair] from a defensive copy of [privateKey].
         */
        fun fromPrivateKey(privateKey: ByteArray): Result<IdentityKeyPair> {
            if (privateKey.size != CryptoConstants.ED25519_PRIVATE_KEY_LENGTH_BYTES) {
                return Result.failure(
                    IllegalArgumentException(
                        "Ed25519 private key must be ${CryptoConstants.ED25519_PRIVATE_KEY_LENGTH_BYTES} bytes, " +
                            "got ${privateKey.size}.",
                    ),
                )
            }
            val copiedPrivate = privateKey.copyOf()
            val publicKeyBytes = CryptoEngine.deriveEd25519PublicKey(copiedPrivate)
            val publicKey = PublicKey.fromBytes(publicKeyBytes).getOrThrow()
            return Result.success(IdentityKeyPair(copiedPrivate, publicKey))
        }

        /**
         * Verifies an Ed25519 [signature] over [data] using [publicKey].
         */
        fun verify(
            publicKey: PublicKey,
            data: ByteArray,
            signature: ByteArray,
        ): Boolean {
            val rawPublicKey = publicKey.toByteArray()
            return CryptoEngine.ed25519Verify(rawPublicKey, data, signature)
        }
    }
}
