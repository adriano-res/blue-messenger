package com.bluemessenger.domain.ports.repository

import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.PeerId
import kotlinx.coroutines.flow.Flow

/**
 * Persistence and reactive observation port for remote [Peer] records and [Conversation] threads.
 *
 * Thread safety: Implementations must synchronize concurrent writes safely and execute storage
 * operations off the main thread.
 *
 * Error handling: Suspend methods wrap persistence errors in [Result.failure] and preserve
 * coroutine cancellation semantics.
 */
interface PeerRepository {
    /**
     * Emits immutable lists of all known or paired peers.
     */
    fun observePeers(): Flow<List<Peer>>

    /**
     * Emits immutable lists of all active or historical conversations.
     */
    fun observeConversations(): Flow<List<Conversation>>

    /**
     * Looks up a persisted peer by [peerId], returning `null` if absent.
     */
    suspend fun getPeerById(peerId: PeerId): Result<Peer?>

    /**
     * Looks up a persisted peer by its Bluetooth [address], returning `null` if absent.
     */
    suspend fun getPeerByAddress(address: BluetoothAddress): Result<Peer?>

    /**
     * Inserts or updates a [Peer] record atomically.
     */
    suspend fun upsertPeer(peer: Peer): Result<Unit>

    /**
     * Inserts or updates a [Conversation] aggregate atomically.
     */
    suspend fun upsertConversation(conversation: Conversation): Result<Unit>

    /**
     * Retrieves the conversation associated with [peerId], or `null` if none exists yet.
     */
    suspend fun getConversationByPeer(peerId: PeerId): Result<Conversation?>

    /**
     * Updates the out-of-band Safety Number verification flag for [peerId].
     */
    suspend fun markPeerVerified(
        peerId: PeerId,
        verified: Boolean,
    ): Result<Unit>
}
