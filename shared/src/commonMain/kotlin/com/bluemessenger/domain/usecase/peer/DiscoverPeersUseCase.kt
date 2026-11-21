package com.bluemessenger.domain.usecase.peer

import com.bluemessenger.domain.error.InvalidInputError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.transport.BluetoothScanner
import kotlinx.coroutines.flow.StateFlow

/**
 * Orchestrates Bluetooth peer discovery and SDP/GATT service advertising.
 *
 * Thread safety: Delegates reactive state snapshots ([discoveredPeers], [isScanning],
 * [isAdvertising]) directly to the thread-safe [BluetoothScanner] port.
 *
 * Failure behavior: Logs structured warnings and propagates `MissingBluetoothPermissionError`,
 * `BluetoothAdapterDisabledError`, or [InvalidInputError] fail-fast.
 */
class DiscoverPeersUseCase(
    private val bluetoothScanner: BluetoothScanner,
    private val logger: AppLogger,
) {
    /**
     * Reactive snapshot of nearby peers discovered during active scanning.
     */
    val discoveredPeers: StateFlow<List<Peer>>
        get() = bluetoothScanner.discoveredPeers

    /**
     * Indicates whether Bluetooth discovery is actively running.
     */
    val isScanning: StateFlow<Boolean>
        get() = bluetoothScanner.isScanning

    /**
     * Indicates whether this device is actively advertising the Blue Messenger service.
     */
    val isAdvertising: StateFlow<Boolean>
        get() = bluetoothScanner.isAdvertising

    /**
     * Starts scanning for nearby peers running the Blue Messenger service.
     */
    suspend fun startDiscovery(): Result<Unit> =
        bluetoothScanner.startDiscovery()
            .onSuccess {
                logger.info(tag = TAG, message = "Started Bluetooth peer discovery.")
            }.onFailure { error ->
                logger.warn(tag = TAG, message = "Failed to start Bluetooth peer discovery.", throwable = error)
            }

    /**
     * Stops an active Bluetooth discovery scan.
     */
    suspend fun stopDiscovery(): Result<Unit> =
        bluetoothScanner.stopDiscovery()
            .onSuccess {
                logger.info(tag = TAG, message = "Stopped Bluetooth peer discovery.")
            }.onFailure { error ->
                logger.warn(tag = TAG, message = "Failed to stop Bluetooth peer discovery.", throwable = error)
            }

    /**
     * Starts advertising the Blue Messenger service using [localNickname].
     */
    suspend fun startAdvertising(localNickname: String): Result<Unit> {
        val normalizedNickname = localNickname.trim()
        if (normalizedNickname.isBlank()) {
            logger.warn(tag = TAG, message = "Rejected Bluetooth advertising with blank nickname.")
            return InvalidInputError("Local nickname must not be blank.").asFailure()
        }
        return bluetoothScanner.startAdvertising(normalizedNickname)
            .onSuccess {
                logger.info(tag = TAG, message = "Started Bluetooth service advertising.")
            }.onFailure { error ->
                logger.warn(tag = TAG, message = "Failed to start Bluetooth service advertising.", throwable = error)
            }
    }

    /**
     * Stops advertising the local Blue Messenger service.
     */
    suspend fun stopAdvertising(): Result<Unit> =
        bluetoothScanner.stopAdvertising()
            .onSuccess {
                logger.info(tag = TAG, message = "Stopped Bluetooth service advertising.")
            }.onFailure { error ->
                logger.warn(tag = TAG, message = "Failed to stop Bluetooth service advertising.", throwable = error)
            }

    private companion object {
        private const val TAG: String = "DiscoverPeersUseCase"
    }
}
