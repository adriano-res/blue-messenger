package com.bluemessenger.transport

import android.Manifest
import android.content.Context
import com.bluemessenger.crypto.CryptoEngine
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.error.BluetoothAdapterDisabledError
import com.bluemessenger.domain.error.InvalidInputError
import com.bluemessenger.domain.error.MissingBluetoothPermissionError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.transport.BluetoothScanner
import com.bluemessenger.domain.ports.transport.constants.TransportConstants
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.transport.constants.AndroidTransportConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Android [BluetoothScanner] adapter backed by [AndroidBluetoothGateway] (`BluetoothAdapter`
 * inquiry discovery, SDP UUID filtering, and insecure RFCOMM SDP service registration).
 *
 * Thread safety: Discovery and advertising lifecycle operations are serialized via a [Mutex] on
 * [CoroutineDispatchers.io]. Broadcast receiver callbacks synchronize peer cache mutations under an
 * internal lock and publish immutable sorted snapshots through [StateFlow].
 *
 * Failure behavior: Validates API 31+ runtime permissions and adapter power state via
 * [AndroidBluetoothPermissionChecker], failing fast with `MissingBluetoothPermissionError`,
 * `BluetoothAdapterDisabledError`, or [InvalidInputError]. Never swallows [SecurityException] and
 * always rethrows [CancellationException].
 */
@OptIn(ExperimentalUuidApi::class)
class AndroidBluetoothScannerImpl internal constructor(
    private val gateway: AndroidBluetoothGateway,
    private val permissionChecker: AndroidBluetoothPermissionChecker,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val clock: () -> Instant,
    private val postDiscoverySdpTimeoutMs: Long = AndroidTransportConstants.POST_DISCOVERY_SDP_WINDOW_MS,
) : BluetoothScanner, Closeable {
    constructor(
        context: Context,
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
        clock: () -> Instant,
    ) : this(
        gateway = AndroidBluetoothGateway(context),
        dispatchers = dispatchers,
        logger = logger,
        clock = clock,
    )

    private constructor(
        gateway: AndroidBluetoothGateway,
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
        clock: () -> Instant,
    ) : this(
        gateway = gateway,
        permissionChecker = AndroidBluetoothPermissionChecker(gateway, logger),
        dispatchers = dispatchers,
        logger = logger,
        clock = clock,
    )

    private val stateMutex: Mutex = Mutex()
    private val peersLock: Any = Any()
    private val resourceLock: Any = Any()
    private val scannerScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _discoveredPeers: MutableStateFlow<List<Peer>> = MutableStateFlow(emptyList())
    override val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()

    private val _isScanning: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val devicesByAddress: MutableMap<String, AndroidDiscoveredDevice> = LinkedHashMap()
    private val discoveredByAddress: MutableMap<String, Peer> = LinkedHashMap()
    private val canonicalServiceAddresses: MutableSet<String> = LinkedHashSet()
    private val pendingSdpAddresses: MutableSet<String> = LinkedHashSet()

    private var discoveryInquiryFinished: Boolean = false
    private var receiverRegistration: AutoCloseable? = null
    private var postDiscoveryCleanupJob: Job? = null

    /**
     * Starts classic Bluetooth discovery, registers `ACTION_FOUND`/`ACTION_UUID`/
     * `ACTION_DISCOVERY_FINISHED` receivers, and filters discovered peers by
     * [TransportConstants.CANONICAL_SERVICE_UUID].
     */
    override suspend fun startDiscovery(): Result<Unit> =
        stateMutex.withLock {
            withContext(dispatchers.io) {
                permissionChecker.ensureScanReady().getOrElse { error ->
                    return@withContext Result.failure(error)
                }
                if (_isScanning.value) {
                    return@withContext Result.success(Unit)
                }
                startDiscoveryInternal()
            }
        }

    /**
     * Cancels active Android Bluetooth discovery and deterministically unregisters broadcast
     * receivers.
     */
    override suspend fun stopDiscovery(): Result<Unit> =
        stateMutex.withLock {
            withContext(dispatchers.io) {
                cancelPostDiscoveryCleanup()
                closeReceiverRegistration()
                synchronized(peersLock) {
                    pendingSdpAddresses.clear()
                    discoveryInquiryFinished = false
                }
                val wasScanning = _isScanning.value
                _isScanning.value = false
                if (!wasScanning) {
                    return@withContext Result.success(Unit)
                }
                cancelActiveDiscovery()
            }
        }

    /**
     * Triggers system Bluetooth discoverability advertising so nearby scanners can discover this
     * device while [AndroidBluetoothTransportImpl] accepts incoming RFCOMM connections.
     */
    override suspend fun startAdvertising(localNickname: String): Result<Unit> {
        val normalizedNickname = localNickname.trim()
        if (normalizedNickname.isBlank()) {
            logger.warn(
                tag = AndroidTransportConstants.TAG_SCANNER,
                message = "Rejected Android Bluetooth advertising with blank nickname.",
            )
            return InvalidInputError("Local nickname must not be blank.").asFailure()
        }
        return stateMutex.withLock {
            withContext(dispatchers.io) {
                permissionChecker.ensureAdvertiseReady().getOrElse { error ->
                    return@withContext Result.failure(error)
                }
                startAdvertisingInternal()
            }
        }
    }

    /**
     * Marks Android discoverability advertising inactive.
     */
    override suspend fun stopAdvertising(): Result<Unit> =
        stateMutex.withLock {
            withContext(dispatchers.io) {
                _isAdvertising.value = false
                logger.info(
                    tag = AndroidTransportConstants.TAG_SCANNER,
                    message = "Stopped Android Bluetooth discoverability advertising.",
                )
                Result.success(Unit)
            }
        }

    /**
     * Deterministically releases registered broadcast receivers and cancels background SDP cleanup.
     */
    override fun close() {
        cancelPostDiscoveryCleanup()
        closeReceiverRegistration()
        synchronized(peersLock) {
            pendingSdpAddresses.clear()
            discoveryInquiryFinished = false
        }
        _isScanning.value = false
        _isAdvertising.value = false
        scannerScope.cancel()
    }

    private fun startDiscoveryInternal(): Result<Unit> {
        return try {
            cancelPostDiscoveryCleanup()
            closeReceiverRegistration()
            resetDiscoveryState()
            val registration =
                gateway.registerDiscoveryReceivers(
                    onDeviceFound = ::handleDeviceFound,
                    onDeviceUuidsFetched = ::handleDeviceUuidsFetched,
                    onDiscoveryFinished = ::handleDiscoveryFinished,
                    onSecurityError = ::handleReceiverSecurityError,
                )
            synchronized(resourceLock) {
                receiverRegistration = registration
            }
            syncBondedDevices()
            val started = gateway.startDiscovery()
            if (!started) {
                closeReceiverRegistration()
                return failAdapterDisabled("Android Bluetooth adapter rejected startDiscovery().")
            }
            _isScanning.value = true
            logger.info(
                tag = AndroidTransportConstants.TAG_SCANNER,
                message = "Started Android Bluetooth peer discovery.",
            )
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            closeReceiverRegistration()
            throw cancellation
        } catch (exception: SecurityException) {
            closeReceiverRegistration()
            failMissingPermission(
                action = "starting Android Bluetooth discovery",
                defaultPermission = Manifest.permission.BLUETOOTH_SCAN,
                exception = exception,
            )
        } catch (exception: IllegalStateException) {
            closeReceiverRegistration()
            failAdapterException("starting Android Bluetooth discovery", exception)
        } catch (exception: IOException) {
            closeReceiverRegistration()
            failAdapterException("starting Android Bluetooth discovery", exception)
        }
    }

    private fun cancelActiveDiscovery(): Result<Unit> =
        try {
            if (permissionChecker.hasScanPermission()) {
                gateway.cancelDiscovery()
            }
            logger.info(
                tag = AndroidTransportConstants.TAG_SCANNER,
                message = "Stopped Android Bluetooth peer discovery.",
            )
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: SecurityException) {
            failMissingPermission(
                action = "stopping Android Bluetooth discovery",
                defaultPermission = Manifest.permission.BLUETOOTH_SCAN,
                exception = exception,
            )
        } catch (exception: IllegalStateException) {
            failAdapterException("stopping Android Bluetooth discovery", exception)
        }

    private fun startAdvertisingInternal(): Result<Unit> =
        try {
            gateway.requestDiscoverable()
            _isAdvertising.value = true
            logger.info(
                tag = AndroidTransportConstants.TAG_SCANNER,
                message = "Started Android Bluetooth discoverability advertising.",
            )
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: SecurityException) {
            _isAdvertising.value = false
            failMissingPermission(
                action = "starting Android Bluetooth advertising",
                defaultPermission = Manifest.permission.BLUETOOTH_ADVERTISE,
                exception = exception,
            )
        } catch (exception: IllegalStateException) {
            _isAdvertising.value = false
            failAdapterException("starting Android Bluetooth advertising", exception)
        }

    private fun resetDiscoveryState() {
        synchronized(peersLock) {
            devicesByAddress.clear()
            discoveredByAddress.clear()
            canonicalServiceAddresses.clear()
            pendingSdpAddresses.clear()
            discoveryInquiryFinished = false
            _discoveredPeers.value = emptyList()
        }
    }

    private fun syncBondedDevices() {
        val bondedDevices = gateway.getBondedDevices()
        val addressesToFetch = mutableListOf<String>()
        for (device in bondedDevices) {
            val merged = mergeAndPublishDevice(device, replaceUuids = false)
            if (!merged.hasCanonicalServiceUuid()) {
                synchronized(peersLock) {
                    if (pendingSdpAddresses.add(merged.address)) {
                        addressesToFetch.add(merged.address)
                    }
                }
            }
        }
        fetchPendingSdpAddresses(addressesToFetch)
    }

    private fun handleDeviceFound(device: AndroidDiscoveredDevice) {
        val merged = mergeAndPublishDevice(device, replaceUuids = false)
        if (!merged.hasCanonicalServiceUuid()) {
            val shouldFetch =
                synchronized(peersLock) {
                    pendingSdpAddresses.add(merged.address)
                }
            if (shouldFetch) {
                fetchPendingSdpAddresses(listOf(merged.address))
            }
        } else {
            synchronized(peersLock) {
                pendingSdpAddresses.remove(merged.address)
            }
        }
    }

    private fun handleDeviceUuidsFetched(
        address: String,
        uuids: List<String>,
    ) {
        val normalizedAddress = address.trim().uppercase()
        mergeAndPublishDevice(
            incoming =
                AndroidDiscoveredDevice(
                    address = normalizedAddress,
                    name = null,
                    uuids = uuids,
                ),
            replaceUuids = true,
        )
        val shouldComplete =
            synchronized(peersLock) {
                pendingSdpAddresses.remove(normalizedAddress)
                discoveryInquiryFinished && pendingSdpAddresses.isEmpty()
            }
        if (shouldComplete) {
            completeDiscoveryScan()
        }
    }

    private fun handleDiscoveryFinished() {
        cancelPostDiscoveryCleanup()
        val remaining =
            synchronized(peersLock) {
                discoveryInquiryFinished = true
                pendingSdpAddresses.toList()
            }
        if (remaining.isEmpty()) {
            completeDiscoveryScan()
            return
        }
        fetchPendingSdpAddresses(remaining)
        val stillPending =
            synchronized(peersLock) {
                pendingSdpAddresses.isNotEmpty()
            }
        if (!stillPending) {
            completeDiscoveryScan()
        } else {
            schedulePostDiscoveryCleanup()
        }
    }

    private fun fetchPendingSdpAddresses(addresses: List<String>) {
        for (address in addresses) {
            val initiated = triggerSdpUuidFetch(address)
            if (!initiated) {
                synchronized(peersLock) {
                    pendingSdpAddresses.remove(address)
                }
            }
        }
    }

    private fun schedulePostDiscoveryCleanup() {
        synchronized(resourceLock) {
            postDiscoveryCleanupJob?.cancel()
            postDiscoveryCleanupJob =
                scannerScope.launch {
                    delay(postDiscoverySdpTimeoutMs)
                    completeDiscoveryScan()
                }
        }
    }

    private fun completeDiscoveryScan() {
        cancelPostDiscoveryCleanup()
        closeReceiverRegistration()
        synchronized(peersLock) {
            pendingSdpAddresses.clear()
            discoveryInquiryFinished = false
        }
        _isScanning.value = false
        logger.debug(
            tag = AndroidTransportConstants.TAG_SCANNER,
            message = "Android Bluetooth discovery finished.",
        )
    }

    private fun handleReceiverSecurityError(exception: SecurityException) {
        logger.warn(
            tag = AndroidTransportConstants.TAG_SCANNER,
            message = "Discovery broadcast receiver encountered SecurityException; stopping discovery.",
            throwable = exception,
        )
        completeDiscoveryScan()
    }

    private fun triggerSdpUuidFetch(address: String): Boolean =
        try {
            gateway.fetchUuidsWithSdp(address)
        } catch (exception: SecurityException) {
            logger.warn(
                tag = AndroidTransportConstants.TAG_SCANNER,
                message = "Missing permission to fetch SDP UUIDs for discovered device.",
                throwable = exception,
                context = mapOf("address" to address),
            )
            false
        } catch (exception: IllegalStateException) {
            logger.warn(
                tag = AndroidTransportConstants.TAG_SCANNER,
                message = "Bluetooth adapter unavailable while fetching SDP UUIDs.",
                throwable = exception,
                context = mapOf("address" to address),
            )
            false
        }

    private fun mergeAndPublishDevice(
        incoming: AndroidDiscoveredDevice,
        replaceUuids: Boolean,
    ): AndroidDiscoveredDevice {
        val key = incoming.address.trim().uppercase()
        synchronized(peersLock) {
            val previous = devicesByAddress[key]
            val mergedName =
                incoming.name?.takeIf { it.isNotBlank() }
                    ?: previous?.name?.takeIf { it.isNotBlank() }
            val mergedUuids =
                when {
                    replaceUuids -> incoming.uuids
                    incoming.uuids.isNotEmpty() -> incoming.uuids
                    else -> previous?.uuids.orEmpty()
                }
            val merged =
                AndroidDiscoveredDevice(
                    address = key,
                    name = mergedName,
                    uuids = mergedUuids,
                )
            devicesByAddress[key] = merged
            val candidate = buildMatchingPeerOrNull(merged)
            if (candidate != null) {
                discoveredByAddress[key] = candidate.peer
                if (candidate.hasCanonicalServiceUuid) {
                    canonicalServiceAddresses.add(key)
                } else {
                    canonicalServiceAddresses.remove(key)
                }
                publishPeersSnapshotLocked()
            } else {
                canonicalServiceAddresses.remove(key)
                if (discoveredByAddress.remove(key) != null) {
                    publishPeersSnapshotLocked()
                }
            }
            return merged
        }
    }

    private fun AndroidDiscoveredDevice.hasCanonicalServiceUuid(): Boolean =
        uuids.any { uuid -> uuid.equals(TransportConstants.CANONICAL_SERVICE_UUID, ignoreCase = true) }

    private fun buildMatchingPeerOrNull(device: AndroidDiscoveredDevice): DiscoveredAndroidCandidate? {
        val hasCanonicalUuid = device.hasCanonicalServiceUuid()
        val displayName = extractHumanReadableName(device.name)
        val shouldInclude = hasCanonicalUuid || displayName != null
        val resolvedAddress =
            if (shouldInclude) {
                BluetoothAddress.parse(device.address).getOrNull()
            } else {
                null
            }
        return resolvedAddress?.let { address ->
            val nickname = displayName ?: address.value
            val existingId = discoveredByAddress[address.value]?.id
            val peer =
                Peer(
                    id = existingId ?: deterministicPeerId(address),
                    nickname = nickname,
                    address = address,
                    identityKey = null,
                    isVerified = false,
                    lastSeenAt = clock(),
                )
            DiscoveredAndroidCandidate(peer = peer, hasCanonicalServiceUuid = hasCanonicalUuid)
        }
    }

    private fun extractHumanReadableName(rawName: String?): String? {
        val trimmed = rawName?.trim() ?: return null
        return trimmed.takeIf { it.isNotEmpty() && !isMacLikeName(it) }
    }

    private fun isMacLikeName(candidate: String): Boolean {
        val normalized =
            candidate.trim().replace(
                AndroidTransportConstants.MAC_HYPHEN_CHAR,
                AndroidTransportConstants.MAC_COLON_CHAR,
            )
        return BluetoothAddress.parse(normalized).isSuccess
    }

    private fun publishPeersSnapshotLocked() {
        _discoveredPeers.value =
            discoveredByAddress.entries
                .sortedWith(
                    compareBy<Map.Entry<String, Peer>> { it.key !in canonicalServiceAddresses }
                        .thenBy { it.value.nickname }
                        .thenBy { it.value.address.value },
                ).map { entry -> entry.value }
    }

    private fun deterministicPeerId(address: BluetoothAddress): PeerId {
        val seed = "${AndroidTransportConstants.PEER_ID_SEED_PREFIX}${address.value}".encodeToByteArray()
        val digest = CryptoEngine.sha256(seed)
        val msb = readLongBigEndian(digest, offset = 0)
        val lsb = readLongBigEndian(digest, offset = AndroidTransportConstants.PEER_ID_BYTES_MSB_COUNT)
        return PeerId(Uuid.fromLongs(msb, lsb))
    }

    private fun readLongBigEndian(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var result = 0L
        for (index in 0 until AndroidTransportConstants.PEER_ID_BYTES_MSB_COUNT) {
            result =
                (result shl AndroidTransportConstants.BITS_PER_BYTE) or
                (bytes[offset + index].toLong() and AndroidTransportConstants.BYTE_MASK)
        }
        return result
    }

    private fun closeReceiverRegistration() {
        val registration =
            synchronized(resourceLock) {
                receiverRegistration.also { receiverRegistration = null }
            } ?: return
        try {
            registration.close()
        } catch (exception: IllegalArgumentException) {
            logger.debug(
                tag = AndroidTransportConstants.TAG_SCANNER,
                message = "Ignored IllegalArgumentException while unregistering discovery receiver.",
                context = mapOf("reason" to (exception.message ?: "unknown")),
            )
        } catch (exception: IOException) {
            logger.debug(
                tag = AndroidTransportConstants.TAG_SCANNER,
                message = "Ignored IOException while closing discovery receiver registration.",
                context = mapOf("reason" to (exception.message ?: "unknown")),
            )
        }
    }

    private fun cancelPostDiscoveryCleanup() {
        val job =
            synchronized(resourceLock) {
                postDiscoveryCleanupJob.also { postDiscoveryCleanupJob = null }
            }
        job?.cancel()
    }

    private fun failMissingPermission(
        action: String,
        defaultPermission: String,
        exception: SecurityException,
    ): Result<Unit> {
        val permission = resolvePermission(exception, defaultPermission)
        val error =
            MissingBluetoothPermissionError(
                permission = permission,
                message = "Missing Bluetooth permission '$permission' while $action.",
            )
        logger.warn(
            tag = AndroidTransportConstants.TAG_SCANNER,
            message = error.message,
            throwable = exception,
            context = mapOf("permission" to permission),
        )
        return error.asFailure(exception)
    }

    private fun failAdapterDisabled(reason: String): Result<Unit> {
        val error = BluetoothAdapterDisabledError(reason)
        logger.warn(
            tag = AndroidTransportConstants.TAG_SCANNER,
            message = reason,
        )
        return error.asFailure()
    }

    private fun failAdapterException(
        action: String,
        exception: Throwable,
    ): Result<Unit> {
        val error =
            BluetoothAdapterDisabledError(
                "Android Bluetooth adapter failure while $action: ${exception.message ?: "unavailable"}.",
            )
        logger.warn(
            tag = AndroidTransportConstants.TAG_SCANNER,
            message = error.message,
            throwable = exception,
        )
        return error.asFailure(exception)
    }

    private fun resolvePermission(
        exception: SecurityException,
        defaultPermission: String,
    ): String {
        val detail = exception.message.orEmpty()
        return when {
            detail.contains(Manifest.permission.BLUETOOTH_CONNECT) -> Manifest.permission.BLUETOOTH_CONNECT
            detail.contains(Manifest.permission.BLUETOOTH_SCAN) -> Manifest.permission.BLUETOOTH_SCAN
            detail.contains(Manifest.permission.BLUETOOTH_ADVERTISE) -> Manifest.permission.BLUETOOTH_ADVERTISE
            else -> defaultPermission
        }
    }

    private data class DiscoveredAndroidCandidate(
        val peer: Peer,
        val hasCanonicalServiceUuid: Boolean,
    )
}
