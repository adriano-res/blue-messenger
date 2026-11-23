package com.bluemessenger.transport.dbus

import com.bluemessenger.transport.constants.BluezDbusConstants
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant

/**
 * BlueZ 5 `org.bluez.Adapter1` D-Bus proxy contract.
 */
@DBusInterfaceName(BluezDbusConstants.ADAPTER1_INTERFACE)
internal interface BluezAdapter1 : DBusInterface {
    @DBusMemberName("StartDiscovery")
    fun startDiscovery()

    @DBusMemberName("StopDiscovery")
    fun stopDiscovery()

    @DBusMemberName("SetDiscoveryFilter")
    fun setDiscoveryFilter(properties: Map<String, Variant<*>>)
}

/**
 * BlueZ 5 `org.bluez.Device1` D-Bus proxy contract.
 */
@DBusInterfaceName(BluezDbusConstants.DEVICE1_INTERFACE)
internal interface BluezDevice1 : DBusInterface {
    @DBusMemberName("Connect")
    fun connect()

    @DBusMemberName("Disconnect")
    fun disconnect() = Unit

    @DBusMemberName("ConnectProfile")
    fun connectProfile(uuid: String)

    @DBusMemberName("DisconnectProfile")
    fun disconnectProfile(uuid: String)
}

/**
 * BlueZ 5 `org.bluez.ProfileManager1` D-Bus proxy contract.
 */
@DBusInterfaceName(BluezDbusConstants.PROFILE_MANAGER1_INTERFACE)
internal interface BluezProfileManager1 : DBusInterface {
    @DBusMemberName("RegisterProfile")
    fun registerProfile(
        profile: DBusPath,
        uuid: String,
        options: Map<String, Variant<*>>,
    )

    @DBusMemberName("UnregisterProfile")
    fun unregisterProfile(profile: DBusPath)
}

/**
 * BlueZ 5 `org.bluez.Profile1` D-Bus callback contract exported by this application to receive
 * native RFCOMM file descriptors from `bluetoothd`.
 */
@DBusInterfaceName(BluezDbusConstants.PROFILE1_INTERFACE)
internal interface BluezProfile1 : DBusInterface {
    @DBusMemberName("Release")
    fun release()

    @DBusMemberName("NewConnection")
    fun newConnection(
        device: DBusPath,
        fd: FileDescriptor,
        fdProperties: Map<String, Variant<*>>,
    )

    @DBusMemberName("RequestDisconnection")
    fun requestDisconnection(device: DBusPath)
}
