package com.bluemessenger.android

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.bluemessenger.android.constants.AndroidAppConstants
import com.bluemessenger.domain.ports.logging.AppLogger
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin coordinator that identifies missing API 31+ Bluetooth runtime permissions and requests
 * them via [ActivityResultContracts.RequestMultiplePermissions] on [ComponentActivity].
 *
 * Thread safety: Intended for invocation on the Android Main Thread during activity creation and
 * result callback delivery.
 *
 * Failure behavior: Logs denied permission names at warn level without exposing sensitive user
 * data and forwards the resulting grant map to [onPermissionsResult].
 */
class BluetoothPermissionRequester internal constructor(
    private val permissionChecker: (String) -> Boolean,
    private val permissionLauncher: (Array<String>) -> Unit,
    private val logger: AppLogger,
    private val onPermissionsResult: (Map<String, Boolean>) -> Unit = {},
) {
    constructor(
        activity: ComponentActivity,
        logger: AppLogger,
        onPermissionsResult: (Map<String, Boolean>) -> Unit = {},
    ) : this(
        activity = activity,
        logger = logger,
        onPermissionsResult = onPermissionsResult,
        selfRef = AtomicReference<BluetoothPermissionRequester?>(null),
    )

    private constructor(
        activity: ComponentActivity,
        logger: AppLogger,
        onPermissionsResult: (Map<String, Boolean>) -> Unit,
        selfRef: AtomicReference<BluetoothPermissionRequester?>,
    ) : this(
        permissionChecker = { permission ->
            activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        },
        permissionLauncher =
            registerPermissionLauncher(activity) { results ->
                selfRef.get()?.handlePermissionsResult(results)
            },
        logger = logger,
        onPermissionsResult = onPermissionsResult,
    ) {
        selfRef.set(this)
    }

    /**
     * Returns the subset of [AndroidAppConstants.REQUIRED_BLUETOOTH_PERMISSIONS] not yet granted.
     */
    fun missingPermissions(): List<String> {
        return AndroidAppConstants.REQUIRED_BLUETOOTH_PERMISSIONS.filterNot(permissionChecker)
    }

    /**
     * Launches the runtime permission prompt for any missing Bluetooth permissions and returns
     * `true` if a prompt was triggered, or `false` if all permissions are already granted.
     */
    fun requestMissingPermissions(): Boolean {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            logger.debug(
                tag = AndroidAppConstants.TAG_PERMISSION_REQUESTER,
                message = "All required Bluetooth permissions are already granted.",
            )
            return false
        }
        logger.info(
            tag = AndroidAppConstants.TAG_PERMISSION_REQUESTER,
            message = "Requesting missing Bluetooth runtime permissions.",
            context =
                mapOf(
                    AndroidAppConstants.CONTEXT_KEY_MISSING_PERMISSIONS to
                        missing.joinToString(AndroidAppConstants.CONTEXT_LIST_DELIMITER),
                ),
        )
        permissionLauncher(missing.toTypedArray())
        return true
    }

    internal fun handlePermissionsResult(results: Map<String, Boolean>) {
        val denied = results.filterValues { granted -> !granted }.keys.sorted()
        if (denied.isEmpty()) {
            logger.info(
                tag = AndroidAppConstants.TAG_PERMISSION_REQUESTER,
                message = "All requested Bluetooth permissions were granted.",
            )
        } else {
            logger.warn(
                tag = AndroidAppConstants.TAG_PERMISSION_REQUESTER,
                message = "One or more Bluetooth permissions were denied.",
                context =
                    mapOf(
                        AndroidAppConstants.CONTEXT_KEY_DENIED_PERMISSIONS to
                            denied.joinToString(AndroidAppConstants.CONTEXT_LIST_DELIMITER),
                    ),
            )
        }
        onPermissionsResult(results)
    }

    private companion object {
        private fun registerPermissionLauncher(
            activity: ComponentActivity,
            onResult: (Map<String, Boolean>) -> Unit,
        ): (Array<String>) -> Unit {
            val launcher =
                activity.registerForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                    onResult,
                )
            return { permissions -> launcher.launch(permissions) }
        }
    }
}
