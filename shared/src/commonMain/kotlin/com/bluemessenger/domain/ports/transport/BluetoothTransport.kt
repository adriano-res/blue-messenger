package com.bluemessenger.domain.ports.transport

import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.ports.transport.model.IncomingPeerConnection
import com.bluemessenger.domain.ports.transport.model.Packet
import com.bluemessenger.domain.primitives.BluetoothAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Platform-agnostic bidirectional Bluetooth RFCOMM/L2CAP transport port exchanging typed [Packet]
 * messages.
 *
 * Binary framing, CRC32 validation, and packet serialization (`com.bluemessenger.transport.protocol`)
 * live behind the transport adapter, which logs and drops malformed frames without terminating
 * healthy streams.
 *
 * Thread safety: Implementations must serialize outbound packet writes, handle socket I/O on an
 * injected IO dispatcher, and safely broadcast [sessionState] and [incomingPackets] to concurrent
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
     * Stream of decoded incoming [Packet] values received from the active Bluetooth link.
     */
    val incomingPackets: Flow<Packet>

    /**
     * Stream of accepted inbound peer connections ready for responder handshake negotiation.
     */
    val incomingConnections: Flow<IncomingPeerConnection>
        get() = emptyFlow()

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
     * Encodes and writes [packet] over the currently active transport connection.
     */
    suspend fun send(packet: Packet): Result<Unit>

    /**
     * Gracefully closes active server or client sockets and transitions state to `DISCONNECTED`.
     */
    suspend fun disconnect(): Result<Unit>
}
