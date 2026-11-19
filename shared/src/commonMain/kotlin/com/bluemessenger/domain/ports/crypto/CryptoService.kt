package com.bluemessenger.domain.ports.crypto

import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey

/**
 * End-to-end encryption port managing P2P handshakes, Double Ratchet AEAD sessions, and SAS codes.
 *
 * Thread safety: Implementations must guard per-peer ratchet state with mutual exclusion so
 * concurrent send/receive operations never reuse ephemeral message keys or corrupt chain counters.
 *
 * Error handling: Authentication failures, invalid MAC tags, malformed handshake packets, or
 * uninitialized sessions must fail fast with [Result.failure] carrying [DomainException]
 * (`DecryptionFailedError`, `CorruptedFrameError`, `InvalidStateTransitionError`).
 */
interface CryptoService {
    /**
     * Initializes a new key-exchange session with [peerId], returning the initial outbound
     * handshake message when [isInitiator] is `true` or an empty array when acting as responder.
     */
    suspend fun initializeHandshake(
        peerId: PeerId,
        isInitiator: Boolean,
    ): Result<ByteArray>

    /**
     * Processes an inbound handshake message for [peerId] and advances the Noise XX state machine.
     */
    suspend fun processHandshakeStep(
        peerId: PeerId,
        incomingPayload: ByteArray,
    ): Result<HandshakeStepResult>

    /**
     * Encrypts [plaintext] using the active session ratchet for [peerId] and authenticates
     * [associatedData].
     */
    suspend fun encrypt(
        peerId: PeerId,
        plaintext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): Result<ByteArray>

    /**
     * Decrypts and authenticates [ciphertext] for [peerId] using [associatedData].
     */
    suspend fun decrypt(
        peerId: PeerId,
        ciphertext: ByteArray,
        associatedData: ByteArray = ByteArray(0),
    ): Result<ByteArray>

    /**
     * Derives a deterministic human-verifiable Safety Number (SAS) from [localKey] and [remoteKey].
     */
    suspend fun computeSafetyNumber(
        localKey: PublicKey,
        remoteKey: PublicKey,
    ): Result<String>

    /**
     * Discards in-memory ephemeral ratchet state for [peerId].
     */
    suspend fun clearSession(peerId: PeerId): Result<Unit>
}
