package com.bluemessenger.android

import android.Manifest
import com.bluemessenger.domain.ports.logging.AppLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothPermissionRequesterTest {
    @Test
    fun `filters only missing API 31+ Bluetooth permissions and launches prompt`() {
        val granted = setOf(Manifest.permission.BLUETOOTH_SCAN)
        val launchedPermissions = mutableListOf<List<String>>()
        val requester =
            BluetoothPermissionRequester(
                permissionChecker = { permission -> permission in granted },
                permissionLauncher = { permissions -> launchedPermissions.add(permissions.toList()) },
                logger = RecordingLogger(),
                onPermissionsResult = {},
            )

        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ),
            requester.missingPermissions(),
        )
        assertTrue(requester.requestMissingPermissions())
        assertEquals(
            listOf(
                listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                ),
            ),
            launchedPermissions,
        )
    }

    @Test
    fun `requests all three Bluetooth permissions when none are granted`() {
        val launchedPermissions = mutableListOf<List<String>>()
        val requester =
            BluetoothPermissionRequester(
                permissionChecker = { false },
                permissionLauncher = { permissions -> launchedPermissions.add(permissions.toList()) },
                logger = RecordingLogger(),
                onPermissionsResult = {},
            )

        assertTrue(requester.requestMissingPermissions())
        assertEquals(
            listOf(
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                ),
            ),
            launchedPermissions,
        )
    }

    @Test
    fun `does not invoke launcher when all Bluetooth permissions are already granted`() {
        var launcherInvocations = 0
        val requester =
            BluetoothPermissionRequester(
                permissionChecker = { true },
                permissionLauncher = { launcherInvocations += 1 },
                logger = RecordingLogger(),
                onPermissionsResult = {},
            )

        assertTrue(requester.missingPermissions().isEmpty())
        assertFalse(requester.requestMissingPermissions())
        assertEquals(0, launcherInvocations)
    }

    @Test
    fun `forwards granted and denied permission results to callback and logs warnings for denials`() {
        val logger = RecordingLogger()
        val receivedCallbacks = mutableListOf<Map<String, Boolean>>()
        val requester =
            BluetoothPermissionRequester(
                permissionChecker = { false },
                permissionLauncher = {},
                logger = logger,
                onPermissionsResult = { result -> receivedCallbacks.add(result) },
            )

        val outcome =
            mapOf(
                Manifest.permission.BLUETOOTH_SCAN to true,
                Manifest.permission.BLUETOOTH_CONNECT to false,
                Manifest.permission.BLUETOOTH_ADVERTISE to true,
            )
        requester.handlePermissionsResult(outcome)

        assertEquals(listOf(outcome), receivedCallbacks)
        assertTrue(logger.warnMessages.any { it.contains("denied") })
    }

    private class RecordingLogger : AppLogger {
        val warnMessages: MutableList<String> = mutableListOf()

        override fun debug(
            tag: String,
            message: String,
            context: Map<String, String>,
        ) = Unit

        override fun info(
            tag: String,
            message: String,
            context: Map<String, String>,
        ) = Unit

        override fun warn(
            tag: String,
            message: String,
            throwable: Throwable?,
            context: Map<String, String>,
        ) {
            warnMessages.add(message)
        }

        override fun error(
            tag: String,
            message: String,
            throwable: Throwable?,
            context: Map<String, String>,
        ) = Unit
    }
}
