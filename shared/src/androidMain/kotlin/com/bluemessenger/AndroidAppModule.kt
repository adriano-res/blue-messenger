package com.bluemessenger

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import com.bluemessenger.crypto.AndroidKeyStoreProviderImpl
import com.bluemessenger.crypto.AndroidKeyStoreVault
import com.bluemessenger.crypto.CryptoServiceImpl
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
import com.bluemessenger.logging.AndroidAppLoggerImpl
import com.bluemessenger.storage.AndroidDatabaseDriverFactory
import com.bluemessenger.storage.BlueMessengerDatabase
import com.bluemessenger.storage.repository.SqlDelightIdentityRepositoryImpl
import com.bluemessenger.storage.repository.SqlDelightMessageRepositoryImpl
import com.bluemessenger.storage.repository.SqlDelightPeerRepositoryImpl
import com.bluemessenger.transport.AndroidBluetoothGateway
import com.bluemessenger.transport.AndroidBluetoothPermissionChecker
import com.bluemessenger.transport.AndroidBluetoothScannerImpl
import com.bluemessenger.transport.AndroidBluetoothTransportImpl
import java.io.Closeable
import java.io.IOException

/**
 * Manual constructor-based dependency composition root for the Android (`androidMain`) target.
 *
 * Retains only [Context.getApplicationContext] to prevent leaking Activity or Service references.
 *
 * Thread safety: Constructed dependencies are immutable references to thread-safe ports,
 * SQLDelight repositories, and domain use cases. [close] deterministically releases Bluetooth
 * transport, scanner, and SQLite driver resources.
 *
 * Failure behavior: Database initialization via [AndroidDatabaseDriverFactory] enforces SQLite
 * foreign keys; Bluetooth and Android KeyStore adapters fail fast with typed `DomainException`
 * errors when runtime permissions, hardware adapters, or key storage are unavailable.
 */
class AndroidAppModule internal constructor(
    val dispatchers: CoroutineDispatchers,
    val clock: () -> Instant,
    val logger: AppLogger,
    private val sqlDriver: SqlDriver,
    bluetoothGateway: AndroidBluetoothGateway,
    keyStoreVault: AndroidKeyStoreVault,
) : Closeable {
    constructor(
        context: Context,
        dispatchers: CoroutineDispatchers,
        clock: () -> Instant = { Instant(System.currentTimeMillis()) },
    ) : this(
        dispatchers = dispatchers,
        clock = clock,
        logger = AndroidAppLoggerImpl(),
        sqlDriver = AndroidDatabaseDriverFactory(context.applicationContext).createDriver(),
        bluetoothGateway = AndroidBluetoothGateway(context.applicationContext),
        keyStoreVault = AndroidKeyStoreVault(context.applicationContext),
    )

    val database: BlueMessengerDatabase = BlueMessengerDatabase(sqlDriver)

    val identityRepository: IdentityRepository = SqlDelightIdentityRepositoryImpl(database, dispatchers)
    val peerRepository: PeerRepository = SqlDelightPeerRepositoryImpl(database, dispatchers)
    val messageRepository: MessageRepository = SqlDelightMessageRepositoryImpl(database, dispatchers)

    val keyStoreProvider: KeyStoreProvider =
        AndroidKeyStoreProviderImpl(
            dispatchers = dispatchers,
            logger = logger,
            vault = keyStoreVault,
        )

    val cryptoService: CryptoService = CryptoServiceImpl(keyStoreProvider)

    val bluetoothPermissionChecker: AndroidBluetoothPermissionChecker =
        AndroidBluetoothPermissionChecker(
            gateway = bluetoothGateway,
            logger = logger,
        )

    private val androidBluetoothScanner: AndroidBluetoothScannerImpl =
        AndroidBluetoothScannerImpl(
            gateway = bluetoothGateway,
            permissionChecker = bluetoothPermissionChecker,
            dispatchers = dispatchers,
            logger = logger,
            clock = clock,
        )
    val bluetoothScanner: BluetoothScanner = androidBluetoothScanner

    private val androidBluetoothTransport: AndroidBluetoothTransportImpl =
        AndroidBluetoothTransportImpl(
            gateway = bluetoothGateway,
            permissionChecker = bluetoothPermissionChecker,
            dispatchers = dispatchers,
            logger = logger,
        )
    val bluetoothTransport: BluetoothTransport = androidBluetoothTransport

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
     * Releases all Bluetooth transport, scanner, and SQLite driver resources.
     */
    override fun close() {
        closeQuietly("transport", androidBluetoothTransport)
        closeQuietly("scanner", androidBluetoothScanner)
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
                message = "Failed to close AndroidAppModule resource.",
                throwable = exception,
                context = mapOf("component" to component),
            )
        }
    }

    private companion object {
        private const val TAG: String = "AndroidAppModule"
    }
}
