package com.bluemessenger.transport

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.ports.transport.constants.TransportConstants
import com.bluemessenger.domain.ports.transport.model.Packet
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.transport.constants.BluezDbusConstants
import com.bluemessenger.transport.constants.LinuxTransportConstants
import com.bluemessenger.transport.dbus.BluezDbusClient
import com.bluemessenger.transport.dbus.BluezDbusErrorMapper
import com.bluemessenger.transport.dbus.BluezProfile1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.types.Variant
import java.io.Closeable
import java.io.IOException

/**
 * Linux BlueZ 5 [BluetoothTransport] adapter implementing server and client RFCOMM channels via
 * `org.bluez.ProfileManager1` and `org.bluez.Profile1` D-Bus `SCM_RIGHTS` file-descriptor passing.
 *
 * Thread safety: Connection lifecycle mutations (`startServer`, `connect`, `disconnect`) are
 * serialized via a [Mutex] on [CoroutineDispatchers.io]. Outbound packet writes delegate to
 * [RfcommSocketChannel], and inbound packets are broadcast through a thread-safe [MutableSharedFlow].
 *
 * Failure behavior: Validates adapter readiness via [LinuxBluetoothPermissionChecker], maps BlueZ
 * D-Bus and I/O failures to typed `DomainException` values (`MissingBluetoothPermissionError`,
 * `BluetoothAdapterDisabledError`, `TransportDisconnectedError`), logs structured warnings/errors
 * without exposing secrets, and always rethrows [CancellationException].
 */
class LinuxBluetoothTransportImpl internal constructor(
    private val bluezClient: BluezDbusClient,
    private val permissionChecker: LinuxBluetoothPermissionChecker,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val connectionTimeoutMs: Long = LinuxTransportConstants.CONNECTION_ACCEPT_TIMEOUT_MS,
) : BluetoothTransport, Closeable {
    constructor(
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
        connectionTimeoutMs: Long = LinuxTransportConstants.CONNECTION_ACCEPT_TIMEOUT_MS,
    ) : this(
        bluezClient = BluezDbusClient(),
        dispatchers = dispatchers,
        logger = logger,
        connectionTimeoutMs = connectionTimeoutMs,
    )

    private constructor(
        bluezClient: BluezDbusClient,
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
        connectionTimeoutMs: Long,
    ) : this(
        bluezClient = bluezClient,
        permissionChecker = LinuxBluetoothPermissionChecker(bluezClient, dispatchers, logger),
        dispatchers = dispatchers,
        logger = logger,
        connectionTimeoutMs = connectionTimeoutMs,
    )

    private val lifecycleMutex: Mutex = Mutex()
    private val channelLock: Any = Any()
    private val transportScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _sessionState: MutableStateFlow<SessionState> = MutableStateFlow(SessionState.DISCONNECTED)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _incomingPackets: MutableSharedFlow<Packet> =
        MutableSharedFlow(extraBufferCapacity = LinuxTransportConstants.INCOMING_PACKET_BUFFER_CAPACITY)
    override val incomingPackets: Flow<Packet> = _incomingPackets.asSharedFlow()

    @Volatile
    private var activeChannel: RfcommSocketChannel? = null

    private var activeReaderJob: Job? = null
    private var pendingClientChannel: CompletableDeferred<RfcommSocketChannel>? = null
    private var isProfileRegistered: Boolean = false
    private var isProfileExported: Boolean = false

    /**
     * Registers the Blue Messenger `org.bluez.Profile1` server profile on `ProfileManager1` and
     * transitions [sessionState] to [SessionState.ADVERTISING] to accept incoming RFCOMM links.
     */
    override suspend fun startServer(): Result<Unit> =
        lifecycleMutex.withLock {
            withContext(dispatchers.io) {
                val adapterPath =
                    permissionChecker.ensureReady().getOrElse { error ->
                        return@withContext Result.failure(error)
                    }
                val currentState = _sessionState.value
                val advertisingState =
                    currentState.transitionTo(SessionState.ADVERTISING).getOrElse { error ->
                        logger.warn(
                            tag = LinuxTransportConstants.TAG_TRANSPORT,
                            message = "Cannot start BlueZ server from current session state.",
                            throwable = error,
                            context = mapOf("state" to currentState.name),
                        )
                        return@withContext Result.failure(error)
                    }
                closeActiveChannelAndReader()
                cleanupProfileRegistration()
                registerTransportProfile(
                    role = BluezDbusConstants.PROFILE_ROLE_SERVER,
                    targetState = advertisingState,
                    adapterPath = adapterPath,
                )
            }
        }

    /**
     * Initiates an outgoing RFCOMM connection to [address] via `org.bluez.Device1.ConnectProfile`,
     * awaiting the native file descriptor delivered to `Profile1.NewConnection`.
     */
    override suspend fun connect(address: BluetoothAddress): Result<Unit> =
        lifecycleMutex.withLock {
            withContext(dispatchers.io) {
                val adapterPath =
                    permissionChecker.ensureReady().getOrElse { error ->
                        return@withContext Result.failure(error)
                    }
                val currentState = _sessionState.value
                val connectingState =
                    currentState.transitionTo(SessionState.CONNECTING).getOrElse { error ->
                        logger.warn(
                            tag = LinuxTransportConstants.TAG_TRANSPORT,
                            message = "Cannot initiate BlueZ RFCOMM connect from current state.",
                            throwable = error,
                            context = mapOf("state" to currentState.name),
                        )
                        return@withContext Result.failure(error)
                    }

                closeActiveChannelAndReader()
                cleanupProfileRegistration()

                _sessionState.value = connectingState
                val deferredChannel = CompletableDeferred<RfcommSocketChannel>()
                synchronized(channelLock) {
                    pendingClientChannel = deferredChannel
                }

                connectClientProfile(
                    adapterPath = adapterPath,
                    address = address,
                    deferredChannel = deferredChannel,
                )
            }
        }

    /**
     * Encodes and transmits [packet] over the active [RfcommSocketChannel].
     */
    override suspend fun send(packet: Packet): Result<Unit> {
        val channel = activeChannel
        if (channel == null || channel.isClosed) {
            logger.warn(
                tag = LinuxTransportConstants.TAG_TRANSPORT,
                message = "Rejected outbound packet send because no RFCOMM channel is connected.",
            )
            return TransportDisconnectedError().asFailure()
        }
        val sendResult = channel.send(packet)
        if (sendResult.isFailure && channel.isClosed) {
            lifecycleMutex.withLock {
                if (activeChannel === channel) {
                    cleanupAfterRemoteDrop(channel)
                }
            }
        }
        return sendResult
    }

    /**
     * Closes the active RFCOMM channel, unregisters any BlueZ profile registration, and resets
     * [sessionState] to [SessionState.DISCONNECTED].
     */
    override suspend fun disconnect(): Result<Unit> =
        lifecycleMutex.withLock {
            withContext(dispatchers.io) {
                synchronized(channelLock) {
                    pendingClientChannel?.cancel()
                    pendingClientChannel = null
                }
                closeActiveChannelAndReader()
                cleanupProfileRegistration()
                _sessionState.value = SessionState.DISCONNECTED
                logger.info(
                    tag = LinuxTransportConstants.TAG_TRANSPORT,
                    message = "Disconnected Linux BlueZ transport.",
                )
                Result.success(Unit)
            }
        }

    /**
     * Releases all socket resources, cancels internal reader coroutines, and unexports D-Bus
     * objects held by this transport instance.
     */
    override fun close() {
        synchronized(channelLock) {
            pendingClientChannel?.cancel()
            pendingClientChannel = null
        }
        closeActiveChannelAndReader()
        cleanupProfileRegistration()
        _sessionState.value = SessionState.DISCONNECTED
        transportScope.cancel()
    }

    private fun registerTransportProfile(
        role: String,
        targetState: SessionState,
        adapterPath: String,
    ): Result<Unit> {
        return try {
            ensureProfileExported()
            val options = buildProfileOptions(role)
            bluezClient.profileManager().registerProfile(
                DBusPath(BluezDbusConstants.LOCAL_TRANSPORT_PROFILE_PATH),
                TransportConstants.CANONICAL_SERVICE_UUID,
                options,
            )
            isProfileRegistered = true
            _sessionState.value = targetState
            logger.info(
                tag = LinuxTransportConstants.TAG_TRANSPORT,
                message = "Registered BlueZ RFCOMM transport profile.",
                context = mapOf("role" to role, "adapterPath" to adapterPath),
            )
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: DBusException) {
            cleanupProfileRegistration()
            failTransport("registering BlueZ profile", exception)
        } catch (exception: DBusExecutionException) {
            cleanupProfileRegistration()
            failTransport("registering BlueZ profile", exception)
        } catch (exception: IOException) {
            cleanupProfileRegistration()
            failTransport("registering BlueZ profile", exception)
        } catch (exception: SecurityException) {
            cleanupProfileRegistration()
            failTransport("registering BlueZ profile", exception)
        }
    }

    private suspend fun connectClientProfile(
        adapterPath: String,
        address: BluetoothAddress,
        deferredChannel: CompletableDeferred<RfcommSocketChannel>,
    ): Result<Unit> {
        val registerResult =
            registerTransportProfile(
                role = BluezDbusConstants.PROFILE_ROLE_CLIENT,
                targetState = SessionState.CONNECTING,
                adapterPath = adapterPath,
            )
        if (registerResult.isFailure) {
            clearPendingClientChannel(deferredChannel)
            _sessionState.value = SessionState.DISCONNECTED
            return registerResult
        }

        return try {
            val devicePath = resolveDevicePath(adapterPath, address)
            bluezClient.device(devicePath).connectProfile(TransportConstants.CANONICAL_SERVICE_UUID)
            val channel =
                withTimeoutOrNull(connectionTimeoutMs) {
                    deferredChannel.await()
                }
            finalizeClientConnection(address, deferredChannel, channel)
        } catch (cancellation: CancellationException) {
            clearPendingClientChannel(deferredChannel)
            closeActiveChannelAndReader()
            cleanupProfileRegistration()
            _sessionState.value = SessionState.DISCONNECTED
            throw cancellation
        } catch (exception: DBusException) {
            handleConnectFailure(deferredChannel, address, exception)
        } catch (exception: DBusExecutionException) {
            handleConnectFailure(deferredChannel, address, exception)
        } catch (exception: IOException) {
            handleConnectFailure(deferredChannel, address, exception)
        } catch (exception: SecurityException) {
            handleConnectFailure(deferredChannel, address, exception)
        } catch (exception: DomainException) {
            handleConnectFailure(deferredChannel, address, exception)
        }
    }

    private fun finalizeClientConnection(
        address: BluetoothAddress,
        deferredChannel: CompletableDeferred<RfcommSocketChannel>,
        channel: RfcommSocketChannel?,
    ): Result<Unit> =
        if (channel == null) {
            clearPendingClientChannel(deferredChannel)
            cleanupProfileRegistration()
            _sessionState.value = SessionState.DISCONNECTED
            val timeoutError =
                TransportDisconnectedError(
                    "Timed out after ${connectionTimeoutMs}ms awaiting RFCOMM NewConnection for ${address.value}.",
                )
            logger.warn(
                tag = LinuxTransportConstants.TAG_TRANSPORT,
                message = timeoutError.message,
                context = mapOf("address" to address.value),
            )
            timeoutError.asFailure()
        } else {
            activateConnectedChannel(channel)
            logger.info(
                tag = LinuxTransportConstants.TAG_TRANSPORT,
                message = "Connected Linux BlueZ RFCOMM client channel.",
                context = mapOf("address" to address.value),
            )
            Result.success(Unit)
        }

    private fun handleConnectFailure(
        deferredChannel: CompletableDeferred<RfcommSocketChannel>,
        address: BluetoothAddress,
        throwable: Throwable,
    ): Result<Unit> {
        clearPendingClientChannel(deferredChannel)
        closeActiveChannelAndReader()
        cleanupProfileRegistration()
        _sessionState.value = SessionState.DISCONNECTED
        if (throwable is DomainException) {
            logger.warn(
                tag = LinuxTransportConstants.TAG_TRANSPORT,
                message = "BlueZ RFCOMM connection failed.",
                throwable = throwable,
                context = mapOf("address" to address.value),
            )
            return Result.failure(throwable)
        }
        return failTransport("connecting to ${address.value}", throwable)
    }

    private fun handleIncomingProfileConnection(
        devicePath: String,
        dbusFd: FileDescriptor,
    ) {
        val channel = openIncomingChannelOrNull(devicePath, dbusFd) ?: return
        val clientWaiter =
            synchronized(channelLock) {
                pendingClientChannel.also { pendingClientChannel = null }
            }
        if (clientWaiter != null) {
            if (!clientWaiter.complete(channel)) {
                channel.close()
            }
        } else {
            acceptServerChannel(devicePath, channel)
        }
    }

    private fun acceptServerChannel(
        devicePath: String,
        channel: RfcommSocketChannel,
    ) {
        val currentState = _sessionState.value
        if (currentState != SessionState.ADVERTISING && currentState != SessionState.CONNECTING) {
            logger.warn(
                tag = LinuxTransportConstants.TAG_TRANSPORT,
                message = "Rejected incoming BlueZ RFCOMM connection in unexpected state.",
                context = mapOf("state" to currentState.name, "devicePath" to devicePath),
            )
            channel.close()
        } else {
            if (currentState == SessionState.ADVERTISING) {
                _sessionState.value = SessionState.CONNECTING
            }
            activateConnectedChannel(channel)
            logger.info(
                tag = LinuxTransportConstants.TAG_TRANSPORT,
                message = "Accepted incoming BlueZ RFCOMM connection.",
                context = mapOf("devicePath" to devicePath),
            )
        }
    }

    private fun openIncomingChannelOrNull(
        devicePath: String,
        dbusFd: FileDescriptor,
    ): RfcommSocketChannel? =
        try {
            bluezClient.openRfcommChannel(dbusFd, dispatchers, logger)
        } catch (exception: DBusException) {
            failIncomingProfileConnection(devicePath, exception)
            null
        } catch (exception: IOException) {
            failIncomingProfileConnection(devicePath, exception)
            null
        } catch (exception: IllegalStateException) {
            failIncomingProfileConnection(devicePath, exception)
            null
        }

    private fun failIncomingProfileConnection(
        devicePath: String,
        throwable: Throwable,
    ) {
        logger.error(
            tag = LinuxTransportConstants.TAG_TRANSPORT,
            message = "Failed to open RFCOMM socket channel from BlueZ NewConnection.",
            throwable = throwable,
            context = mapOf("devicePath" to devicePath),
        )
        val waiter =
            synchronized(channelLock) {
                pendingClientChannel.also { pendingClientChannel = null }
            }
        waiter?.completeExceptionally(
            DomainException(
                TransportDisconnectedError(
                    "Failed to open RFCOMM file descriptor for $devicePath: ${throwable.message ?: "unknown"}",
                ),
                throwable,
            ),
        )
    }

    private fun activateConnectedChannel(channel: RfcommSocketChannel) {
        closeActiveChannelAndReader()
        synchronized(channelLock) {
            activeChannel = channel
            _sessionState.value = SessionState.HANDSHAKING
            val readerJob =
                transportScope.launch {
                    channel.incomingPackets()
                        .catch { cause ->
                            if (cause is CancellationException) throw cause
                            logger.error(
                                tag = LinuxTransportConstants.TAG_TRANSPORT,
                                message = "RFCOMM packet reader stream failed.",
                                throwable = cause,
                            )
                        }.collect { packet ->
                            _incomingPackets.emit(packet)
                        }
                }
            readerJob.invokeOnCompletion {
                cleanupAfterRemoteDrop(channel)
            }
            activeReaderJob = readerJob
        }
    }

    private fun cleanupAfterRemoteDrop(channel: RfcommSocketChannel) {
        val shouldDisconnectState =
            synchronized(channelLock) {
                if (activeChannel === channel) {
                    activeChannel = null
                    activeReaderJob = null
                    true
                } else {
                    false
                }
            }
        channel.close()
        if (shouldDisconnectState && _sessionState.value != SessionState.DISCONNECTED) {
            _sessionState.value = SessionState.DISCONNECTED
        }
    }

    private fun handleProfileDisconnectionRequest(devicePath: String) {
        logger.info(
            tag = LinuxTransportConstants.TAG_TRANSPORT,
            message = "BlueZ requested RFCOMM profile disconnection.",
            context = mapOf("devicePath" to devicePath),
        )
        closeActiveChannelAndReader()
        if (_sessionState.value != SessionState.DISCONNECTED) {
            _sessionState.value = SessionState.DISCONNECTED
        }
    }

    private fun handleProfileRelease() {
        logger.info(
            tag = LinuxTransportConstants.TAG_TRANSPORT,
            message = "BlueZ released transport profile registration.",
        )
        isProfileRegistered = false
        closeActiveChannelAndReader()
        if (_sessionState.value != SessionState.DISCONNECTED) {
            _sessionState.value = SessionState.DISCONNECTED
        }
    }

    private fun closeActiveChannelAndReader() {
        val (channel, readerJob) =
            synchronized(channelLock) {
                val currentChannel = activeChannel
                val currentJob = activeReaderJob
                activeChannel = null
                activeReaderJob = null
                currentChannel to currentJob
            }
        readerJob?.cancel()
        channel?.close()
    }

    private fun ensureProfileExported() {
        if (!isProfileExported) {
            bluezClient.exportProfile(
                BluezDbusConstants.LOCAL_TRANSPORT_PROFILE_PATH,
                TransportProfileCallback(),
            )
            isProfileExported = true
        }
    }

    private fun cleanupProfileRegistration() {
        if (isProfileRegistered) {
            isProfileRegistered = false
            try {
                bluezClient.profileManager().unregisterProfile(
                    DBusPath(BluezDbusConstants.LOCAL_TRANSPORT_PROFILE_PATH),
                )
            } catch (exception: DBusException) {
                logger.warn(
                    tag = LinuxTransportConstants.TAG_TRANSPORT,
                    message = "Failed to unregister BlueZ transport profile.",
                    throwable = exception,
                )
            } catch (exception: DBusExecutionException) {
                logger.warn(
                    tag = LinuxTransportConstants.TAG_TRANSPORT,
                    message = "Failed to unregister BlueZ transport profile.",
                    throwable = exception,
                )
            } catch (exception: IOException) {
                logger.warn(
                    tag = LinuxTransportConstants.TAG_TRANSPORT,
                    message = "Failed to unregister BlueZ transport profile.",
                    throwable = exception,
                )
            }
        }
        if (isProfileExported) {
            isProfileExported = false
            bluezClient.unexportObject(BluezDbusConstants.LOCAL_TRANSPORT_PROFILE_PATH)
        }
    }

    private fun resolveDevicePath(
        adapterPath: String,
        address: BluetoothAddress,
    ): String {
        val managedObjects = bluezClient.objectManager().GetManagedObjects()
        val matchedPath =
            managedObjects.entries.firstOrNull { (_, interfaces) ->
                val deviceProps = interfaces[BluezDbusConstants.DEVICE1_INTERFACE] ?: return@firstOrNull false
                val propAddress = deviceProps[BluezDbusConstants.PROPERTY_ADDRESS]?.value as? String
                propAddress != null && propAddress.equals(address.value, ignoreCase = true)
            }?.key?.path
        if (matchedPath != null) {
            return matchedPath
        }
        val formattedMac =
            address.value.replace(
                LinuxTransportConstants.MAC_COLON_CHAR,
                LinuxTransportConstants.MAC_UNDERSCORE_CHAR,
            )
        return "$adapterPath/${LinuxTransportConstants.BLUEZ_DEVICE_PATH_PREFIX}$formattedMac"
    }

    private fun buildProfileOptions(role: String): Map<String, Variant<*>> =
        mapOf(
            BluezDbusConstants.PROFILE_OPTION_NAME to Variant(BluezDbusConstants.PROFILE_NAME_BLUE_MESSENGER),
            BluezDbusConstants.PROFILE_OPTION_ROLE to Variant(role),
            BluezDbusConstants.PROFILE_OPTION_SERVICE to Variant(TransportConstants.CANONICAL_SERVICE_UUID),
            BluezDbusConstants.PROFILE_OPTION_REQUIRE_AUTHENTICATION to Variant(false),
            BluezDbusConstants.PROFILE_OPTION_REQUIRE_AUTHORIZATION to Variant(false),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun clearPendingClientChannel(deferredChannel: CompletableDeferred<RfcommSocketChannel>) {
        synchronized(channelLock) {
            if (pendingClientChannel === deferredChannel) {
                pendingClientChannel = null
            }
        }
        if (deferredChannel.isCompleted &&
            !deferredChannel.isCancelled &&
            deferredChannel.getCompletionExceptionOrNull() == null
        ) {
            deferredChannel.getCompleted().close()
        }
        deferredChannel.cancel()
    }

    private fun failTransport(
        action: String,
        throwable: Throwable,
    ): Result<Unit> {
        val domainError = BluezDbusErrorMapper.mapTransportFailure(throwable, action)
        logger.warn(
            tag = LinuxTransportConstants.TAG_TRANSPORT,
            message = "BlueZ transport failure while $action.",
            throwable = throwable,
        )
        return domainError.asFailure(throwable)
    }

    private inner class TransportProfileCallback : BluezProfile1 {
        override fun isRemote(): Boolean = false

        override fun getObjectPath(): String = BluezDbusConstants.LOCAL_TRANSPORT_PROFILE_PATH

        override fun release() {
            handleProfileRelease()
        }

        override fun newConnection(
            device: DBusPath,
            fd: FileDescriptor,
            fdProperties: Map<String, Variant<*>>,
        ) {
            handleIncomingProfileConnection(device.path, fd)
        }

        override fun requestDisconnection(device: DBusPath) {
            handleProfileDisconnectionRequest(device.path)
        }
    }
}
