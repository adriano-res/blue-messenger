package com.bluemessenger.storage

import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.storage.repository.SqlDelightPeerRepositoryImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SqlDelightPeerRepositoryTest {
    private fun TestScope.createRepository(): SqlDelightPeerRepositoryImpl =
        SqlDelightPeerRepositoryImpl(BlueMessengerDatabase(createInMemoryTestDriver()), testDispatchers())

    @Test
    fun `upsertPeer persists and is retrievable by id and address`() =
        runTest {
            val repository = createRepository()
            val peer = testPeer()

            repository.upsertPeer(peer).getOrThrow()

            assertEquals(peer, repository.getPeerById(peer.id).getOrThrow())
            assertEquals(peer, repository.getPeerByAddress(peer.address).getOrThrow())
        }

    @Test
    fun `lookups return null for unknown peers`() =
        runTest {
            val repository = createRepository()
            val unknown = testPeer()

            assertNull(repository.getPeerById(unknown.id).getOrThrow())
            assertNull(repository.getPeerByAddress(unknown.address).getOrThrow())
            assertNull(repository.getConversationByPeer(unknown.id).getOrThrow())
        }

    @Test
    fun `upsertPeer updates an existing peer without duplicating it`() =
        runTest {
            val repository = createRepository()
            val peer = testPeer()
            val renamed = peer.copy(nickname = "renamed", lastSeenAt = testInstant(1))

            repository.upsertPeer(peer).getOrThrow()
            repository.upsertPeer(renamed).getOrThrow()

            assertEquals(renamed, repository.getPeerById(peer.id).getOrThrow())
            assertEquals(listOf(renamed), repository.observePeers().first())
        }

    @Test
    fun `peer without identity key round trips as null key`() =
        runTest {
            val repository = createRepository()
            val peer = testPeer(identityKey = null)

            repository.upsertPeer(peer).getOrThrow()

            assertNull(repository.getPeerById(peer.id).getOrThrow()?.identityKey)
        }

    @Test
    fun `upsertConversation stores peer and conversation atomically`() =
        runTest {
            val repository = createRepository()
            val conversation = testConversation()

            repository.upsertConversation(conversation).getOrThrow()

            assertEquals(conversation, repository.getConversationByPeer(conversation.peer.id).getOrThrow())
            assertEquals(conversation.peer, repository.getPeerById(conversation.peer.id).getOrThrow())
            assertEquals(listOf(conversation), repository.observeConversations().first())
        }

    @Test
    fun `upsertConversation updates state of existing conversation`() =
        runTest {
            val repository = createRepository()
            val conversation = testConversation()
            val updated = conversation.copy(state = SessionState.CONNECTING, updatedAt = testInstant(5))

            repository.upsertConversation(conversation).getOrThrow()
            repository.upsertConversation(updated).getOrThrow()

            assertEquals(listOf(updated), repository.observeConversations().first())
        }

    @Test
    fun `observeConversations orders by most recent update`() =
        runTest {
            val repository = createRepository()
            val older = testConversation(peer = testPeer(index = 1), updatedOffsetMillis = 1)
            val newer = testConversation(peer = testPeer(index = 2), updatedOffsetMillis = 2)

            repository.upsertConversation(older).getOrThrow()
            repository.upsertConversation(newer).getOrThrow()

            assertEquals(listOf(newer, older), repository.observeConversations().first())
        }

    @Test
    fun `observePeers emits a new snapshot after every change`() =
        runTest {
            val repository = createRepository()
            val peer = testPeer()
            val emissions = mutableListOf<List<Peer>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observePeers().toList(emissions)
            }

            repository.upsertPeer(peer).getOrThrow()

            assertEquals(listOf(emptyList(), listOf(peer)), emissions)
        }

    @Test
    fun `observeConversations emits a new snapshot after every change`() =
        runTest {
            val repository = createRepository()
            val conversation = testConversation()
            val emissions = mutableListOf<List<Conversation>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeConversations().toList(emissions)
            }

            repository.upsertConversation(conversation).getOrThrow()

            assertEquals(listOf(emptyList(), listOf(conversation)), emissions)
        }

    @Test
    fun `markPeerVerified persists the verification flag in both directions`() =
        runTest {
            val repository = createRepository()
            val peer = testPeer()
            repository.upsertPeer(peer).getOrThrow()

            repository.markPeerVerified(peer.id, verified = true).getOrThrow()
            assertEquals(true, repository.getPeerById(peer.id).getOrThrow()?.isVerified)

            repository.markPeerVerified(peer.id, verified = false).getOrThrow()
            assertEquals(false, repository.getPeerById(peer.id).getOrThrow()?.isVerified)
        }

    @Test
    fun `markPeerVerified rejects peers without identity key`() =
        runTest {
            val repository = createRepository()
            val peer = testPeer(identityKey = null)
            repository.upsertPeer(peer).getOrThrow()

            val failure = repository.markPeerVerified(peer.id, verified = true).exceptionOrNull()

            assertIs<InvalidStateTransitionError>(assertIs<DomainException>(failure).error)
            assertEquals(false, repository.getPeerById(peer.id).getOrThrow()?.isVerified)
        }

    @Test
    fun `markPeerVerified fails with storage error for unknown peer`() =
        runTest {
            val repository = createRepository()

            val failure = repository.markPeerVerified(PeerId.generate(testInstant()), verified = true).exceptionOrNull()

            assertIs<StorageError>(assertIs<DomainException>(failure).error)
        }

    @Test
    fun `upsertPeer fails with storage error on duplicate bluetooth address`() =
        runTest {
            val repository = createRepository()
            val first = testPeer(index = 1)
            val conflicting = testPeer(index = 2).copy(address = first.address)
            repository.upsertPeer(first).getOrThrow()

            val failure = repository.upsertPeer(conflicting).exceptionOrNull()

            assertIs<StorageError>(assertIs<DomainException>(failure).error)
        }
}
