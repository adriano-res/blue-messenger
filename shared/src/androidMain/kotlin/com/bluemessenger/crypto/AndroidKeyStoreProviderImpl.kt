package com.bluemessenger.crypto

import android.content.Context
import com.bluemessenger.crypto.constants.AndroidKeyStoreConstants
import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.crypto.util.ByteArrayZeroizer
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.ports.crypto.KeyStoreProvider
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.primitives.PublicKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Android [KeyStoreProvider] anchoring the long-term Ed25519 identity private key inside an
 * AES-256-GCM authenticated envelope protected by a non-exportable `"AndroidKeyStore"` master key
 * in [AndroidKeyStoreVault].
 *
 * Thread safety: All keystore lookups, generation, signing, and deletion operations are serialized
 * by an internal [Mutex] on [CoroutineDispatchers.io]. Temporary private-key byte arrays and
 * [IdentityKeyPair] copies are zeroized unconditionally in `finally` blocks.
 *
 * Failure behavior: Fails fast with [Result.failure] wrapping `DomainException`([StorageError]) on
 * security, AEAD authentication, corruption, or I/O failures without ever falling back to
 * plaintext disk storage or logging secret material. Always rethrows [CancellationException].
 */
class AndroidKeyStoreProviderImpl internal constructor(
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val vault: AndroidKeyStoreVault,
) : KeyStoreProvider {
    constructor(
        context: Context,
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
    ) : this(
        dispatchers = dispatchers,
        logger = logger,
        vault = AndroidKeyStoreVault(context),
    )

    private val mutex: Mutex = Mutex()

    /**
     * Checks whether an encrypted identity key envelope is present in [AndroidKeyStoreVault].
     */
    override suspend fun hasIdentityKeyPair(): Result<Boolean> =
        executeKeyStoreOperation("checking identity key presence") {
            vault.hasEnvelope()
        }

    /**
     * Generates a fresh Ed25519 private key, encrypts and stores it in [AndroidKeyStoreVault],
     * zeroizes all temporary key buffers, and returns its [PublicKey].
     */
    override suspend fun generateIdentityKeyPair(): Result<PublicKey> =
        executeKeyStoreOperation("generating and storing identity key pair") {
            val privateKeyBytes = CryptoEngine.generateEd25519PrivateKey()
            ByteArrayZeroizer.useAndZeroize(privateKeyBytes) { secret ->
                val publicKeyBytes = CryptoEngine.deriveEd25519PublicKey(secret)
                val publicKey = PublicKey.fromBytes(publicKeyBytes).getOrThrow()
                vault.encryptAndStore(secret)
                logger.info(
                    tag = AndroidKeyStoreConstants.TAG_KEYSTORE,
                    message = "Generated and stored identity key pair in Android KeyStore vault.",
                )
                publicKey
            }
        }

    /**
     * Loads and decrypts the Ed25519 identity private key long enough to derive and return its
     * [PublicKey], or `null` if no identity key exists, zeroizing the secret buffer immediately.
     */
    override suspend fun getIdentityPublicKey(): Result<PublicKey?> =
        executeKeyStoreOperation("loading identity public key") {
            val privateKeyBytes =
                vault.loadAndDecrypt()
                    ?: return@executeKeyStoreOperation null
            ByteArrayZeroizer.useAndZeroize(privateKeyBytes) { secret ->
                validatePrivateKeyLength(secret)
                val publicKeyBytes = CryptoEngine.deriveEd25519PublicKey(secret)
                PublicKey.fromBytes(publicKeyBytes).getOrThrow()
            }
        }

    /**
     * Signs [data] with the identity key protected by [AndroidKeyStoreVault] without exposing
     * private key bytes to callers, zeroizing all in-memory key copies before returning.
     */
    override suspend fun signWithIdentityKey(data: ByteArray): Result<ByteArray> =
        executeKeyStoreOperation("signing payload with identity key") {
            val privateKeyBytes =
                vault.loadAndDecrypt()
                    ?: error("Identity key pair is missing from Android KeyStore vault.")
            ByteArrayZeroizer.useAndZeroize(privateKeyBytes) { secret ->
                validatePrivateKeyLength(secret)
                val keyPair = IdentityKeyPair.fromPrivateKey(secret).getOrThrow()
                try {
                    keyPair.sign(data).getOrThrow()
                } finally {
                    keyPair.zeroize()
                }
            }
        }

    /**
     * Securely erases the stored identity envelope and deletes the Android KeyStore master key.
     */
    override suspend fun deleteIdentityKeyPair(): Result<Unit> =
        executeKeyStoreOperation("deleting identity key pair") {
            vault.deleteAll()
            logger.info(
                tag = AndroidKeyStoreConstants.TAG_KEYSTORE,
                message = "Deleted identity key pair from Android KeyStore vault.",
            )
        }

    private suspend fun <T> executeKeyStoreOperation(
        operationName: String,
        block: () -> T,
    ): Result<T> =
        mutex.withLock {
            withContext(dispatchers.io) {
                try {
                    Result.success(block())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: GeneralSecurityException) {
                    failStorageError(operationName, exception)
                } catch (exception: IOException) {
                    failStorageError(operationName, exception)
                } catch (exception: SecurityException) {
                    failStorageError(operationName, exception)
                } catch (exception: IllegalStateException) {
                    failStorageError(operationName, exception)
                } catch (exception: IllegalArgumentException) {
                    failStorageError(operationName, exception)
                }
            }
        }

    private fun validatePrivateKeyLength(secret: ByteArray) {
        require(secret.size == CryptoConstants.ED25519_PRIVATE_KEY_LENGTH_BYTES) {
            "Corrupted identity key length in Android KeyStore vault: expected " +
                "${CryptoConstants.ED25519_PRIVATE_KEY_LENGTH_BYTES} bytes, got ${secret.size}."
        }
    }

    private fun <T> failStorageError(
        operationName: String,
        throwable: Throwable,
    ): Result<T> {
        val reason =
            throwable.message?.takeIf { it.isNotBlank() }
                ?: (throwable::class.simpleName ?: "unknown error")
        val storageError =
            StorageError(
                "${AndroidKeyStoreConstants.KEYSTORE_FAILURE_PREFIX} " +
                    "Operation '$operationName' failed: $reason.",
            )
        logger.error(
            tag = AndroidKeyStoreConstants.TAG_KEYSTORE,
            message = "Android KeyStore operation failed while $operationName.",
            throwable = throwable,
        )
        return storageError.asFailure(throwable)
    }
}
