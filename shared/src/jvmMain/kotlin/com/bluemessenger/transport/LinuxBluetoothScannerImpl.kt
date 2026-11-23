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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val canonicalServicePaths: MutableSet<String> = LinkedHashSet()
    private val signalRegistrations: MutableList<AutoCloseable> = mutableListOf()

    private var activeDiscoveryAdapterPath: String? = null
    private var activeAdvertisingAdapterPath: String? = null

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
     * Updates the adapter alias to [localNickname] and enables discoverability so nearby peers can
     * resolve and connect to the active Blue Messenger RFCOMM transport profile.
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
     * Disables adapter discoverability and marks local advertising inactive.
     */
    override suspend fun stopAdvertising(): Result<Unit> =
        stateMutex.withLock {
            withContext(dispatchers.io) {
                val wasAdvertising = _isAdvertising.value
                val adapterPath = activeAdvertisingAdapterPath
                _isAdvertising.value = false
                activeAdvertisingAdapterPath = null
                try {
                    if (wasAdvertising && adapterPath != null) {
                        bluezClient.adapterProperties(adapterPath).Set(
                            BluezDbusConstants.ADAPTER1_INTERFACE,
                            BluezDbusConstants.PROPERTY_DISCOVERABLE,
                            false,
                        )
                    }
                    logger.info(
                        tag = LinuxTransportConstants.TAG_SCANNER,
                        message = "Stopped BlueZ Bluetooth discoverability advertising.",
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
                }
            }
        }

    /**
     * Releases D-Bus signal listeners and resets scanner discovery/advertising state.
     */
    override fun close() {
        closeSignalRegistrations()
        _isScanning.value = false
        _isAdvertising.value = false
        activeDiscoveryAdapterPath = null
        activeAdvertisingAdapterPath = null
    }

    private fun startDiscoveryOnAdapter(adapterPath: String): Result<Unit> {
        return try {
            synchronized(peersLock) {
                devicePropsByPath.clear()
                discoveredByPath.clear()
                canonicalServicePaths.clear()
                _discoveredPeers.value = emptyList()
            }
            registerSignalHandlers()
            syncExistingManagedDevices()

            val adapter = bluezClient.adapter(adapterPath)
            val filter =
                mapOf(
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

            activeAdvertisingAdapterPath = adapterPath
            _isAdvertising.value = true
            logger.info(
                tag = LinuxTransportConstants.TAG_SCANNER,
                message = "Started BlueZ Bluetooth discoverability advertising.",
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

    private fun cleanupFailedAdvertising() {
        _isAdvertising.value = false
        activeAdvertisingAdapterPath = null
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
        val devicePath = signal.signalSource?.path ?: return
        val deviceProps = signal.interfaces[BluezDbusConstants.DEVICE1_INTERFACE] ?: return
        upsertDeviceFromProperties(devicePath, deviceProps)
    }

    private fun handleInterfacesRemoved(signal: ObjectManager.InterfacesRemoved) {
        if (BluezDbusConstants.DEVICE1_INTERFACE !in signal.interfaces) {
            return
        }
        val devicePath = signal.signalSource?.path ?: return
        synchronized(peersLock) {
            devicePropsByPath.remove(devicePath)
            canonicalServicePaths.remove(devicePath)
            if (discoveredByPath.remove(devicePath) != null) {
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
            val candidate = buildCandidatePeerOrNull(mergedProps)
            if (candidate != null) {
                discoveredByPath[objectPath] = candidate.peer
                if (candidate.hasCanonicalServiceUuid) {
                    canonicalServicePaths.add(objectPath)
                } else {
                    canonicalServicePaths.remove(objectPath)
                }
                publishPeersSnapshotLocked()
            } else {
                canonicalServicePaths.remove(objectPath)
                if (discoveredByPath.remove(objectPath) != null) {
                    publishPeersSnapshotLocked()
                }
            }
        }
    }

    private fun buildCandidatePeerOrNull(props: Map<String, Variant<*>>): DiscoveredCandidate? {
        val uuids = extractStringList(props[BluezDbusConstants.PROPERTY_UUIDS])
        val hasCanonicalServiceUuid =
            uuids.any { uuid -> uuid.equals(TransportConstants.CANONICAL_SERVICE_UUID, ignoreCase = true) }
        val displayName = extractHumanReadableName(props)
        val isDiscoverableOrPaired =
            props.containsKey(BluezDbusConstants.PROPERTY_RSSI) ||
                extractBoolean(props[BluezDbusConstants.PROPERTY_PAIRED]) ||
                extractBoolean(props[BluezDbusConstants.PROPERTY_CONNECTED])
        val shouldInclude = hasCanonicalServiceUuid || (isDiscoverableOrPaired && displayName != null)
        val address =
            if (shouldInclude) {
                extractString(props[BluezDbusConstants.PROPERTY_ADDRESS])
                    ?.let { raw -> BluetoothAddress.parse(raw).getOrNull() }
            } else {
                null
            }
        return address?.let { resolvedAddress ->
            val nickname = displayName ?: resolvedAddress.value
            val existingId = discoveredByPath.values.firstOrNull { it.address == resolvedAddress }?.id
            val peer =
                Peer(
                    id = existingId ?: deterministicPeerId(resolvedAddress),
                    nickname = nickname,
                    address = resolvedAddress,
                    identityKey = null,
                    isVerified = false,
                    lastSeenAt = clock(),
                )
            DiscoveredCandidate(peer = peer, hasCanonicalServiceUuid = hasCanonicalServiceUuid)
        }
    }

    private fun extractHumanReadableName(props: Map<String, Variant<*>>): String? {
        val name =
            extractString(props[BluezDbusConstants.PROPERTY_NAME])
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !isMacLikeName(it) }
        val alias =
            extractString(props[BluezDbusConstants.PROPERTY_ALIAS])
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !isMacLikeName(it) }
        return name ?: alias
    }

    private fun isMacLikeName(candidate: String): Boolean {
        val normalized =
            candidate.trim().replace(
                LinuxTransportConstants.MAC_HYPHEN_CHAR,
                LinuxTransportConstants.MAC_COLON_CHAR,
            )
        return BluetoothAddress.parse(normalized).isSuccess
    }

    private fun publishPeersSnapshotLocked() {
        _discoveredPeers.value =
            discoveredByPath.entries
                .sortedWith(
                    compareBy<Map.Entry<String, Peer>> { it.key !in canonicalServicePaths }
                        .thenBy { it.value.nickname }
                        .thenBy { it.value.address.value },
                ).map { entry -> entry.value }
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

    private fun extractBoolean(variant: Variant<*>?): Boolean = variant?.value as? Boolean ?: false

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

    private data class DiscoveredCandidate(
        val peer: Peer,
        val hasCanonicalServiceUuid: Boolean,
    )
}
