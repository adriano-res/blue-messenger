package com.bluemessenger.transport

import android.Manifest
import android.content.Context
import com.bluemessenger.domain.error.BluetoothAdapterDisabledError
import com.bluemessenger.domain.error.MissingBluetoothPermissionError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.transport.constants.AndroidTransportConstants

/**
 * Stateless validator for Android 12+ (API 31+) Bluetooth runtime permissions (`BLUETOOTH_SCAN`,
 * `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`) and local [android.bluetooth.BluetoothAdapter]
 * availability and power state.
 *
 * Thread safety: Stateless; safe to invoke concurrently across threads and dispatchers.
 *
 * Failure behavior: Fails fast via [Result.failure] wrapping `DomainException` with
 * [MissingBluetoothPermissionError] or [BluetoothAdapterDisabledError], logging structured warnings
 * via [AppLogger.warn].
 */
class AndroidBluetoothPermissionChecker internal constructor(
    private val gateway: AndroidBluetoothGateway,
    private val logger: AppLogger,
) {
    constructor(
        context: Context,
        logger: AppLogger,
    ) : this(
        gateway = AndroidBluetoothGateway(context),
        logger = logger,
    )

    /**
     * Returns whether `Manifest.permission.BLUETOOTH_SCAN` is currently granted.
     */
    fun hasScanPermission(): Boolean = gateway.hasPermission(Manifest.permission.BLUETOOTH_SCAN)

    /**
     * Verifies `BLUETOOTH_SCAN` permission and adapter availability/power state before scanning.
     */
    fun ensureScanReady(): Result<Unit> = ensurePermissionsAndAdapter(listOf(Manifest.permission.BLUETOOTH_SCAN))

    /**
     * Verifies `BLUETOOTH_ADVERTISE` permission and adapter availability/power state before
     * publishing an SDP service record.
     */
    fun ensureAdvertiseReady(): Result<Unit> {
        return ensurePermissionsAndAdapter(listOf(Manifest.permission.BLUETOOTH_ADVERTISE))
    }

    /**
     * Verifies `BLUETOOTH_CONNECT` permission and adapter availability/power state before opening
     * RFCOMM server or client sockets.
     */
    fun ensureConnectReady(): Result<Unit> = ensurePermissionsAndAdapter(listOf(Manifest.permission.BLUETOOTH_CONNECT))

    /**
     * Verifies all Android 12+ Bluetooth runtime permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`,
     * `BLUETOOTH_ADVERTISE`) and adapter readiness.
     */
    fun ensureAllReady(): Result<Unit> =
        ensurePermissionsAndAdapter(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ),
        )

    private fun ensurePermissionsAndAdapter(requiredPermissions: List<String>): Result<Unit> {
        val missingPermission = requiredPermissions.firstOrNull { permission -> !gateway.hasPermission(permission) }
        val failureError =
            when {
                missingPermission != null -> {
                    val error = MissingBluetoothPermissionError(permission = missingPermission)
                    logger.warn(
                        tag = AndroidTransportConstants.TAG_PERMISSION_CHECKER,
                        message = error.message,
                        context = mapOf("permission" to missingPermission),
                    )
                    error
                }
                !gateway.isAdapterAvailable() -> {
                    val error =
                        BluetoothAdapterDisabledError("Android Bluetooth adapter is unavailable on this device.")
                    logger.warn(
                        tag = AndroidTransportConstants.TAG_PERMISSION_CHECKER,
                        message = error.message,
                    )
                    error
                }
                !gateway.isAdapterEnabled() -> {
                    val error = BluetoothAdapterDisabledError("Android Bluetooth adapter is disabled.")
                    logger.warn(
                        tag = AndroidTransportConstants.TAG_PERMISSION_CHECKER,
                        message = error.message,
                    )
                    error
                }
                else -> null
            }
        return failureError?.asFailure() ?: Result.success(Unit)
    }
}
