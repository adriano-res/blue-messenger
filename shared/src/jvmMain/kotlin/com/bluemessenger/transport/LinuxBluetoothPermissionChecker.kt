package com.bluemessenger.transport

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.error.BluetoothAdapterDisabledError
import com.bluemessenger.domain.error.DomainError
import com.bluemessenger.domain.error.MissingBluetoothPermissionError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.transport.constants.BluezDbusConstants
import com.bluemessenger.transport.constants.LinuxTransportConstants
import com.bluemessenger.transport.dbus.BluezDbusClient
import com.bluemessenger.transport.dbus.BluezDbusErrorMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.types.Variant
import java.io.IOException

/**
 * Verifies Linux System D-Bus BlueZ access, user permissions (`bluetooth` group / D-Bus policy),
 * adapter availability, and `Powered` hardware state before scanning or opening RFCOMM links.
 *
 * Thread safety: Stateless; safe to invoke concurrently across coroutine dispatchers. Blocking
 * D-Bus queries execute on [CoroutineDispatchers.io].
 *
 * Failure behavior: Fails fast via [Result.failure] carrying [MissingBluetoothPermissionError]
 * when System D-Bus rejects access, or [BluetoothAdapterDisabledError] when `org.bluez`, a local
 * adapter, or adapter power is unavailable. Always rethrows [CancellationException].
 */
class LinuxBluetoothPermissionChecker internal constructor(
    private val bluezClient: BluezDbusClient,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
) {
    constructor(
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
    ) : this(
        bluezClient = BluezDbusClient(),
        dispatchers = dispatchers,
        logger = logger,
    )

    /**
     * Validates System D-Bus connectivity, BlueZ adapter presence, and `Powered == true`,
     * returning the resolved BlueZ adapter D-Bus object path (e.g., `/org/bluez/hci0`).
     */
    suspend fun ensureReady(): Result<String> =
        withContext(dispatchers.io) {
            try {
                bluezClient.ensureConnected()
                val managedObjects = bluezClient.objectManager().GetManagedObjects()
                val adapterPath =
                    resolveAdapterPath(managedObjects)
                        ?: return@withContext failDisabled(
                            "No BlueZ Bluetooth adapter (${BluezDbusConstants.ADAPTER1_INTERFACE}) was found.",
                        )
                val poweredValue: Any? =
                    bluezClient.adapterProperties(adapterPath).Get<Any?>(
                        BluezDbusConstants.ADAPTER1_INTERFACE,
                        BluezDbusConstants.PROPERTY_POWERED,
                    )
                val isPowered = extractBooleanProperty(poweredValue)
                if (!isPowered) {
                    return@withContext failDisabled("BlueZ Bluetooth adapter '$adapterPath' is powered off.")
                }
                logger.debug(
                    tag = LinuxTransportConstants.TAG_PERMISSION_CHECKER,
                    message = "BlueZ adapter readiness verified.",
                    context = mapOf("adapterPath" to adapterPath),
                )
                Result.success(adapterPath)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: DBusException) {
                failMapped(exception)
            } catch (exception: DBusExecutionException) {
                failMapped(exception)
            } catch (exception: IOException) {
                failMapped(exception)
            } catch (exception: SecurityException) {
                failMapped(exception)
            } catch (exception: IllegalStateException) {
                failMapped(exception)
            }
        }

    private fun resolveAdapterPath(managedObjects: Map<DBusPath, Map<String, Map<String, Variant<*>>>>): String? {
        val adapterPaths =
            managedObjects.entries
                .filter { (_, interfaces) -> interfaces.containsKey(BluezDbusConstants.ADAPTER1_INTERFACE) }
                .map { (path, _) -> path.path }
        return adapterPaths.firstOrNull { it == BluezDbusConstants.DEFAULT_ADAPTER_PATH }
            ?: adapterPaths.firstOrNull()
    }

    private fun extractBooleanProperty(rawValue: Any?): Boolean =
        when (rawValue) {
            is Boolean -> rawValue
            is Variant<*> -> rawValue.value as? Boolean ?: false
            else -> false
        }

    private fun failDisabled(reason: String): Result<String> {
        val error: DomainError = BluetoothAdapterDisabledError(reason)
        logger.warn(
            tag = LinuxTransportConstants.TAG_PERMISSION_CHECKER,
            message = reason,
        )
        return error.asFailure()
    }

    private fun failMapped(throwable: Throwable): Result<String> {
        val domainError = BluezDbusErrorMapper.mapSystemFailure(throwable, "verifying BlueZ adapter")
        logger.warn(
            tag = LinuxTransportConstants.TAG_PERMISSION_CHECKER,
            message = "BlueZ permission or adapter readiness check failed.",
            throwable = throwable,
            context = mapOf("errorType" to (domainError::class.simpleName ?: "DomainError")),
        )
        return domainError.asFailure(throwable)
    }
}
