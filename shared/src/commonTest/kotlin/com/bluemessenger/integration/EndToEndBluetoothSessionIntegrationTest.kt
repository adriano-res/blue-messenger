package com.bluemessenger.integration

import com.bluemessenger.crypto.CryptoServiceImpl
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.constants.SessionConstants
import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.ports.transport.model.Packet
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.domain.usecase.FakeAppLogger
import com.bluemessenger.domain.usecase.FakeKeyStoreProvider
import com.bluemessenger.domain.usecase.InMemoryIdentityRepository
import com.bluemessenger.domain.usecase.InMemoryMessageRepository
import com.bluemessenger.domain.usecase.InMemoryPeerRepository
import com.bluemessenger.domain.usecase.message.FlushPendingMessagesUseCase
import com.bluemessenger.domain.usecase.message.ObserveIncomingFramesUseCase
import com.bluemessenger.domain.usecase.message.SendMessageUseCase
import com.bluemessenger.domain.usecase.message.util.EncryptedPacketSender
import com.bluemessenger.domain.usecase.peer.InitializeIdentityUseCase
import com.bluemessenger.domain.usecase.peer.VerifyPeerSafetyNumberUseCase
import com.bluemessenger.domain.usecase.session.EstablishSecureSessionUseCase
import com.bluemessenger.domain.usecase.session.ManageKeepAliveAndReconnectUseCase
import com.bluemessenger.domain.usecase.testDispatchers
import com.bluemessenger.transport.protocol.FrameDecoder
import com.bluemessenger.transport.protocol.FrameEncoder
import com.bluemessenger.transport.protocol.PacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndBluetoothSessionIntegrationTest {
    private val baseTimeMs: Long = 1_700_000_000_000L

    @Test
    fun `completes full P2P E2EE lifecycle over framed fragmented RFCOMM bridge`() =
        runTest {
            val dispatchers = testDispatchers(testScheduler)
            val clock: () -> Instant = { Instant(testScheduler.currentTime + baseTimeMs) }
            val bridge = InMemoryRfcommBridge(scope = this, dispatchers = dispatchers)
            val alice = createNode("Alice", "AA:BB:CC:DD:EE:FF", bridge.aliceTransport, dispatchers, clock)
            val bob = createNode("Bob", "11:22:33:44:55:66", bridge.bobTransport, dispatchers, clock)

            val session = establishAndVerifyInitialSession(alice, bob, clock)
            val context = IntegrationContext(alice = alice, bob = bob, bridge = bridge, session = session)
            val aliceObserverJob = startObserver(this, alice, session.bobPeer, session.aliceConv)
            val bobObserverJob = startObserver(this, bob, session.alicePeer, session.bobConv)
            runCurrent()

            val firstPlaintext = "Hello Bob over fragmented RFCOMM"
            val secondPlaintext = "Hello Alice from Double Ratchet"
            val offlinePlaintext = "Queued offline during link drop"

            verifyBidirectionalMessaging(context, firstPlaintext, secondPlaintext)

            val keepAliveJob = alice.keepAlive.start(this, session.bobPeer, session.aliceConv.id)
            verifyHealthyKeepAliveCycle(alice, session.bobPeer)

            val offlineMessage = verifyLinkDropAndOfflineQueue(context, offlinePlaintext)
            verifyReconnectAndOfflineFlush(context, offlineMessage.id, offlinePlaintext)

            alice.logger.assertNoSensitiveData(firstPlaintext, secondPlaintext, offlinePlaintext)
            bob.logger.assertNoSensitiveData(firstPlaintext, secondPlaintext, offlinePlaintext)

            keepAliveJob.cancel()
            aliceObserverJob.cancel()
            bobObserverJob.cancel()
            bridge.close()
        }

    private suspend fun TestScope.establishAndVerifyInitialSession(
        alice: IntegrationNode,
        bob: IntegrationNode,
        clock: () -> Instant,
    ): InitializedSession {
        val aliceIdentity = alice.initializeIdentity("Alice").getOrThrow()
        val bobIdentity = bob.initializeIdentity("Bob").getOrThrow()
        assertEquals("Alice", aliceIdentity.nickname)
        assertEquals("Bob", bobIdentity.nickname)

        val alicePeer = Peer(aliceIdentity.id, aliceIdentity.nickname, alice.address, lastSeenAt = clock())
        val bobPeer = Peer(bobIdentity.id, bobIdentity.nickname, bob.address, lastSeenAt = clock())

        bob.transport.startServer().getOrThrow()
        val bobAcceptDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                bob.establish.acceptAndEstablish(alicePeer)
            }
        val aliceConv = alice.establish.connectAndEstablish(bobPeer).getOrThrow()
        val bobConv = bobAcceptDeferred.await().getOrThrow()

        assertEquals(SessionState.SECURE_ACTIVE, aliceConv.state)
        assertEquals(SessionState.SECURE_ACTIVE, bobConv.state)
        assertEquals(bobIdentity.identityPublicKey, aliceConv.peer.identityKey)
        assertEquals(aliceIdentity.identityPublicKey, bobConv.peer.identityKey)

        val aliceSafetyNumber = alice.verifySafety.computeSafetyNumber(bobPeer.id).getOrThrow()
        val bobSafetyNumber = bob.verifySafety.computeSafetyNumber(alicePeer.id).getOrThrow()
        val safetyGroups = aliceSafetyNumber.split(' ')
        assertEquals(12, safetyGroups.size)
        assertTrue(safetyGroups.all { group -> group.length == 5 && group.all(Char::isDigit) })
        assertEquals(60, aliceSafetyNumber.count(Char::isDigit))
        assertEquals(aliceSafetyNumber, bobSafetyNumber)

        alice.verifySafety.markPeerVerified(bobPeer.id, verified = true).getOrThrow()
        assertTrue(alice.peerRepo.getPeerById(bobPeer.id).getOrThrow()?.isVerified == true)

        return InitializedSession(alicePeer, bobPeer, aliceConv, bobConv)
    }

    private fun startObserver(
        scope: CoroutineScope,
        node: IntegrationNode,
        peer: Peer,
        conversation: Conversation,
    ): Job =
        node.observeIncoming(
            scope = scope,
            peerId = peer.id,
            conversationId = conversation.id,
            onPongReceived = node.keepAlive::onPongReceived,
        )

    private suspend fun TestScope.verifyBidirectionalMessaging(
        context: IntegrationContext,
        firstPlaintext: String,
        secondPlaintext: String,
    ) {
        val alice = context.alice
        val bob = context.bob
        val session = context.session
        val sentByAlice =
            alice.sendMessage(session.bobPeer.id, session.aliceConv.id, firstPlaintext, ackTimeoutScope = this)
                .getOrThrow()
        assertEquals(MessageStatus.SENT, sentByAlice.status)
        runCurrent()

        assertEquals(MessageStatus.DELIVERED, alice.messageRepo.getById(sentByAlice.id).getOrThrow()?.status)
        val bobReceived = bob.messageRepo.allMessages().single { it.id == sentByAlice.id }
        assertEquals(MessageDirection.INCOMING, bobReceived.direction)
        assertEquals(MessageStatus.DELIVERED, bobReceived.status)
        assertEquals(firstPlaintext, bobReceived.plaintext)

        val sentByBob =
            bob.sendMessage(session.alicePeer.id, session.bobConv.id, secondPlaintext, ackTimeoutScope = this)
                .getOrThrow()
        assertEquals(MessageStatus.SENT, sentByBob.status)
        runCurrent()

        assertEquals(MessageStatus.DELIVERED, bob.messageRepo.getById(sentByBob.id).getOrThrow()?.status)
        val aliceReceived = alice.messageRepo.allMessages().single { it.id == sentByBob.id }
        assertEquals(MessageDirection.INCOMING, aliceReceived.direction)
        assertEquals(MessageStatus.DELIVERED, aliceReceived.status)
        assertEquals(secondPlaintext, aliceReceived.plaintext)

        assertFalse(context.bridge.wireContainsPlaintext(firstPlaintext))
        assertFalse(context.bridge.wireContainsPlaintext(secondPlaintext))
    }

    private suspend fun TestScope.verifyHealthyKeepAliveCycle(
        alice: IntegrationNode,
        bobPeer: Peer,
    ) {
        advanceTimeBy(SessionConstants.KEEP_ALIVE_INTERVAL_MS)
        runCurrent()
        assertEquals(
            SessionState.SECURE_ACTIVE,
            alice.peerRepo.getConversationByPeer(bobPeer.id).getOrThrow()?.state,
        )
    }

    private suspend fun TestScope.verifyLinkDropAndOfflineQueue(
        context: IntegrationContext,
        offlinePlaintext: String,
    ) = run {
        val alice = context.alice
        val session = context.session
        context.bridge.simulateLinkDrop()
        advanceTimeBy(SessionConstants.KEEP_ALIVE_INTERVAL_MS)
        runCurrent()
        advanceTimeBy(SessionConstants.KEEP_ALIVE_TIMEOUT_MS)
        runCurrent()

        assertEquals(
            SessionState.RECONNECTING,
            alice.peerRepo.getConversationByPeer(session.bobPeer.id).getOrThrow()?.state,
        )

        val offlineMessage =
            alice.sendMessage(
                peerId = session.bobPeer.id,
                conversationId = session.aliceConv.id,
                plaintext = offlinePlaintext,
                ackTimeoutScope = this,
            ).getOrThrow()
        assertEquals(MessageStatus.PENDING, offlineMessage.status)

        advanceTimeBy(SessionConstants.INITIAL_RECONNECT_BACKOFF_MS)
        runCurrent()
        assertEquals(
            SessionState.RECONNECTING,
            alice.peerRepo.getConversationByPeer(session.bobPeer.id).getOrThrow()?.state,
        )
        assertEquals(MessageStatus.PENDING, alice.messageRepo.getById(offlineMessage.id).getOrThrow()?.status)
        offlineMessage
    }

    private suspend fun TestScope.verifyReconnectAndOfflineFlush(
        context: IntegrationContext,
        offlineMessageId: MessageId,
        offlinePlaintext: String,
    ) {
        val alice = context.alice
        val bob = context.bob
        val session = context.session
        bob.crypto.clearSession(session.alicePeer.id).getOrThrow()
        bob.transport.disconnect().getOrThrow()
        context.bridge.restoreLink()
        bob.transport.startServer().getOrThrow()
        val bobReconnectDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                bob.establish.acceptAndEstablish(session.alicePeer)
            }

        advanceTimeBy(SessionConstants.INITIAL_RECONNECT_BACKOFF_MS * 2L)
        runCurrent()

        val bobReconnectedConv = bobReconnectDeferred.await().getOrThrow()
        assertEquals(SessionState.SECURE_ACTIVE, bobReconnectedConv.state)
        assertEquals(
            SessionState.SECURE_ACTIVE,
            alice.peerRepo.getConversationByPeer(session.bobPeer.id).getOrThrow()?.state,
        )
        assertEquals(MessageStatus.DELIVERED, alice.messageRepo.getById(offlineMessageId).getOrThrow()?.status)
        val bobDeliveredOffline = bob.messageRepo.allMessages().single { it.id == offlineMessageId }
        assertEquals(MessageDirection.INCOMING, bobDeliveredOffline.direction)
        assertEquals(MessageStatus.DELIVERED, bobDeliveredOffline.status)
        assertEquals(offlinePlaintext, bobDeliveredOffline.plaintext)
        assertFalse(context.bridge.wireContainsPlaintext(offlinePlaintext))
    }

    private fun createNode(
        name: String,
        addressValue: String,
        transport: BluetoothTransport,
        dispatchers: CoroutineDispatchers,
        clock: () -> Instant,
    ): IntegrationNode {
        val keyStore = FakeKeyStoreProvider()
        val crypto = CryptoServiceImpl(keyStore)
        val identityRepo = InMemoryIdentityRepository()
        val peerRepo = InMemoryPeerRepository()
        val messageRepo = InMemoryMessageRepository()
        val logger = FakeAppLogger()
        val packetSender = EncryptedPacketSender(cryptoService = crypto, bluetoothTransport = transport)
        val establish =
            EstablishSecureSessionUseCase(transport, crypto, peerRepo, dispatchers, logger, clock)
        val flushPending =
            FlushPendingMessagesUseCase(messageRepo, peerRepo, transport, packetSender, dispatchers, logger)
        return IntegrationNode(
            name = name,
            address = BluetoothAddress(addressValue),
            transport = transport,
            crypto = crypto,
            peerRepo = peerRepo,
            messageRepo = messageRepo,
            logger = logger,
            initializeIdentity = InitializeIdentityUseCase(identityRepo, keyStore, dispatchers, logger, clock),
            verifySafety = VerifyPeerSafetyNumberUseCase(identityRepo, peerRepo, crypto, logger),
            establish = establish,
            sendMessage =
                SendMessageUseCase(messageRepo, peerRepo, transport, packetSender, dispatchers, logger, clock),
            observeIncoming =
                ObserveIncomingFramesUseCase(transport, crypto, messageRepo, dispatchers, logger, clock),
            keepAlive =
                ManageKeepAliveAndReconnectUseCase(
                    transport,
                    crypto,
                    peerRepo,
                    establish,
                    flushPending,
                    dispatchers,
                    logger,
                    clock,
                ),
        )
    }

    private data class IntegrationContext(
        val alice: IntegrationNode,
        val bob: IntegrationNode,
        val bridge: InMemoryRfcommBridge,
        val session: InitializedSession,
    )

    private data class InitializedSession(
        val alicePeer: Peer,
        val bobPeer: Peer,
        val aliceConv: Conversation,
        val bobConv: Conversation,
    )

    private data class IntegrationNode(
        val name: String,
        val address: BluetoothAddress,
        val transport: BluetoothTransport,
        val crypto: CryptoServiceImpl,
        val peerRepo: InMemoryPeerRepository,
        val messageRepo: InMemoryMessageRepository,
        val logger: FakeAppLogger,
        val initializeIdentity: InitializeIdentityUseCase,
        val verifySafety: VerifyPeerSafetyNumberUseCase,
        val establish: EstablishSecureSessionUseCase,
        val sendMessage: SendMessageUseCase,
        val observeIncoming: ObserveIncomingFramesUseCase,
        val keepAlive: ManageKeepAliveAndReconnectUseCase,
    )

    private class InMemoryRfcommBridge(
        scope: CoroutineScope,
        dispatchers: CoroutineDispatchers,
    ) {
        private var linkUp: Boolean = true
        private var linkBlackedOut: Boolean = false
        private val capturedWireFrames: MutableList<ByteArray> = mutableListOf()

        val aliceTransport: FramedRfcommTransport =
            FramedRfcommTransport(
                scope = scope,
                dispatchers = dispatchers,
                onConnectRequested = { connectAliceToBob() },
                onPacketSent = { packet -> transmit(fromAlice = true, packet = packet) },
            )

        val bobTransport: FramedRfcommTransport =
            FramedRfcommTransport(
                scope = scope,
                dispatchers = dispatchers,
                onConnectRequested = { TransportDisconnectedError("Responder does not dial out.").asFailure() },
                onPacketSent = { packet -> transmit(fromAlice = false, packet = packet) },
            )

        fun simulateLinkDrop() {
            linkUp = false
            linkBlackedOut = true
            bobTransport.stopListening()
        }

        fun restoreLink() {
            linkUp = true
            linkBlackedOut = false
        }

        fun wireContainsPlaintext(plaintext: String): Boolean {
            val needle = plaintext.encodeToByteArray()
            return capturedWireFrames.any { frame -> containsSubarray(frame, needle) }
        }

        fun close() {
            aliceTransport.close()
            bobTransport.close()
        }

        private fun connectAliceToBob(): Result<Unit> =
            if (!linkUp || !bobTransport.isListening) {
                TransportDisconnectedError("RFCOMM peer is unreachable.").asFailure()
            } else {
                aliceTransport.markHandshaking()
                bobTransport.markHandshaking()
                Result.success(Unit)
            }

        private fun transmit(
            fromAlice: Boolean,
            packet: Packet,
        ): Result<Unit> {
            val packetBytes = PacketCodec.encode(packet)
            return FrameEncoder.encode(packetBytes).map { frameBytes ->
                capturedWireFrames.add(frameBytes)
                if (!linkBlackedOut && linkUp) {
                    val receiver = if (fromAlice) bobTransport else aliceTransport
                    for (chunk in fragmentFrameBytes(frameBytes)) {
                        receiver.enqueueWireChunk(chunk)
                    }
                }
            }
        }

        private fun fragmentFrameBytes(frameBytes: ByteArray): List<ByteArray> {
            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            var stepIndex = 0
            val pattern = intArrayOf(1, 3, 7, 11)
            while (offset < frameBytes.size) {
                val chunkSize = minOf(pattern[stepIndex % pattern.size], frameBytes.size - offset)
                chunks.add(frameBytes.copyOfRange(offset, offset + chunkSize))
                offset += chunkSize
                stepIndex += 1
            }
            return chunks
        }

        private fun containsSubarray(
            haystack: ByteArray,
            needle: ByteArray,
        ): Boolean =
            needle.isNotEmpty() &&
                needle.size <= haystack.size &&
                (0..(haystack.size - needle.size)).any { start ->
                    needle.indices.all { index -> haystack[start + index] == needle[index] }
                }
    }

    private class FramedRfcommTransport(
        scope: CoroutineScope,
        dispatchers: CoroutineDispatchers,
        private val onConnectRequested: () -> Result<Unit>,
        private val onPacketSent: (Packet) -> Result<Unit>,
    ) : BluetoothTransport {
        private val mutableSessionState: MutableStateFlow<SessionState> =
            MutableStateFlow(SessionState.DISCONNECTED)
        private val mutableIncomingPackets: MutableSharedFlow<Packet> =
            MutableSharedFlow(extraBufferCapacity = 64)
        private val wireChunkChannel: Channel<ByteArray> = Channel(Channel.UNLIMITED)
        private val frameDecoder: FrameDecoder = FrameDecoder()

        override val sessionState: StateFlow<SessionState> = mutableSessionState.asStateFlow()
        override val incomingPackets: Flow<Packet> = mutableIncomingPackets.asSharedFlow()

        var isListening: Boolean = false
            private set

        private val readerJob: Job =
            scope.launch(dispatchers.io) {
                for (chunk in wireChunkChannel) {
                    val payloads = frameDecoder.feed(chunk).getOrThrow()
                    for (payload in payloads) {
                        val packet = PacketCodec.decode(payload).getOrThrow()
                        mutableIncomingPackets.emit(packet)
                        yield()
                    }
                }
            }

        fun markHandshaking() {
            mutableSessionState.value = SessionState.HANDSHAKING
        }

        fun stopListening() {
            isListening = false
        }

        fun enqueueWireChunk(chunk: ByteArray) {
            wireChunkChannel.trySend(chunk)
        }

        fun close() {
            readerJob.cancel()
            wireChunkChannel.close()
            frameDecoder.reset()
        }

        override suspend fun startServer(): Result<Unit> {
            isListening = true
            mutableSessionState.value = SessionState.CONNECTING
            return Result.success(Unit)
        }

        override suspend fun connect(address: BluetoothAddress): Result<Unit> {
            val outcome = onConnectRequested()
            if (outcome.isFailure) {
                mutableSessionState.value = SessionState.DISCONNECTED
            }
            return outcome
        }

        override suspend fun send(packet: Packet): Result<Unit> {
            val state = mutableSessionState.value
            return if (state != SessionState.HANDSHAKING && state != SessionState.SECURE_ACTIVE) {
                TransportDisconnectedError("RFCOMM link is not open.").asFailure()
            } else {
                onPacketSent(packet)
            }
        }

        override suspend fun disconnect(): Result<Unit> {
            isListening = false
            mutableSessionState.value = SessionState.DISCONNECTED
            while (wireChunkChannel.tryReceive().isSuccess) {
                Unit
            }
            frameDecoder.reset()
            return Result.success(Unit)
        }
    }
}
