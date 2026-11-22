package com.bluemessenger

import app.cash.sqldelight.db.SqlDriver
import com.bluemessenger.crypto.CryptoServiceImpl
import com.bluemessenger.crypto.LinuxSecretServiceKeyStoreProviderImpl
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.ports.crypto.CryptoService
import com.bluemessenger.domain.ports.crypto.KeyStoreProvider
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.storage.IdentityRepository
import com.bluemessenger.domain.ports.storage.MessageRepository
import com.bluemessenger.domain.ports.storage.PeerRepository
import com.bluemessenger.domain.ports.transport.BluetoothScanner
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.usecase.message.FlushPendingMessagesUseCase
import com.bluemessenger.domain.usecase.message.ObserveIncomingFramesUseCase
import com.bluemessenger.domain.usecase.message.ObserveMessagesUseCase
import com.bluemessenger.domain.usecase.message.SendMessageUseCase
import com.bluemessenger.domain.usecase.message.util.EncryptedPacketSender
import com.bluemessenger.domain.usecase.peer.DiscoverPeersUseCase
import com.bluemessenger.domain.usecase.peer.InitializeIdentityUseCase
import com.bluemessenger.domain.usecase.peer.ObserveConversationsUseCase
import com.bluemessenger.domain.usecase.peer.VerifyPeerSafetyNumberUseCase
import com.bluemessenger.domain.usecase.session.EstablishSecureSessionUseCase
import com.bluemessenger.domain.usecase.session.ManageKeepAliveAndReconnectUseCase
import com.bluemessenger.logging.JvmAppLoggerImpl
import com.bluemessenger.storage.BlueMessengerDatabase
import com.bluemessenger.storage.JvmDatabaseDriverFactory
import com.bluemessenger.storage.repository.SqlDelightIdentityRepositoryImpl
import com.bluemessenger.storage.repository.SqlDelightMessageRepositoryImpl
import com.bluemessenger.storage.repository.SqlDelightPeerRepositoryImpl
import com.bluemessenger.transport.LinuxBluetoothPermissionChecker
import com.bluemessenger.transport.LinuxBluetoothScannerImpl
import com.bluemessenger.transport.LinuxBluetoothTransportImpl
import com.bluemessenger.transport.dbus.BluezDbusClient
import java.io.Closeable
import java.io.IOException

/**
 * Manual constructor-based dependency composition root for the Linux Desktop (`jvmMain`) target.
 *
 * Thread safety: Constructed dependencies are immutable references to thread-safe ports,
 * SQLDelight repositories, and domain use cases. [close] deterministically releases Bluetooth,
 * D-Bus, Secret Service, and SQLite resources.
 *
 * Failure behavior: Database initialization via [JvmDatabaseDriverFactory] fails fast on schema
 * errors; D-Bus Bluetooth and Secret Service adapters connect lazily and fail fast with typed
 * `DomainException` errors when platform services or permissions are unavailable.
 */
class LinuxAppModule(
    jdbcUrl: String,
    val dispatchers: CoroutineDispatchers,
    val clock: () -> Instant = { Instant(System.currentTimeMillis()) },
) : Closeable {
    val logger: AppLogger = JvmAppLoggerImpl()

    private val sqlDriver: SqlDriver = JvmDatabaseDriverFactory(jdbcUrl).createDriver()
    val database: BlueMessengerDatabase = BlueMessengerDatabase(sqlDriver)

    val identityRepository: IdentityRepository = SqlDelightIdentityRepositoryImpl(database, dispatchers)
    val peerRepository: PeerRepository = SqlDelightPeerRepositoryImpl(database, dispatchers)
    val messageRepository: MessageRepository = SqlDelightMessageRepositoryImpl(database, dispatchers)

    private val secretServiceKeyStoreProvider: LinuxSecretServiceKeyStoreProviderImpl =
        LinuxSecretServiceKeyStoreProviderImpl(dispatchers = dispatchers, logger = logger)
    val keyStoreProvider: KeyStoreProvider = secretServiceKeyStoreProvider

    val cryptoService: CryptoService = CryptoServiceImpl(keyStoreProvider)

    private val bluezDbusClient: BluezDbusClient = BluezDbusClient()
    val bluetoothPermissionChecker: LinuxBluetoothPermissionChecker =
        LinuxBluetoothPermissionChecker(
            bluezClient = bluezDbusClient,
            dispatchers = dispatchers,
            logger = logger,
        )

    private val linuxBluetoothScanner: LinuxBluetoothScannerImpl =
        LinuxBluetoothScannerImpl(
            bluezClient = bluezDbusClient,
            permissionChecker = bluetoothPermissionChecker,
            dispatchers = dispatchers,
            logger = logger,
            clock = clock,
        )
    val bluetoothScanner: BluetoothScanner = linuxBluetoothScanner

    private val linuxBluetoothTransport: LinuxBluetoothTransportImpl =
        LinuxBluetoothTransportImpl(
            bluezClient = bluezDbusClient,
            permissionChecker = bluetoothPermissionChecker,
            dispatchers = dispatchers,
            logger = logger,
        )
    val bluetoothTransport: BluetoothTransport = linuxBluetoothTransport

    val encryptedPacketSender: EncryptedPacketSender =
        EncryptedPacketSender(
            cryptoService = cryptoService,
            bluetoothTransport = bluetoothTransport,
        )

    val initializeIdentityUseCase: InitializeIdentityUseCase =
        InitializeIdentityUseCase(
            identityRepository = identityRepository,
            keyStoreProvider = keyStoreProvider,
            dispatchers = dispatchers,
            logger = logger,
            clock = clock,
        )

    val discoverPeersUseCase: DiscoverPeersUseCase =
        DiscoverPeersUseCase(
            bluetoothScanner = bluetoothScanner,
            logger = logger,
        )

    val observeConversationsUseCase: ObserveConversationsUseCase =
        ObserveConversationsUseCase(
            peerRepository = peerRepository,
            logger = logger,
        )

    val verifyPeerSafetyNumberUseCase: VerifyPeerSafetyNumberUseCase =
        VerifyPeerSafetyNumberUseCase(
            identityRepository = identityRepository,
            peerRepository = peerRepository,
            cryptoService = cryptoService,
            logger = logger,
        )

    val establishSecureSessionUseCase: EstablishSecureSessionUseCase =
        EstablishSecureSessionUseCase(
            bluetoothTransport = bluetoothTransport,
            cryptoService = cryptoService,
            peerRepository = peerRepository,
            dispatchers = dispatchers,
            logger = logger,
            clock = clock,
        )

    val observeMessagesUseCase: ObserveMessagesUseCase =
        ObserveMessagesUseCase(
            messageRepository = messageRepository,
            logger = logger,
        )

    val observeIncomingFramesUseCase: ObserveIncomingFramesUseCase =
        ObserveIncomingFramesUseCase(
            bluetoothTransport = bluetoothTransport,
            cryptoService = cryptoService,
            messageRepository = messageRepository,
            dispatchers = dispatchers,
            logger = logger,
            clock = clock,
        )

    val sendMessageUseCase: SendMessageUseCase =
        SendMessageUseCase(
            messageRepository = messageRepository,
            peerRepository = peerRepository,
            bluetoothTransport = bluetoothTransport,
            packetSender = encryptedPacketSender,
            dispatchers = dispatchers,
            logger = logger,
            clock = clock,
        )

    val flushPendingMessagesUseCase: FlushPendingMessagesUseCase =
        FlushPendingMessagesUseCase(
            messageRepository = messageRepository,
            peerRepository = peerRepository,
            bluetoothTransport = bluetoothTransport,
            packetSender = encryptedPacketSender,
            dispatchers = dispatchers,
            logger = logger,
        )

    val manageKeepAliveAndReconnectUseCase: ManageKeepAliveAndReconnectUseCase =
        ManageKeepAliveAndReconnectUseCase(
            bluetoothTransport = bluetoothTransport,
            cryptoService = cryptoService,
            peerRepository = peerRepository,
            establishSecureSessionUseCase = establishSecureSessionUseCase,
            flushPendingMessagesUseCase = flushPendingMessagesUseCase,
            dispatchers = dispatchers,
            logger = logger,
            clock = clock,
        )

    /**
     * Releases all transport, scanner, D-Bus, Secret Service, and SQLite driver resources.
     */
    override fun close() {
        closeQuietly("transport", linuxBluetoothTransport)
        closeQuietly("scanner", linuxBluetoothScanner)
        closeQuietly("bluezDbusClient", bluezDbusClient)
        closeQuietly("secretServiceKeyStore", secretServiceKeyStoreProvider)
        closeQuietly("sqlDriver", sqlDriver)
    }

    private fun closeQuietly(
        component: String,
        closeable: Closeable,
    ) {
        try {
            closeable.close()
        } catch (exception: IOException) {
            logger.warn(
                tag = TAG,
                message = "Failed to close LinuxAppModule resource.",
                throwable = exception,
                context = mapOf("component" to component),
            )
        }
    }

    private companion object {
        private const val TAG: String = "LinuxAppModule"
    }
}
