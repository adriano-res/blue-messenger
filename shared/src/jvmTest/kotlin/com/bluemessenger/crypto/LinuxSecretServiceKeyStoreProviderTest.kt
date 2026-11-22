package com.bluemessenger.crypto

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bluemessenger.LinuxAppModule
import com.bluemessenger.crypto.dbus.SecretServiceClient
import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.ports.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxSecretServiceKeyStoreProviderTest {
    private val dispatchers =
        CoroutineDispatchers(
            main = Dispatchers.Unconfined,
            io = Dispatchers.IO,
            default = Dispatchers.Default,
        )

    @Test
    fun generatesRetrievesSignsDeletesAndZeroizesTemporaryPrivateKeyBuffers() =
        runBlocking {
            val fakeClient = InMemorySecretServiceClient()
            val provider =
                LinuxSecretServiceKeyStoreProviderImpl(
                    dispatchers = dispatchers,
                    logger = NoOpLogger,
                    secretServiceClient = fakeClient,
                )

            assertFalse(provider.hasIdentityKeyPair().getOrThrow())
            assertNull(provider.getIdentityPublicKey().getOrThrow())

            val generatedPublicKey = provider.generateIdentityKeyPair().getOrThrow()
            assertTrue(provider.hasIdentityKeyPair().getOrThrow())
            assertEquals(generatedPublicKey, provider.getIdentityPublicKey().getOrThrow())

            val payload = "blue-messenger-handshake-transcript".encodeToByteArray()
            val signature = provider.signWithIdentityKey(payload).getOrThrow()
            assertTrue(IdentityKeyPair.verify(generatedPublicKey, payload, signature))

            assertTrue(fakeClient.observedStoreBuffers.isNotEmpty())
            assertTrue(fakeClient.observedReadBuffers.isNotEmpty())
            assertTrue(fakeClient.observedStoreBuffers.all { buffer -> buffer.all { it == 0.toByte() } })
            assertTrue(fakeClient.observedReadBuffers.all { buffer -> buffer.all { it == 0.toByte() } })

            assertTrue(provider.deleteIdentityKeyPair().isSuccess)
            assertFalse(provider.hasIdentityKeyPair().getOrThrow())
            assertNull(provider.getIdentityPublicKey().getOrThrow())

            val signAfterDelete = provider.signWithIdentityKey(payload)
            assertTrue(signAfterDelete.isFailure)
            assertIs<StorageError>(signAfterDelete.exceptionOrNull()?.toDomainErrorOrNull())
            provider.close()
        }

    @Test
    fun failsFastWithStorageErrorMentioningGnomeKeyringAndKWalletWhenSecretServiceAbsentOrLocked() =
        runBlocking {
            val unavailableClient =
                UnavailableSecretServiceClient {
                    DBusException("The name org.freedesktop.secrets was not provided by any .service files")
                }
            val unavailableProvider =
                LinuxSecretServiceKeyStoreProviderImpl(
                    dispatchers = dispatchers,
                    logger = NoOpLogger,
                    secretServiceClient = unavailableClient,
                )

            val checkResult = unavailableProvider.hasIdentityKeyPair()
            assertTrue(checkResult.isFailure)
            val checkError = assertIs<StorageError>(checkResult.exceptionOrNull()?.toDomainErrorOrNull())
            assertTrue(checkError.message.contains("GNOME Keyring"))
            assertTrue(checkError.message.contains("KWallet"))

            val lockedClient =
                UnavailableSecretServiceClient {
                    DBusExecutionException("Secret Service keyring is locked and requires interactive unlocking.")
                }
            val lockedProvider =
                LinuxSecretServiceKeyStoreProviderImpl(
                    dispatchers = dispatchers,
                    logger = NoOpLogger,
                    secretServiceClient = lockedClient,
                )

            val generateResult = lockedProvider.generateIdentityKeyPair()
            assertTrue(generateResult.isFailure)
            val generateError = assertIs<StorageError>(generateResult.exceptionOrNull()?.toDomainErrorOrNull())
            assertTrue(generateError.message.contains("GNOME Keyring"))
            assertTrue(generateError.message.contains("KWallet"))
        }

    @Test
    fun linuxAppModuleWiresAllRepositoriesAndUseCasesWithInMemoryDatabase() {
        val module =
            LinuxAppModule(
                jdbcUrl = JdbcSqliteDriver.IN_MEMORY,
                dispatchers = dispatchers,
            )
        try {
            assertNotNull(module.logger)
            assertNotNull(module.database)
            assertNotNull(module.identityRepository)
            assertNotNull(module.peerRepository)
            assertNotNull(module.messageRepository)
            assertNotNull(module.keyStoreProvider)
            assertNotNull(module.cryptoService)
            assertNotNull(module.bluetoothPermissionChecker)
            assertNotNull(module.bluetoothScanner)
            assertNotNull(module.bluetoothTransport)
            assertNotNull(module.encryptedPacketSender)
            assertNotNull(module.initializeIdentityUseCase)
            assertNotNull(module.discoverPeersUseCase)
            assertNotNull(module.observeConversationsUseCase)
            assertNotNull(module.verifyPeerSafetyNumberUseCase)
            assertNotNull(module.establishSecureSessionUseCase)
            assertNotNull(module.manageKeepAliveAndReconnectUseCase)
            assertNotNull(module.sendMessageUseCase)
            assertNotNull(module.observeMessagesUseCase)
            assertNotNull(module.observeIncomingFramesUseCase)
            assertNotNull(module.flushPendingMessagesUseCase)
        } finally {
            module.close()
        }
    }

    private class InMemorySecretServiceClient : SecretServiceClient() {
        private val entries: MutableMap<Map<String, String>, ByteArray> = LinkedHashMap()
        val observedStoreBuffers: MutableList<ByteArray> = mutableListOf()
        val observedReadBuffers: MutableList<ByteArray> = mutableListOf()

        override fun hasSecret(attributes: Map<String, String>): Boolean = entries.containsKey(attributes)

        override fun storeSecret(
            attributes: Map<String, String>,
            label: String,
            secret: ByteArray,
        ) {
            observedStoreBuffers.add(secret)
            entries[attributes] = secret.copyOf()
        }

        override fun readSecret(attributes: Map<String, String>): ByteArray? =
            entries[attributes]?.copyOf()?.also { copy ->
                observedReadBuffers.add(copy)
            }

        override fun deleteSecret(attributes: Map<String, String>) {
            entries.remove(attributes)?.fill(0)
        }

        override fun close() {
            entries.values.forEach { it.fill(0) }
            entries.clear()
        }
    }

    private class UnavailableSecretServiceClient(
        private val errorFactory: () -> Throwable,
    ) : SecretServiceClient() {
        override fun hasSecret(attributes: Map<String, String>): Boolean = throw errorFactory()

        override fun storeSecret(
            attributes: Map<String, String>,
            label: String,
            secret: ByteArray,
        ): Unit = throw errorFactory()

        override fun readSecret(attributes: Map<String, String>): ByteArray? = throw errorFactory()

        override fun deleteSecret(attributes: Map<String, String>): Unit = throw errorFactory()

        override fun close() = Unit
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
