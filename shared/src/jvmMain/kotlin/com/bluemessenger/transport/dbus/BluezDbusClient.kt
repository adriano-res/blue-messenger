package com.bluemessenger.transport.dbus

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.transport.RfcommSocketChannel
import com.bluemessenger.transport.constants.BluezDbusConstants
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.transport.junixsocket.JUnixSocketSocketProvider
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * System D-Bus client for BlueZ 5 proxies, signal subscriptions, and `SCM_RIGHTS` RFCOMM
 * file-descriptor conversion, backed by `dbus-java` and the `junixsocket` transport provider.
 *
 * Thread safety: Connection initialization and shutdown are synchronized via an internal lock.
 */
internal open class BluezDbusClient(
    private val connectionProvider: () -> DBusConnection = {
        DBusConnectionBuilder.forSystemBus().withShared(false).build()
    },
) : Closeable {
    private val connectionLock: Any = Any()
    private val socketProvider: JUnixSocketSocketProvider by lazy { JUnixSocketSocketProvider() }

    @Volatile
    private var connection: DBusConnection? = null

    /**
     * Ensures an active connection to the System D-Bus is established.
     */
    open fun ensureConnected() {
        requireConnection()
    }

    /**
     * Returns the BlueZ root `org.freedesktop.DBus.ObjectManager` proxy.
     */
    open fun objectManager(): ObjectManager =
        requireConnection().getRemoteObject(
            BluezDbusConstants.BLUEZ_BUS_NAME,
            BluezDbusConstants.BLUEZ_ROOT_PATH,
            ObjectManager::class.java,
        )

    /**
     * Returns the `org.freedesktop.DBus.Properties` proxy for [adapterPath].
     */
    open fun adapterProperties(adapterPath: String): Properties =
        requireConnection().getRemoteObject(
            BluezDbusConstants.BLUEZ_BUS_NAME,
            adapterPath,
            Properties::class.java,
        )

    /**
     * Returns the `org.bluez.Adapter1` proxy for [adapterPath].
     */
    open fun adapter(adapterPath: String): BluezAdapter1 =
        requireConnection().getRemoteObject(
            BluezDbusConstants.BLUEZ_BUS_NAME,
            adapterPath,
            BluezAdapter1::class.java,
        )

    /**
     * Returns the `org.bluez.Device1` proxy for [devicePath].
     */
    open fun device(devicePath: String): BluezDevice1 =
        requireConnection().getRemoteObject(
            BluezDbusConstants.BLUEZ_BUS_NAME,
            devicePath,
            BluezDevice1::class.java,
        )

    /**
     * Returns the `org.bluez.ProfileManager1` proxy at `/org/bluez`.
     */
    open fun profileManager(): BluezProfileManager1 =
        requireConnection().getRemoteObject(
            BluezDbusConstants.BLUEZ_BUS_NAME,
            BluezDbusConstants.BLUEZ_MANAGER_PATH,
            BluezProfileManager1::class.java,
        )

    /**
     * Exports a local `org.bluez.Profile1` object at [objectPath].
     */
    open fun exportProfile(
        objectPath: String,
        profile: BluezProfile1,
    ) {
        requireConnection().exportObject(objectPath, profile)
    }

    /**
     * Unexports a previously exported D-Bus object at [objectPath].
     */
    open fun unexportObject(objectPath: String) {
        synchronized(connectionLock) {
            connection?.unExportObject(objectPath)
        }
    }

    /**
     * Subscribes to `org.freedesktop.DBus.ObjectManager.InterfacesAdded` signals from `org.bluez`.
     */
    open fun addInterfacesAddedHandler(handler: (ObjectManager.InterfacesAdded) -> Unit): AutoCloseable =
        requireConnection().addSigHandler(
            ObjectManager.InterfacesAdded::class.java,
            BluezDbusConstants.BLUEZ_BUS_NAME,
            handler::invoke,
        )

    /**
     * Subscribes to `org.freedesktop.DBus.ObjectManager.InterfacesRemoved` signals from `org.bluez`.
     */
    open fun addInterfacesRemovedHandler(handler: (ObjectManager.InterfacesRemoved) -> Unit): AutoCloseable =
        requireConnection().addSigHandler(
            ObjectManager.InterfacesRemoved::class.java,
            BluezDbusConstants.BLUEZ_BUS_NAME,
            handler::invoke,
        )

    /**
     * Subscribes to `org.freedesktop.DBus.Properties.PropertiesChanged` signals from `org.bluez`.
     */
    open fun addPropertiesChangedHandler(handler: (Properties.PropertiesChanged) -> Unit): AutoCloseable =
        requireConnection().addSigHandler(
            Properties.PropertiesChanged::class.java,
            BluezDbusConstants.BLUEZ_BUS_NAME,
            handler::invoke,
        )

    /**
     * Converts a BlueZ-supplied [dbusFd] into a bidirectional [RfcommSocketChannel].
     */
    open fun openRfcommChannel(
        dbusFd: FileDescriptor,
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
    ): RfcommSocketChannel {
        val javaFd = dbusFd.toJavaFileDescriptor(socketProvider)
        val inputStream = FileInputStream(javaFd)
        val outputStream = FileOutputStream(javaFd)
        val descriptorCloseable =
            Closeable {
                try {
                    inputStream.close()
                } finally {
                    outputStream.close()
                }
            }
        return RfcommSocketChannel(
            inputStream = inputStream,
            outputStream = outputStream,
            closeable = descriptorCloseable,
            dispatchers = dispatchers,
            logger = logger,
        )
    }

    override fun close() {
        synchronized(connectionLock) {
            val activeConnection = connection ?: return
            connection = null
            activeConnection.disconnect()
        }
    }

    private fun requireConnection(): DBusConnection {
        connection?.let { return it }
        return synchronized(connectionLock) {
            connection ?: connectionProvider().also { created ->
                connection = created
            }
        }
    }
}
