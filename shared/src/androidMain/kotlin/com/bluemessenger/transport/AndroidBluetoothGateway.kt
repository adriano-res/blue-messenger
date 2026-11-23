package com.bluemessenger.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.transport.constants.AndroidTransportConstants
import java.io.Closeable
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight immutable snapshot of an Android Bluetooth device discovered via bonded-device
 * cache or `ACTION_FOUND` broadcasts.
 */
internal data class AndroidDiscoveredDevice(
    val address: String,
    val name: String?,
    val uuids: List<String>,
)

/**
 * Result of accepting an incoming insecure RFCOMM client socket along with resolved peer metadata.
 */
internal data class AcceptedAndroidConnection(
    val channel: RfcommSocketChannel,
    val remoteAddress: String?,
    val remoteName: String?,
)

/**
 * Closeable handle wrapping an Android [BluetoothServerSocket] for accepting incoming insecure
 * RFCOMM client connections.
 *
 * Thread safety: [accept] is blocking and must run on an injected I/O dispatcher; [close] is
 * thread-safe and idempotent.
 */
internal open class AndroidServerSocketHandle(
    private val serverSocket: BluetoothServerSocket?,
) : Closeable {
    private val closed: AtomicBoolean = AtomicBoolean(false)

    /**
     * Blocks until a remote peer connects and returns an [AcceptedAndroidConnection] wrapping a
     * framed [RfcommSocketChannel] and the remote Bluetooth address/name when available.
     */
    @Throws(IOException::class)
    open fun accept(
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
    ): AcceptedAndroidConnection {
        val server = checkNotNull(serverSocket) { "BluetoothServerSocket is not initialized." }
        val clientSocket = server.accept()
        val remoteAddress = clientSocket.remoteDevice?.address
        val remoteName = extractRemoteDeviceName(clientSocket)
        val channel = openChannelFromSocket(clientSocket, dispatchers, logger)
        return AcceptedAndroidConnection(
            channel = channel,
            remoteAddress = remoteAddress,
            remoteName = remoteName,
        )
    }

    @SuppressLint("MissingPermission")
    private fun extractRemoteDeviceName(clientSocket: BluetoothSocket): String? =
        try {
            clientSocket.remoteDevice?.name
        } catch (_: SecurityException) {
            null
        }

    /**
     * Closes the underlying [BluetoothServerSocket] idempotently.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        serverSocket?.close()
    }
}

/**
 * Low-level Android Bluetooth SDK gateway encapsulating [BluetoothManager], [BluetoothAdapter],
 * runtime permission checks, discovery [BroadcastReceiver] lifecycle, and insecure RFCOMM
 * server/client sockets.
 *
 * Thread safety: Retains only [Context.getApplicationContext]. Returned [AutoCloseable] receiver
 * registrations and [AndroidServerSocketHandle] instances are idempotent on [close].
 *
 * Failure behavior: Low-level calls assume runtime permissions were pre-validated by
 * [AndroidBluetoothPermissionChecker] and propagate [SecurityException], [IOException], and
 * [IllegalStateException] without swallowing errors.
 */
internal open class AndroidBluetoothGateway private constructor(
    private val applicationContext: Context?,
    private val onDiscoverableRequested: (() -> Unit)?,
    @Suppress("UNUSED_PARAMETER") unusedMarker: Boolean,
) {
    constructor(
        context: Context,
        onDiscoverableRequested: (() -> Unit)? = null,
    ) : this(
        applicationContext = context.applicationContext,
        onDiscoverableRequested = onDiscoverableRequested,
        unusedMarker = false,
    )

    protected constructor() : this(
        applicationContext = null,
        onDiscoverableRequested = null,
        unusedMarker = true,
    )

    /**
     * Triggers the platform discoverability prompt if an entrypoint callback is registered.
     */
    open fun requestDiscoverable() {
        onDiscoverableRequested?.invoke()
    }

    /**
     * Returns whether [permission] is currently granted to the application process.
     */
    open fun hasPermission(permission: String): Boolean {
        val ctx = checkNotNull(applicationContext) { "Android Context is not configured." }
        return ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns whether the device exposes a non-null [BluetoothAdapter].
     */
    open fun isAdapterAvailable(): Boolean = resolveAdapterOrNull() != null

    /**
     * Returns whether the device [BluetoothAdapter] is currently powered on.
     */
    open fun isAdapterEnabled(): Boolean = resolveAdapterOrNull()?.isEnabled == true

    /**
     * Starts Android classic Bluetooth inquiry discovery.
     */
    @SuppressLint("MissingPermission")
    open fun startDiscovery(): Boolean = requireAdapter().startDiscovery()

    /**
     * Cancels an active Android classic Bluetooth inquiry discovery session.
     */
    @SuppressLint("MissingPermission")
    open fun cancelDiscovery(): Boolean = requireAdapter().cancelDiscovery()

    /**
     * Returns immutable snapshots of all currently bonded devices known to the local adapter.
     */
    @SuppressLint("MissingPermission")
    open fun getBondedDevices(): List<AndroidDiscoveredDevice> {
        val adapter = requireAdapter()
        return adapter.bondedDevices.orEmpty().map { device -> device.toDiscoveredDevice() }
    }

    /**
     * Triggers an asynchronous SDP UUID refresh for the remote device at [address].
     */
    @SuppressLint("MissingPermission")
    open fun fetchUuidsWithSdp(address: String): Boolean {
        val device = requireAdapter().getRemoteDevice(address)
        return device.fetchUuidsWithSdp()
    }

    /**
     * Registers [BroadcastReceiver] listeners for `ACTION_FOUND`, `ACTION_UUID`, and
     * `ACTION_DISCOVERY_FINISHED`, returning an idempotent [AutoCloseable] to unregister them.
     */
    open fun registerDiscoveryReceivers(
        onDeviceFound: (AndroidDiscoveredDevice) -> Unit,
        onDeviceUuidsFetched: (address: String, uuids: List<String>) -> Unit,
        onDiscoveryFinished: () -> Unit,
        onSecurityError: (SecurityException) -> Unit,
    ): AutoCloseable {
        val ctx = checkNotNull(applicationContext) { "Android Context is not configured." }
        val receiver =
            DiscoveryBroadcastReceiver(
                onDeviceFound = onDeviceFound,
                onDeviceUuidsFetched = onDeviceUuidsFetched,
                onDiscoveryFinished = onDiscoveryFinished,
                onSecurityError = onSecurityError,
            )
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_UUID)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerLegacyReceiver(ctx, receiver, filter)
        }
        val unregistered = AtomicBoolean(false)
        return AutoCloseable {
            if (unregistered.compareAndSet(false, true)) {
                unregisterQuietly(ctx, receiver)
            }
        }
    }

    /**
     * Opens an insecure RFCOMM SDP server socket listening for [serviceUuid] under [serviceName].
     */
    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    open fun openInsecureServerSocket(
        serviceName: String,
        serviceUuid: String,
    ): AndroidServerSocketHandle {
        val serverSocket =
            requireAdapter().listenUsingInsecureRfcommWithServiceRecord(
                serviceName,
                UUID.fromString(serviceUuid),
            )
        return AndroidServerSocketHandle(serverSocket)
    }

    /**
     * Opens and connects an insecure RFCOMM client socket to [address] for [serviceUuid], closing
     * the socket immediately if [BluetoothSocket.connect] or stream initialization fails.
     */
    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    open fun connectInsecureClientSocket(
        address: String,
        serviceUuid: String,
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
    ): RfcommSocketChannel {
        val parsedUuid = UUID.fromString(serviceUuid)
        val device = requireAdapter().getRemoteDevice(address)
        val isBonded = device.bondState == BluetoothDevice.BOND_BONDED
        val factories = buildClientSocketFactories(device, parsedUuid, isBonded, logger)
        var lastException: IOException? = null
        for (factory in factories) {
            val socket = factory() ?: continue
            try {
                socket.connect()
                return openChannelFromSocket(socket, dispatchers, logger)
            } catch (exception: IOException) {
                closeSocketQuietly(socket)
                lastException = exception
                logger.debug(
                    tag = AndroidTransportConstants.TAG_TRANSPORT,
                    message = "Android RFCOMM socket connection attempt failed; trying next fallback.",
                    context = mapOf("address" to address, "reason" to (exception.message ?: "io_error")),
                )
            } catch (exception: SecurityException) {
                closeSocketQuietly(socket)
                throw exception
            }
        }
        throw lastException ?: IOException("Unable to open Android RFCOMM client socket to $address.")
    }

    @SuppressLint("MissingPermission")
    private fun buildClientSocketFactories(
        device: BluetoothDevice,
        serviceUuid: UUID,
        isBonded: Boolean,
        logger: AppLogger,
    ): List<() -> BluetoothSocket?> =
        buildList {
            add { device.createInsecureRfcommSocketToServiceRecord(serviceUuid) }
            if (isBonded) {
                add { device.createRfcommSocketToServiceRecord(serviceUuid) }
            }
            add {
                createChannelRfcommSocketOrNull(
                    device = device,
                    channel = AndroidTransportConstants.RFCOMM_FALLBACK_CHANNEL,
                    methodName = AndroidTransportConstants.METHOD_CREATE_INSECURE_RFCOMM_SOCKET,
                    logger = logger,
                )
            }
            if (isBonded) {
                add {
                    createChannelRfcommSocketOrNull(
                        device = device,
                        channel = AndroidTransportConstants.RFCOMM_FALLBACK_CHANNEL,
                        methodName = AndroidTransportConstants.METHOD_CREATE_RFCOMM_SOCKET,
                        logger = logger,
                    )
                }
            }
        }

    private fun createChannelRfcommSocketOrNull(
        device: BluetoothDevice,
        channel: Int,
        methodName: String,
        logger: AppLogger,
    ): BluetoothSocket? =
        try {
            val method = device.javaClass.getMethod(methodName, Int::class.javaPrimitiveType)
            method.invoke(device, channel) as? BluetoothSocket
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: IllegalAccessException) {
            null
        } catch (exception: InvocationTargetException) {
            val target = exception.targetException
            if (target is SecurityException) {
                throw SecurityException(target.message, exception)
            }
            val reason = target?.message ?: exception.message ?: "invocation_error"
            logger.debug(
                tag = AndroidTransportConstants.TAG_TRANSPORT,
                message = "Channel RFCOMM socket reflection invocation failed.",
                context = mapOf("method" to methodName, "reason" to reason),
            )
            null
        }

    private fun resolveAdapterOrNull(): BluetoothAdapter? {
        val ctx = applicationContext ?: return null
        val manager = ctx.getSystemService(BluetoothManager::class.java)
        return manager?.adapter
    }

    private fun requireAdapter(): BluetoothAdapter =
        checkNotNull(resolveAdapterOrNull()) {
            "Android BluetoothAdapter is unavailable on this device."
        }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerLegacyReceiver(
        context: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        context.registerReceiver(receiver, filter)
    }

    private fun unregisterQuietly(
        context: Context,
        receiver: BroadcastReceiver,
    ) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            return
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toDiscoveredDevice(): AndroidDiscoveredDevice =
        AndroidDiscoveredDevice(
            address = address,
            name = name,
            uuids = uuids?.map { parcelUuid -> parcelUuid.uuid.toString() }.orEmpty(),
        )

    private inner class DiscoveryBroadcastReceiver(
        private val onDeviceFound: (AndroidDiscoveredDevice) -> Unit,
        private val onDeviceUuidsFetched: (address: String, uuids: List<String>) -> Unit,
        private val onDiscoveryFinished: () -> Unit,
        private val onSecurityError: (SecurityException) -> Unit,
    ) : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?,
        ) {
            val action = intent?.action ?: return
            try {
                when (action) {
                    BluetoothDevice.ACTION_FOUND ->
                        extractBluetoothDevice(intent)?.let { device ->
                            onDeviceFound(device.toDiscoveredDevice())
                        }
                    BluetoothDevice.ACTION_UUID ->
                        extractBluetoothDevice(intent)?.let { device ->
                            onDeviceUuidsFetched(device.address, extractParcelUuids(intent))
                        }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onDiscoveryFinished()
                }
            } catch (exception: SecurityException) {
                onSecurityError(exception)
            }
        }
    }

    private fun extractBluetoothDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun extractParcelUuids(intent: Intent): List<String> {
        val parcelUuids: List<ParcelUuid> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                    ?.toList()
                    .orEmpty()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    ?.filterIsInstance<ParcelUuid>()
                    .orEmpty()
            }
        return parcelUuids.map { parcelUuid -> parcelUuid.uuid.toString() }
    }
}

private fun openChannelFromSocket(
    socket: BluetoothSocket,
    dispatchers: CoroutineDispatchers,
    logger: AppLogger,
): RfcommSocketChannel =
    try {
        RfcommSocketChannel(
            inputStream = socket.inputStream,
            outputStream = socket.outputStream,
            closeable = socket,
            dispatchers = dispatchers,
            logger = logger,
        )
    } catch (exception: IOException) {
        closeSocketQuietly(socket)
        throw exception
    }

private fun closeSocketQuietly(socket: BluetoothSocket) {
    try {
        socket.close()
    } catch (_: IOException) {
        return
    }
}
