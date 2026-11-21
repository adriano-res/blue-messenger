package com.bluemessenger.domain.usecase.peer

import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.ports.crypto.CryptoService
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.storage.IdentityRepository
import com.bluemessenger.domain.ports.storage.PeerRepository
import com.bluemessenger.domain.primitives.PeerId

/**
 * Computes deterministic Safety Numbers (SAS) and updates peer verification status.
 *
 * Thread safety: Stateless; safe to invoke concurrently across dispatchers.
 *
 * Failure behavior: Fails fast if local identity is uninitialized, peer does not exist, or the
 * remote peer has no persisted identity public key.
 */
class VerifyPeerSafetyNumberUseCase(
    private val identityRepository: IdentityRepository,
    private val peerRepository: PeerRepository,
    private val cryptoService: CryptoService,
    private val logger: AppLogger,
) {
    /**
     * Derives the 60-digit Safety Number shared between the local identity and [peerId].
     */
    suspend fun computeSafetyNumber(peerId: PeerId): Result<String> =
        identityRepository.getLocalIdentity().fold(
            onSuccess = { localIdentity ->
                if (localIdentity == null) {
                    InvalidStateTransitionError(
                        entity = ENTITY_LOCAL_IDENTITY,
                        fromState = STATE_UNINITIALIZED,
                        toState = ACTION_COMPUTE_SAFETY_NUMBER,
                        message = "Local identity must be initialized before computing a Safety Number.",
                    ).asFailure()
                } else {
                    computeSafetyNumberForPeer(localIdentity.identityPublicKey, peerId)
                }
            },
            onFailure = { Result.failure(it) },
        )

    /**
     * Persists out-of-band Safety Number verification status [verified] for [peerId].
     */
    suspend fun markPeerVerified(
        peerId: PeerId,
        verified: Boolean = true,
    ): Result<Unit> =
        peerRepository.getPeerById(peerId).fold(
            onSuccess = { peer ->
                when {
                    peer == null -> StorageError("Peer $peerId was not found.").asFailure()
                    peer.identityKey == null ->
                        InvalidStateTransitionError(
                            entity = ENTITY_PEER,
                            fromState = STATE_UNKEYED,
                            toState = STATE_VERIFIED,
                            message = "Peer $peerId cannot be verified without an identity public key.",
                        ).asFailure()
                    else -> applyPeerVerification(peerId, verified)
                }
            },
            onFailure = { Result.failure(it) },
        )

    private suspend fun computeSafetyNumberForPeer(
        localKey: com.bluemessenger.domain.primitives.PublicKey,
        peerId: PeerId,
    ): Result<String> =
        peerRepository.getPeerById(peerId).fold(
            onSuccess = { peer ->
                val remoteKey = peer?.identityKey
                when {
                    peer == null -> StorageError("Peer $peerId was not found.").asFailure()
                    remoteKey == null ->
                        InvalidStateTransitionError(
                            entity = ENTITY_PEER,
                            fromState = STATE_UNKEYED,
                            toState = ACTION_COMPUTE_SAFETY_NUMBER,
                            message = "Peer $peerId has no persisted identity public key.",
                        ).asFailure()
                    else ->
                        cryptoService.computeSafetyNumber(localKey, remoteKey)
                            .onFailure { error ->
                                logger.warn(
                                    tag = TAG,
                                    message = "Failed to compute peer safety number.",
                                    throwable = error,
                                    context = mapOf("peerId" to peerId.toString()),
                                )
                            }
                }
            },
            onFailure = { Result.failure(it) },
        )

    private suspend fun applyPeerVerification(
        peerId: PeerId,
        verified: Boolean,
    ): Result<Unit> =
        peerRepository.markPeerVerified(peerId, verified)
            .onSuccess {
                logger.info(
                    tag = TAG,
                    message = "Updated peer verification status.",
                    context =
                        mapOf(
                            "peerId" to peerId.toString(),
                            "verified" to verified.toString(),
                        ),
                )
            }.onFailure { error ->
                logger.warn(
                    tag = TAG,
                    message = "Failed to update peer verification status.",
                    throwable = error,
                    context = mapOf("peerId" to peerId.toString()),
                )
            }

    private companion object {
        private const val TAG: String = "VerifyPeerSafetyNumberUseCase"
        private const val ENTITY_LOCAL_IDENTITY: String = "LocalIdentity"
        private const val ENTITY_PEER: String = "Peer"
        private const val STATE_UNINITIALIZED: String = "UNINITIALIZED"
        private const val STATE_UNKEYED: String = "UNKEYED"
        private const val STATE_VERIFIED: String = "VERIFIED"
        private const val ACTION_COMPUTE_SAFETY_NUMBER: String = "COMPUTE_SAFETY_NUMBER"
    }
}
