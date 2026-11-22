package com.bluemessenger.crypto

import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.crypto.constants.SecretServiceConstants
import com.bluemessenger.crypto.dbus.SecretServiceClient
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
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import java.io.Closeable
import java.io.IOException

/**
 * Linux Desktop [KeyStoreProvider] storing the long-term Ed25519 identity private key in the
 * OS Secret Service (`org.freedesktop.secrets`, such as GNOME Keyring or KWallet) over Session
 * D-Bus, never writing plaintext keys to disk.
 *
 * Thread safety: All keystore reads, writes, signing operations, and deletions are serialized via
 * an internal [Mutex] and executed on [CoroutineDispatchers.io]. Temporary private-key byte arrays
 * and [IdentityKeyPair] copies are zeroized unconditionally in `finally` blocks.
 *
 * Failure behavior: When `org.freedesktop.secrets` is absent, unreachable, or locked, fails fast
 * with [Result.failure] wrapping `DomainException`([StorageError]) instructing that a key service
 * such as GNOME Keyring or KWallet is required. Always rethrows [CancellationException].
 */
class LinuxSecretServiceKeyStoreProviderImpl internal constructor(
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val secretServiceClient: SecretServiceClient,
) : KeyStoreProvider, Closeable {
    constructor(
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
    ) : this(
        dispatchers = dispatchers,
        logger = logger,
        secretServiceClient = SecretServiceClient(),
    )

    private val mutex: Mutex = Mutex()

    private val identityItemAttributes: Map<String, String> =
        mapOf(
            SecretServiceConstants.ATTRIBUTE_APPLICATION_KEY to SecretServiceConstants.ATTRIBUTE_APPLICATION_VALUE,
            SecretServiceConstants.ATTRIBUTE_KEY_KIND_KEY to SecretServiceConstants.ATTRIBUTE_KEY_KIND_VALUE,
        )

    /**
     * Checks whether the local Ed25519 identity key exists in the Linux Secret Service.
     */
    override suspend fun hasIdentityKeyPair(): Result<Boolean> =
        executeKeyStoreOperation("checking identity key presence") {
            secretServiceClient.hasSecret(identityItemAttributes)
        }

    /**
     * Generates a fresh Ed25519 private key, stores it in the Linux Secret Service, zeroizes all
     * temporary key buffers, and returns its [PublicKey].
     */
    override suspend fun generateIdentityKeyPair(): Result<PublicKey> =
        executeKeyStoreOperation("generating and storing identity key pair") {
            val privateKeyBytes = CryptoEngine.generateEd25519PrivateKey()
            ByteArrayZeroizer.useAndZeroize(privateKeyBytes) { secret ->
                val publicKeyBytes = CryptoEngine.deriveEd25519PublicKey(secret)
                val publicKey = PublicKey.fromBytes(publicKeyBytes).getOrThrow()
                secretServiceClient.storeSecret(
                    attributes = identityItemAttributes,
                    label = SecretServiceConstants.ITEM_LABEL_IDENTITY_KEY,
                    secret = secret,
                )
                logger.info(
                    tag = SecretServiceConstants.TAG_KEYSTORE,
                    message = "Generated and stored identity key pair in Linux Secret Service.",
                )
                publicKey
            }
        }

    /**
     * Retrieves the Ed25519 [PublicKey] derived from the key stored in Secret Service, or `null`
     * when no identity key has been created.
     */
    override suspend fun getIdentityPublicKey(): Result<PublicKey?> =
        executeKeyStoreOperation("loading identity public key") {
            val privateKeyBytes =
                secretServiceClient.readSecret(identityItemAttributes)
                    ?: return@executeKeyStoreOperation null
            ByteArrayZeroizer.useAndZeroize(privateKeyBytes) { secret ->
                validatePrivateKeyLength(secret)
                val publicKeyBytes = CryptoEngine.deriveEd25519PublicKey(secret)
                PublicKey.fromBytes(publicKeyBytes).getOrThrow()
            }
        }

    /**
     * Signs [data] with the identity key held in Secret Service without exposing private key bytes
     * to callers, zeroizing all in-memory key copies before returning.
     */
    override suspend fun signWithIdentityKey(data: ByteArray): Result<ByteArray> =
        executeKeyStoreOperation("signing payload with identity key") {
            val privateKeyBytes =
                secretServiceClient.readSecret(identityItemAttributes)
                    ?: error("Identity key pair is missing from Linux Secret Service.")
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
     * Permanently deletes the stored local identity key item from Linux Secret Service.
     */
    override suspend fun deleteIdentityKeyPair(): Result<Unit> =
        executeKeyStoreOperation("deleting identity key pair") {
            secretServiceClient.deleteSecret(identityItemAttributes)
            logger.info(
                tag = SecretServiceConstants.TAG_KEYSTORE,
                message = "Deleted identity key pair from Linux Secret Service.",
            )
        }

    /**
     * Closes the underlying Secret Service D-Bus client connection and active session.
     */
    override fun close() {
        try {
            secretServiceClient.close()
        } catch (exception: IOException) {
            logger.warn(
                tag = SecretServiceConstants.TAG_KEYSTORE,
                message = "Failed to close Linux Secret Service client cleanly.",
                throwable = exception,
            )
        }
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
                } catch (exception: DBusException) {
                    failStorageError(operationName, exception)
                } catch (exception: DBusExecutionException) {
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
            "Corrupted identity key length in Secret Service: expected " +
                "${CryptoConstants.ED25519_PRIVATE_KEY_LENGTH_BYTES} bytes, got ${secret.size}."
        }
    }

    private fun <T> failStorageError(
        operationName: String,
        throwable: Throwable,
    ): Result<T> {
        val reason = throwable.message?.takeIf { it.isNotBlank() } ?: (throwable::class.simpleName ?: "unknown error")
        val storageError =
            StorageError(
                "${SecretServiceConstants.SECRET_SERVICE_UNAVAILABLE_MESSAGE} " +
                    "Operation '$operationName' failed: $reason.",
            )
        logger.error(
            tag = SecretServiceConstants.TAG_KEYSTORE,
            message = "Linux Secret Service operation failed while $operationName.",
            throwable = throwable,
        )
        return storageError.asFailure(throwable)
    }
}
