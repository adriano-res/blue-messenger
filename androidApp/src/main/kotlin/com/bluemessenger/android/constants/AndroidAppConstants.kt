package com.bluemessenger.android.constants

import android.Manifest

/**
 * Permission list, logging tags, and structured context keys for the Android application
 * entrypoint.
 */
internal object AndroidAppConstants {
    val REQUIRED_BLUETOOTH_PERMISSIONS: List<String> =
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )

    const val TAG_MAIN_ACTIVITY: String = "MainActivity"
    const val TAG_PERMISSION_REQUESTER: String = "BluetoothPermissionRequester"

    const val CONTEXT_KEY_MISSING_PERMISSIONS: String = "missingPermissions"
    const val CONTEXT_KEY_DENIED_PERMISSIONS: String = "deniedPermissions"
    const val CONTEXT_LIST_DELIMITER: String = ","
    const val DISCOVERABLE_DURATION_SECONDS: Int = 300
}
