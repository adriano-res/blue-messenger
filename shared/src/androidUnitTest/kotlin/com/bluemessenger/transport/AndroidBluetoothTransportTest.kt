package com.bluemessenger.transport

import android.Manifest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bluemessenger.AndroidAppModule
import com.bluemessenger.crypto.AndroidKeyStoreVault
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.BluetoothAdapterDisabledError
import com.bluemessenger.domain.error.InvalidInputError
import com.bluemessenger.domain.error.MissingBluetoothPermissionError
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.transport.constants.TransportConstants
import com.bluemessenger.domain.ports.transport.model.AckPacket
import com.bluemessenger.domain.ports.transport.model.EncryptedPayloadPacket
import com.bluemessenger.domain.ports.transport.model.HandshakePacket
import com.bluemessenger.domain.ports.transport.model.KeepAlivePacket
import com.bluemessenger.domain.ports.transport.model.Packet
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.storage.BlueMessengerDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidBluetoothTransportTest {
    private val dispatchers =
        CoroutineDispatchers(
            main = Dispatchers.Unconfined,
            io = Dispatchers.IO,
            default = Dispatchers.Default,
        )

    @Test
    fun permissionCheckerFailsFastForMissingPermissionsAndUnavailableOrDisabledAdapter() {
        val logger = RecordingAppLogger()

        val missingScanGateway =
            FakeAndroidBluetoothGateway(
                grantedPermissions =
                    setOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
            )
        val scanResult = AndroidBluetoothPermissionChecker(missingScanGateway, logger).ensureScanReady()
        assertTrue(scanResult.isFailure)
        val scanError = assertIs<MissingBluetoothPermissionError>(scanResult.exceptionOrNull()?.toDomainErrorOrNull())
        assertEquals(Manifest.permission.BLUETOOTH_SCAN, scanError.permission)

        val missingConnectGateway =
            FakeAndroidBluetoothGateway(
                grantedPermissions =
                    setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE),
            )
        val connectResult = AndroidBluetoothPermissionChecker(missingConnectGateway, logger).ensureConnectReady()
        assertTrue(connectResult.isFailure)
        val connectError =
            assertIs<MissingBluetoothPermissionError>(connectResult.exceptionOrNull()?.toDomainErrorOrNull())
        assertEquals(Manifest.permission.BLUETOOTH_CONNECT, connectError.permission)

        val missingAdvertiseGateway =
            FakeAndroidBluetoothGateway(
                grantedPermissions = setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            )
        val advertiseResult = AndroidBluetoothPermissionChecker(missingAdvertiseGateway, logger).ensureAdvertiseReady()
        assertTrue(advertiseResult.isFailure)
        val advertiseError =
            assertIs<MissingBluetoothPermissionError>(advertiseResult.exceptionOrNull()?.toDomainErrorOrNull())
        assertEquals(Manifest.permission.BLUETOOTH_ADVERTISE, advertiseError.permission)

        val noAdapterGateway = FakeAndroidBluetoothGateway(adapterAvailable = false, adapterEnabled = false)
        val noAdapterResult = AndroidBluetoothPermissionChecker(noAdapterGateway, logger).ensureAllReady()
        assertTrue(noAdapterResult.isFailure)
        assertIs<BluetoothAdapterDisabledError>(noAdapterResult.exceptionOrNull()?.toDomainErrorOrNull())

        val disabledAdapterGateway = FakeAndroidBluetoothGateway(adapterAvailable = true, adapterEnabled = false)
        val disabledResult = AndroidBluetoothPermissionChecker(disabledAdapterGateway, logger).ensureAllReady()
        assertTrue(disabledResult.isFailure)
        assertIs<BluetoothAdapterDisabledError>(disabledResult.exceptionOrNull()?.toDomainErrorOrNull())

        val readyGateway = FakeAndroidBluetoothGateway()
        assertTrue(AndroidBluetoothPermissionChecker(readyGateway, logger).ensureAllReady().isSuccess)
        assertTrue(logger.warnings.isNotEmpty())
    }

    @Test
    fun scannerFiltersByCanonicalUuidHandlesBondedAndDiscoveryFinishedAndCleansUpDeterministically() {
        runBlocking {
            val logger = RecordingAppLogger()
            val bondedNoUuid =
                AndroidDiscoveredDevice(
                    address = "AA:BB:CC:DD:EE:01",
                    name = "BondedPeer",
                    uuids = emptyList(),
                )
            val gateway =
                FakeAndroidBluetoothGateway(
                    bondedDevices = listOf(bondedNoUuid),
                )
            val scanner = createScanner(gateway, logger)

            assertTrue(scanner.startDiscovery().isSuccess)
            assertTrue(scanner.isScanning.value)
            assertTrue(gateway.discoveryStarted)
            assertEquals(1, gateway.activeReceiverCount)
            assertEquals(listOf("AA:BB:CC:DD:EE:01"), gateway.sdpFetchRequests.toList())
            assertTrue(scanner.discoveredPeers.value.isEmpty())

            gateway.emitDeviceFound(
                AndroidDiscoveredDevice(
                    address = "11:22:33:44:55:66",
                    name = "BluetoothSpeaker",
                    uuids = listOf("0000110b-0000-1000-8000-00805f9b34fb"),
                ),
            )
            assertTrue(scanner.discoveredPeers.value.isEmpty())

            gateway.emitDeviceUuidsFetched(
                address = "aa:bb:cc:dd:ee:01",
                uuids = listOf(TransportConstants.CANONICAL_SERVICE_UUID.uppercase()),
            )
            gateway.emitDeviceFound(
                AndroidDiscoveredDevice(
                    address = "22:33:44:55:66:77",
                    name = "LinuxPeer",
                    uuids = listOf(TransportConstants.CANONICAL_SERVICE_UUID),
                ),
            )

            val peers = scanner.discoveredPeers.value
            assertEquals(2, peers.size)
            assertEquals(listOf("BondedPeer", "LinuxPeer"), peers.map { it.nickname })
            assertEquals(
                listOf(BluetoothAddress("AA:BB:CC:DD:EE:01"), BluetoothAddress("22:33:44:55:66:77")),
                peers.map { it.address },
            )

            gateway.emitDiscoveryFinished()
            assertFalse(scanner.isScanning.value)
            assertEquals(0, gateway.activeReceiverCount)

            assertTrue(scanner.startDiscovery().isSuccess)
            assertEquals(1, gateway.activeReceiverCount)
            assertTrue(scanner.stopDiscovery().isSuccess)
            assertFalse(scanner.isScanning.value)
            assertFalse(gateway.discoveryStarted)
            assertEquals(0, gateway.activeReceiverCount)
            assertEquals(gateway.registeredReceiversCount, gateway.unregisteredReceiversCount)

            scanner.close()
        }
    }

    @Test
    fun scannerMapsSecurityExceptionFailFastAndManagesAdvertisingSocketLifecycle() {
        runBlocking {
            val logger = RecordingAppLogger()
            val securityErrorGateway =
                FakeAndroidBluetoothGateway().apply {
                    onStartDiscovery = { throw SecurityException("Need android.permission.BLUETOOTH_SCAN") }
                }
            val failingScanner = createScanner(securityErrorGateway, logger)
            val startResult = failingScanner.startDiscovery()
            assertTrue(startResult.isFailure)
            val permissionError =
                assertIs<MissingBluetoothPermissionError>(startResult.exceptionOrNull()?.toDomainErrorOrNull())
            assertEquals(Manifest.permission.BLUETOOTH_SCAN, permissionError.permission)
            assertEquals(0, securityErrorGateway.activeReceiverCount)

            val firstHandle = FakeServerSocketHandle()
            val secondHandle = FakeServerSocketHandle()
            val handleQueue = ArrayDeque(listOf(firstHandle, secondHandle))
            val advertisingGateway =
                FakeAndroidBluetoothGateway(
                    serverSocketFactory = { _, _ -> handleQueue.removeFirst() },
                )
            val scanner = createScanner(advertisingGateway, logger)

            val blankResult = scanner.startAdvertising("   ")
            assertTrue(blankResult.isFailure)
            assertIs<InvalidInputError>(blankResult.exceptionOrNull()?.toDomainErrorOrNull())

            assertTrue(scanner.startAdvertising("PixelPhone").isSuccess)
            assertTrue(scanner.isAdvertising.value)
            assertFalse(firstHandle.closed.get())

            assertTrue(scanner.startAdvertising("PixelPhoneRenamed").isSuccess)
            assertTrue(firstHandle.closed.get())
            assertFalse(secondHandle.closed.get())

            assertTrue(scanner.stopAdvertising().isSuccess)
            assertFalse(scanner.isAdvertising.value)
            assertTrue(secondHandle.closed.get())

            scanner.close()
        }
    }

    @Test
    fun transportExchangesPacketsBidirectionallyOverServerAndClientLoopbackPipes() {
        runBlocking {
            val logger = RecordingAppLogger()
            val (clientChannel, serverChannel) = createLoopbackChannelPair(logger)
            val serverHandle = FakeServerSocketHandle(onAccept = { _, _ -> serverChannel })
            val serverGateway = FakeAndroidBluetoothGateway(serverSocketFactory = { _, _ -> serverHandle })
            val serverTransport = createTransport(serverGateway, logger)

            val clientGateway =
                FakeAndroidBluetoothGateway(
                    grantedPermissions = setOf(Manifest.permission.BLUETOOTH_CONNECT),
                    onConnectClient = { _, _, _, _ -> clientChannel },
                )
            val clientTransport = createTransport(clientGateway, logger)

            try {
                assertTrue(serverTransport.startServer().isSuccess)
                awaitCondition { serverTransport.sessionState.value == SessionState.HANDSHAKING }
                assertTrue(serverHandle.closed.get())

                assertTrue(clientTransport.connect(BluetoothAddress("AA:BB:CC:DD:EE:FF")).isSuccess)
                assertEquals(SessionState.HANDSHAKING, clientTransport.sessionState.value)
                assertFalse(clientGateway.cancelDiscoveryCalled)

                verifyBidirectionalExchange(serverTransport, clientTransport)

                assertTrue(clientTransport.disconnect().isSuccess)
                awaitCondition { serverTransport.sessionState.value == SessionState.DISCONNECTED }
                assertEquals(SessionState.DISCONNECTED, clientTransport.sessionState.value)
            } finally {
                clientTransport.close()
                serverTransport.close()
            }
        }
    }

    @Test
    fun transportFailsFastOnTimeoutAndSecurityExceptionAndCancelsDiscoveryWhenPermitted() {
        runBlocking {
            val logger = RecordingAppLogger()
            val timeoutGateway =
                FakeAndroidBluetoothGateway(
                    onConnectClient = { _, _, _, _ ->
                        Thread.sleep(400L)
                        throw IOException("Should have timed out before returning")
                    },
                )
            val timeoutTransport = createTransport(timeoutGateway, logger, connectionTimeoutMs = 40L)
            val timeoutResult = timeoutTransport.connect(BluetoothAddress("AA:BB:CC:DD:EE:01"))
            assertTrue(timeoutResult.isFailure)
            assertIs<TransportDisconnectedError>(timeoutResult.exceptionOrNull()?.toDomainErrorOrNull())
            assertTrue(timeoutGateway.cancelDiscoveryCalled)
            assertEquals(SessionState.DISCONNECTED, timeoutTransport.sessionState.value)
            timeoutTransport.close()

            val securityGateway =
                FakeAndroidBluetoothGateway(
                    onConnectClient = { _, _, _, _ ->
                        throw SecurityException("Revoked BLUETOOTH_CONNECT")
                    },
                )
            val securityTransport = createTransport(securityGateway, logger)
            val securityResult = securityTransport.connect(BluetoothAddress("AA:BB:CC:DD:EE:02"))
            assertTrue(securityResult.isFailure)
            assertIs<MissingBluetoothPermissionError>(securityResult.exceptionOrNull()?.toDomainErrorOrNull())
            assertEquals(SessionState.DISCONNECTED, securityTransport.sessionState.value)
            securityTransport.close()

            assertFailsWith<IllegalArgumentException> {
                BluetoothAddress("INVALID")
            }
        }
    }

    @Test
    fun transportRethrowsCancellationExceptionWhenConnectIsCancelled() {
        runBlocking {
            val logger = RecordingAppLogger()
            val connectEntered = CompletableDeferred<Unit>()
            val cancellableGateway =
                FakeAndroidBluetoothGateway(
                    onConnectClient = { _, _, _, _ ->
                        connectEntered.complete(Unit)
                        Thread.sleep(5_000L)
                        throw IOException("Interrupted")
                    },
                )
            val cancellableTransport = createTransport(cancellableGateway, logger, connectionTimeoutMs = 5_000L)
            val cancellationSeen = AtomicBoolean(false)
            val connectJob =
                launch {
                    try {
                        cancellableTransport.connect(BluetoothAddress("AA:BB:CC:DD:EE:03"))
                    } catch (cancellation: CancellationException) {
                        cancellationSeen.set(true)
                        throw cancellation
                    }
                }
            withTimeout(3_000L) { connectEntered.await() }
            connectJob.cancelAndJoin()
            assertTrue(cancellationSeen.get())
            cancellableTransport.close()
        }
    }

    @Test
    fun androidAppModuleWiresAllRepositoriesAdaptersAndUseCases() {
        val tempDir = Files.createTempDirectory("android-app-module-test").toFile()
        val properties = Properties().apply { setProperty("foreign_keys", "true") }
        val sqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, properties)
        BlueMessengerDatabase.Schema.create(sqlDriver)
        val aesKey = generateAes256TestKey()
        val vault = TestKeyStoreVault(tempDir, aesKey)
        val module =
            AndroidAppModule(
                dispatchers = dispatchers,
                clock = { Instant(1_700_000_000_000L) },
                logger = RecordingAppLogger(),
                sqlDriver = sqlDriver,
                bluetoothGateway = FakeAndroidBluetoothGateway(),
                keyStoreVault = vault,
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
            assertNotNull(module.observeMessagesUseCase)
            assertNotNull(module.observeIncomingFramesUseCase)
            assertNotNull(module.sendMessageUseCase)
            assertNotNull(module.flushPendingMessagesUseCase)
            assertNotNull(module.manageKeepAliveAndReconnectUseCase)
        } finally {
            module.close()
            tempDir.deleteRecursively()
        }
    }

    private fun createScanner(
        gateway: FakeAndroidBluetoothGateway,
        logger: AppLogger,
    ): AndroidBluetoothScannerImpl =
        AndroidBluetoothScannerImpl(
            gateway = gateway,
            permissionChecker = AndroidBluetoothPermissionChecker(gateway, logger),
            dispatchers = dispatchers,
            logger = logger,
            clock = { Instant(1_700_000_000_123L) },
        )

    private fun createTransport(
        gateway: FakeAndroidBluetoothGateway,
        logger: AppLogger,
        connectionTimeoutMs: Long = 3_000L,
    ): AndroidBluetoothTransportImpl =
        AndroidBluetoothTransportImpl(
            gateway = gateway,
            permissionChecker = AndroidBluetoothPermissionChecker(gateway, logger),
            dispatchers = dispatchers,
            logger = logger,
            connectionTimeoutMs = connectionTimeoutMs,
        )

    private suspend fun verifyBidirectionalExchange(
        serverTransport: AndroidBluetoothTransportImpl,
        clientTransport: AndroidBluetoothTransportImpl,
    ) = coroutineScope {
        val msgId = MessageId.generate(Instant(1_700_000_000_000L))
        val outgoingFromClient =
            listOf(
                HandshakePacket(step = 1, payload = byteArrayOf(1, 2, 3, 4)),
                EncryptedPayloadPacket(
                    sequenceNumber = 7L,
                    salt = byteArrayOf(5, 6, 7),
                    ciphertext = "sealed-message".encodeToByteArray(),
                ),
                AckPacket(msgId),
                KeepAlivePacket.Ping(100L),
            )
        val serverReply = KeepAlivePacket.Pong(200L)

        val serverReceived = CopyOnWriteArrayList<Packet>()
        val serverAllDone = CompletableDeferred<Unit>()
        val serverCollectJob =
            launch(start = CoroutineStart.UNDISPATCHED) {
                serverTransport.incomingPackets.collect { packet ->
                    serverReceived.add(packet)
                    if (serverReceived.size == outgoingFromClient.size) {
                        serverAllDone.complete(Unit)
                    }
                }
            }
        val clientInbound =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(3_000L) { clientTransport.incomingPackets.first() }
            }

        for (packet in outgoingFromClient) {
            assertTrue(clientTransport.send(packet).isSuccess)
        }
        assertTrue(serverTransport.send(serverReply).isSuccess)

        withTimeout(3_000L) {
            serverAllDone.await()
            assertEquals(outgoingFromClient, serverReceived.toList())
            assertEquals(serverReply, clientInbound.await())
        }
        serverCollectJob.cancelAndJoin()
    }

    private fun createLoopbackChannelPair(logger: AppLogger): Pair<RfcommSocketChannel, RfcommSocketChannel> {
        val aToBIn = PipedInputStream(65_536)
        val aToBOut = PipedOutputStream(aToBIn)
        val bToAIn = PipedInputStream(65_536)
        val bToAOut = PipedOutputStream(bToAIn)

        val channelA =
            RfcommSocketChannel(
                inputStream = bToAIn,
                outputStream = aToBOut,
                closeable = Closeable { },
                dispatchers = dispatchers,
                logger = logger,
            )
        val channelB =
            RfcommSocketChannel(
                inputStream = aToBIn,
                outputStream = bToAOut,
                closeable = Closeable { },
                dispatchers = dispatchers,
                logger = logger,
            )
        return channelA to channelB
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(3_000L) {
            while (!predicate()) {
                delay(10L)
            }
        }
    }

    private fun generateAes256TestKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        return generator.generateKey()
    }

    private class TestKeyStoreVault(
        private val dir: File,
        private val key: SecretKey,
    ) : AndroidKeyStoreVault(
            vaultDirProvider = { dir },
            masterKeyProvider = { key },
            masterKeyDeleter = { },
        )

    private class RecordingAppLogger : AppLogger {
        val warnings: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

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
        ) {
            warnings.add(message)
        }

        override fun error(
            tag: String,
            message: String,
            throwable: Throwable?,
            context: Map<String, String>,
        ) = Unit
    }

    private class FakeServerSocketHandle(
        private val onAccept: ((CoroutineDispatchers, AppLogger) -> RfcommSocketChannel)? = null,
    ) : AndroidServerSocketHandle(null) {
        val closed: AtomicBoolean = AtomicBoolean(false)

        override fun accept(
            dispatchers: CoroutineDispatchers,
            logger: AppLogger,
        ): RfcommSocketChannel =
            onAccept?.invoke(dispatchers, logger)
                ?: throw IOException("No accept handler configured in test.")

        override fun close() {
            closed.set(true)
        }
    }

    private class FakeAndroidBluetoothGateway(
        private val grantedPermissions: Set<String> =
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ),
        private val adapterAvailable: Boolean = true,
        private val adapterEnabled: Boolean = true,
        private val bondedDevices: List<AndroidDiscoveredDevice> = emptyList(),
        private val serverSocketFactory: ((String, String) -> AndroidServerSocketHandle)? = null,
        private val onConnectClient: (
            (String, String, CoroutineDispatchers, AppLogger) -> RfcommSocketChannel
        )? = null,
    ) : AndroidBluetoothGateway() {
        var onStartDiscovery: (() -> Boolean)? = null
        var discoveryStarted: Boolean = false
        var cancelDiscoveryCalled: Boolean = false
        val sdpFetchRequests: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
        var registeredReceiversCount: Int = 0
        var unregisteredReceiversCount: Int = 0

        private val activeReceivers = AtomicInteger(0)
        val activeReceiverCount: Int
            get() = activeReceivers.get()

        private var onDeviceFoundHandler: ((AndroidDiscoveredDevice) -> Unit)? = null
        private var onDeviceUuidsHandler: ((String, List<String>) -> Unit)? = null
        private var onDiscoveryFinishedHandler: (() -> Unit)? = null

        override fun hasPermission(permission: String): Boolean = permission in grantedPermissions

        override fun isAdapterAvailable(): Boolean = adapterAvailable

        override fun isAdapterEnabled(): Boolean = adapterEnabled

        override fun startDiscovery(): Boolean {
            val result = onStartDiscovery?.invoke() ?: true
            discoveryStarted = result
            return result
        }

        override fun cancelDiscovery(): Boolean {
            cancelDiscoveryCalled = true
            discoveryStarted = false
            return true
        }

        override fun getBondedDevices(): List<AndroidDiscoveredDevice> = bondedDevices

        override fun fetchUuidsWithSdp(address: String): Boolean {
            sdpFetchRequests.add(address)
            return true
        }

        override fun registerDiscoveryReceivers(
            onDeviceFound: (AndroidDiscoveredDevice) -> Unit,
            onDeviceUuidsFetched: (address: String, uuids: List<String>) -> Unit,
            onDiscoveryFinished: () -> Unit,
            onSecurityError: (SecurityException) -> Unit,
        ): AutoCloseable {
            onDeviceFoundHandler = onDeviceFound
            onDeviceUuidsHandler = onDeviceUuidsFetched
            onDiscoveryFinishedHandler = onDiscoveryFinished
            registeredReceiversCount += 1
            activeReceivers.incrementAndGet()
            val closed = AtomicBoolean(false)
            return AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    onDeviceFoundHandler = null
                    onDeviceUuidsHandler = null
                    onDiscoveryFinishedHandler = null
                    unregisteredReceiversCount += 1
                    activeReceivers.decrementAndGet()
                }
            }
        }

        override fun openInsecureServerSocket(
            serviceName: String,
            serviceUuid: String,
        ): AndroidServerSocketHandle =
            serverSocketFactory?.invoke(serviceName, serviceUuid)
                ?: FakeServerSocketHandle()

        override fun connectInsecureClientSocket(
            address: String,
            serviceUuid: String,
            dispatchers: CoroutineDispatchers,
            logger: AppLogger,
        ): RfcommSocketChannel =
            onConnectClient?.invoke(address, serviceUuid, dispatchers, logger)
                ?: throw IOException("No client connection handler configured.")

        fun emitDeviceFound(device: AndroidDiscoveredDevice) {
            onDeviceFoundHandler?.invoke(device)
        }

        fun emitDeviceUuidsFetched(
            address: String,
            uuids: List<String>,
        ) {
            onDeviceUuidsHandler?.invoke(address, uuids)
        }

        fun emitDiscoveryFinished() {
            onDiscoveryFinishedHandler?.invoke()
        }
    }
}
