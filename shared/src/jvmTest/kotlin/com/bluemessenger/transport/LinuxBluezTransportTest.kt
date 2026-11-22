package com.bluemessenger.transport

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.BluetoothAdapterDisabledError
import com.bluemessenger.domain.error.DomainException
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
import com.bluemessenger.transport.constants.BluezDbusConstants
import com.bluemessenger.transport.dbus.BluezAdapter1
import com.bluemessenger.transport.dbus.BluezDbusClient
import com.bluemessenger.transport.dbus.BluezDevice1
import com.bluemessenger.transport.dbus.BluezProfile1
import com.bluemessenger.transport.dbus.BluezProfileManager1
import com.bluemessenger.transport.protocol.FrameEncoder
import com.bluemessenger.transport.protocol.PacketCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.errors.AccessDenied
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinuxBluezTransportTest {
    private val dispatchers =
        CoroutineDispatchers(
            main = Dispatchers.Unconfined,
            io = Dispatchers.IO,
            default = Dispatchers.Default,
        )

    @Test
    fun rfcommChannelExchangesPacketsBidirectionallyOverFragmentedAndBatchedStreams() =
        runBlocking {
            val logger = RecordingAppLogger()
            val msgId = MessageId.generate(Instant(1_700_000_000_000L))
            val outgoingFromA =
                listOf(
                    HandshakePacket(step = 1, payload = byteArrayOf(1, 2, 3, 4)),
                    EncryptedPayloadPacket(
                        sequenceNumber = 42L,
                        salt = byteArrayOf(9, 8, 7),
                        ciphertext = "cipher-bytes".encodeToByteArray(),
                    ),
                    AckPacket(msgId),
                    KeepAlivePacket.Ping(123_456L),
                    KeepAlivePacket.Pong(123_457L),
                )
            val replyFromB = KeepAlivePacket.Pong(999_999L)
            val (channelA, channelB) = createLoopbackChannelPair(logger)
            val receivedAtB = CopyOnWriteArrayList<Packet>()
            val allReceivedAtB = CompletableDeferred<Unit>()
            val receivedAtA = CompletableDeferred<Packet>()
            val collectBJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    channelB.incomingPackets().collect { packet ->
                        receivedAtB.add(packet)
                        if (receivedAtB.size == outgoingFromA.size) {
                            allReceivedAtB.complete(Unit)
                        }
                    }
                }
            val collectAJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    channelA.incomingPackets().collect { receivedAtA.complete(it) }
                }
            try {
                for (packet in outgoingFromA) {
                    assertTrue(channelA.send(packet).isSuccess)
                }
                assertTrue(channelB.send(replyFromB).isSuccess)

                withTimeout(3_000L) {
                    allReceivedAtB.await()
                    assertEquals(outgoingFromA, receivedAtB.toList())
                    assertEquals(replyFromB, receivedAtA.await())
                }
            } finally {
                collectBJob.cancel()
                collectAJob.cancel()
                channelA.close()
                channelB.close()
            }
        }

    @Test
    fun rfcommChannelHandlesByteByByteFragmentsAndConcatenatedFrames() =
        runBlocking {
            val logger = RecordingAppLogger()
            val pipedIn = PipedInputStream(65_536)
            val pipedOut = PipedOutputStream(pipedIn)
            val sinkOut = PipedOutputStream()
            val channel =
                RfcommSocketChannel(
                    inputStream = pipedIn,
                    outputStream = sinkOut,
                    closeable = Closeable { },
                    dispatchers = dispatchers,
                    logger = logger,
                )

            val packet1 = HandshakePacket(step = 1, payload = byteArrayOf(10, 20, 30))
            val packet2 = KeepAlivePacket.Ping(555L)
            val frame1 = FrameEncoder.encode(PacketCodec.encode(packet1)).getOrThrow()
            val frame2 = FrameEncoder.encode(PacketCodec.encode(packet2)).getOrThrow()

            val receiver = async { withTimeout(3_000L) { channel.incomingPackets().take(2).toList() } }

            for (byte in frame1) {
                pipedOut.write(byte.toInt() and 0xFF)
                pipedOut.flush()
            }
            pipedOut.write(frame2)
            pipedOut.flush()

            assertEquals(listOf(packet1, packet2), receiver.await())
            channel.close()
            pipedOut.close()
        }

    @Test
    fun rfcommChannelDropsCorruptedFrameWithWarningAndKeepsStreamHealthy() =
        runBlocking {
            val logger = RecordingAppLogger()
            val pipedIn = PipedInputStream(65_536)
            val pipedOut = PipedOutputStream(pipedIn)
            val channel =
                RfcommSocketChannel(
                    inputStream = pipedIn,
                    outputStream = PipedOutputStream(),
                    closeable = Closeable { },
                    dispatchers = dispatchers,
                    logger = logger,
                )

            val validPacket1 = KeepAlivePacket.Ping(100L)
            val validPacket2 = KeepAlivePacket.Pong(200L)
            val validFrame1 = FrameEncoder.encode(PacketCodec.encode(validPacket1)).getOrThrow()
            val validFrame2 = FrameEncoder.encode(PacketCodec.encode(validPacket2)).getOrThrow()
            val corruptedFrame = validFrame1.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0xFF).toByte() }

            val collected = CopyOnWriteArrayList<Packet>()
            val collectorJob =
                launch {
                    channel.incomingPackets().collect { packet ->
                        collected.add(packet)
                    }
                }

            pipedOut.write(validFrame1)
            pipedOut.flush()
            awaitCondition { collected.size == 1 }

            pipedOut.write(corruptedFrame)
            pipedOut.flush()
            awaitCondition { logger.warnings.isNotEmpty() }

            pipedOut.write(validFrame2)
            pipedOut.flush()
            awaitCondition { collected.size == 2 }

            val expected: List<Packet> = listOf(validPacket1, validPacket2)
            assertEquals(expected, collected.toList())
            assertTrue(logger.warnings.any { it.contains("corrupted", ignoreCase = true) })

            collectorJob.cancelAndJoin()
            pipedOut.close()
        }

    @Test
    fun rfcommChannelClosesDescriptorsDeterministicallyOnCoroutineCancellation() =
        runBlocking {
            val logger = RecordingAppLogger()
            val socketClosed = AtomicBoolean(false)
            val inputClosed = AtomicBoolean(false)
            val outputClosed = AtomicBoolean(false)

            val rawIn = PipedInputStream()
            val dummySource = PipedOutputStream(rawIn)
            val readStarted = CompletableDeferred<Unit>()
            val trackedIn =
                object : InputStream() {
                    override fun read(): Int {
                        readStarted.complete(Unit)
                        return rawIn.read()
                    }

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        readStarted.complete(Unit)
                        return rawIn.read(b, off, len)
                    }

                    override fun close() {
                        inputClosed.set(true)
                        rawIn.close()
                    }
                }
            val trackedOut =
                object : FilterOutputStream(PipedOutputStream()) {
                    override fun close() {
                        outputClosed.set(true)
                        super.close()
                    }
                }
            val channel =
                RfcommSocketChannel(
                    inputStream = trackedIn,
                    outputStream = trackedOut,
                    closeable = Closeable { socketClosed.set(true) },
                    dispatchers = dispatchers,
                    logger = logger,
                )

            val cancellationRethrown = AtomicBoolean(false)
            val readerJob =
                launch {
                    try {
                        channel.incomingPackets().collect { }
                    } catch (cancellation: CancellationException) {
                        cancellationRethrown.set(true)
                        throw cancellation
                    }
                }

            withTimeout(3_000L) { readStarted.await() }
            readerJob.cancelAndJoin()

            assertTrue(cancellationRethrown.get())
            assertTrue(channel.isClosed)
            assertTrue(socketClosed.get())
            assertTrue(inputClosed.get())
            assertTrue(outputClosed.get())
            dummySource.close()
        }

    @Test
    fun rfcommChannelPropagatesDomainExceptionOnStreamReadIOException() =
        runBlocking {
            val logger = RecordingAppLogger()
            val failingIn =
                object : InputStream() {
                    override fun read(): Int = throw IOException("Simulated RFCOMM link loss")

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int = throw IOException("Simulated RFCOMM link loss")
                }
            val channel =
                RfcommSocketChannel(
                    inputStream = failingIn,
                    outputStream = PipedOutputStream(),
                    closeable = Closeable { },
                    dispatchers = dispatchers,
                    logger = logger,
                )

            val thrown =
                assertFailsWith<DomainException> {
                    withTimeout(3_000L) {
                        channel.incomingPackets().collect { }
                    }
                }
            assertIs<TransportDisconnectedError>(thrown.error)
            assertTrue(channel.isClosed)
            assertTrue(logger.warnings.any { it.contains("read failed", ignoreCase = true) })
        }

    @Test
    fun permissionCheckerMapsMissingPermissionsAndDisabledAdapterFailFast() =
        runBlocking {
            val logger = RecordingAppLogger()

            val accessDeniedClient =
                FakeBluezDbusClient(
                    onConnect = {
                        throw AccessDenied("Access denied")
                    },
                )
            val accessChecker = LinuxBluetoothPermissionChecker(accessDeniedClient, dispatchers, logger)
            val deniedResult = accessChecker.ensureReady()
            assertTrue(deniedResult.isFailure)
            assertIs<MissingBluetoothPermissionError>(deniedResult.exceptionOrNull()?.toDomainErrorOrNull())

            val poweredOffClient = FakeBluezDbusClient(adapterPowered = false)
            val poweredOffChecker = LinuxBluetoothPermissionChecker(poweredOffClient, dispatchers, logger)
            val offResult = poweredOffChecker.ensureReady()
            assertTrue(offResult.isFailure)
            assertIs<BluetoothAdapterDisabledError>(offResult.exceptionOrNull()?.toDomainErrorOrNull())

            val noAdapterClient = FakeBluezDbusClient(includeAdapter = false)
            val noAdapterChecker = LinuxBluetoothPermissionChecker(noAdapterClient, dispatchers, logger)
            val noAdapterResult = noAdapterChecker.ensureReady()
            assertTrue(noAdapterResult.isFailure)
            assertIs<BluetoothAdapterDisabledError>(noAdapterResult.exceptionOrNull()?.toDomainErrorOrNull())

            val readyClient = FakeBluezDbusClient(adapterPowered = true)
            val readyChecker = LinuxBluetoothPermissionChecker(readyClient, dispatchers, logger)
            val readyResult = readyChecker.ensureReady()
            assertEquals(BluezDbusConstants.DEFAULT_ADAPTER_PATH, readyResult.getOrThrow())
        }

    @Test
    fun scannerDiscoversMatchingPeersViaDbusFakes() =
        runBlocking {
            val logger = RecordingAppLogger()
            val fakeClient = FakeBluezDbusClient(adapterPowered = true)
            val scanner = createScanner(fakeClient, logger)

            assertTrue(scanner.startDiscovery().isSuccess)
            assertTrue(scanner.isScanning.value)
            assertTrue(fakeClient.adapterDiscoveryStarted)

            val matchingDevicePath = "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF"
            val unrelatedDevicePath = "/org/bluez/hci0/dev_11_22_33_44_55_66"
            fakeClient.emitInterfacesAdded(
                objectPath = unrelatedDevicePath,
                interfaces =
                    deviceInterfaces(
                        address = "11:22:33:44:55:66",
                        alias = "Headphones",
                        uuids = listOf("0000110b-0000-1000-8000-00805f9b34fb"),
                    ),
            )
            fakeClient.emitInterfacesAdded(
                objectPath = matchingDevicePath,
                interfaces =
                    deviceInterfaces(
                        address = "aa:bb:cc:dd:ee:ff",
                        alias = "PixelPeer",
                        uuids = listOf(TransportConstants.CANONICAL_SERVICE_UUID),
                    ),
            )

            assertEquals(1, scanner.discoveredPeers.value.size)
            val discovered = scanner.discoveredPeers.value.single()
            assertEquals("PixelPeer", discovered.nickname)
            assertEquals(BluetoothAddress("AA:BB:CC:DD:EE:FF"), discovered.address)

            fakeClient.emitInterfacesRemoved(
                objectPath = matchingDevicePath,
                interfaces = listOf(BluezDbusConstants.DEVICE1_INTERFACE),
            )
            assertTrue(scanner.discoveredPeers.value.isEmpty())
            assertTrue(scanner.stopDiscovery().isSuccess)
            assertFalse(scanner.isScanning.value)
            scanner.close()
        }

    @Test
    fun scannerAdvertisesAndUnregistersProfile() =
        runBlocking {
            val logger = RecordingAppLogger()
            val fakeClient = FakeBluezDbusClient(adapterPowered = true)
            val scanner = createScanner(fakeClient, logger)

            val blankResult = scanner.startAdvertising("   ")
            assertTrue(blankResult.isFailure)
            assertIs<InvalidInputError>(blankResult.exceptionOrNull()?.toDomainErrorOrNull())

            assertTrue(scanner.startAdvertising("LinuxDesktop").isSuccess)
            assertTrue(scanner.isAdvertising.value)
            assertEquals(
                TransportConstants.CANONICAL_SERVICE_UUID,
                fakeClient.registeredProfiles[BluezDbusConstants.LOCAL_SCANNER_PROFILE_PATH],
            )
            assertTrue(scanner.stopAdvertising().isSuccess)
            assertFalse(scanner.isAdvertising.value)
            scanner.close()
        }

    private fun createScanner(
        fakeClient: FakeBluezDbusClient,
        logger: AppLogger,
    ): LinuxBluetoothScannerImpl =
        LinuxBluetoothScannerImpl(
            bluezClient = fakeClient,
            permissionChecker = LinuxBluetoothPermissionChecker(fakeClient, dispatchers, logger),
            dispatchers = dispatchers,
            logger = logger,
            clock = { Instant(1_700_000_000_123L) },
        )

    private fun deviceInterfaces(
        address: String,
        alias: String,
        uuids: List<String>,
    ): Map<String, Map<String, Variant<*>>> =
        mapOf(
            BluezDbusConstants.DEVICE1_INTERFACE to
                mapOf(
                    BluezDbusConstants.PROPERTY_ADDRESS to Variant(address),
                    BluezDbusConstants.PROPERTY_ALIAS to Variant(alias),
                    BluezDbusConstants.PROPERTY_UUIDS to
                        Variant(uuids, BluezDbusConstants.DBUS_STRING_ARRAY_SIGNATURE),
                ),
        )

    @Test
    fun transportConnectsInClientAndServerModesOverFakeBluezProfile() =
        runBlocking {
            val logger = RecordingAppLogger()
            val (clientChannel, serverChannel) = createLoopbackChannelPair(logger)

            val serverDbusClient =
                FakeBluezDbusClient(
                    adapterPowered = true,
                    rfcommChannelFactory = { serverChannel },
                )
            val serverTransport =
                LinuxBluetoothTransportImpl(
                    bluezClient = serverDbusClient,
                    permissionChecker = LinuxBluetoothPermissionChecker(serverDbusClient, dispatchers, logger),
                    dispatchers = dispatchers,
                    logger = logger,
                )

            assertTrue(serverTransport.startServer().isSuccess)
            assertEquals(SessionState.ADVERTISING, serverTransport.sessionState.value)

            serverDbusClient.triggerNewConnection(
                objectPath = BluezDbusConstants.LOCAL_TRANSPORT_PROFILE_PATH,
                devicePath = "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF",
            )
            assertEquals(SessionState.HANDSHAKING, serverTransport.sessionState.value)

            val clientDbusClient =
                FakeBluezDbusClient(
                    adapterPowered = true,
                    rfcommChannelFactory = { clientChannel },
                    onConnectProfile = { _, fake ->
                        fake.triggerNewConnection(
                            objectPath = BluezDbusConstants.LOCAL_TRANSPORT_PROFILE_PATH,
                            devicePath = "/org/bluez/hci0/dev_11_22_33_44_55_66",
                        )
                    },
                )
            val clientTransport =
                LinuxBluetoothTransportImpl(
                    bluezClient = clientDbusClient,
                    permissionChecker = LinuxBluetoothPermissionChecker(clientDbusClient, dispatchers, logger),
                    dispatchers = dispatchers,
                    logger = logger,
                )

            assertTrue(clientTransport.connect(BluetoothAddress("11:22:33:44:55:66")).isSuccess)
            assertEquals(SessionState.HANDSHAKING, clientTransport.sessionState.value)

            val serverInbound =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(3_000L) { serverTransport.incomingPackets.first() }
                }
            val testPacket = HandshakePacket(step = 1, payload = byteArrayOf(7, 8, 9))
            assertTrue(clientTransport.send(testPacket).isSuccess)
            assertEquals(testPacket, serverInbound.await())

            assertTrue(clientTransport.disconnect().isSuccess)
            assertTrue(serverTransport.disconnect().isSuccess)
            assertEquals(SessionState.DISCONNECTED, clientTransport.sessionState.value)
            assertEquals(SessionState.DISCONNECTED, serverTransport.sessionState.value)
            clientTransport.close()
            serverTransport.close()
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
                kotlinx.coroutines.delay(10L)
            }
        }
    }

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

    private class FakeBluezDbusClient(
        private val adapterPowered: Boolean = true,
        private val includeAdapter: Boolean = true,
        private val onConnect: (() -> Unit)? = null,
        private val rfcommChannelFactory: (() -> RfcommSocketChannel)? = null,
        private val onConnectProfile: ((String, FakeBluezDbusClient) -> Unit)? = null,
    ) : BluezDbusClient() {
        var adapterDiscoveryStarted: Boolean = false
        val registeredProfiles: MutableMap<String, String> = LinkedHashMap()
        private val exportedProfiles: MutableMap<String, BluezProfile1> = LinkedHashMap()
        private val interfacesAddedHandlers: MutableList<(ObjectManager.InterfacesAdded) -> Unit> = mutableListOf()
        private val interfacesRemovedHandlers: MutableList<(ObjectManager.InterfacesRemoved) -> Unit> = mutableListOf()
        private val propertiesChangedHandlers: MutableList<(Properties.PropertiesChanged) -> Unit> = mutableListOf()
        private var adapterAlias: String = "BlueMessenger"
        private var adapterDiscoverable: Boolean = false

        override fun ensureConnected() {
            onConnect?.invoke()
        }

        override fun objectManager(): ObjectManager =
            object : ObjectManager {
                override fun isRemote(): Boolean = false

                override fun getObjectPath(): String = BluezDbusConstants.BLUEZ_ROOT_PATH

                override fun GetManagedObjects(): Map<DBusPath, Map<String, Map<String, Variant<*>>>> =
                    if (!includeAdapter) {
                        emptyMap()
                    } else {
                        mapOf(
                            DBusPath(BluezDbusConstants.DEFAULT_ADAPTER_PATH) to
                                mapOf(
                                    BluezDbusConstants.ADAPTER1_INTERFACE to
                                        mapOf(BluezDbusConstants.PROPERTY_POWERED to Variant(adapterPowered)),
                                ),
                        )
                    }
            }

        override fun adapterProperties(adapterPath: String): Properties =
            object : Properties {
                override fun isRemote(): Boolean = false

                override fun getObjectPath(): String = adapterPath

                @Suppress("UNCHECKED_CAST")
                override fun <A> Get(
                    interfaceName: String,
                    propertyName: String,
                ): A =
                    when (propertyName) {
                        BluezDbusConstants.PROPERTY_POWERED -> adapterPowered as A
                        BluezDbusConstants.PROPERTY_ALIAS -> adapterAlias as A
                        BluezDbusConstants.PROPERTY_DISCOVERABLE -> adapterDiscoverable as A
                        else -> throw DBusExecutionException("Unknown property $propertyName")
                    }

                override fun <A> Set(
                    interfaceName: String,
                    propertyName: String,
                    value: A,
                ) {
                    when (propertyName) {
                        BluezDbusConstants.PROPERTY_ALIAS -> adapterAlias = value as String
                        BluezDbusConstants.PROPERTY_DISCOVERABLE -> adapterDiscoverable = value as Boolean
                    }
                }

                override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
                    mapOf(BluezDbusConstants.PROPERTY_POWERED to Variant(adapterPowered))
            }

        override fun adapter(adapterPath: String): BluezAdapter1 =
            object : BluezAdapter1 {
                override fun isRemote(): Boolean = false

                override fun getObjectPath(): String = adapterPath

                override fun startDiscovery() {
                    adapterDiscoveryStarted = true
                }

                override fun stopDiscovery() {
                    adapterDiscoveryStarted = false
                }

                override fun setDiscoveryFilter(properties: Map<String, Variant<*>>) = Unit
            }

        override fun device(devicePath: String): BluezDevice1 =
            object : BluezDevice1 {
                override fun isRemote(): Boolean = false

                override fun getObjectPath(): String = devicePath

                override fun connectProfile(uuid: String) {
                    onConnectProfile?.invoke(uuid, this@FakeBluezDbusClient)
                }

                override fun disconnectProfile(uuid: String) = Unit
            }

        override fun profileManager(): BluezProfileManager1 =
            object : BluezProfileManager1 {
                override fun isRemote(): Boolean = false

                override fun getObjectPath(): String = BluezDbusConstants.BLUEZ_MANAGER_PATH

                override fun registerProfile(
                    profile: DBusPath,
                    uuid: String,
                    options: Map<String, Variant<*>>,
                ) {
                    registeredProfiles[profile.path] = uuid
                }

                override fun unregisterProfile(profile: DBusPath) {
                    registeredProfiles.remove(profile.path)
                }
            }

        override fun exportProfile(
            objectPath: String,
            profile: BluezProfile1,
        ) {
            exportedProfiles[objectPath] = profile
        }

        override fun unexportObject(objectPath: String) {
            exportedProfiles.remove(objectPath)
        }

        override fun addInterfacesAddedHandler(handler: (ObjectManager.InterfacesAdded) -> Unit): AutoCloseable {
            interfacesAddedHandlers.add(handler)
            return AutoCloseable { interfacesAddedHandlers.remove(handler) }
        }

        override fun addInterfacesRemovedHandler(handler: (ObjectManager.InterfacesRemoved) -> Unit): AutoCloseable {
            interfacesRemovedHandlers.add(handler)
            return AutoCloseable { interfacesRemovedHandlers.remove(handler) }
        }

        override fun addPropertiesChangedHandler(handler: (Properties.PropertiesChanged) -> Unit): AutoCloseable {
            propertiesChangedHandlers.add(handler)
            return AutoCloseable { propertiesChangedHandlers.remove(handler) }
        }

        override fun openRfcommChannel(
            dbusFd: FileDescriptor,
            dispatchers: CoroutineDispatchers,
            logger: AppLogger,
        ): RfcommSocketChannel =
            rfcommChannelFactory?.invoke()
                ?: throw DBusException("No loopback channel configured in test fake.")

        override fun close() = Unit

        fun emitInterfacesAdded(
            objectPath: String,
            interfaces: Map<String, Map<String, Variant<*>>>,
        ) {
            val signal =
                ObjectManager.InterfacesAdded(
                    BluezDbusConstants.BLUEZ_ROOT_PATH,
                    DBusPath(objectPath),
                    interfaces,
                )
            for (handler in interfacesAddedHandlers.toList()) {
                handler(signal)
            }
        }

        fun emitInterfacesRemoved(
            objectPath: String,
            interfaces: List<String>,
        ) {
            val signal =
                ObjectManager.InterfacesRemoved(
                    BluezDbusConstants.BLUEZ_ROOT_PATH,
                    DBusPath(objectPath),
                    interfaces,
                )
            for (handler in interfacesRemovedHandlers.toList()) {
                handler(signal)
            }
        }

        fun triggerNewConnection(
            objectPath: String,
            devicePath: String,
        ) {
            val profile = checkNotNull(exportedProfiles[objectPath]) { "No exported profile at $objectPath" }
            profile.newConnection(DBusPath(devicePath), FileDescriptor(1), emptyMap())
        }
    }
}
