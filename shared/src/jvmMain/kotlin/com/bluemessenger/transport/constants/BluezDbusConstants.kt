package com.bluemessenger.transport.constants

/**
 * D-Bus bus names, object paths, interface names, property keys, and profile options for BlueZ 5.
 */
internal object BluezDbusConstants {
    const val BLUEZ_BUS_NAME: String = "org.bluez"
    const val BLUEZ_ROOT_PATH: String = "/"
    const val BLUEZ_MANAGER_PATH: String = "/org/bluez"
    const val DEFAULT_ADAPTER_PATH: String = "/org/bluez/hci0"

    const val ADAPTER1_INTERFACE: String = "org.bluez.Adapter1"
    const val DEVICE1_INTERFACE: String = "org.bluez.Device1"
    const val PROFILE_MANAGER1_INTERFACE: String = "org.bluez.ProfileManager1"
    const val PROFILE1_INTERFACE: String = "org.bluez.Profile1"

    const val PROPERTY_POWERED: String = "Powered"
    const val PROPERTY_ADDRESS: String = "Address"
    const val PROPERTY_NAME: String = "Name"
    const val PROPERTY_ALIAS: String = "Alias"
    const val PROPERTY_UUIDS: String = "UUIDs"
    const val PROPERTY_DISCOVERABLE: String = "Discoverable"
    const val PROPERTY_PAIRED: String = "Paired"
    const val PROPERTY_CONNECTED: String = "Connected"
    const val PROPERTY_RSSI: String = "RSSI"

    const val DISCOVERY_FILTER_UUIDS: String = "UUIDs"
    const val DISCOVERY_FILTER_TRANSPORT: String = "Transport"
    const val DISCOVERY_TRANSPORT_BR_EDR: String = "bredr"
    const val DBUS_STRING_ARRAY_SIGNATURE: String = "as"

    const val PROFILE_OPTION_ROLE: String = "Role"
    const val PROFILE_OPTION_NAME: String = "Name"
    const val PROFILE_OPTION_SERVICE: String = "Service"
    const val PROFILE_OPTION_CHANNEL: String = "Channel"
    const val PROFILE_OPTION_AUTO_CONNECT: String = "AutoConnect"
    const val PROFILE_OPTION_REQUIRE_AUTHENTICATION: String = "RequireAuthentication"
    const val PROFILE_OPTION_REQUIRE_AUTHORIZATION: String = "RequireAuthorization"
    const val PROFILE_ROLE_SERVER: String = "server"
    const val PROFILE_ROLE_CLIENT: String = "client"
    const val PROFILE_NAME_BLUE_MESSENGER: String = "BlueMessenger"

    const val LOCAL_SCANNER_PROFILE_PATH: String = "/com/bluemessenger/bluez/scanner_profile"
    const val LOCAL_TRANSPORT_PROFILE_PATH: String = "/com/bluemessenger/bluez/transport_profile"

    const val PERMISSION_LINUX_BLUETOOTH_DBUS: String = "org.bluez (Linux bluetooth group / D-Bus policy)"
    const val ERROR_TOKEN_ACCESS_DENIED: String = "AccessDenied"
    const val ERROR_TOKEN_NOT_AUTHORIZED: String = "NotAuthorized"
    const val ERROR_TOKEN_PERMISSION: String = "Permission"
    const val ERROR_TOKEN_NOT_READY: String = "NotReady"
    const val ERROR_TOKEN_NOT_POWERED: String = "NotPowered"
    const val ERROR_TOKEN_NOT_AVAILABLE: String = "NotAvailable"
    const val ERROR_TOKEN_NOT_SUPPORTED: String = "NotSupported"
    const val ERROR_TOKEN_CONNECTION_NOT_SUPPORTED: String = "connection-not-supported"
    const val ERROR_TOKEN_ALREADY_CONNECTED: String = "AlreadyConnected"
    const val ERROR_TOKEN_IN_PROGRESS: String = "InProgress"
    const val ERROR_TOKEN_NOT_CONNECTED: String = "NotConnected"

    val PREFERRED_FALLBACK_PROBE_UUIDS: List<String> =
        listOf(
            "00001200-0000-1000-8000-00805f9b34fb",
            "00001800-0000-1000-8000-00805f9b34fb",
            "00001801-0000-1000-8000-00805f9b34fb",
            "00001105-0000-1000-8000-00805f9b34fb",
            "0000112f-0000-1000-8000-00805f9b34fb",
            "00001132-0000-1000-8000-00805f9b34fb",
        )
}
