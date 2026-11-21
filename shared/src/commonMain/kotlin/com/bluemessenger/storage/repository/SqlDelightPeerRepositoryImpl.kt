package com.bluemessenger.storage.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.storage.PeerRepository
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.storage.BlueMessengerDatabase
import com.bluemessenger.storage.BlueMessengerDatabaseQueries
import com.bluemessenger.storage.mapStorageErrors
import com.bluemessenger.storage.mapper.toDomain
import com.bluemessenger.storage.mapper.toSqliteLong
import com.bluemessenger.storage.runStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val OPERATION_OBSERVE_PEERS: String = "observePeers"
private const val OPERATION_OBSERVE_CONVERSATIONS: String = "observeConversations"
private const val OPERATION_GET_PEER_BY_ID: String = "getPeerById"
private const val OPERATION_GET_PEER_BY_ADDRESS: String = "getPeerByAddress"
private const val OPERATION_UPSERT_PEER: String = "upsertPeer"
private const val OPERATION_UPSERT_CONVERSATION: String = "upsertConversation"
private const val OPERATION_GET_CONVERSATION_BY_PEER: String = "getConversationByPeer"
private const val OPERATION_MARK_PEER_VERIFIED: String = "markPeerVerified"
private const val ENTITY_PEER: String = "Peer"
private const val STATE_UNVERIFIED: String = "UNVERIFIED"
private const val STATE_VERIFIED: String = "VERIFIED"

/**
 * SQLDelight-backed [PeerRepository] persisting peers and their one-to-one conversations.
 *
 * Thread safety: blocking SQLite calls run on [CoroutineDispatchers.io]; multi-statement mutations
 * execute inside a single SQLite transaction.
 */
class SqlDelightPeerRepositoryImpl(
    private val database: BlueMessengerDatabase,
    private val dispatchers: CoroutineDispatchers,
) : PeerRepository {
    private val queries = database.blueMessengerDatabaseQueries

    override fun observePeers(): Flow<List<Peer>> =
        queries
            .selectAllPeers()
            .asFlow()
            .mapToList(dispatchers.io)
            .map { rows -> rows.map { row -> row.toDomain() } }
            .mapStorageErrors(OPERATION_OBSERVE_PEERS)

    override fun observeConversations(): Flow<List<Conversation>> =
        queries
            .selectConversationsWithPeer()
            .asFlow()
            .mapToList(dispatchers.io)
            .map { rows -> rows.map { row -> row.toDomain() } }
            .mapStorageErrors(OPERATION_OBSERVE_CONVERSATIONS)

    override suspend fun getPeerById(peerId: PeerId): Result<Peer?> =
        dispatchers.runStorage(OPERATION_GET_PEER_BY_ID) {
            queries.selectPeerById(peerId.toString()).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun getPeerByAddress(address: BluetoothAddress): Result<Peer?> =
        dispatchers.runStorage(OPERATION_GET_PEER_BY_ADDRESS) {
            queries.selectPeerByAddress(address.value).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun upsertPeer(peer: Peer): Result<Unit> =
        dispatchers.runStorage(OPERATION_UPSERT_PEER) {
            queries.upsertPeerRecord(peer)
        }

    override suspend fun upsertConversation(conversation: Conversation): Result<Unit> =
        dispatchers.runStorage(OPERATION_UPSERT_CONVERSATION) {
            database.transaction {
                queries.upsertPeerRecord(conversation.peer)
                queries.upsertConversation(
                    id = conversation.id.toString(),
                    peerId = conversation.peer.id.toString(),
                    state = conversation.state.name,
                    updatedAt = conversation.updatedAt.epochMillis,
                )
            }
        }

    override suspend fun getConversationByPeer(peerId: PeerId): Result<Conversation?> =
        dispatchers.runStorage(OPERATION_GET_CONVERSATION_BY_PEER) {
            database.transactionWithResult {
                val conversationRow = queries.selectConversationByPeerId(peerId.toString()).executeAsOneOrNull()
                val peerRow = queries.selectPeerById(peerId.toString()).executeAsOneOrNull()
                if (conversationRow == null) {
                    null
                } else {
                    conversationRow.toDomain(
                        peer = peerRow?.toDomain() ?: throw peerNotFound(peerId),
                    )
                }
            }
        }

    override suspend fun markPeerVerified(
        peerId: PeerId,
        verified: Boolean,
    ): Result<Unit> =
        dispatchers.runStorage(OPERATION_MARK_PEER_VERIFIED) {
            database.transaction {
                val peer =
                    queries.selectPeerById(peerId.toString()).executeAsOneOrNull()?.toDomain()
                        ?: throw peerNotFound(peerId)
                if (verified && peer.identityKey == null) {
                    throw DomainException(
                        InvalidStateTransitionError(
                            entity = ENTITY_PEER,
                            fromState = STATE_UNVERIFIED,
                            toState = STATE_VERIFIED,
                            message = "Peer $peerId cannot be verified without an identity public key.",
                        ),
                    )
                }
                queries.updatePeerVerified(isVerified = verified.toSqliteLong(), id = peerId.toString())
            }
        }

    private fun peerNotFound(peerId: PeerId): DomainException {
        val error = StorageError("Peer $peerId was not found.")
        return DomainException(error)
    }
}

private fun BlueMessengerDatabaseQueries.upsertPeerRecord(peer: Peer) {
    upsertPeer(
        id = peer.id.toString(),
        nickname = peer.nickname,
        bluetoothAddress = peer.address.value,
        identityKey = peer.identityKey?.toByteArray(),
        isVerified = peer.isVerified.toSqliteLong(),
        lastSeenAt = peer.lastSeenAt.epochMillis,
    )
}
