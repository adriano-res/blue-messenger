package com.bluemessenger.transport.dbus

import com.bluemessenger.domain.error.BluetoothAdapterDisabledError
import com.bluemessenger.domain.error.DomainError
import com.bluemessenger.domain.error.MissingBluetoothPermissionError
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.transport.constants.BluezDbusConstants
import org.freedesktop.dbus.errors.AccessDenied

/**
 * Shared mapper translating BlueZ D-Bus and OS security/I/O failures into typed [DomainError]s.
 */
internal object BluezDbusErrorMapper {
    fun mapSystemFailure(
        throwable: Throwable,
        contextAction: String,
    ): DomainError {
        val rawDetail = extractErrorDetail(throwable)
        return when {
            throwable is SecurityException ||
                throwable is AccessDenied ||
                isPermissionDeniedDetail(rawDetail) ->
                MissingBluetoothPermissionError(
                    permission = BluezDbusConstants.PERMISSION_LINUX_BLUETOOTH_DBUS,
                    message =
                        "Missing Linux Bluetooth permission while $contextAction: " +
                            "$rawDetail. Verify user membership in the 'bluetooth' group and D-Bus policy.",
                )
            else ->
                BluetoothAdapterDisabledError(
                    message = "Bluetooth adapter or BlueZ service unavailable while $contextAction: $rawDetail.",
                )
        }
    }

    fun mapTransportFailure(
        throwable: Throwable,
        contextAction: String,
    ): DomainError {
        val rawDetail = extractErrorDetail(throwable)
        return when {
            throwable is SecurityException ||
                throwable is AccessDenied ||
                isPermissionDeniedDetail(rawDetail) ->
                MissingBluetoothPermissionError(
                    permission = BluezDbusConstants.PERMISSION_LINUX_BLUETOOTH_DBUS,
                    message = "Missing Linux Bluetooth permission while $contextAction: $rawDetail.",
                )
            isAdapterNotReadyDetail(rawDetail) ->
                BluetoothAdapterDisabledError(
                    message = "Bluetooth adapter disabled or not ready while $contextAction: $rawDetail.",
                )
            else ->
                TransportDisconnectedError(
                    message = "Bluetooth transport failed while $contextAction: $rawDetail.",
                )
        }
    }

    fun isPermissionDeniedDetail(detail: String): Boolean =
        detail.contains(BluezDbusConstants.ERROR_TOKEN_ACCESS_DENIED, ignoreCase = true) ||
            detail.contains(BluezDbusConstants.ERROR_TOKEN_NOT_AUTHORIZED, ignoreCase = true) ||
            detail.contains(BluezDbusConstants.ERROR_TOKEN_PERMISSION, ignoreCase = true)

    fun isAdapterNotReadyDetail(detail: String): Boolean =
        detail.contains(BluezDbusConstants.ERROR_TOKEN_NOT_READY, ignoreCase = true) ||
            detail.contains(BluezDbusConstants.ERROR_TOKEN_NOT_POWERED, ignoreCase = true)

    private fun extractErrorDetail(throwable: Throwable): String {
        val typeName = throwable::class.simpleName
        val message = throwable.message
        return listOfNotNull(typeName, message)
            .filter { it.isNotBlank() }
            .joinToString(separator = ": ")
            .ifBlank { "unknown D-Bus error" }
    }
}
