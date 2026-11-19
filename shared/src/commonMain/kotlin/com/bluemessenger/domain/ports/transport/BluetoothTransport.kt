package com.bluemessenger.domain.ports.transport

import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.primitives.BluetoothAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic bidirectional Bluetooth RFCOMM/L2CAP transport port.
 *
 * Thread safety: Implementations must serialize outbound frame writes, handle socket I/O on an
 * injected IO dispatcher, and safely broadcast [sessionState] and [incomingFrames] to concurrent
 * collectors without blocking the main thread.
 *
 * Error handling: Failures due to missing OS permissions, disabled adapters, or dropped sockets
 * must fail fast via [Result.failure] wrapping [DomainException] with typed errors such as
 * `MissingBluetoothPermissionError`, `BluetoothAdapterDisabledError`, or
 * `TransportDisconnectedError`, and never fall back silently.
 */
interface BluetoothTransport {
    /**
     * Observable transport and session connection lifecycle state.
     */
    val sessionState: StateFlow<SessionState>

    /**
     * Stream of raw incoming frame payloads received from the active Bluetooth link.
     */
    val incomingFrames: Flow<ByteArray>

    /**
     * Opens a server socket listening on `TransportConstants.CANONICAL_SERVICE_UUID` for incoming
     * client connections.
     */
    suspend fun startServer(): Result<Unit>

    /**
     * Establishes a client RFCOMM/L2CAP connection to the remote peer at [address].
     */
    suspend fun connect(address: BluetoothAddress): Result<Unit>

    /**
     * Writes an encoded binary frame [payload] over the currently active transport connection.
     */
    suspend fun send(payload: ByteArray): Result<Unit>

    /**
     * Gracefully closes active server or client sockets and transitions state to `DISCONNECTED`.
     */
    suspend fun disconnect(): Result<Unit>
}
