package com.bluemessenger.presentation

import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.domain.constants.SessionConstants
import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.LocalIdentity
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.storage.IdentityRepository
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.usecase.InMemoryIdentityRepository
import com.bluemessenger.domain.usecase.PeerSideFixture
import com.bluemessenger.domain.usecase.createPeerSideFixture
import com.bluemessenger.domain.usecase.message.FlushPendingMessagesUseCase
import com.bluemessenger.domain.usecase.message.ObserveIncomingFramesUseCase
import com.bluemessenger.domain.usecase.message.ObserveMessagesUseCase
import com.bluemessenger.domain.usecase.message.SendMessageUseCase
import com.bluemessenger.domain.usecase.peer.ObserveConversationsUseCase
import com.bluemessenger.domain.usecase.peer.VerifyPeerSafetyNumberUseCase
import com.bluemessenger.domain.usecase.session.ManageKeepAliveAndReconnectUseCase
import com.bluemessenger.domain.usecase.testDispatchers
import com.bluemessenger.presentation.chat.ChatIntent
import com.bluemessenger.presentation.chat.ChatViewModel
import com.bluemessenger.presentation.constants.PresentationConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Test
    fun `rejects send without conversation and queues PENDING when disconnected with conversation`() =
        runTest {
            val baseTimeMs = 1_700_000_000_000L
            val clock: () -> Instant = { Instant(testScheduler.currentTime + baseTimeMs) }
            val alice = createPeerSideFixture(clock)
            val bobPeer = peer("Bob", "11:22:33:44:55:66", clock())
            val viewModel = buildChatViewModel(alice, InMemoryIdentityRepository(), clock)

            viewModel.onIntent(ChatIntent.OpenPeer(bobPeer))
            viewModel.onIntent(ChatIntent.UpdateMessageInput("Needs session"))
            viewModel.onIntent(ChatIntent.SendMessage)
            runCurrent()

            assertIs<PresentationError.Validation>(viewModel.uiState.value.error)
            assertEquals("Needs session", viewModel.uiState.value.messageInput)

            val conv = Conversation(ConversationId.generate(clock()), bobPeer, SessionState.DISCONNECTED, clock())
            alice.peerRepo.upsertConversation(conv).getOrThrow()
            viewModel.onIntent(ChatIntent.OpenPeer(bobPeer, conv.id, SessionState.DISCONNECTED))
            viewModel.onIntent(ChatIntent.UpdateMessageInput("Offline queued"))
            viewModel.onIntent(ChatIntent.SendMessage)
            runCurrent()

            assertNull(viewModel.uiState.value.error)
            assertEquals("", viewModel.uiState.value.messageInput)
            val messages = viewModel.uiState.value.messages
            assertEquals(1, messages.size)
            assertEquals(MessageStatus.PENDING, messages.single().status)
            assertEquals("Offline queued", messages.single().plaintext)
        }

    @Test
    fun `connects E2EE, flushes pending messages, and delivers ACKed live messages`() =
        runTest {
            val setup = setupPairedViewModel(withQueuedOfflineMessage = true, attachBobObserverOnAccept = true)
            val viewModel = setup.viewModel

            assertEquals(SessionState.SECURE_ACTIVE, viewModel.uiState.value.sessionState)
            assertFalse(viewModel.uiState.value.isConnecting)
            assertEquals(MessageStatus.DELIVERED, viewModel.uiState.value.messages.first().status)

            advanceTimeBy(10L)
            viewModel.onIntent(ChatIntent.UpdateMessageInput("Live encrypted"))
            viewModel.onIntent(ChatIntent.SendMessage)
            runCurrent()

            val liveDelivered = viewModel.uiState.value.messages.last()
            assertEquals("Live encrypted", liveDelivered.plaintext)
            assertEquals(MessageStatus.DELIVERED, liveDelivered.status)
        }

    @Test
    fun `handles ACK timeout FAILED, retries preserving MessageId to DELIVERED, and disconnects`() =
        runTest {
            val setup = setupPairedViewModel(withQueuedOfflineMessage = false)
            val viewModel = setup.viewModel

            setup.bob.transport.onSend = { Result.success(Unit) }
            viewModel.onIntent(ChatIntent.UpdateMessageInput("Will timeout"))
            viewModel.onIntent(ChatIntent.SendMessage)
            runCurrent()

            val timedOutId = viewModel.uiState.value.messages.single().id
            assertEquals(MessageStatus.SENT, viewModel.uiState.value.messages.single().status)

            advanceTimeBy(SessionConstants.ACK_TIMEOUT_MS)
            runCurrent()
            assertEquals(MessageStatus.FAILED, viewModel.uiState.value.messages.single().status)

            ObserveIncomingFramesUseCase(
                setup.bob.transport,
                setup.bob.crypto,
                setup.bob.messageRepo,
                testDispatchers(testScheduler),
                setup.bob.logger,
                setup.clock,
            )(
                scope = backgroundScope,
                peerId = setup.alicePeer.id,
                conversationId = setup.bobConvId,
            )
            wireDuplex(setup.alice, setup.bob)
            viewModel.onIntent(ChatIntent.RetryFailedMessage(timedOutId))
            runCurrent()

            val retriedMessage = viewModel.uiState.value.messages.single()
            assertEquals(timedOutId, retriedMessage.id)
            assertEquals(MessageStatus.DELIVERED, retriedMessage.status)

            viewModel.onIntent(ChatIntent.Disconnect)
            runCurrent()
            assertEquals(SessionState.DISCONNECTED, viewModel.uiState.value.sessionState)
        }

    @Test
    fun `computes safety number, generates QR matrix, and marks peer verified`() =
        runTest {
            val setup = setupPairedViewModel(withQueuedOfflineMessage = false)
            val viewModel = setup.viewModel

            viewModel.onIntent(ChatIntent.OpenSafetyNumberDialog)
            runCurrent()

            val dialog = viewModel.uiState.value.safetyNumberDialog
            assertNotNull(dialog)
            assertEquals(
                PresentationConstants.SAFETY_NUMBER_TOTAL_DIGITS,
                dialog.safetyNumber.filter(Char::isDigit).length,
            )
            assertNotNull(dialog.qrCodeMatrix)
            assertFalse(dialog.isVerified)
            assertFalse(dialog.isLoading)

            viewModel.onIntent(ChatIntent.MarkPeerVerified)
            runCurrent()

            assertTrue(viewModel.uiState.value.peer?.isVerified == true)
            assertTrue(viewModel.uiState.value.safetyNumberDialog?.isVerified == true)

            viewModel.onIntent(ChatIntent.DismissSafetyNumberDialog)
            assertNull(viewModel.uiState.value.safetyNumberDialog)
        }

    @Test
    fun `openPeer is idempotent for same peer and restarts active loops when switching back to SECURE_ACTIVE`() =
        runTest {
            val setup = setupPairedViewModel(withQueuedOfflineMessage = false, attachBobObserverOnAccept = true)
            val viewModel = setup.viewModel

            viewModel.onIntent(ChatIntent.UpdateMessageInput("Draft text"))
            viewModel.onIntent(ChatIntent.OpenPeer(setup.bobPeer))
            runCurrent()

            assertEquals("Draft text", viewModel.uiState.value.messageInput)
            assertEquals(SessionState.SECURE_ACTIVE, viewModel.uiState.value.sessionState)

            val otherPeer = peer("Carol", "22:33:44:55:66:77", setup.clock())
            viewModel.onIntent(ChatIntent.OpenPeer(otherPeer))
            runCurrent()
            assertEquals(otherPeer.id, viewModel.uiState.value.peer?.id)

            val queuedWhileAway =
                Message(
                    id = MessageId.generate(Instant(setup.clock().epochMillis + 50L)),
                    conversationId = setup.aliceConvId,
                    plaintext = "Flush on return",
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.PENDING,
                    sentAt = Instant(setup.clock().epochMillis + 50L),
                )
            setup.alice.messageRepo.saveIdempotent(queuedWhileAway).getOrThrow()

            viewModel.onIntent(ChatIntent.OpenPeer(setup.bobPeer))
            runCurrent()

            assertEquals(SessionState.SECURE_ACTIVE, viewModel.uiState.value.sessionState)
            assertEquals(MessageStatus.DELIVERED, viewModel.uiState.value.messages.single().status)
        }

    @Test
    fun `cancels in-flight safety number and verification on peer switch or dialog dismiss`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val baseTimeMs = 1_700_000_000_000L
            val clock: () -> Instant = { Instant(testScheduler.currentTime + baseTimeMs) }
            val alice = createPeerSideFixture(clock)
            val bobPeer = peer("Bob", "11:22:33:44:55:66", clock())
            val carolPeer = peer("Carol", "22:33:44:55:66:77", Instant(clock().epochMillis + 1L))
            val bobKey = IdentityKeyPair.generate().publicKey
            alice.peerRepo.upsertPeer(bobPeer.copy(identityKey = bobKey)).getOrThrow()
            alice.peerRepo.upsertPeer(carolPeer).getOrThrow()
            val gatedIdentityRepo = gatedIdentityRepository(gate, clock())
            val viewModel = buildChatViewModel(alice, gatedIdentityRepo, clock)

            viewModel.onIntent(ChatIntent.OpenPeer(bobPeer))
            viewModel.onIntent(ChatIntent.OpenSafetyNumberDialog)
            runCurrent()
            assertTrue(viewModel.uiState.value.safetyNumberDialog?.isLoading == true)

            viewModel.onIntent(ChatIntent.DismissSafetyNumberDialog)
            gate.complete(Unit)
            runCurrent()
            assertNull(viewModel.uiState.value.safetyNumberDialog)

            val secondGate = CompletableDeferred<Unit>()
            val secondVm = buildChatViewModel(alice, gatedIdentityRepository(secondGate, clock()), clock)
            secondVm.onIntent(ChatIntent.OpenPeer(bobPeer))
            secondVm.onIntent(ChatIntent.OpenSafetyNumberDialog)
            runCurrent()

            secondVm.onIntent(ChatIntent.OpenPeer(carolPeer))
            secondGate.complete(Unit)
            runCurrent()

            assertEquals(carolPeer.id, secondVm.uiState.value.peer?.id)
            assertNull(secondVm.uiState.value.safetyNumberDialog)
        }

    @Test
    fun `clear cancels scope, peer observations, and active session loops`() =
        runTest {
            val baseTimeMs = 1_700_000_000_000L
            val clock: () -> Instant = { Instant(testScheduler.currentTime + baseTimeMs) }
            val alice = createPeerSideFixture(clock)
            val dispatchers = testDispatchers(testScheduler)
            val ownedScope = CoroutineScope(SupervisorJob() + dispatchers.main)
            val viewModel = buildChatViewModel(alice, InMemoryIdentityRepository(), clock, scope = ownedScope)
            val bobPeer = peer("Bob", "11:22:33:44:55:66", clock())
            val convId = ConversationId.generate(clock())
            alice.peerRepo.upsertConversation(Conversation(convId, bobPeer, SessionState.DISCONNECTED, clock()))
                .getOrThrow()
            viewModel.onIntent(ChatIntent.OpenPeer(bobPeer, convId, SessionState.DISCONNECTED))
            runCurrent()

            viewModel.clear()
            assertFalse(ownedScope.isActive)

            val msg =
                Message(
                    id = MessageId.generate(clock()),
                    conversationId = convId,
                    plaintext = "After clear",
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.PENDING,
                    sentAt = clock(),
                )
            alice.messageRepo.saveIdempotent(msg).getOrThrow()
            runCurrent()

            assertTrue(viewModel.uiState.value.messages.isEmpty())
        }

    private suspend fun TestScope.setupPairedViewModel(
        withQueuedOfflineMessage: Boolean,
        attachBobObserverOnAccept: Boolean = false,
    ): PairedViewModelSetup {
        val baseTimeMs = 1_700_000_000_000L
        val clock: () -> Instant = { Instant(testScheduler.currentTime + baseTimeMs) }
        val alice = createPeerSideFixture(clock)
        val bob = createPeerSideFixture(clock)
        wireDuplex(alice, bob)

        val now = clock()
        val alicePeer = peer("Alice", "AA:BB:CC:DD:EE:FF", now)
        val bobPeer = peer("Bob", "11:22:33:44:55:66", Instant(now.epochMillis + 1L))
        val aliceIdentity =
            LocalIdentity(
                id = alicePeer.id,
                nickname = alicePeer.nickname,
                identityPublicKey = alice.keyStore.getIdentityPublicKey().getOrThrow()!!,
                createdAt = now,
            )
        val aliceConvId = ConversationId.generate(now)
        val bobConvId = ConversationId.generate(Instant(now.epochMillis + 1L))
        if (attachBobObserverOnAccept) {
            attachBobIncomingObserver(bob, alicePeer, bobConvId, clock)
        }
        if (withQueuedOfflineMessage) {
            alice.peerRepo.upsertConversation(Conversation(aliceConvId, bobPeer, SessionState.DISCONNECTED, now))
                .getOrThrow()
            val queued =
                Message(
                    id = MessageId.generate(Instant(now.epochMillis + 2L)),
                    conversationId = aliceConvId,
                    plaintext = "Queued offline",
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.PENDING,
                    sentAt = Instant(now.epochMillis + 2L),
                )
            alice.messageRepo.saveIdempotent(queued).getOrThrow()
        }

        val viewModel = buildChatViewModel(alice, InMemoryIdentityRepository(aliceIdentity), clock)
        if (withQueuedOfflineMessage) {
            viewModel.onIntent(ChatIntent.OpenPeer(bobPeer, aliceConvId, SessionState.DISCONNECTED))
        } else {
            viewModel.onIntent(ChatIntent.OpenPeer(bobPeer))
        }
        runCurrent()

        val bobAcceptDeferred =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                bob.establish.acceptAndEstablish(alicePeer).getOrThrow()
            }
        viewModel.onIntent(ChatIntent.Connect)
        runCurrent()
        val bobConv = bobAcceptDeferred.await()
        val activeAliceConvId = viewModel.uiState.value.conversationId ?: aliceConvId
        return PairedViewModelSetup(alice, bob, alicePeer, bobPeer, activeAliceConvId, bobConv.id, viewModel, clock)
    }

    private fun gatedIdentityRepository(
        gate: CompletableDeferred<Unit>,
        now: Instant,
    ): IdentityRepository {
        val identity =
            LocalIdentity(
                id = PeerId.generate(now),
                nickname = "Alice",
                identityPublicKey = IdentityKeyPair.generate().publicKey,
                createdAt = now,
            )
        val delegate = InMemoryIdentityRepository(identity)
        return object : IdentityRepository {
            override fun observeLocalIdentity() = delegate.observeLocalIdentity()

            override suspend fun getLocalIdentity(): Result<LocalIdentity?> {
                gate.await()
                return delegate.getLocalIdentity()
            }

            override suspend fun saveLocalIdentity(identity: LocalIdentity): Result<Unit> {
                return delegate.saveLocalIdentity(identity)
            }
        }
    }

    private suspend fun TestScope.attachBobIncomingObserver(
        bob: PeerSideFixture,
        alicePeer: Peer,
        bobConvId: ConversationId,
        clock: () -> Instant,
    ) {
        bob.peerRepo.upsertConversation(Conversation(bobConvId, alicePeer, SessionState.DISCONNECTED, clock()))
            .getOrThrow()
        ObserveIncomingFramesUseCase(
            bob.transport,
            bob.crypto,
            bob.messageRepo,
            testDispatchers(testScheduler),
            bob.logger,
            clock,
        )(
            scope = backgroundScope,
            peerId = alicePeer.id,
            conversationId = bobConvId,
        )
    }

    private fun peer(
        nickname: String,
        address: String,
        timestamp: Instant,
    ): Peer = Peer(PeerId.generate(timestamp), nickname, BluetoothAddress(address), lastSeenAt = timestamp)

    private fun wireDuplex(
        alice: PeerSideFixture,
        bob: PeerSideFixture,
    ) {
        alice.transport.onSend = { packet ->
            bob.transport.emitIncomingPacket(packet)
            Result.success(Unit)
        }
        bob.transport.onSend = { packet ->
            alice.transport.emitIncomingPacket(packet)
            Result.success(Unit)
        }
    }

    private fun TestScope.buildChatViewModel(
        side: PeerSideFixture,
        identityRepo: IdentityRepository,
        clock: () -> Instant,
        scope: CoroutineScope = backgroundScope,
    ): ChatViewModel {
        val dispatchers = testDispatchers(testScheduler)
        val sendUseCase =
            SendMessageUseCase(
                side.messageRepo,
                side.peerRepo,
                side.transport,
                side.packetSender,
                dispatchers,
                side.logger,
                clock,
            )
        val flushUseCase =
            FlushPendingMessagesUseCase(
                side.messageRepo,
                side.peerRepo,
                side.transport,
                side.packetSender,
                dispatchers,
                side.logger,
            )
        return ChatViewModel(
            establishSecureSessionUseCase = side.establish,
            observeConversationsUseCase = ObserveConversationsUseCase(side.peerRepo, side.logger),
            observeMessagesUseCase = ObserveMessagesUseCase(side.messageRepo, side.logger),
            observeIncomingFramesUseCase =
                ObserveIncomingFramesUseCase(
                    side.transport,
                    side.crypto,
                    side.messageRepo,
                    dispatchers,
                    side.logger,
                    clock,
                ),
            sendMessageUseCase = sendUseCase,
            flushPendingMessagesUseCase = flushUseCase,
            manageKeepAliveAndReconnectUseCase =
                ManageKeepAliveAndReconnectUseCase(
                    side.transport,
                    side.crypto,
                    side.peerRepo,
                    side.establish,
                    flushUseCase,
                    dispatchers,
                    side.logger,
                    clock,
                ),
            verifyPeerSafetyNumberUseCase =
                VerifyPeerSafetyNumberUseCase(
                    identityRepo,
                    side.peerRepo,
                    side.crypto,
                    side.logger,
                ),
            dispatchers = dispatchers,
            logger = side.logger,
            scope = scope,
        )
    }

    private data class PairedViewModelSetup(
        val alice: PeerSideFixture,
        val bob: PeerSideFixture,
        val alicePeer: Peer,
        val bobPeer: Peer,
        val aliceConvId: ConversationId,
        val bobConvId: ConversationId,
        val viewModel: ChatViewModel,
        val clock: () -> Instant,
    )
}
