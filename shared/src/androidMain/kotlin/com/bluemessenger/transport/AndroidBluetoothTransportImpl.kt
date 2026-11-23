package com.bluemessenger.transport

import android.Manifest
import android.content.Context
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.MissingBluetoothPermissionError
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.ports.transport.constants.TransportConstants
import com.bluemessenger.domain.ports.transport.model.IncomingPeerConnection
import com.bluemessenger.domain.ports.transport.model.Packet
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.transport.constants.AndroidTransportConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException

/**
 * Android [BluetoothTransport] adapter implementing insecure RFCOMM server and client channels via
 * [AndroidBluetoothGateway] (`listenUsingInsecureRfcommWithServiceRecord` /
 * `createInsecureRfcommSocketToServiceRecord`) and shared [RfcommSocketChannel] binary framing.
 *
 * Thread safety: Connection lifecycle transitions (`startServer`, `connect`, `disconnect`) are
 * serialized by a [Mutex] on [CoroutineDispatchers.io]. Outbound writes delegate to
 * [RfcommSocketChannel.send], and incoming packets are emitted through a thread-safe
 * [MutableSharedFlow].
 *
 * Failure behavior: Validates `BLUETOOTH_CONNECT` and adapter readiness via
 * [AndroidBluetoothPermissionChecker], maps security and I/O failures to typed
 * `MissingBluetoothPermissionError`, `BluetoothAdapterDisabledError`, or
 * [TransportDisconnectedError], logs structured warnings/errors, and always rethrows
 * [CancellationException].
 */
class AndroidBluetoothTransportImpl internal constructor(
    private val gateway: AndroidBluetoothGateway,
    private val permissionChecker: AndroidBluetoothPermissionChecker,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val connectionTimeoutMs: Long = AndroidTransportConstants.CONNECTION_ACCEPT_TIMEOUT_MS,
) : BluetoothTransport, Closeable {
    constructor(
        context: Context,
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
        connectionTimeoutMs: Long = AndroidTransportConstants.CONNECTION_ACCEPT_TIMEOUT_MS,
    ) : this(
        gateway = AndroidBluetoothGateway(context),
        dispatchers = dispatchers,
        logger = logger,
        connectionTimeoutMs = connectionTimeoutMs,
    )

    private constructor(
        gateway: AndroidBluetoothGateway,
        dispatchers: CoroutineDispatchers,
        logger: AppLogger,
        connectionTimeoutMs: Long,
    ) : this(
        gateway = gateway,
        permissionChecker = AndroidBluetoothPermissionChecker(gateway, logger),
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
        MutableSharedFlow(extraBufferCapacity = AndroidTransportConstants.INCOMING_PACKET_BUFFER_CAPACITY)
    override val incomingPackets: Flow<Packet> = _incomingPackets.asSharedFlow()

    private val _incomingConnections: MutableSharedFlow<IncomingPeerConnection> =
        MutableSharedFlow(extraBufferCapacity = AndroidTransportConstants.INCOMING_CONNECTION_BUFFER_CAPACITY)
    override val incomingConnections: Flow<IncomingPeerConnection> = _incomingConnections.asSharedFlow()

    @Volatile
    private var activeChannel: RfcommSocketChannel? = null

    private var activeReaderJob: Job? = null
    private var activeAcceptJob: Job? = null
    private var activeServerSocket: AndroidServerSocketHandle? = null

    /**
     * Opens an insecure RFCOMM SDP server socket for [TransportConstants.CANONICAL_SERVICE_UUID],
     * transitions [sessionState] to [SessionState.ADVERTISING], and awaits incoming connections.
     */
    override suspend fun startServer(): Result<Unit> =
        lifecycleMutex.withLock {
            withContext(dispatchers.io) {
                permissionChecker.ensureConnectReady().getOrElse { error ->
                    return@withContext Result.failure(error)
                }
                val currentState = _sessionState.value
                if (currentState == SessionState.ADVERTISING && activeServerSocket != null) {
                    return@withContext Result.success(Unit)
                }
                val advertisingState =
                    currentState.transitionTo(SessionState.ADVERTISING).getOrElse { error ->
                        logger.warn(
                            tag = AndroidTransportConstants.TAG_TRANSPORT,
                            message = "Cannot start Android RFCOMM server from current session state.",
                            throwable = error,
                            context = mapOf("state" to currentState.name),
                        )
                        return@withContext Result.failure(error)
                    }
                closeServerAndAcceptJob()
                closeActiveChannelAndReader()
                launchServerAcceptLoop(advertisingState)
            }
        }

    /**
     * Initiates an insecure RFCOMM client connection to [address] with a bounded timeout,
     * cancelling active discovery only when `BLUETOOTH_SCAN` is granted.
     */
    override suspend fun connect(address: BluetoothAddress): Result<Unit> =
        lifecycleMutex.withLock {
            withContext(dispatchers.io) {
                permissionChecker.ensureConnectReady().getOrElse { error ->
                    return@withContext Result.failure(error)
                }
                val currentState = _sessionState.value
                val connectingState =
                    currentState.transitionTo(SessionState.CONNECTING).getOrElse { error ->
                        logger.warn(
                            tag = AndroidTransportConstants.TAG_TRANSPORT,
                            message = "Cannot initiate Android RFCOMM connect from current state.",
                            throwable = error,
                            context = mapOf("state" to currentState.name),
                        )
                        return@withContext Result.failure(error)
                    }
                closeServerAndAcceptJob()
                closeActiveChannelAndReader()
                _sessionState.value = connectingState
                if (permissionChecker.hasScanPermission()) {
                    cancelDiscoveryQuietly()
                }
                connectClientSocket(address)
            }
        }

    /**
     * Encodes and transmits [packet] over the active [RfcommSocketChannel].
     */
    override suspend fun send(packet: Packet): Result<Unit> {
        val channel = activeChannel
        if (channel == null || channel.isClosed) {
            logger.warn(
                tag = AndroidTransportConstants.TAG_TRANSPORT,
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
     * Closes active server/client RFCOMM sockets and resets [sessionState] to
     * [SessionState.DISCONNECTED].
     */
    override suspend fun disconnect(): Result<Unit> =
        lifecycleMutex.withLock {
            withContext(dispatchers.io) {
                closeServerAndAcceptJob()
                closeActiveChannelAndReader()
                _sessionState.value = SessionState.DISCONNECTED
                logger.info(
                    tag = AndroidTransportConstants.TAG_TRANSPORT,
                    message = "Disconnected Android Bluetooth transport.",
                )
                Result.success(Unit)
            }
        }

    /**
     * Releases all socket handles, cancels accept/reader coroutines, and cancels the internal
     * transport coroutine scope.
     */
    override fun close() {
        closeServerAndAcceptJob()
        closeActiveChannelAndReader()
        _sessionState.value = SessionState.DISCONNECTED
        transportScope.cancel()
    }

    private fun launchServerAcceptLoop(advertisingState: SessionState): Result<Unit> {
        return try {
            val serverHandle =
                gateway.openInsecureServerSocket(
                    serviceName = AndroidTransportConstants.SERVICE_NAME_BLUE_MESSENGER,
                    serviceUuid = TransportConstants.CANONICAL_SERVICE_UUID,
                )
            synchronized(channelLock) {
                activeServerSocket = serverHandle
                _sessionState.value = advertisingState
                activeAcceptJob =
                    transportScope.launch {
                        acceptIncomingClient(serverHandle)
                    }
            }
            logger.info(
                tag = AndroidTransportConstants.TAG_TRANSPORT,
                message = "Started Android insecure RFCOMM server socket.",
            )
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            closeServerAndAcceptJob()
            _sessionState.value = SessionState.DISCONNECTED
            throw cancellation
        } catch (exception: SecurityException) {
            closeServerAndAcceptJob()
            _sessionState.value = SessionState.DISCONNECTED
            failMissingConnectPermission("starting RFCOMM server", exception)
        } catch (exception: IOException) {
            closeServerAndAcceptJob()
            _sessionState.value = SessionState.DISCONNECTED
            failDisconnected("starting RFCOMM server", exception)
        } catch (exception: IllegalStateException) {
            closeServerAndAcceptJob()
            _sessionState.value = SessionState.DISCONNECTED
            failDisconnected("starting RFCOMM server", exception)
        }
    }

    private suspend fun acceptIncomingClient(serverHandle: AndroidServerSocketHandle) {
        try {
            val acceptedConnection =
                runInterruptible {
                    serverHandle.accept(dispatchers, logger)
                }
            releaseAcceptedServerSocket(serverHandle)
            val accepted = transitionToConnectingAndActivate(acceptedConnection.channel)
            if (accepted) {
                emitIncomingPeerConnection(acceptedConnection)
                logger.info(
                    tag = AndroidTransportConstants.TAG_TRANSPORT,
                    message = "Accepted incoming Android RFCOMM client connection.",
                )
            }
        } catch (cancellation: CancellationException) {
            closeHandleQuietly(serverHandle)
            throw cancellation
        } catch (exception: SecurityException) {
            logger.error(
                tag = AndroidTransportConstants.TAG_TRANSPORT,
                message = "Android RFCOMM server accept failed due to missing permission.",
                throwable = exception,
            )
            cleanupAfterAcceptError(serverHandle)
        } catch (exception: IOException) {
            val wasIntentionalClose =
                synchronized(channelLock) { activeServerSocket !== serverHandle } ||
                    !currentCoroutineContext().isActive
            if (!wasIntentionalClose) {
                logger.warn(
                    tag = AndroidTransportConstants.TAG_TRANSPORT,
                    message = "Android RFCOMM server accept failed.",
                    throwable = exception,
                )
                cleanupAfterAcceptError(serverHandle)
            } else {
                closeHandleQuietly(serverHandle)
            }
        } catch (exception: IllegalStateException) {
            logger.warn(
                tag = AndroidTransportConstants.TAG_TRANSPORT,
                message = "Android RFCOMM server accept failed in invalid adapter state.",
                throwable = exception,
            )
            cleanupAfterAcceptError(serverHandle)
        }
    }

    private fun releaseAcceptedServerSocket(serverHandle: AndroidServerSocketHandle) {
        synchronized(channelLock) {
            if (activeServerSocket === serverHandle) {
                activeServerSocket = null
            }
        }
        closeHandleQuietly(serverHandle)
    }

    private fun transitionToConnectingAndActivate(channel: RfcommSocketChannel): Boolean {
        var shouldCloseRejected = false
        synchronized(channelLock) {
            val currentState = _sessionState.value
            if (currentState != SessionState.ADVERTISING && currentState != SessionState.CONNECTING) {
                logger.warn(
                    tag = AndroidTransportConstants.TAG_TRANSPORT,
                    message = "Rejected incoming Android RFCOMM connection in unexpected state.",
                    context = mapOf("state" to currentState.name),
                )
                shouldCloseRejected = true
            } else {
                if (currentState == SessionState.ADVERTISING) {
                    _sessionState.value = SessionState.CONNECTING
                }
            }
        }
        if (shouldCloseRejected) {
            channel.close()
            return false
        }
        activateConnectedChannel(channel)
        return true
    }

    private fun cleanupAfterAcceptError(serverHandle: AndroidServerSocketHandle) {
        val shouldResetState =
            synchronized(channelLock) {
                activeAcceptJob = null
                if (activeServerSocket === serverHandle) {
                    activeServerSocket = null
                    true
                } else {
                    false
                }
            }
        closeHandleQuietly(serverHandle)
        if (shouldResetState && _sessionState.value == SessionState.ADVERTISING) {
            _sessionState.value = SessionState.DISCONNECTED
        }
    }

    private suspend fun connectClientSocket(address: BluetoothAddress): Result<Unit> {
        return try {
            val channel =
                withTimeoutOrNull(connectionTimeoutMs) {
                    runInterruptible {
                        gateway.connectInsecureClientSocket(
                            address = address.value,
                            serviceUuid = TransportConstants.CANONICAL_SERVICE_UUID,
                            dispatchers = dispatchers,
                            logger = logger,
                        )
                    }
                }
            finalizeClientConnection(address, channel)
        } catch (cancellation: CancellationException) {
            closeActiveChannelAndReader()
            _sessionState.value = SessionState.DISCONNECTED
            throw cancellation
        } catch (exception: SecurityException) {
            closeActiveChannelAndReader()
            _sessionState.value = SessionState.DISCONNECTED
            failMissingConnectPermission("connecting RFCOMM socket to ${address.value}", exception)
        } catch (exception: IOException) {
            closeActiveChannelAndReader()
            _sessionState.value = SessionState.DISCONNECTED
            failDisconnected("connecting RFCOMM socket to ${address.value}", exception)
        } catch (exception: IllegalStateException) {
            closeActiveChannelAndReader()
            _sessionState.value = SessionState.DISCONNECTED
            failDisconnected("connecting RFCOMM socket to ${address.value}", exception)
        } catch (exception: IllegalArgumentException) {
            closeActiveChannelAndReader()
            _sessionState.value = SessionState.DISCONNECTED
            failDisconnected("connecting RFCOMM socket to ${address.value}", exception)
        }
    }

    private fun finalizeClientConnection(
        address: BluetoothAddress,
        channel: RfcommSocketChannel?,
    ): Result<Unit> =
        if (channel == null) {
            _sessionState.value = SessionState.DISCONNECTED
            val timeoutError =
                TransportDisconnectedError(
                    "Timed out after ${connectionTimeoutMs}ms connecting RFCOMM socket to ${address.value}.",
                )
            logger.warn(
                tag = AndroidTransportConstants.TAG_TRANSPORT,
                message = timeoutError.message,
                context = mapOf("address" to address.value),
            )
            timeoutError.asFailure()
        } else {
            activateConnectedChannel(channel)
            logger.info(
                tag = AndroidTransportConstants.TAG_TRANSPORT,
                message = "Connected Android insecure RFCOMM client channel.",
                context = mapOf("address" to address.value),
            )
            Result.success(Unit)
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
                                tag = AndroidTransportConstants.TAG_TRANSPORT,
                                message = "RFCOMM packet reader stream failed.",
                                throwable = cause,
                            )
                        }.collect { packet ->
                            _incomingPackets.subscriptionCount.first { count -> count > 0 }
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

    private fun closeServerAndAcceptJob() {
        val (serverHandle, acceptJob) =
            synchronized(channelLock) {
                val currentServer = activeServerSocket
                val currentAccept = activeAcceptJob
                activeServerSocket = null
                activeAcceptJob = null
                currentServer to currentAccept
            }
        acceptJob?.cancel()
        if (serverHandle != null) {
            closeHandleQuietly(serverHandle)
        }
    }

    private fun emitIncomingPeerConnection(acceptedConnection: AcceptedAndroidConnection) {
        val rawAddress = acceptedConnection.remoteAddress ?: return
        val remoteAddress = BluetoothAddress.parse(rawAddress).getOrNull() ?: return
        val incomingPeer =
            IncomingPeerConnection(
                address = remoteAddress,
                name = acceptedConnection.remoteName?.takeIf { it.isNotBlank() },
            )
        transportScope.launch {
            _incomingConnections.subscriptionCount.first { count -> count > 0 }
            _incomingConnections.emit(incomingPeer)
        }
    }

    private fun closeHandleQuietly(handle: AndroidServerSocketHandle) {
        try {
            handle.close()
        } catch (exception: IOException) {
            logger.debug(
                tag = AndroidTransportConstants.TAG_TRANSPORT,
                message = "Ignored IOException while closing Android RFCOMM server socket.",
                context = mapOf("reason" to (exception.message ?: "unknown")),
            )
        }
    }

    private fun cancelDiscoveryQuietly() {
        try {
            gateway.cancelDiscovery()
        } catch (exception: SecurityException) {
            logger.warn(
                tag = AndroidTransportConstants.TAG_TRANSPORT,
                message = "Failed to cancel discovery before RFCOMM connect due to missing permission.",
                throwable = exception,
            )
        } catch (exception: IllegalStateException) {
            logger.debug(
                tag = AndroidTransportConstants.TAG_TRANSPORT,
                message = "Skipped discovery cancellation because adapter was unavailable.",
                context = mapOf("reason" to (exception.message ?: "unknown")),
            )
        }
    }

    private fun failMissingConnectPermission(
        action: String,
        exception: SecurityException,
    ): Result<Unit> {
        val error =
            MissingBluetoothPermissionError(
                permission = Manifest.permission.BLUETOOTH_CONNECT,
                message = "Missing Bluetooth permission '${Manifest.permission.BLUETOOTH_CONNECT}' while $action.",
            )
        logger.warn(
            tag = AndroidTransportConstants.TAG_TRANSPORT,
            message = error.message,
            throwable = exception,
            context = mapOf("permission" to Manifest.permission.BLUETOOTH_CONNECT),
        )
        return error.asFailure(exception)
    }

    private fun failDisconnected(
        action: String,
        exception: Throwable,
    ): Result<Unit> {
        val error =
            TransportDisconnectedError(
                "Android Bluetooth RFCOMM failure while $action: ${exception.message ?: "connection failed"}.",
            )
        logger.warn(
            tag = AndroidTransportConstants.TAG_TRANSPORT,
            message = error.message,
            throwable = exception,
        )
        return error.asFailure(exception)
    }
}
