package com.bluemessenger.domain.usecase.peer

import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.storage.PeerRepository
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey
import com.bluemessenger.domain.usecase.FakeAppLogger
import com.bluemessenger.domain.usecase.InMemoryPeerRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveConversationsUseCaseTest {
    @Test
    fun `emits historical conversations and live additions, session transitions, and peer verification updates`() =
        runTest {
            val baseMs = 1_700_000_000_000L
            val peerRepo = InMemoryPeerRepository()
            val logger = FakeAppLogger()
            val useCase = ObserveConversationsUseCase(peerRepo, logger)

            val aliceKey = PublicKey.fromBytes(ByteArray(PublicKey.EXPECTED_KEY_LENGTH_BYTES) { 1 }).getOrThrow()
            val aliceConv = conversation("Alice", "AA:BB:CC:DD:EE:01", baseMs, aliceKey)
            peerRepo.upsertConversation(aliceConv).getOrThrow()

            val emissions = mutableListOf<List<Conversation>>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { useCase().collect(emissions::add) }
            runCurrent()
            assertEquals(listOf(aliceConv), emissions.single())

            peerRepo.upsertConversation(activate(aliceConv, baseMs)).getOrThrow()
            runCurrent()
            assertEquals(SessionState.SECURE_ACTIVE, emissions[1].single().state)

            peerRepo.markPeerVerified(aliceConv.peer.id, verified = true).getOrThrow()
            runCurrent()
            assertTrue(emissions[2].single().peer.isVerified)

            val bobConv = conversation("Bob", "AA:BB:CC:DD:EE:02", baseMs + 20L)
            peerRepo.upsertConversation(bobConv).getOrThrow()
            runCurrent()

            assertEquals(4, emissions.size)
            assertEquals(listOf(aliceConv.peer.id, bobConv.peer.id), emissions[3].map { it.peer.id })

            job.cancel()
            assertTrue(logger.entries.isEmpty())
        }

    @Test
    fun `filters consecutive identical conversation snapshots via distinctUntilChanged`() =
        runTest {
            val baseMs = 1_700_000_000_000L
            val sharedPeerRepo = SharedFlowPeerRepository()
            val useCase = ObserveConversationsUseCase(sharedPeerRepo, FakeAppLogger())
            val conv1 = conversation("Alice", "AA:BB:CC:DD:EE:01", baseMs)
            val conv2 = conversation("Bob", "AA:BB:CC:DD:EE:02", baseMs + 10L)

            val emissions = mutableListOf<List<Conversation>>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { useCase().collect(emissions::add) }
            runCurrent()

            sharedPeerRepo.emit(listOf(conv1))
            sharedPeerRepo.emit(listOf(conv1.copy()))
            sharedPeerRepo.emit(listOf(conv1, conv2))
            runCurrent()

            assertEquals(listOf(listOf(conv1), listOf(conv1, conv2)), emissions)
            job.cancel()
        }

    @Test
    fun `logs structured error on stream failure and ignores normal cancellation`() =
        runTest {
            val peerRepo = InMemoryPeerRepository()
            val logger = FakeAppLogger()
            val useCase = ObserveConversationsUseCase(peerRepo, logger)

            peerRepo.observeFailure = StorageError("SQLite conversations read failure")
            val thrown =
                assertFailsWith<DomainException> {
                    useCase().collect { }
                }

            assertIs<StorageError>(thrown.error)
            assertEquals(1, logger.entries.size)
            val errorEntry = logger.entries.single()
            assertEquals(FakeAppLogger.Level.ERROR, errorEntry.level)
            assertEquals(thrown, errorEntry.throwable)

            peerRepo.observeFailure = null
            val activeJob = launch(start = CoroutineStart.UNDISPATCHED) { useCase().collect { } }
            runCurrent()
            activeJob.cancel()
            runCurrent()

            assertEquals(1, logger.entries.size)
        }

    private fun conversation(
        nickname: String,
        address: String,
        timestampMs: Long,
        identityKey: PublicKey? = null,
    ): Conversation {
        val timestamp = Instant(timestampMs)
        return Conversation(
            id = ConversationId.generate(timestamp),
            peer =
                Peer(
                    id = PeerId.generate(timestamp),
                    nickname = nickname,
                    address = BluetoothAddress(address),
                    identityKey = identityKey,
                    isVerified = false,
                    lastSeenAt = timestamp,
                ),
            state = SessionState.DISCONNECTED,
            updatedAt = timestamp,
        )
    }

    private fun activate(
        conversation: Conversation,
        baseMs: Long,
    ): Conversation =
        conversation
            .transitionSessionState(SessionState.CONNECTING, Instant(baseMs + 5L))
            .getOrThrow()
            .transitionSessionState(SessionState.HANDSHAKING, Instant(baseMs + 10L))
            .getOrThrow()
            .transitionSessionState(SessionState.SECURE_ACTIVE, Instant(baseMs + 15L))
            .getOrThrow()

    private class SharedFlowPeerRepository : PeerRepository {
        private val conversationsFlow = MutableSharedFlow<List<Conversation>>(extraBufferCapacity = 8)

        suspend fun emit(snapshot: List<Conversation>) {
            conversationsFlow.emit(snapshot)
        }

        override fun observePeers(): Flow<List<Peer>> = error("Unused in test.")

        override fun observeConversations(): Flow<List<Conversation>> = conversationsFlow.asSharedFlow()

        override suspend fun getPeerById(peerId: PeerId): Result<Peer?> = Result.success(null)

        override suspend fun getPeerByAddress(address: BluetoothAddress): Result<Peer?> = Result.success(null)

        override suspend fun upsertPeer(peer: Peer): Result<Unit> = Result.success(Unit)

        override suspend fun upsertConversation(conversation: Conversation): Result<Unit> = Result.success(Unit)

        override suspend fun getConversationByPeer(peerId: PeerId): Result<Conversation?> = Result.success(null)

        override suspend fun markPeerVerified(
            peerId: PeerId,
            verified: Boolean,
        ): Result<Unit> = Result.success(Unit)
    }
}
