package com.bluemessenger.crypto

import com.bluemessenger.crypto.constants.AndroidKeyStoreConstants
import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.ports.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidKeyStoreProviderTest {
    private val dispatchers =
        CoroutineDispatchers(
            main = Dispatchers.Unconfined,
            io = Dispatchers.IO,
            default = Dispatchers.Default,
        )

    @Test
    fun generatesRetrievesSignsZeroizesAndDeletesIdentityKeyViaRealAesGcmVault() {
        runBlocking {
            val tempDir = Files.createTempDirectory("android-keystore-test").toFile()
            try {
                val vault = InMemoryAesKeyStoreVault(tempDir, generateAes256Key())
                val provider =
                    AndroidKeyStoreProviderImpl(
                        dispatchers = dispatchers,
                        logger = NoOpLogger,
                        vault = vault,
                    )

                assertFalse(provider.hasIdentityKeyPair().getOrThrow())
                assertNull(provider.getIdentityPublicKey().getOrThrow())

                val missingSignResult = provider.signWithIdentityKey("payload".encodeToByteArray())
                assertTrue(missingSignResult.isFailure)
                assertIs<StorageError>(missingSignResult.exceptionOrNull()?.toDomainErrorOrNull())

                val generatedPublicKey = provider.generateIdentityKeyPair().getOrThrow()
                assertTrue(provider.hasIdentityKeyPair().getOrThrow())
                assertEquals(generatedPublicKey, provider.getIdentityPublicKey().getOrThrow())

                val envelopeFile = File(tempDir, AndroidKeyStoreConstants.ENVELOPE_FILE_NAME)
                assertTrue(envelopeFile.exists())
                val envelopeBytes = envelopeFile.readBytes()
                assertEquals(AndroidKeyStoreConstants.ENVELOPE_VERSION, envelopeBytes[0])
                assertTrue(vault.capturedPlaintextSnapshots.isNotEmpty())
                val rawSecretSnapshot = vault.capturedPlaintextSnapshots.first()
                assertFalse(containsSubarray(envelopeBytes, rawSecretSnapshot))

                val payload = "blue-messenger-android-handshake".encodeToByteArray()
                val signature = provider.signWithIdentityKey(payload).getOrThrow()
                assertTrue(IdentityKeyPair.verify(generatedPublicKey, payload, signature))
                assertTrue(
                    CryptoEngine.ed25519Verify(
                        publicKey = generatedPublicKey.toByteArray(),
                        message = payload,
                        signature = signature,
                    ),
                )

                assertTrue(vault.observedEncryptInputBuffers.isNotEmpty())
                assertTrue(vault.observedDecryptOutputBuffers.isNotEmpty())
                assertTrue(vault.observedEncryptInputBuffers.all { buf -> buf.all { it == 0.toByte() } })
                assertTrue(vault.observedDecryptOutputBuffers.all { buf -> buf.all { it == 0.toByte() } })

                assertTrue(provider.deleteIdentityKeyPair().isSuccess)
                assertFalse(provider.hasIdentityKeyPair().getOrThrow())
                assertNull(provider.getIdentityPublicKey().getOrThrow())
                assertFalse(envelopeFile.exists())

                val signAfterDelete = provider.signWithIdentityKey(payload)
                assertTrue(signAfterDelete.isFailure)
                assertIs<StorageError>(signAfterDelete.exceptionOrNull()?.toDomainErrorOrNull())
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    @Test
    fun failsFastWithStorageErrorWhenEnvelopeCiphertextOrTagIsTampered() {
        runBlocking {
            val tempDir = Files.createTempDirectory("android-keystore-tamper-test").toFile()
            try {
                val vault = InMemoryAesKeyStoreVault(tempDir, generateAes256Key())
                val provider =
                    AndroidKeyStoreProviderImpl(
                        dispatchers = dispatchers,
                        logger = NoOpLogger,
                        vault = vault,
                    )

                assertNotNull(provider.generateIdentityKeyPair().getOrThrow())

                val envelopeFile = File(tempDir, AndroidKeyStoreConstants.ENVELOPE_FILE_NAME)
                val tamperedBytes = envelopeFile.readBytes()
                val lastIndex = tamperedBytes.lastIndex
                tamperedBytes[lastIndex] = (tamperedBytes[lastIndex].toInt() xor 0xFF).toByte()
                envelopeFile.writeBytes(tamperedBytes)

                val loadResult = provider.getIdentityPublicKey()
                assertTrue(loadResult.isFailure)
                val loadException = loadResult.exceptionOrNull()
                assertIs<StorageError>(loadException?.toDomainErrorOrNull())
                assertIs<AEADBadTagException>(loadException?.cause)

                val signResult = provider.signWithIdentityKey("tampered-check".encodeToByteArray())
                assertTrue(signResult.isFailure)
                assertIs<StorageError>(signResult.exceptionOrNull()?.toDomainErrorOrNull())
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    @Test
    fun rethrowsCancellationExceptionWithoutWrappingAsStorageError() {
        runBlocking {
            val tempDir = Files.createTempDirectory("android-keystore-cancel-test").toFile()
            try {
                val cancellingVault =
                    object : AndroidKeyStoreVault(
                        vaultDirProvider = { tempDir },
                        masterKeyProvider = { generateAes256Key() },
                        masterKeyDeleter = { },
                    ) {
                        override fun hasEnvelope(): Boolean = throw CancellationException("Coroutine cancelled")
                    }
                val provider =
                    AndroidKeyStoreProviderImpl(
                        dispatchers = dispatchers,
                        logger = NoOpLogger,
                        vault = cancellingVault,
                    )
                var cancellationCaught = false
                try {
                    provider.hasIdentityKeyPair()
                } catch (_: CancellationException) {
                    cancellationCaught = true
                }
                assertTrue(cancellationCaught)
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    private fun generateAes256Key(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(AndroidKeyStoreConstants.AES_KEY_SIZE_BITS)
        return generator.generateKey()
    }

    private fun containsSubarray(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean =
        if (needle.isEmpty() || needle.size > haystack.size) {
            false
        } else {
            (0..(haystack.size - needle.size)).any { start ->
                needle.indices.all { offset -> haystack[start + offset] == needle[offset] }
            }
        }

    private class InMemoryAesKeyStoreVault(
        private val dir: File,
        private val key: SecretKey,
    ) : AndroidKeyStoreVault(
            vaultDirProvider = { dir },
            masterKeyProvider = { key },
            masterKeyDeleter = { },
        ) {
        val observedEncryptInputBuffers: MutableList<ByteArray> = mutableListOf()
        val observedDecryptOutputBuffers: MutableList<ByteArray> = mutableListOf()
        val capturedPlaintextSnapshots: MutableList<ByteArray> = mutableListOf()

        override fun encryptAndStore(plaintextSecret: ByteArray) {
            observedEncryptInputBuffers.add(plaintextSecret)
            capturedPlaintextSnapshots.add(plaintextSecret.copyOf())
            super.encryptAndStore(plaintextSecret)
        }

        override fun loadAndDecrypt(): ByteArray? =
            super.loadAndDecrypt()?.also { decrypted ->
                observedDecryptOutputBuffers.add(decrypted)
            }
    }

    private object NoOpLogger : AppLogger {
        override fun debug(
            tag: String,
            message: String,
            context: Map<String, String>,
        ) = Unit

        override fun info(
            tag: String,
            message: String,
            context: Map<String, String>,
        ) = Unit

        override fun warn(
            tag: String,
            message: String,
            throwable: Throwable?,
            context: Map<String, String>,
        ) = Unit

        override fun error(
            tag: String,
            message: String,
            throwable: Throwable?,
            context: Map<String, String>,
        ) = Unit
    }
}
