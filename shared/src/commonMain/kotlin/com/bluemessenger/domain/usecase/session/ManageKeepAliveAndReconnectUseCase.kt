package com.bluemessenger.domain.usecase.session

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.constants.SessionConstants
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.crypto.CryptoService
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.storage.PeerRepository
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.ports.transport.model.KeepAlivePacket
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.usecase.message.FlushPendingMessagesUseCase
import com.bluemessenger.domain.usecase.message.util.hasOpenTransportLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Monitors active Bluetooth links via periodic Keep-Alive `PING`/`PONG` probes and drives
 * exponential-backoff reconnection with automatic pending message flushing upon recovery.
 *
 * Thread safety: Runs within a caller-supplied [CoroutineScope] on [CoroutineDispatchers.io] and
 * receives `PONG` timestamps asynchronously via [onPongReceived].
 *
 * Failure behavior: Clears ephemeral crypto state, closes the socket, transitions the conversation
 * to `RECONNECTING`, and retries up to [SessionConstants.MAX_RECONNECT_ATTEMPTS] times using
 * exponential backoff (`1s, 2s, 4s, 8s, 16s`). Exhausting all attempts transitions the conversation
 * to `DISCONNECTED` while preserving queued `PENDING` messages.
 */
@Suppress("LongParameterList")
class ManageKeepAliveAndReconnectUseCase(
    private val bluetoothTransport: BluetoothTransport,
    private val cryptoService: CryptoService,
    private val peerRepository: PeerRepository,
    private val establishSecureSessionUseCase: EstablishSecureSessionUseCase,
    private val flushPendingMessagesUseCase: FlushPendingMessagesUseCase,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val clock: () -> Instant,
) {
    private val pongSignals: MutableSharedFlow<Long> =
        MutableSharedFlow(extraBufferCapacity = SessionConstants.PONG_SIGNAL_BUFFER_CAPACITY)

    /**
     * Delivers a received `PONG` [timestamp] from the incoming frame observer to the active
     * Keep-Alive probe loop.
     */
    suspend fun onPongReceived(timestamp: Long) {
        pongSignals.emit(timestamp)
    }

    /**
     * Starts the Keep-Alive and exponential-backoff reconnection loop for [peer] and
     * [conversationId] inside [scope].
     */
    fun start(
        scope: CoroutineScope,
        peer: Peer,
        conversationId: ConversationId,
    ): Job =
        scope.launch(dispatchers.io) {
            while (isActive) {
                delay(SessionConstants.KEEP_ALIVE_INTERVAL_MS)
                val linkHealthy = sendPingAndAwaitPong(peer, conversationId)
                if (!linkHealthy) {
                    val recovered = handleDisconnectAndReconnect(peer, conversationId)
                    if (!recovered) {
                        return@launch
                    }
                }
            }
        }

    private suspend fun sendPingAndAwaitPong(
        peer: Peer,
        conversationId: ConversationId,
    ): Boolean =
        peerRepository.getConversationByPeer(peer.id).fold(
            onSuccess = { conversation ->
                if (conversation?.state != SessionState.SECURE_ACTIVE || !bluetoothTransport.hasOpenTransportLink()) {
                    logger.warn(
                        tag = TAG,
                        message = "Keep-Alive detected inactive peer session or closed transport link.",
                        context =
                            mapOf(
                                "peerId" to peer.id.toString(),
                                "conversationId" to conversationId.toString(),
                                "peerState" to (conversation?.state?.name ?: STATE_NONE),
                                "transportState" to bluetoothTransport.sessionState.value.name,
                            ),
                    )
                    false
                } else {
                    val pingTimestamp = clock().epochMillis
                    awaitPongForPing(peer, conversationId, pingTimestamp)
                }
            },
            onFailure = { error ->
                logger.error(
                    tag = TAG,
                    message = "Keep-Alive failed to inspect peer conversation state.",
                    throwable = error,
                    context =
                        mapOf(
                            "peerId" to peer.id.toString(),
                            "conversationId" to conversationId.toString(),
                        ),
                )
                false
            },
        )

    private suspend fun awaitPongForPing(
        peer: Peer,
        conversationId: ConversationId,
        pingTimestamp: Long,
    ): Boolean =
        coroutineScope {
            val pongWaiter =
                async(start = CoroutineStart.UNDISPATCHED) {
                    pongSignals.first { receivedTimestamp -> receivedTimestamp == pingTimestamp }
                }
            val sendResult = bluetoothTransport.send(KeepAlivePacket.Ping(pingTimestamp))
            if (sendResult.isFailure) {
                pongWaiter.cancel()
                logger.warn(
                    tag = TAG,
                    message = "Failed to send Keep-Alive PING packet.",
                    throwable = sendResult.exceptionOrNull(),
                    context =
                        mapOf(
                            "peerId" to peer.id.toString(),
                            "conversationId" to conversationId.toString(),
                        ),
                )
                return@coroutineScope false
            }

            val matchedPong =
                withTimeoutOrNull(SessionConstants.KEEP_ALIVE_TIMEOUT_MS) {
                    pongWaiter.await()
                }
            if (matchedPong == null) {
                pongWaiter.cancel()
                logger.warn(
                    tag = TAG,
                    message = "Keep-Alive PONG timed out.",
                    context =
                        mapOf(
                            "peerId" to peer.id.toString(),
                            "conversationId" to conversationId.toString(),
                            "timeoutMs" to SessionConstants.KEEP_ALIVE_TIMEOUT_MS.toString(),
                        ),
                )
                false
            } else {
                true
            }
        }

    private suspend fun handleDisconnectAndReconnect(
        peer: Peer,
        conversationId: ConversationId,
    ): Boolean {
        cleanupTransportAndCrypto(peer)
        transitionConversationToReconnecting(peer, conversationId)

        for (attempt in 1..SessionConstants.MAX_RECONNECT_ATTEMPTS) {
            if (attemptReconnectWithBackoff(peer, conversationId, attempt)) {
                return true
            }
        }

        logger.error(
            tag = TAG,
            message = "Exhausted maximum reconnect attempts; marking session DISCONNECTED.",
            context =
                mapOf(
                    "peerId" to peer.id.toString(),
                    "conversationId" to conversationId.toString(),
                    "maxAttempts" to SessionConstants.MAX_RECONNECT_ATTEMPTS.toString(),
                ),
        )
        transitionConversationToDisconnected(peer, conversationId)
        return false
    }

    private suspend fun attemptReconnectWithBackoff(
        peer: Peer,
        conversationId: ConversationId,
        attempt: Int,
    ): Boolean {
        val backoffMs = computeBackoffMillis(attempt)
        logger.warn(
            tag = TAG,
            message = "Scheduling reconnect attempt with exponential backoff.",
            context =
                mapOf(
                    "peerId" to peer.id.toString(),
                    "conversationId" to conversationId.toString(),
                    "attempt" to attempt.toString(),
                    "maxAttempts" to SessionConstants.MAX_RECONNECT_ATTEMPTS.toString(),
                    "backoffMs" to backoffMs.toString(),
                ),
        )
        delay(backoffMs)

        val reconnectResult =
            establishSecureSessionUseCase.connectAndEstablish(
                peer = peer,
                preserveReconnectingOnFailure = true,
            )
        if (reconnectResult.isFailure) {
            return false
        }

        logger.info(
            tag = TAG,
            message = "Reconnected secure session; flushing pending messages.",
            context =
                mapOf(
                    "peerId" to peer.id.toString(),
                    "conversationId" to conversationId.toString(),
                    "attempt" to attempt.toString(),
                ),
        )
        flushPendingMessagesUseCase(peer.id, conversationId).onFailure { flushError ->
            logger.warn(
                tag = TAG,
                message = "Failed to flush pending messages after reconnect.",
                throwable = flushError,
                context =
                    mapOf(
                        "peerId" to peer.id.toString(),
                        "conversationId" to conversationId.toString(),
                    ),
            )
        }
        return true
    }

    private suspend fun cleanupTransportAndCrypto(peer: Peer) {
        cryptoService.clearSession(peer.id).onFailure { error ->
            logger.warn(
                tag = TAG,
                message = "Failed to clear crypto session before reconnect.",
                throwable = error,
                context = mapOf("peerId" to peer.id.toString()),
            )
        }
        bluetoothTransport.disconnect().onFailure { error ->
            logger.warn(
                tag = TAG,
                message = "Failed to close Bluetooth transport before reconnect.",
                throwable = error,
                context = mapOf("peerId" to peer.id.toString()),
            )
        }
    }

    private suspend fun transitionConversationToReconnecting(
        peer: Peer,
        conversationId: ConversationId,
    ) {
        val current =
            loadConversationOrDefault(peer, conversationId, SessionState.SECURE_ACTIVE)
                ?: return
        if (current.state == SessionState.RECONNECTING) {
            return
        }
        current.transitionSessionState(SessionState.RECONNECTING, clock())
            .onSuccess { reconnecting ->
                peerRepository.upsertConversation(reconnecting).onFailure { error ->
                    logger.error(
                        tag = TAG,
                        message = "Failed to persist RECONNECTING conversation state.",
                        throwable = error,
                        context = mapOf("conversationId" to conversationId.toString()),
                    )
                }
            }.onFailure { error ->
                logger.warn(
                    tag = TAG,
                    message = "Could not transition conversation to RECONNECTING.",
                    throwable = error,
                    context =
                        mapOf(
                            "conversationId" to conversationId.toString(),
                            "currentState" to current.state.name,
                        ),
                )
            }
    }

    private suspend fun transitionConversationToDisconnected(
        peer: Peer,
        conversationId: ConversationId,
    ) {
        val current =
            loadConversationOrDefault(peer, conversationId, SessionState.RECONNECTING)
                ?: return
        if (current.state == SessionState.DISCONNECTED) {
            return
        }
        current.transitionSessionState(SessionState.DISCONNECTED, clock())
            .onSuccess { disconnected ->
                peerRepository.upsertConversation(disconnected).onFailure { error ->
                    logger.error(
                        tag = TAG,
                        message = "Failed to persist DISCONNECTED conversation state.",
                        throwable = error,
                        context = mapOf("conversationId" to conversationId.toString()),
                    )
                }
            }.onFailure { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to transition conversation to DISCONNECTED after exhausting retries.",
                    throwable = error,
                    context =
                        mapOf(
                            "conversationId" to conversationId.toString(),
                            "currentState" to current.state.name,
                        ),
                )
            }
    }

    private suspend fun loadConversationOrDefault(
        peer: Peer,
        conversationId: ConversationId,
        fallbackState: SessionState,
    ): Conversation? =
        peerRepository.getConversationByPeer(peer.id).fold(
            onSuccess = { persisted ->
                persisted ?: Conversation(
                    id = conversationId,
                    peer = peer,
                    state = fallbackState,
                    updatedAt = clock(),
                )
            },
            onFailure = { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to load conversation during Keep-Alive transition.",
                    throwable = error,
                    context = mapOf("conversationId" to conversationId.toString()),
                )
                null
            },
        )

    private fun computeBackoffMillis(attempt: Int): Long =
        SessionConstants.INITIAL_RECONNECT_BACKOFF_MS * (1L shl (attempt - SessionConstants.BACKOFF_BASE_OFFSET))

    private companion object {
        private const val TAG: String = "ManageKeepAliveAndReconnectUseCase"
        private const val STATE_NONE: String = "NONE"
    }
}
