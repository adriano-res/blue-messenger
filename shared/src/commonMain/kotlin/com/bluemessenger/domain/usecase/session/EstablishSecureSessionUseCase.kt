package com.bluemessenger.domain.usecase.session

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.constants.SessionConstants
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.DecryptionFailedError
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.HandshakeTimeoutError
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.crypto.CryptoService
import com.bluemessenger.domain.ports.crypto.model.HandshakeStepResult
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.storage.PeerRepository
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.ports.transport.model.HandshakePacket
import com.bluemessenger.domain.ports.transport.model.Packet
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orchestrates Bluetooth RFCOMM session setup and the three-way Noise XX authenticated handshake
 * for both initiator ([connectAndEstablish]) and responder ([acceptAndEstablish]) roles.
 *
 * Thread safety: Executes transport and crypto handshake steps on [CoroutineDispatchers.io] and
 * relies on per-peer serialization in [CryptoService].
 *
 * Failure behavior: On any transport, timeout, or cryptographic failure, clears ephemeral crypto
 * session state, closes the transport socket, logs structured diagnostics without exposing keys, and
 * transitions the persisted conversation to `DISCONNECTED` (unless [preserveReconnectingOnFailure]
 * is `true` during managed reconnection).
 */
class EstablishSecureSessionUseCase(
    private val bluetoothTransport: BluetoothTransport,
    private val cryptoService: CryptoService,
    private val peerRepository: PeerRepository,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val clock: () -> Instant,
) {
    /**
     * Initiates a Bluetooth connection to [peer] and executes the 3-way Noise XX handshake.
     */
    suspend fun connectAndEstablish(
        peer: Peer,
        preserveReconnectingOnFailure: Boolean = false,
    ): Result<Conversation> =
        withContext(dispatchers.io) {
            val baseConversation = loadOrCreateConversation(peer).getOrElse { return@withContext Result.failure(it) }
            val connectingConv =
                transitionToConnecting(baseConversation).getOrElse { return@withContext Result.failure(it) }
            if (!preserveReconnectingOnFailure) {
                peerRepository.upsertConversation(connectingConv).getOrElse { return@withContext Result.failure(it) }
            }

            bluetoothTransport.connect(peer.address).getOrElse { error ->
                return@withContext cleanupAndFail(
                    peerId = peer.id,
                    conversation = connectingConv,
                    preserveReconnectingOnFailure = preserveReconnectingOnFailure,
                    error = error,
                )
            }

            val handshakingConv =
                connectingConv.transitionSessionState(SessionState.HANDSHAKING, clock())
                    .getOrElse { error ->
                        return@withContext cleanupAndFail(
                            peerId = peer.id,
                            conversation = connectingConv,
                            preserveReconnectingOnFailure = preserveReconnectingOnFailure,
                            error = error,
                        )
                    }
            if (!preserveReconnectingOnFailure) {
                peerRepository.upsertConversation(handshakingConv).getOrElse { error ->
                    return@withContext cleanupAndFail(
                        peerId = peer.id,
                        conversation = handshakingConv,
                        preserveReconnectingOnFailure = preserveReconnectingOnFailure,
                        error = error,
                    )
                }
            }

            runInitiatorHandshake(peer, handshakingConv, preserveReconnectingOnFailure)
        }

    /**
     * Accepts an incoming peer connection for [peer] and executes the responder Noise XX handshake.
     */
    suspend fun acceptAndEstablish(peer: Peer): Result<Conversation> =
        withContext(dispatchers.io) {
            val baseConversation = loadOrCreateConversation(peer).getOrElse { return@withContext Result.failure(it) }
            val connectingConv =
                transitionToConnecting(baseConversation).getOrElse { return@withContext Result.failure(it) }
            peerRepository.upsertConversation(connectingConv).getOrElse { return@withContext Result.failure(it) }

            val handshakingConv =
                connectingConv.transitionSessionState(SessionState.HANDSHAKING, clock())
                    .getOrElse { error ->
                        return@withContext cleanupAndFail(
                            peerId = peer.id,
                            conversation = connectingConv,
                            preserveReconnectingOnFailure = false,
                            error = error,
                        )
                    }
            peerRepository.upsertConversation(handshakingConv).getOrElse { error ->
                return@withContext cleanupAndFail(
                    peerId = peer.id,
                    conversation = handshakingConv,
                    preserveReconnectingOnFailure = false,
                    error = error,
                )
            }

            runResponderHandshake(peer, handshakingConv)
        }

    /**
     * Clears ephemeral cryptographic session state for [peerId], closes the active Bluetooth
     * transport link, and transitions any persisted conversation for [peerId] to
     * [SessionState.DISCONNECTED].
     */
    suspend fun disconnect(peerId: PeerId): Result<Unit> =
        withContext(dispatchers.io) {
            val clearOutcome =
                cryptoService.clearSession(peerId).onFailure { error ->
                    logger.warn(
                        tag = TAG,
                        message = "Failed to clear crypto session during manual disconnect.",
                        throwable = error,
                        context = mapOf("peerId" to peerId.toString()),
                    )
                }
            val transportOutcome =
                bluetoothTransport.disconnect().onFailure { error ->
                    logger.warn(
                        tag = TAG,
                        message = "Failed to disconnect Bluetooth transport during manual disconnect.",
                        throwable = error,
                        context = mapOf("peerId" to peerId.toString()),
                    )
                }
            val storageOutcome = persistDisconnectedConversation(peerId)
            val firstFailure =
                clearOutcome.exceptionOrNull()
                    ?: transportOutcome.exceptionOrNull()
                    ?: storageOutcome.exceptionOrNull()
            if (firstFailure != null) {
                Result.failure(firstFailure)
            } else {
                logger.info(
                    tag = TAG,
                    message = "Disconnected peer session.",
                    context = mapOf("peerId" to peerId.toString()),
                )
                Result.success(Unit)
            }
        }

    private suspend fun persistDisconnectedConversation(peerId: PeerId): Result<Unit> =
        peerRepository.getConversationByPeer(peerId).fold(
            onSuccess = { conversation ->
                if (conversation == null || conversation.state == SessionState.DISCONNECTED) {
                    Result.success(Unit)
                } else {
                    transitionConversationDisconnected(conversation)
                }
            },
            onFailure = { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to load peer conversation during disconnect.",
                    throwable = error,
                    context = mapOf("peerId" to peerId.toString()),
                )
                Result.failure(error)
            },
        )

    private suspend fun transitionConversationDisconnected(conversation: Conversation): Result<Unit> =
        conversation.transitionSessionState(SessionState.DISCONNECTED, clock()).fold(
            onSuccess = { disconnected ->
                peerRepository.upsertConversation(disconnected).onFailure { persistError ->
                    logger.error(
                        tag = TAG,
                        message = "Failed to persist DISCONNECTED conversation state.",
                        throwable = persistError,
                        context = mapOf("conversationId" to conversation.id.toString()),
                    )
                }
            },
            onFailure = { transitionError ->
                logger.warn(
                    tag = TAG,
                    message = "Could not transition conversation to DISCONNECTED.",
                    throwable = transitionError,
                    context =
                        mapOf(
                            "conversationId" to conversation.id.toString(),
                            "currentState" to conversation.state.name,
                        ),
                )
                Result.failure(transitionError)
            },
        )

    private suspend fun runInitiatorHandshake(
        peer: Peer,
        handshakingConv: Conversation,
        preserveReconnectingOnFailure: Boolean,
    ): Result<Conversation> =
        withIncomingPacketChannel(peer.id) { packetChannel ->
            val msg1 =
                cryptoService.initializeHandshake(peer.id, isInitiator = true)
                    .getOrElse { error ->
                        return@withIncomingPacketChannel cleanupAndFail(
                            peer.id,
                            handshakingConv,
                            preserveReconnectingOnFailure,
                            error,
                        )
                    }
            sendHandshakeStep(SessionConstants.HANDSHAKE_STEP_INITIATOR_HELLO, msg1)
                .getOrElse { error ->
                    return@withIncomingPacketChannel cleanupAndFail(
                        peer.id,
                        handshakingConv,
                        preserveReconnectingOnFailure,
                        error,
                    )
                }

            val msg2Packet =
                awaitHandshakePacket(packetChannel, SessionConstants.HANDSHAKE_STEP_RESPONDER_HELLO)
                    .getOrElse { error ->
                        return@withIncomingPacketChannel cleanupAndFail(
                            peer.id,
                            handshakingConv,
                            preserveReconnectingOnFailure,
                            error,
                        )
                    }

            val stepResult =
                cryptoService.processHandshakeStep(peer.id, msg2Packet.payload)
                    .getOrElse { error ->
                        return@withIncomingPacketChannel cleanupAndFail(
                            peer.id,
                            handshakingConv,
                            preserveReconnectingOnFailure,
                            error,
                        )
                    }

            sendInitiatorFinish(peer, handshakingConv, stepResult, preserveReconnectingOnFailure)
        }

    private suspend fun sendInitiatorFinish(
        peer: Peer,
        handshakingConv: Conversation,
        stepResult: HandshakeStepResult,
        preserveReconnectingOnFailure: Boolean,
    ): Result<Conversation> {
        val msg3Payload = stepResult.copyResponsePayload()
        return if (msg3Payload == null) {
            cleanupAndFail(
                peer.id,
                handshakingConv,
                preserveReconnectingOnFailure,
                DomainException(DecryptionFailedError("Initiator handshake step failed to produce message 3.")),
            )
        } else {
            sendHandshakeStep(SessionConstants.HANDSHAKE_STEP_INITIATOR_FINISH, msg3Payload).fold(
                onSuccess = {
                    finalizeHandshake(peer, handshakingConv, stepResult, preserveReconnectingOnFailure, ROLE_INITIATOR)
                },
                onFailure = { error ->
                    cleanupAndFail(peer.id, handshakingConv, preserveReconnectingOnFailure, error)
                },
            )
        }
    }

    private suspend fun runResponderHandshake(
        peer: Peer,
        handshakingConv: Conversation,
    ): Result<Conversation> =
        withIncomingPacketChannel(peer.id) { packetChannel ->
            cryptoService.initializeHandshake(peer.id, isInitiator = false)
                .getOrElse { error ->
                    return@withIncomingPacketChannel cleanupAndFail(peer.id, handshakingConv, false, error)
                }

            val msg1Packet =
                awaitHandshakePacket(packetChannel, SessionConstants.HANDSHAKE_STEP_INITIATOR_HELLO)
                    .getOrElse { error ->
                        return@withIncomingPacketChannel cleanupAndFail(peer.id, handshakingConv, false, error)
                    }

            val msg2Result =
                cryptoService.processHandshakeStep(peer.id, msg1Packet.payload)
                    .getOrElse { error ->
                        return@withIncomingPacketChannel cleanupAndFail(peer.id, handshakingConv, false, error)
                    }

            val msg2Payload =
                msg2Result.copyResponsePayload()
                    ?: return@withIncomingPacketChannel cleanupAndFail(
                        peer.id,
                        handshakingConv,
                        false,
                        DomainException(DecryptionFailedError("Responder handshake step failed to produce message 2.")),
                    )
            sendHandshakeStep(SessionConstants.HANDSHAKE_STEP_RESPONDER_HELLO, msg2Payload)
                .getOrElse { error ->
                    return@withIncomingPacketChannel cleanupAndFail(peer.id, handshakingConv, false, error)
                }

            val msg3Packet =
                awaitHandshakePacket(packetChannel, SessionConstants.HANDSHAKE_STEP_INITIATOR_FINISH)
                    .getOrElse { error ->
                        return@withIncomingPacketChannel cleanupAndFail(peer.id, handshakingConv, false, error)
                    }

            val finalStepResult =
                cryptoService.processHandshakeStep(peer.id, msg3Packet.payload)
                    .getOrElse { error ->
                        return@withIncomingPacketChannel cleanupAndFail(peer.id, handshakingConv, false, error)
                    }

            finalizeHandshake(peer, handshakingConv, finalStepResult, false, ROLE_RESPONDER)
        }

    private suspend fun finalizeHandshake(
        peer: Peer,
        handshakingConv: Conversation,
        stepResult: HandshakeStepResult,
        preserveReconnectingOnFailure: Boolean,
        role: String,
    ): Result<Conversation> {
        val remoteIdentityKey = stepResult.remoteIdentityKey
        return if (!stepResult.isComplete || remoteIdentityKey == null) {
            cleanupAndFail(
                peerId = peer.id,
                conversation = handshakingConv,
                preserveReconnectingOnFailure = preserveReconnectingOnFailure,
                error =
                    DomainException(
                        DecryptionFailedError(
                            "Handshake failed to complete with an authenticated remote identity key.",
                        ),
                    ),
            )
        } else {
            persistActiveConversation(
                peer = peer,
                handshakingConv = handshakingConv,
                remoteIdentityKey = remoteIdentityKey,
                preserveReconnectingOnFailure = preserveReconnectingOnFailure,
                role = role,
            )
        }
    }

    private suspend fun persistActiveConversation(
        peer: Peer,
        handshakingConv: Conversation,
        remoteIdentityKey: PublicKey,
        preserveReconnectingOnFailure: Boolean,
        role: String,
    ): Result<Conversation> {
        val now = clock()
        val updatedPeer = resolvePeerAfterHandshake(handshakingConv.peer, peer, remoteIdentityKey, now)
        return handshakingConv.copy(peer = updatedPeer)
            .transitionSessionState(SessionState.SECURE_ACTIVE, now)
            .fold(
                onSuccess = { activeConv ->
                    peerRepository.upsertConversation(activeConv).fold(
                        onSuccess = {
                            logger.info(
                                tag = TAG,
                                message = "Established encrypted peer session.",
                                context =
                                    mapOf(
                                        "peerId" to peer.id.toString(),
                                        "conversationId" to activeConv.id.toString(),
                                        "role" to role,
                                    ),
                            )
                            Result.success(activeConv)
                        },
                        onFailure = { error ->
                            cleanupAndFail(peer.id, handshakingConv, preserveReconnectingOnFailure, error)
                        },
                    )
                },
                onFailure = { error ->
                    cleanupAndFail(peer.id, handshakingConv, preserveReconnectingOnFailure, error)
                },
            )
    }

    private fun resolvePeerAfterHandshake(
        persistedPeer: Peer,
        incomingPeer: Peer,
        remoteIdentityKey: PublicKey,
        now: Instant,
    ): Peer {
        val previousKey = persistedPeer.identityKey ?: incomingPeer.identityKey
        val keyChanged = previousKey != null && previousKey != remoteIdentityKey
        val updatedVerified =
            when {
                keyChanged -> {
                    logger.warn(
                        tag = TAG,
                        message = "Remote peer identity key changed; resetting Safety Number verification.",
                        context = mapOf("peerId" to persistedPeer.id.toString()),
                    )
                    false
                }
                persistedPeer.identityKey != null -> persistedPeer.isVerified
                incomingPeer.identityKey == remoteIdentityKey -> incomingPeer.isVerified
                else -> false
            }
        return persistedPeer.copy(
            nickname = incomingPeer.nickname,
            address = incomingPeer.address,
            identityKey = remoteIdentityKey,
            isVerified = updatedVerified,
            lastSeenAt = now,
        )
    }

    private suspend fun loadOrCreateConversation(peer: Peer): Result<Conversation> {
        val now = clock()
        return peerRepository.getConversationByPeer(peer.id).fold(
            onSuccess = { existingConversation ->
                if (existingConversation != null) {
                    Result.success(
                        existingConversation.copy(
                            peer =
                                existingConversation.peer.copy(
                                    nickname = peer.nickname,
                                    address = peer.address,
                                    lastSeenAt = now,
                                ),
                        ),
                    )
                } else {
                    loadPeerOrCreateConversation(peer, now)
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun loadPeerOrCreateConversation(
        incomingPeer: Peer,
        now: Instant,
    ): Result<Conversation> =
        peerRepository.getPeerById(incomingPeer.id).map { persistedPeer ->
            val mergedPeer =
                persistedPeer?.copy(
                    nickname = incomingPeer.nickname,
                    address = incomingPeer.address,
                    lastSeenAt = now,
                ) ?: incomingPeer.copy(lastSeenAt = now)
            Conversation(
                id = ConversationId.generate(now),
                peer = mergedPeer,
                state = SessionState.DISCONNECTED,
                updatedAt = now,
            )
        }

    private fun transitionToConnecting(conversation: Conversation): Result<Conversation> {
        val now = clock()
        return when {
            conversation.state.canTransitionTo(SessionState.CONNECTING) ->
                conversation.transitionSessionState(SessionState.CONNECTING, now)
            conversation.state.canTransitionTo(SessionState.DISCONNECTED) ->
                conversation.transitionSessionState(SessionState.DISCONNECTED, now).fold(
                    onSuccess = { disconnected -> disconnected.transitionSessionState(SessionState.CONNECTING, now) },
                    onFailure = { Result.failure(it) },
                )
            else -> conversation.transitionSessionState(SessionState.CONNECTING, now)
        }
    }

    private suspend fun sendHandshakeStep(
        step: Byte,
        payload: ByteArray,
    ): Result<Unit> = bluetoothTransport.send(HandshakePacket(step = step, payload = payload))

    private suspend fun <T> withIncomingPacketChannel(
        peerId: PeerId,
        block: suspend (Channel<Result<Packet>>) -> T,
    ): T =
        coroutineScope {
            val packetChannel = Channel<Result<Packet>>(Channel.UNLIMITED)
            val collectorJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    bluetoothTransport.incomingPackets
                        .map { packet -> Result.success(packet) }
                        .catch { cause ->
                            if (cause is CancellationException) throw cause
                            logger.error(
                                tag = TAG,
                                message = "Incoming Bluetooth packet stream failed during handshake.",
                                throwable = cause,
                                context = mapOf("peerId" to peerId.toString()),
                            )
                            emit(Result.failure(cause))
                        }.collect { packetResult ->
                            packetChannel.trySend(packetResult)
                        }
                    packetChannel.close()
                }
            try {
                block(packetChannel)
            } finally {
                collectorJob.cancel()
                packetChannel.close()
            }
        }

    private suspend fun awaitHandshakePacket(
        packetChannel: Channel<Result<Packet>>,
        expectedStep: Byte,
    ): Result<HandshakePacket> =
        withTimeoutOrNull(SessionConstants.HANDSHAKE_TIMEOUT_MS) {
            receiveExpectedHandshakePacket(packetChannel, expectedStep)
        } ?: HandshakeTimeoutError(
            "Timed out after ${SessionConstants.HANDSHAKE_TIMEOUT_MS}ms waiting for handshake step $expectedStep.",
        ).asFailure()

    private suspend fun receiveExpectedHandshakePacket(
        packetChannel: Channel<Result<Packet>>,
        expectedStep: Byte,
    ): Result<HandshakePacket> {
        var outcome: Result<HandshakePacket>? = null
        while (outcome == null) {
            val received = packetChannel.receiveCatching()
            if (received.isClosed) {
                outcome =
                    TransportDisconnectedError(
                        "Transport closed while awaiting handshake step $expectedStep.",
                    ).asFailure()
            } else {
                outcome = matchHandshakeCandidate(received.getOrThrow(), expectedStep)
            }
        }
        return outcome
    }

    private fun matchHandshakeCandidate(
        packetResult: Result<Packet>,
        expectedStep: Byte,
    ): Result<HandshakePacket>? =
        packetResult.fold(
            onSuccess = { packet ->
                when {
                    packet is HandshakePacket && packet.step == expectedStep ->
                        Result.success(packet)
                    packet is HandshakePacket ->
                        CorruptedFrameError(
                            "Unexpected handshake step ${packet.step}, expected $expectedStep.",
                        ).asFailure()
                    else -> {
                        logger.debug(
                            tag = TAG,
                            message = "Ignoring non-handshake packet while awaiting handshake step.",
                            context = mapOf("expectedStep" to expectedStep.toString()),
                        )
                        null
                    }
                }
            },
            onFailure = { Result.failure(it) },
        )

    private suspend fun cleanupAndFail(
        peerId: PeerId,
        conversation: Conversation,
        preserveReconnectingOnFailure: Boolean,
        error: Throwable,
    ): Result<Conversation> {
        logger.warn(
            tag = TAG,
            message = "Secure session establishment failed; cleaning up transport and crypto state.",
            throwable = error,
            context =
                mapOf(
                    "peerId" to peerId.toString(),
                    "conversationId" to conversation.id.toString(),
                    "preserveReconnecting" to preserveReconnectingOnFailure.toString(),
                ),
        )
        cryptoService.clearSession(peerId).onFailure { clearError ->
            logger.warn(
                tag = TAG,
                message = "Failed to clear crypto session during handshake cleanup.",
                throwable = clearError,
                context = mapOf("peerId" to peerId.toString()),
            )
        }
        bluetoothTransport.disconnect().onFailure { disconnectError ->
            logger.warn(
                tag = TAG,
                message = "Failed to disconnect Bluetooth transport during handshake cleanup.",
                throwable = disconnectError,
                context = mapOf("peerId" to peerId.toString()),
            )
        }
        if (!preserveReconnectingOnFailure && conversation.state != SessionState.DISCONNECTED) {
            conversation.transitionSessionState(SessionState.DISCONNECTED, clock())
                .onSuccess { disconnected ->
                    peerRepository.upsertConversation(disconnected).onFailure { persistError ->
                        logger.error(
                            tag = TAG,
                            message = "Failed to persist DISCONNECTED state after handshake failure.",
                            throwable = persistError,
                            context = mapOf("conversationId" to conversation.id.toString()),
                        )
                    }
                }
        }
        return Result.failure(error)
    }

    private companion object {
        private const val TAG: String = "EstablishSecureSessionUseCase"
        private const val ROLE_INITIATOR: String = "initiator"
        private const val ROLE_RESPONDER: String = "responder"
    }
}
