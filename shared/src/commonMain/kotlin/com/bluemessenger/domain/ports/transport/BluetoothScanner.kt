package com.bluemessenger.domain.ports.transport

import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.model.Peer
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic Bluetooth device discovery and service advertising port.
 *
 * Thread safety: Implementations must expose thread-safe immutable state snapshots via
 * [StateFlow] and execute platform BlueZ/Android adapter operations off the main thread.
 *
 * Error handling: Missing permissions, disabled Bluetooth hardware, or SDP registration failures
 * must fail fast via [Result.failure] wrapping [DomainException] (`MissingBluetoothPermissionError`,
 * `BluetoothAdapterDisabledError`).
 */
interface BluetoothScanner {
    /**
     * Read-only snapshot of nearby peers discovered during active scanning.
     */
    val discoveredPeers: StateFlow<List<Peer>>

    /**
     * Indicates whether Bluetooth device discovery is currently running.
     */
    val isScanning: StateFlow<Boolean>

    /**
     * Indicates whether this device is currently advertising `TransportConstants.CANONICAL_SERVICE_UUID`.
     */
    val isAdvertising: StateFlow<Boolean>

    /**
     * Starts scanning for nearby peers exposing `TransportConstants.CANONICAL_SERVICE_UUID`.
     */
    suspend fun startDiscovery(): Result<Unit>

    /**
     * Stops an ongoing Bluetooth discovery scan.
     */
    suspend fun stopDiscovery(): Result<Unit>

    /**
     * Publishes the local Blue Messenger SDP/GATT service record using [localNickname].
     */
    suspend fun startAdvertising(localNickname: String): Result<Unit>

    /**
     * Unregisters the local Blue Messenger service advertisement.
     */
    suspend fun stopAdvertising(): Result<Unit>
}
