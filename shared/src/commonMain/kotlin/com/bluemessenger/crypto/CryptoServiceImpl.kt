package com.bluemessenger.crypto

import com.bluemessenger.crypto.util.ByteArrayZeroizer
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.ports.crypto.CryptoService
import com.bluemessenger.domain.ports.crypto.KeyStoreProvider
import com.bluemessenger.domain.ports.crypto.model.HandshakeStepResult
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Concrete [CryptoService] implementation coordinating [CryptoHandshake], [CryptoSession],
 * and [CryptoSafetyNumber] backed by a platform [KeyStoreProvider].
 *
 * Thread safety: Serializes per-peer handshake and Double Ratchet operations via per-peer
 * [Mutex] instances guarded by a central map [Mutex], preventing key reuse or chain races.
 *
 * Error handling: Fails fast with `DomainException` (`InvalidStateTransitionError`,
 * `DecryptionFailedError`, `CorruptedFrameError`) when identity keys are absent, sessions are
 * uninitialized, or authentication fails.
 */
class CryptoServiceImpl(
    private val keyStoreProvider: KeyStoreProvider,
) : CryptoService {
    private class PeerCryptoState {
        val mutex: Mutex = Mutex()
        var handshake: CryptoHandshake? = null
        var session: CryptoSession? = null

        fun zeroizeAll() {
            handshake?.zeroize()
            handshake = null
            session?.zeroize()
            session = null
        }

        fun isIdle(): Boolean = handshake == null && session == null
    }

    private val mapMutex: Mutex = Mutex()
    private val peerStates: MutableMap<PeerId, PeerCryptoState> = LinkedHashMap()

    /**
     * Called when a transport connection opens to register per-peer handshake state.
     * Initiator starts immediately and returns message 1; responder returns an empty array
     * and waits for inbound payloads via [processHandshakeStep].
     */
    override suspend fun initializeHandshake(
        peerId: PeerId,
        isInitiator: Boolean,
    ): Result<ByteArray> =
        withPeerLock(peerId) { state ->
            val identityKeyResult = keyStoreProvider.getIdentityPublicKey()
            val localIdentityKey =
                identityKeyResult.getOrNull()
                    ?: return@withPeerLock identityKeyResult.exceptionOrNull()?.let { Result.failure(it) }
                        ?: InvalidStateTransitionError(
                            entity = ENTITY_NAME,
                            fromState = STATE_UNINITIALIZED_IDENTITY,
                            toState = ACTION_HANDSHAKE,
                        ).asFailure()

            state.zeroizeAll()
            val handshake =
                CryptoHandshake(
                    isInitiator = isInitiator,
                    localIdentityPublicKey = localIdentityKey,
                    signWithIdentity = keyStoreProvider::signWithIdentityKey,
                )
            if (isInitiator) {
                handshake.start().onSuccess {
                    state.handshake = handshake
                }.onFailure {
                    handshake.zeroize()
                }
            } else {
                state.handshake = handshake
                Result.success(ByteArray(0))
            }
        }

    /**
     * Advances the active handshake registered by [initializeHandshake] using [incomingPayload],
     * activating a Double Ratchet [CryptoSession] once the handshake completes.
     */
    override suspend fun processHandshakeStep(
        peerId: PeerId,
        incomingPayload: ByteArray,
    ): Result<HandshakeStepResult> =
        withPeerLock(peerId) { state ->
            val handshake =
                state.handshake
                    ?: return@withPeerLock InvalidStateTransitionError(
                        entity = ENTITY_NAME,
                        fromState = STATE_NO_HANDSHAKE,
                        toState = ACTION_HANDSHAKE,
                    ).asFailure()

            handshake.processMessage(incomingPayload).fold(
                onSuccess = { step ->
                    val completion = step.completion
                    if (completion != null) {
                        activateSession(state, completion).map {
                            HandshakeStepResult(
                                responsePayload = step.outboundMessage,
                                isComplete = true,
                                remoteIdentityKey = completion.remoteIdentityKey,
                            )
                        }
                    } else {
                        Result.success(
                            HandshakeStepResult(
                                responsePayload = step.outboundMessage,
                                isComplete = false,
                                remoteIdentityKey = null,
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    handshake.zeroize()
                    state.handshake = null
                    Result.failure(error)
                },
            )
        }

    override suspend fun encrypt(
        peerId: PeerId,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): Result<ByteArray> =
        withPeerLock(peerId) { state ->
            val session =
                state.session
                    ?: return@withPeerLock InvalidStateTransitionError(
                        entity = ENTITY_NAME,
                        fromState = STATE_NO_SESSION,
                        toState = ACTION_ENCRYPT,
                    ).asFailure()
            session.encrypt(plaintext, associatedData)
        }

    override suspend fun decrypt(
        peerId: PeerId,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): Result<ByteArray> =
        withPeerLock(peerId) { state ->
            val session =
                state.session
                    ?: return@withPeerLock InvalidStateTransitionError(
                        entity = ENTITY_NAME,
                        fromState = STATE_NO_SESSION,
                        toState = ACTION_DECRYPT,
                    ).asFailure()
            session.decrypt(ciphertext, associatedData)
        }

    override suspend fun computeSafetyNumber(
        localKey: PublicKey,
        remoteKey: PublicKey,
    ): Result<String> = Result.success(CryptoSafetyNumber.generate(localKey, remoteKey))

    override suspend fun clearSession(peerId: PeerId): Result<Unit> {
        val state = mapMutex.withLock { peerStates[peerId] }
        if (state != null) {
            state.mutex.withLock {
                state.zeroizeAll()
            }
            mapMutex.withLock {
                if (peerStates[peerId] === state && state.isIdle()) {
                    peerStates.remove(peerId)
                }
            }
        }
        return Result.success(Unit)
    }

    private fun activateSession(
        state: PeerCryptoState,
        completion: HandshakeCompletion,
    ): Result<Unit> {
        val sessionResult =
            if (completion.isInitiator) {
                val remoteEphemeral = completion.remoteEphemeralPublicKey
                if (remoteEphemeral == null) {
                    InvalidStateTransitionError(
                        entity = ENTITY_NAME,
                        fromState = STATE_HANDSHAKING,
                        toState = STATE_ACTIVE_SESSION,
                    ).asFailure()
                } else {
                    CryptoSession.createInitiator(completion.rootKey, remoteEphemeral)
                }
            } else {
                val localEphemeral = completion.localEphemeralKeyPair
                if (localEphemeral == null) {
                    InvalidStateTransitionError(
                        entity = ENTITY_NAME,
                        fromState = STATE_HANDSHAKING,
                        toState = STATE_ACTIVE_SESSION,
                    ).asFailure()
                } else {
                    CryptoSession.createResponder(completion.rootKey, localEphemeral)
                }
            }
        ByteArrayZeroizer.zeroize(completion.rootKey)
        state.handshake = null
        return sessionResult.map { session ->
            state.session?.zeroize()
            state.session = session
        }.onFailure {
            completion.zeroize()
        }
    }

    private suspend fun <T> withPeerLock(
        peerId: PeerId,
        action: suspend (PeerCryptoState) -> T,
    ): T {
        val state = mapMutex.withLock { peerStates.getOrPut(peerId) { PeerCryptoState() } }
        return state.mutex.withLock { action(state) }
    }

    private companion object {
        private const val ENTITY_NAME: String = "CryptoServiceImpl"
        private const val STATE_UNINITIALIZED_IDENTITY: String = "UNINITIALIZED_IDENTITY"
        private const val STATE_NO_HANDSHAKE: String = "NO_ACTIVE_HANDSHAKE"
        private const val STATE_NO_SESSION: String = "NO_ACTIVE_SESSION"
        private const val STATE_HANDSHAKING: String = "HANDSHAKING"
        private const val STATE_ACTIVE_SESSION: String = "ACTIVE_SESSION"
        private const val ACTION_HANDSHAKE: String = "HANDSHAKE"
        private const val ACTION_ENCRYPT: String = "ENCRYPT"
        private const val ACTION_DECRYPT: String = "DECRYPT"
    }
}
