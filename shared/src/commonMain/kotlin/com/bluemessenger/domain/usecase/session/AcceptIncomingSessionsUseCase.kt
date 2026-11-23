package com.bluemessenger.domain.usecase.session

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.storage.PeerRepository
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.ports.transport.model.IncomingPeerConnection
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Ensures the RFCOMM server socket/profile listener stays active when idle and drives responder
 * Noise XX handshakes whenever remote peers connect via [BluetoothTransport.incomingConnections].
 *
 * Thread safety: Server arming via [ensureServerListening] is serialized by a [Mutex] on
 * [CoroutineDispatchers.io]. Incoming connections are processed sequentially inside the caller
 * [CoroutineScope].
 *
 * Failure behavior: Logs structured warnings for responder handshake failures and automatically
 * re-arms the server listener so subsequent incoming peer connections can still succeed. Always
 * rethrows [CancellationException].
 */
class AcceptIncomingSessionsUseCase(
    private val bluetoothTransport: BluetoothTransport,
    private val peerRepository: PeerRepository,
    private val establishSecureSessionUseCase: EstablishSecureSessionUseCase,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val clock: () -> Instant,
) {
    private val serverStartMutex: Mutex = Mutex()

    /**
     * Starts the local RFCOMM server listener when [BluetoothTransport.sessionState] is
     * [SessionState.DISCONNECTED], leaving active advertising, handshaking, or live sessions intact.
     */
    suspend fun ensureServerListening(): Result<Unit> =
        serverStartMutex.withLock {
            withContext(dispatchers.io) {
                if (bluetoothTransport.sessionState.value == SessionState.DISCONNECTED) {
                    bluetoothTransport.startServer()
                } else {
                    Result.success(Unit)
                }
            }
        }

    /**
     * Collects [BluetoothTransport.incomingConnections] and [BluetoothTransport.sessionState] in
     * [scope], executing responder handshakes and re-arming the server whenever a session drops
     * back to [SessionState.DISCONNECTED].
     */
    fun observeIncomingSessions(
        scope: CoroutineScope,
        onSessionEstablished: suspend (Conversation) -> Unit = {},
    ): Job =
        scope.launch(dispatchers.io) {
            launch {
                collectIncomingConnections(onSessionEstablished)
            }
            launch {
                observeTransportStateToRearm()
            }
        }

    private suspend fun collectIncomingConnections(onSessionEstablished: suspend (Conversation) -> Unit) {
        bluetoothTransport.incomingConnections
            .catch { cause ->
                if (cause is CancellationException) throw cause
                logger.error(
                    tag = TAG,
                    message = "Incoming Bluetooth connection stream failed.",
                    throwable = cause,
                )
            }.collect { incoming ->
                handleIncomingConnection(incoming, onSessionEstablished)
            }
    }

    private suspend fun handleIncomingConnection(
        incoming: IncomingPeerConnection,
        onSessionEstablished: suspend (Conversation) -> Unit,
    ) {
        val peer = resolveIncomingPeer(incoming)
        establishSecureSessionUseCase.acceptAndEstablish(peer).fold(
            onSuccess = { activeConversation ->
                onSessionEstablished(activeConversation)
            },
            onFailure = { error ->
                logger.warn(
                    tag = TAG,
                    message = "Failed to accept incoming peer session; re-arming server listener.",
                    throwable = error,
                    context = mapOf("address" to incoming.address.value),
                )
                rearmServerQuietly()
            },
        )
    }

    private suspend fun observeTransportStateToRearm() {
        var previousState = bluetoothTransport.sessionState.value
        bluetoothTransport.sessionState.collect { currentState ->
            val shouldRearm =
                previousState in REARM_TRIGGER_STATES &&
                    currentState == SessionState.DISCONNECTED
            previousState = currentState
            if (shouldRearm) {
                rearmServerQuietly()
            }
        }
    }

    private suspend fun resolveIncomingPeer(incoming: IncomingPeerConnection): Peer {
        val now = clock()
        val existing =
            peerRepository.getPeerByAddress(incoming.address).getOrElse { error ->
                logger.warn(
                    tag = TAG,
                    message = "Failed to look up incoming peer by address; creating ephemeral peer.",
                    throwable = error,
                    context = mapOf("address" to incoming.address.value),
                )
                null
            }
        val preferredNickname = incoming.name?.takeIf { it.isNotBlank() }
        return if (existing != null) {
            existing.copy(
                nickname = preferredNickname ?: existing.nickname,
                lastSeenAt = now,
            )
        } else {
            Peer(
                id = PeerId.generate(now),
                nickname = preferredNickname ?: incoming.address.value,
                address = incoming.address,
                identityKey = null,
                isVerified = false,
                lastSeenAt = now,
            )
        }
    }

    private suspend fun rearmServerQuietly() {
        ensureServerListening().onFailure { error ->
            logger.warn(
                tag = TAG,
                message = "Unable to restart RFCOMM server listener after session ended.",
                throwable = error,
            )
        }
    }

    private companion object {
        private const val TAG: String = "AcceptIncomingSessionsUseCase"
        private val REARM_TRIGGER_STATES: Set<SessionState> =
            setOf(
                SessionState.ADVERTISING,
                SessionState.CONNECTING,
                SessionState.HANDSHAKING,
                SessionState.SECURE_ACTIVE,
            )
    }
}
