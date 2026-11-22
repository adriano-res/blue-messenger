package com.bluemessenger.transport

import com.bluemessenger.crypto.CryptoEngine
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.error.InvalidInputError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.transport.BluetoothScanner
import com.bluemessenger.domain.ports.transport.constants.TransportConstants
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.transport.constants.BluezDbusConstants
import com.bluemessenger.transport.constants.LinuxTransportConstants
import com.bluemessenger.transport.dbus.BluezDbusClient
import com.bluemessenger.transport.dbus.BluezDbusErrorMapper
import com.bluemessenger.transport.dbus.BluezProfile1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.io.Closeable
import java.io.IOException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Linux BlueZ 5 [BluetoothScanner] adapter backed by System D-Bus (`org.bluez.Adapter1`,
 * `org.freedesktop.DBus.ObjectManager`, and `org.bluez.ProfileManager1`).
 *
 * Thread safety: Discovery and advertising transitions are serialized via a [Mutex] on
 * [CoroutineDispatchers.io]. Signal callbacks synchronize peer map updates via an internal lock
 * and publish immutable snapshots through [StateFlow].
 *
 * Failure behavior: Validates adapter readiness via [LinuxBluetoothPermissionChecker] and fails
 * fast with `MissingBluetoothPermissionError`, `BluetoothAdapterDisabledError`, or
 * [InvalidInputError]. Always rethrows [CancellationException].
 */
@OptIn(ExperimentalUuidApi::class)
class LinuxBluetoothScannerImpl internal constructor(
    private val bluezClient: BluezDbusClient,
    private val permissionChecker: LinuxBluetoothPermissionChecker,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val clock: () -> Instant,
) : BluetoothScanner, Closeable {
    constructor(
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
        clock: () -> Instant,
    ) : this(
        bluezClient = BluezDbusClient(),
        dispatchers = dispatchers,
        logger = logger,
        clock = clock,
    )

    private constructor(
        bluezClient: BluezDbusClient,
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
        clock: () -> Instant,
    ) : this(
        bluezClient = bluezClient,
        permissionChecker = LinuxBluetoothPermissionChecker(bluezClient, dispatchers, logger),
        dispatchers = dispatchers,
        logger = logger,
        clock = clock,
    )

    private val stateMutex: Mutex = Mutex()
    private val peersLock: Any = Any()

    private val _discoveredPeers: MutableStateFlow<List<Peer>> = MutableStateFlow(emptyList())
    override val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()

    private val _isScanning: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val devicePropsByPath: MutableMap<String, MutableMap<String, Variant<*>>> = LinkedHashMap()
    private val discoveredByPath: MutableMap<String, Peer> = LinkedHashMap()
    private val signalRegistrations: MutableList<AutoCloseable> = mutableListOf()

    private var activeDiscoveryAdapterPath: String? = null
    private var isAdvertisingProfileExported: Boolean = false

    /**
     * Starts filtered BlueZ BR/EDR discovery for peers advertising
     * [TransportConstants.CANONICAL_SERVICE_UUID].
     */
    override suspend fun startDiscovery(): Result<Unit> =
        stateMutex.withLock {
            withContext(dispatchers.io) {
                val adapterPath =
                    permissionChecker.ensureReady().getOrElse { error ->
                        return@withContext Result.failure(error)
                    }
                if (_isScanning.value) {
                    return@withContext Result.success(Unit)
                }
                startDiscoveryOnAdapter(adapterPath)
            }
        }

    /**
     * Stops active BlueZ discovery and unregisters discovery D-Bus signal listeners.
     */
    override suspend fun stopDiscovery(): Result<Unit> =
        stateMutex.withLock {
            withContext(dispatchers.io) {
                val adapterPath = activeDiscoveryAdapterPath
                closeSignalRegistrations()
                activeDiscoveryAdapterPath = null
                val wasScanning = _isScanning.value
                _isScanning.value = false
                if (!wasScanning || adapterPath == null) {
                    return@withContext Result.success(Unit)
                }
                try {
                    bluezClient.adapter(adapterPath).stopDiscovery()
                    logger.info(
                        tag = LinuxTransportConstants.TAG_SCANNER,
                        message = "Stopped BlueZ peer discovery.",
                        context = mapOf("adapterPath" to adapterPath),
                    )
                    Result.success(Unit)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: DBusException) {
                    failMapped("stopping BlueZ discovery", exception)
                } catch (exception: DBusExecutionException) {
                    failMapped("stopping BlueZ discovery", exception)
                } catch (exception: IOException) {
                    failMapped("stopping BlueZ discovery", exception)
                }
            }
        }

    /**
     * Updates the adapter alias to [localNickname], enables discoverability, and registers the
     * Blue Messenger SDP service record on BlueZ `ProfileManager1`.
     */
    override suspend fun startAdvertising(localNickname: String): Result<Unit> {
        val normalizedNickname = localNickname.trim()
        if (normalizedNickname.isBlank()) {
            logger.warn(
                tag = LinuxTransportConstants.TAG_SCANNER,
                message = "Rejected BlueZ advertising with blank nickname.",
            )
            return InvalidInputError("Local nickname must not be blank.").asFailure()
        }
        return stateMutex.withLock {
            withContext(dispatchers.io) {
                val adapterPath =
                    permissionChecker.ensureReady().getOrElse { error ->
                        return@withContext Result.failure(error)
                    }
                startAdvertisingOnAdapter(adapterPath, normalizedNickname)
            }
        }
    }

    /**
     * Unregisters the Blue Messenger SDP service record and disables advertising state.
     */
    override suspend fun stopAdvertising(): Result<Unit> =
        stateMutex.withLock {
            withContext(dispatchers.io) {
                val wasAdvertising = _isAdvertising.value
                val wasExported = isAdvertisingProfileExported
                _isAdvertising.value = false
                isAdvertisingProfileExported = false
                try {
                    if (wasAdvertising) {
                        bluezClient.profileManager().unregisterProfile(
                            DBusPath(BluezDbusConstants.LOCAL_SCANNER_PROFILE_PATH),
                        )
                    }
                    if (wasExported) {
                        bluezClient.unexportObject(BluezDbusConstants.LOCAL_SCANNER_PROFILE_PATH)
                    }
                    logger.info(
                        tag = LinuxTransportConstants.TAG_SCANNER,
                        message = "Stopped BlueZ SDP advertising.",
                    )
                    Result.success(Unit)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: DBusException) {
                    failMapped("stopping BlueZ advertising", exception)
                } catch (exception: DBusExecutionException) {
                    failMapped("stopping BlueZ advertising", exception)
                } catch (exception: IOException) {
                    failMapped("stopping BlueZ advertising", exception)
                } finally {
                    if (wasExported) {
                        bluezClient.unexportObject(BluezDbusConstants.LOCAL_SCANNER_PROFILE_PATH)
                    }
                }
            }
        }

    /**
     * Releases D-Bus signal listeners and unexports the local scanner profile object if active.
     */
    override fun close() {
        closeSignalRegistrations()
        _isScanning.value = false
        _isAdvertising.value = false
        activeDiscoveryAdapterPath = null
        if (isAdvertisingProfileExported) {
            isAdvertisingProfileExported = false
            bluezClient.unexportObject(BluezDbusConstants.LOCAL_SCANNER_PROFILE_PATH)
        }
    }

    private fun startDiscoveryOnAdapter(adapterPath: String): Result<Unit> {
        return try {
            synchronized(peersLock) {
                devicePropsByPath.clear()
                discoveredByPath.clear()
                _discoveredPeers.value = emptyList()
            }
            registerSignalHandlers()
            syncExistingManagedDevices()

            val adapter = bluezClient.adapter(adapterPath)
            val filter =
                mapOf(
                    BluezDbusConstants.DISCOVERY_FILTER_UUIDS to
                        Variant(
                            listOf(TransportConstants.CANONICAL_SERVICE_UUID),
                            BluezDbusConstants.DBUS_STRING_ARRAY_SIGNATURE,
                        ),
                    BluezDbusConstants.DISCOVERY_FILTER_TRANSPORT to
                        Variant(BluezDbusConstants.DISCOVERY_TRANSPORT_BR_EDR),
                )
            adapter.setDiscoveryFilter(filter)
            adapter.startDiscovery()
            activeDiscoveryAdapterPath = adapterPath
            _isScanning.value = true
            logger.info(
                tag = LinuxTransportConstants.TAG_SCANNER,
                message = "Started BlueZ peer discovery.",
                context = mapOf("adapterPath" to adapterPath),
            )
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            closeSignalRegistrations()
            throw cancellation
        } catch (exception: DBusException) {
            closeSignalRegistrations()
            failMapped("starting BlueZ discovery", exception)
        } catch (exception: DBusExecutionException) {
            closeSignalRegistrations()
            failMapped("starting BlueZ discovery", exception)
        } catch (exception: IOException) {
            closeSignalRegistrations()
            failMapped("starting BlueZ discovery", exception)
        } catch (exception: SecurityException) {
            closeSignalRegistrations()
            failMapped("starting BlueZ discovery", exception)
        }
    }

    private fun startAdvertisingOnAdapter(
        adapterPath: String,
        nickname: String,
    ): Result<Unit> {
        return try {
            val adapterProperties = bluezClient.adapterProperties(adapterPath)
            adapterProperties.Set(
                BluezDbusConstants.ADAPTER1_INTERFACE,
                BluezDbusConstants.PROPERTY_ALIAS,
                nickname,
            )
            adapterProperties.Set(
                BluezDbusConstants.ADAPTER1_INTERFACE,
                BluezDbusConstants.PROPERTY_DISCOVERABLE,
                true,
            )

            registerAdvertisingProfile(nickname)
            _isAdvertising.value = true
            logger.info(
                tag = LinuxTransportConstants.TAG_SCANNER,
                message = "Started BlueZ SDP advertising.",
                context = mapOf("adapterPath" to adapterPath),
            )
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: DBusException) {
            cleanupFailedAdvertising()
            failMapped("starting BlueZ advertising", exception)
        } catch (exception: DBusExecutionException) {
            cleanupFailedAdvertising()
            failMapped("starting BlueZ advertising", exception)
        } catch (exception: IOException) {
            cleanupFailedAdvertising()
            failMapped("starting BlueZ advertising", exception)
        } catch (exception: SecurityException) {
            cleanupFailedAdvertising()
            failMapped("starting BlueZ advertising", exception)
        }
    }

    private fun registerAdvertisingProfile(nickname: String) {
        if (_isAdvertising.value) {
            bluezClient.profileManager().unregisterProfile(
                DBusPath(BluezDbusConstants.LOCAL_SCANNER_PROFILE_PATH),
            )
            _isAdvertising.value = false
        }
        if (!isAdvertisingProfileExported) {
            bluezClient.exportProfile(
                BluezDbusConstants.LOCAL_SCANNER_PROFILE_PATH,
                AdvertisingStubProfile(bluezClient, dispatchers, logger),
            )
            isAdvertisingProfileExported = true
        }
        val options =
            mapOf(
                BluezDbusConstants.PROFILE_OPTION_NAME to Variant(nickname),
                BluezDbusConstants.PROFILE_OPTION_ROLE to Variant(BluezDbusConstants.PROFILE_ROLE_SERVER),
                BluezDbusConstants.PROFILE_OPTION_SERVICE to Variant(TransportConstants.CANONICAL_SERVICE_UUID),
            )
        bluezClient.profileManager().registerProfile(
            DBusPath(BluezDbusConstants.LOCAL_SCANNER_PROFILE_PATH),
            TransportConstants.CANONICAL_SERVICE_UUID,
            options,
        )
    }

    private fun cleanupFailedAdvertising() {
        _isAdvertising.value = false
        if (isAdvertisingProfileExported) {
            isAdvertisingProfileExported = false
            bluezClient.unexportObject(BluezDbusConstants.LOCAL_SCANNER_PROFILE_PATH)
        }
    }

    private fun registerSignalHandlers() {
        closeSignalRegistrations()
        signalRegistrations.add(
            bluezClient.addInterfacesAddedHandler { signal ->
                handleInterfacesAdded(signal)
            },
        )
        signalRegistrations.add(
            bluezClient.addInterfacesRemovedHandler { signal ->
                handleInterfacesRemoved(signal)
            },
        )
        signalRegistrations.add(
            bluezClient.addPropertiesChangedHandler { signal ->
                handlePropertiesChanged(signal)
            },
        )
    }

    private fun syncExistingManagedDevices() {
        val managedObjects = bluezClient.objectManager().GetManagedObjects()
        for ((path, interfaces) in managedObjects) {
            val deviceProps = interfaces[BluezDbusConstants.DEVICE1_INTERFACE] ?: continue
            upsertDeviceFromProperties(path.path, deviceProps)
        }
    }

    private fun handleInterfacesAdded(signal: ObjectManager.InterfacesAdded) {
        val deviceProps = signal.interfaces[BluezDbusConstants.DEVICE1_INTERFACE] ?: return
        upsertDeviceFromProperties(signal.objectPath, deviceProps)
    }

    private fun handleInterfacesRemoved(signal: ObjectManager.InterfacesRemoved) {
        if (BluezDbusConstants.DEVICE1_INTERFACE !in signal.interfaces) {
            return
        }
        synchronized(peersLock) {
            devicePropsByPath.remove(signal.objectPath)
            if (discoveredByPath.remove(signal.objectPath) != null) {
                publishPeersSnapshotLocked()
            }
        }
    }

    private fun handlePropertiesChanged(signal: Properties.PropertiesChanged) {
        if (signal.interfaceName != BluezDbusConstants.DEVICE1_INTERFACE) {
            return
        }
        val objectPath = signal.path ?: return
        upsertDeviceFromProperties(objectPath, signal.propertiesChanged)
    }

    private fun upsertDeviceFromProperties(
        objectPath: String,
        incomingProps: Map<String, Variant<*>>,
    ) {
        synchronized(peersLock) {
            val mergedProps = devicePropsByPath.getOrPut(objectPath) { LinkedHashMap() }
            mergedProps.putAll(incomingProps)
            val peer = buildMatchingPeerOrNull(mergedProps)
            if (peer != null) {
                discoveredByPath[objectPath] = peer
                publishPeersSnapshotLocked()
            } else if (discoveredByPath.remove(objectPath) != null) {
                publishPeersSnapshotLocked()
            }
        }
    }

    private fun buildMatchingPeerOrNull(props: Map<String, Variant<*>>): Peer? {
        val uuids = extractStringList(props[BluezDbusConstants.PROPERTY_UUIDS])
        val advertisesCanonicalService =
            uuids.any { uuid -> uuid.equals(TransportConstants.CANONICAL_SERVICE_UUID, ignoreCase = true) }
        val address =
            if (advertisesCanonicalService) {
                extractString(props[BluezDbusConstants.PROPERTY_ADDRESS])
                    ?.let { raw -> BluetoothAddress.parse(raw).getOrNull() }
            } else {
                null
            }
        return address?.let { resolvedAddress ->
            val nickname =
                extractString(props[BluezDbusConstants.PROPERTY_ALIAS])?.takeIf { it.isNotBlank() }
                    ?: extractString(props[BluezDbusConstants.PROPERTY_NAME])?.takeIf { it.isNotBlank() }
                    ?: resolvedAddress.value
            val existingId = discoveredByPath.values.firstOrNull { it.address == resolvedAddress }?.id
            Peer(
                id = existingId ?: deterministicPeerId(resolvedAddress),
                nickname = nickname,
                address = resolvedAddress,
                identityKey = null,
                isVerified = false,
                lastSeenAt = clock(),
            )
        }
    }

    private fun publishPeersSnapshotLocked() {
        _discoveredPeers.value =
            discoveredByPath.values
                .sortedWith(compareBy<Peer> { it.nickname }.thenBy { it.address.value })
    }

    private fun closeSignalRegistrations() {
        for (registration in signalRegistrations) {
            try {
                registration.close()
            } catch (exception: IOException) {
                logger.debug(
                    tag = LinuxTransportConstants.TAG_SCANNER,
                    message = "Ignored IOException while closing D-Bus signal registration.",
                    context = mapOf("reason" to (exception.message ?: "unknown")),
                )
            } catch (exception: DBusException) {
                logger.debug(
                    tag = LinuxTransportConstants.TAG_SCANNER,
                    message = "Ignored DBusException while closing D-Bus signal registration.",
                    context = mapOf("reason" to (exception.message ?: "unknown")),
                )
            }
        }
        signalRegistrations.clear()
    }

    private fun extractString(variant: Variant<*>?): String? = variant?.value as? String

    private fun extractStringList(variant: Variant<*>?): List<String> =
        when (val value = variant?.value) {
            is Collection<*> -> value.filterIsInstance<String>()
            is Array<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }

    private fun deterministicPeerId(address: BluetoothAddress): PeerId {
        val seed = "${LinuxTransportConstants.PEER_ID_SEED_PREFIX}${address.value}".encodeToByteArray()
        val digest = CryptoEngine.sha256(seed)
        val msb = readLongBigEndian(digest, offset = 0)
        val lsb = readLongBigEndian(digest, offset = LinuxTransportConstants.PEER_ID_BYTES_MSB_COUNT)
        return PeerId(Uuid.fromLongs(msb, lsb))
    }

    private fun readLongBigEndian(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var result = 0L
        for (index in 0 until LinuxTransportConstants.PEER_ID_BYTES_MSB_COUNT) {
            result =
                (result shl LinuxTransportConstants.BITS_PER_BYTE) or
                (bytes[offset + index].toLong() and LinuxTransportConstants.BYTE_MASK)
        }
        return result
    }

    private fun failMapped(
        action: String,
        throwable: Throwable,
    ): Result<Unit> {
        val domainError = BluezDbusErrorMapper.mapSystemFailure(throwable, action)
        logger.warn(
            tag = LinuxTransportConstants.TAG_SCANNER,
            message = "BlueZ scanner operation failed while $action.",
            throwable = throwable,
        )
        return domainError.asFailure(throwable)
    }

    private class AdvertisingStubProfile(
        private val bluezClient: BluezDbusClient,
        private val dispatchers: CoroutineDispatchers,
        private val logger: AppLogger,
    ) : BluezProfile1 {
        override fun isRemote(): Boolean = false

        override fun getObjectPath(): String = BluezDbusConstants.LOCAL_SCANNER_PROFILE_PATH

        override fun release() {
            logger.debug(
                tag = LinuxTransportConstants.TAG_SCANNER,
                message = "BlueZ advertising profile released.",
            )
        }

        override fun newConnection(
            device: DBusPath,
            fd: FileDescriptor,
            fdProperties: Map<String, Variant<*>>,
        ) {
            logger.warn(
                tag = LinuxTransportConstants.TAG_SCANNER,
                message = "Received unexpected connection on advertising-only BlueZ profile; closing FD.",
                context = mapOf("devicePath" to device.path),
            )
            try {
                bluezClient.openRfcommChannel(fd, dispatchers, logger).close()
            } catch (exception: DBusException) {
                logger.warn(
                    tag = LinuxTransportConstants.TAG_SCANNER,
                    message = "Failed to close unexpected FD on advertising profile.",
                    throwable = exception,
                    context = mapOf("devicePath" to device.path),
                )
            } catch (exception: IOException) {
                logger.warn(
                    tag = LinuxTransportConstants.TAG_SCANNER,
                    message = "Failed to close unexpected FD on advertising profile.",
                    throwable = exception,
                    context = mapOf("devicePath" to device.path),
                )
            } catch (exception: IllegalStateException) {
                logger.warn(
                    tag = LinuxTransportConstants.TAG_SCANNER,
                    message = "Failed to close unexpected FD on advertising profile.",
                    throwable = exception,
                    context = mapOf("devicePath" to device.path),
                )
            }
        }

        override fun requestDisconnection(device: DBusPath) {
            logger.debug(
                tag = LinuxTransportConstants.TAG_SCANNER,
                message = "BlueZ advertising profile received RequestDisconnection.",
                context = mapOf("devicePath" to device.path),
            )
        }
    }
}
