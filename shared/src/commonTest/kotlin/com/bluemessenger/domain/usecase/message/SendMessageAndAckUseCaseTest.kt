package com.bluemessenger.domain.usecase.message

import com.bluemessenger.crypto.CryptoServiceImpl
import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.constants.SessionConstants
import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.InvalidInputError
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.transport.BluetoothTransport
import com.bluemessenger.domain.ports.transport.model.AckPacket
import com.bluemessenger.domain.ports.transport.model.EncryptedPayloadPacket
import com.bluemessenger.domain.ports.transport.model.Packet
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.usecase.FakeAppLogger
import com.bluemessenger.domain.usecase.FakeBluetoothTransport
import com.bluemessenger.domain.usecase.FakeKeyStoreProvider
import com.bluemessenger.domain.usecase.InMemoryMessageRepository
import com.bluemessenger.domain.usecase.PairedSessionHarness
import com.bluemessenger.domain.usecase.PeerSideFixture
import com.bluemessenger.domain.usecase.createPairedActiveSession
import com.bluemessenger.domain.usecase.message.util.MessagePayloadCodec
import com.bluemessenger.domain.usecase.testDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SendMessageAndAckUseCaseTest {
    @Test
    fun `encrypts, transmits, receives, stores idempotently, and marks message DELIVERED on ACK`() =
        runTest {
            val harness = createPairedActiveSession()
            val dispatchers = testDispatchers(testScheduler)
            val localClockMs = 1_700_000_000_100L
            val aliceObserver = buildObserver(harness.alice, dispatchers) { Instant(localClockMs) }
            val bobObserver = buildObserver(harness.bob, dispatchers) { Instant(localClockMs - 500L) }

            val aliceJob = aliceObserver(this, harness.bobPeer.id, harness.aliceConversation.id)
            val bobJob = bobObserver(this, harness.alicePeer.id, harness.bobConversation.id)
            runCurrent()

            val sendUseCase = buildSendUseCase(harness.alice, dispatchers) { Instant(localClockMs) }
            val secretPlaintext = "Top secret Bluetooth chat!"
            val sent = sendUseCase(harness.bobPeer.id, harness.aliceConversation.id, secretPlaintext).getOrThrow()
            runCurrent()

            val alicePersisted = harness.alice.messageRepo.getById(sent.id).getOrThrow()!!
            assertEquals(MessageStatus.DELIVERED, alicePersisted.status)
            assertEquals(MessageDirection.OUTGOING, alicePersisted.direction)

            val bobPersisted = harness.bob.messageRepo.getById(sent.id).getOrThrow()!!
            assertEquals(secretPlaintext, bobPersisted.plaintext)
            assertEquals(MessageDirection.INCOMING, bobPersisted.direction)
            assertEquals(MessageStatus.DELIVERED, bobPersisted.status)
            assertTrue(bobPersisted.deliveredAt!! >= bobPersisted.sentAt)

            harness.alice.logger.assertNoSensitiveData(secretPlaintext)
            harness.bob.logger.assertNoSensitiveData(secretPlaintext)
            aliceJob.cancel()
            bobJob.cancel()
        }

    @Test
    fun `queues message as PENDING when offline or targeting offline peer while other peer active`() =
        runTest {
            val harness = createPairedActiveSession()
            val dispatchers = testDispatchers(testScheduler)
            val now = Instant(1_700_000_000_000L)
            val sendUseCase = buildSendUseCase(harness.alice, dispatchers) { now }

            val blankFailure = sendUseCase(harness.bobPeer.id, harness.aliceConversation.id, "   ")
            assertIs<InvalidInputError>(blankFailure.exceptionOrNull()?.toDomainErrorOrNull())

            val offlinePeerId = PeerId.generate(Instant(now.epochMillis + 50L))
            val offlineConvId = ConversationId.generate(Instant(now.epochMillis + 50L))
            val sentCountBefore = harness.alice.transport.sentPackets.size
            val queuedWhileOtherActive = sendUseCase(offlinePeerId, offlineConvId, "Peer C offline").getOrThrow()
            assertEquals(MessageStatus.PENDING, queuedWhileOtherActive.status)
            assertEquals(sentCountBefore, harness.alice.transport.sentPackets.size)

            harness.alice.transport.disconnect().getOrThrow()
            val queuedWhileDisconnected =
                sendUseCase(harness.bobPeer.id, harness.aliceConversation.id, "Offline hello").getOrThrow()
            assertEquals(MessageStatus.PENDING, queuedWhileDisconnected.status)
            assertEquals(sentCountBefore, harness.alice.transport.sentPackets.size)
        }

    @Test
    fun `marks message FAILED on transport write failure or ACK timeout while leaving DELIVERED intact`() =
        runTest {
            val harness = createPairedActiveSession()
            val dispatchers = testDispatchers(testScheduler)
            val now = Instant(1_700_000_000_000L)
            harness.alice.transport.onSend = { Result.success(Unit) }
            val sendUseCase = buildSendUseCase(harness.alice, dispatchers) { now }

            val unacked =
                sendUseCase(
                    harness.bobPeer.id,
                    harness.aliceConversation.id,
                    "Wait for ACK",
                    ackTimeoutScope = this,
                ).getOrThrow()
            assertEquals(MessageStatus.SENT, unacked.status)
            advanceTimeBy(SessionConstants.ACK_TIMEOUT_MS)
            runCurrent()
            assertEquals(MessageStatus.FAILED, harness.alice.messageRepo.getById(unacked.id).getOrThrow()?.status)

            val deliveredMsg =
                sendUseCase(
                    harness.bobPeer.id,
                    harness.aliceConversation.id,
                    "Already ACKed",
                    ackTimeoutScope = this,
                ).getOrThrow()
            harness.alice.messageRepo.updateStatus(
                deliveredMsg.id,
                MessageStatus.DELIVERED,
                deliveredAt = Instant(now.epochMillis + 10L),
            ).getOrThrow()
            advanceTimeBy(SessionConstants.ACK_TIMEOUT_MS)
            runCurrent()
            assertEquals(
                MessageStatus.DELIVERED,
                harness.alice.messageRepo.getById(deliveredMsg.id).getOrThrow()?.status,
            )

            harness.alice.transport.failSendAfterCount = harness.alice.transport.sentPackets.size
            val sendFailure = sendUseCase(harness.bobPeer.id, harness.aliceConversation.id, "Fails immediately")
            assertIs<TransportDisconnectedError>(sendFailure.exceptionOrNull()?.toDomainErrorOrNull())
            assertEquals(MessageStatus.FAILED, harness.alice.messageRepo.allMessages().last().status)
        }

    @Test
    fun `observer deduplicates repeated message and re-sends ACK while surviving corrupted payloads`() =
        runTest {
            val harness = createPairedActiveSession()
            val dispatchers = testDispatchers(testScheduler)
            val now = Instant(1_700_000_000_000L)
            val bobAckSent = captureAcks(harness.bob.transport)
            val bobObserver = buildObserver(harness.bob, dispatchers) { now }
            val observerJob = bobObserver(this, harness.alicePeer.id, harness.bobConversation.id)
            runCurrent()

            val msgId = MessageId.generate(now)
            val payloadBytes = MessagePayloadCodec.encode(msgId, now, "Idempotent text")
            val malformedInnerPacket = encodeEncryptedPacket(harness, 0L, byteArrayOf(0x01, 0x02))
            val firstPacket = encodeEncryptedPacket(harness, 1L, payloadBytes)
            val retryPacket = encodeEncryptedPacket(harness, 2L, payloadBytes)

            harness.bob.transport.emitIncomingPacket(
                EncryptedPayloadPacket(sequenceNumber = 0L, salt = ByteArray(0), ciphertext = byteArrayOf(0x00, 0x01)),
            )
            harness.bob.transport.emitIncomingPacket(malformedInnerPacket)
            harness.bob.transport.emitIncomingPacket(firstPacket)
            harness.bob.transport.emitIncomingPacket(retryPacket)
            runCurrent()

            assertEquals(1, harness.bob.messageRepo.allMessages().size)
            assertEquals(2, bobAckSent.size)
            assertEquals(msgId, bobAckSent[0].messageId)
            assertEquals(msgId, bobAckSent[1].messageId)
            assertTrue(harness.bob.logger.entries.any { it.level == FakeAppLogger.Level.WARN })
            assertTrue(harness.bob.logger.entries.any { it.level == FakeAppLogger.Level.ERROR })
            observerJob.cancel()
        }

    @Test
    fun `observer skips ACK on persistence failure and drops tampered ciphertexts safely`() =
        runTest {
            val harness = createPairedActiveSession()
            val dispatchers = testDispatchers(testScheduler)
            val now = Instant(1_700_000_000_000L)
            val bobAckSent = captureAcks(harness.bob.transport)
            val bobObserver = buildObserver(harness.bob, dispatchers) { now }
            val observerJob = bobObserver(this, harness.alicePeer.id, harness.bobConversation.id)
            runCurrent()

            val msgId1 = MessageId.generate(now)
            val packet1 = encodeEncryptedPacket(harness, 1L, MessagePayloadCodec.encode(msgId1, now, "DB fail"))
            harness.bob.messageRepo.saveFailure = StorageError("sqlite busy")
            harness.bob.transport.emitIncomingPacket(packet1)
            runCurrent()
            assertTrue(bobAckSent.isEmpty())
            assertTrue(harness.bob.messageRepo.allMessages().isEmpty())

            harness.bob.messageRepo.saveFailure = null
            val msgId2 = MessageId.generate(Instant(now.epochMillis + 1L))
            val validCipher =
                harness.alice.crypto.encrypt(
                    harness.bobPeer.id,
                    MessagePayloadCodec.encode(msgId2, now, "Tampered"),
                    MessagePayloadCodec.encodeSequenceNumberAad(2L),
                ).getOrThrow()
            val wrongAadPacket =
                EncryptedPayloadPacket(
                    sequenceNumber = 99L,
                    salt = ByteArray(0),
                    ciphertext = validCipher,
                )
            harness.bob.transport.emitIncomingPacket(wrongAadPacket)
            runCurrent()
            assertTrue(bobAckSent.isEmpty())
            assertTrue(harness.bob.messageRepo.allMessages().isEmpty())

            val msgId3 = MessageId.generate(Instant(now.epochMillis + 2L))
            val validAfterTamper =
                encodeEncryptedPacket(harness, 3L, MessagePayloadCodec.encode(msgId3, now, "Still alive"))
            harness.bob.transport.emitIncomingPacket(validAfterTamper)
            runCurrent()
            assertEquals(1, harness.bob.messageRepo.allMessages().size)
            assertEquals(listOf(AckPacket(msgId3)), bobAckSent)
            observerJob.cancel()
        }

    @Test
    fun `observer ignores cross-conversation or incoming-direction ACK packets`() =
        runTest {
            val harness = createPairedActiveSession()
            val dispatchers = testDispatchers(testScheduler)
            val now = Instant(1_700_000_000_000L)
            val otherConvMsg =
                Message(
                    id = MessageId.generate(now),
                    conversationId = ConversationId.generate(Instant(now.epochMillis + 1L)),
                    plaintext = "Other conversation",
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.SENT,
                    sentAt = now,
                )
            val incomingMsg =
                Message(
                    id = MessageId.generate(Instant(now.epochMillis + 2L)),
                    conversationId = harness.aliceConversation.id,
                    plaintext = "Already incoming",
                    direction = MessageDirection.INCOMING,
                    status = MessageStatus.SENT,
                    sentAt = now,
                )
            harness.alice.messageRepo.saveIdempotent(otherConvMsg).getOrThrow()
            harness.alice.messageRepo.saveIdempotent(incomingMsg).getOrThrow()

            val observer = buildObserver(harness.alice, dispatchers) { now }
            val job = observer(this, harness.bobPeer.id, harness.aliceConversation.id)
            runCurrent()

            harness.alice.transport.emitIncomingPacket(AckPacket(otherConvMsg.id))
            harness.alice.transport.emitIncomingPacket(AckPacket(incomingMsg.id))
            runCurrent()

            assertEquals(MessageStatus.SENT, harness.alice.messageRepo.getById(otherConvMsg.id).getOrThrow()?.status)
            assertEquals(MessageStatus.SENT, harness.alice.messageRepo.getById(incomingMsg.id).getOrThrow()?.status)
            assertTrue(harness.alice.logger.entries.count { it.level == FakeAppLogger.Level.WARN } >= 2)
            job.cancel()
        }

    @Test
    fun `combined flush and live send produce strictly monotonic non-colliding sequence numbers`() =
        runTest {
            val harness = createPairedActiveSession()
            val dispatchers = testDispatchers(testScheduler)
            val now = Instant(1_700_000_000_000L)
            harness.alice.transport.onSend = { Result.success(Unit) }

            val offline1 =
                Message(
                    MessageId.generate(Instant(now.epochMillis + 1L)),
                    harness.aliceConversation.id,
                    "Offline 1",
                    MessageDirection.OUTGOING,
                    MessageStatus.PENDING,
                    Instant(now.epochMillis + 1L),
                )
            val offline2 =
                Message(
                    MessageId.generate(Instant(now.epochMillis + 2L)),
                    harness.aliceConversation.id,
                    "Offline 2",
                    MessageDirection.OUTGOING,
                    MessageStatus.PENDING,
                    Instant(now.epochMillis + 2L),
                )
            harness.alice.messageRepo.saveIdempotent(offline1).getOrThrow()
            harness.alice.messageRepo.saveIdempotent(offline2).getOrThrow()

            val sentBefore = harness.alice.transport.sentPackets.size
            val flushUseCase =
                FlushPendingMessagesUseCase(
                    harness.alice.messageRepo,
                    harness.alice.peerRepo,
                    harness.alice.transport,
                    harness.alice.packetSender,
                    dispatchers,
                    harness.alice.logger,
                )
            val sendUseCase = buildSendUseCase(harness.alice, dispatchers) { now }

            assertEquals(2, flushUseCase(harness.bobPeer.id, harness.aliceConversation.id).getOrThrow())
            sendUseCase(harness.bobPeer.id, harness.aliceConversation.id, "Live 3").getOrThrow()
            sendUseCase(harness.bobPeer.id, harness.aliceConversation.id, "Live 4").getOrThrow()

            val emittedSequences =
                harness.alice.transport.sentPackets
                    .drop(sentBefore)
                    .map { it as EncryptedPayloadPacket }
                    .map { it.sequenceNumber }
            assertEquals(listOf(0L, 1L, 2L, 3L), emittedSequences)
        }

    @Test
    fun `observer catches stream exception and logs structured error without crashing`() =
        runTest {
            val dispatchers = testDispatchers(testScheduler)
            val failingTransport =
                object : BluetoothTransport {
                    override val sessionState = FakeBluetoothTransport(SessionState.HANDSHAKING).sessionState
                    override val incomingPackets = flow<Packet> { error("rfcomm read broken") }

                    override suspend fun startServer() = Result.success(Unit)

                    override suspend fun connect(address: BluetoothAddress) = Result.success(Unit)

                    override suspend fun send(packet: Packet) = Result.success(Unit)

                    override suspend fun disconnect() = Result.success(Unit)
                }
            val logger = FakeAppLogger()
            val observer =
                ObserveIncomingFramesUseCase(
                    failingTransport,
                    CryptoServiceImpl(FakeKeyStoreProvider(IdentityKeyPair.generate())),
                    InMemoryMessageRepository(),
                    dispatchers,
                    logger,
                ) { Instant(1_000L) }

            val now = Instant(1_000L)
            val job = observer(this, PeerId.generate(now), ConversationId.generate(now))
            runCurrent()

            assertTrue(job.isCompleted)
            assertTrue(logger.entries.any { it.level == FakeAppLogger.Level.ERROR })
        }

    @Test
    fun `retryFailedMessage preserves MessageId, delivers active, queues offline, and rejects bad state`() =
        runTest {
            val harness = createPairedActiveSession()
            val dispatchers = testDispatchers(testScheduler)
            val now = Instant(1_700_000_000_000L)
            harness.alice.transport.onSend = { Result.success(Unit) }
            val sendUseCase = buildSendUseCase(harness.alice, dispatchers) { now }

            val failedMsg =
                sendUseCase(
                    harness.bobPeer.id,
                    harness.aliceConversation.id,
                    "Retry me",
                    ackTimeoutScope = this,
                ).getOrThrow()
            advanceTimeBy(SessionConstants.ACK_TIMEOUT_MS)
            runCurrent()
            assertEquals(MessageStatus.FAILED, harness.alice.messageRepo.getById(failedMsg.id).getOrThrow()?.status)

            val bobJob =
                buildObserver(harness.bob, dispatchers) { now }(
                    this,
                    harness.alicePeer.id,
                    harness.bobConversation.id,
                )
            val aliceJob =
                buildObserver(harness.alice, dispatchers) { now }(
                    this,
                    harness.bobPeer.id,
                    harness.aliceConversation.id,
                )
            harness.wireDuplexTransports()
            runCurrent()

            val retried =
                sendUseCase.retryFailedMessage(harness.bobPeer.id, failedMsg.id, ackTimeoutScope = this)
                    .getOrThrow()
            assertEquals(failedMsg.id, retried.id)
            runCurrent()
            assertEquals(MessageStatus.DELIVERED, harness.alice.messageRepo.getById(failedMsg.id).getOrThrow()?.status)

            val deliveredRetryFailure = sendUseCase.retryFailedMessage(harness.bobPeer.id, failedMsg.id)
            assertIs<InvalidStateTransitionError>(deliveredRetryFailure.exceptionOrNull()?.toDomainErrorOrNull())

            val missingRetryFailure = sendUseCase.retryFailedMessage(harness.bobPeer.id, MessageId.generate(now))
            assertIs<StorageError>(missingRetryFailure.exceptionOrNull()?.toDomainErrorOrNull())

            harness.alice.transport.failSendAfterCount = harness.alice.transport.sentPackets.size
            val immediateFail = sendUseCase(harness.bobPeer.id, harness.aliceConversation.id, "Offline retry")
            assertTrue(immediateFail.isFailure)
            val failedOfflineId = harness.alice.messageRepo.allMessages().last().id
            harness.alice.transport.disconnect().getOrThrow()

            val queuedRetry = sendUseCase.retryFailedMessage(harness.bobPeer.id, failedOfflineId).getOrThrow()
            assertEquals(failedOfflineId, queuedRetry.id)
            assertEquals(MessageStatus.PENDING, queuedRetry.status)

            bobJob.cancel()
            aliceJob.cancel()
        }

    @Test
    fun `retryFailedMessage rejects cross-peer retry even when target peer has SECURE_ACTIVE`() =
        runTest {
            val harness = createPairedActiveSession()
            val dispatchers = testDispatchers(testScheduler)
            val now = Instant(1_700_000_000_000L)
            val sendUseCase = buildSendUseCase(harness.alice, dispatchers) { now }

            val otherPeer =
                Peer(
                    PeerId.generate(Instant(now.epochMillis + 99L)),
                    "Carol",
                    BluetoothAddress("22:33:44:55:66:77"),
                    lastSeenAt = now,
                )
            val otherConv =
                Conversation(
                    id = ConversationId.generate(Instant(now.epochMillis + 99L)),
                    peer = otherPeer,
                    state = SessionState.DISCONNECTED,
                    updatedAt = now,
                ).transitionSessionState(SessionState.CONNECTING, now).getOrThrow()
                    .transitionSessionState(SessionState.HANDSHAKING, now).getOrThrow()
                    .transitionSessionState(SessionState.SECURE_ACTIVE, now).getOrThrow()
            harness.alice.peerRepo.upsertConversation(otherConv).getOrThrow()
            harness.alice.transport.failSendAfterCount = harness.alice.transport.sentPackets.size
            assertTrue(sendUseCase(harness.bobPeer.id, harness.aliceConversation.id, "Owned by Bob").isFailure)
            val bobFailedId = harness.alice.messageRepo.allMessages().last().id
            harness.alice.transport.failSendAfterCount = null

            val packetsBeforeCrossRetry = harness.alice.transport.sentPackets.size
            val crossPeerRetry = sendUseCase.retryFailedMessage(otherPeer.id, bobFailedId)
            assertIs<InvalidStateTransitionError>(crossPeerRetry.exceptionOrNull()?.toDomainErrorOrNull())
            assertEquals(packetsBeforeCrossRetry, harness.alice.transport.sentPackets.size)
            assertEquals(MessageStatus.FAILED, harness.alice.messageRepo.getById(bobFailedId).getOrThrow()?.status)
        }

    private fun buildSendUseCase(
        side: PeerSideFixture,
        dispatchers: CoroutineDispatchers,
        clock: () -> Instant,
    ): SendMessageUseCase =
        SendMessageUseCase(
            side.messageRepo,
            side.peerRepo,
            side.transport,
            side.packetSender,
            dispatchers,
            side.logger,
            clock,
        )

    private fun buildObserver(
        side: PeerSideFixture,
        dispatchers: CoroutineDispatchers,
        clock: () -> Instant,
    ): ObserveIncomingFramesUseCase =
        ObserveIncomingFramesUseCase(
            side.transport,
            side.crypto,
            side.messageRepo,
            dispatchers,
            side.logger,
            clock,
        )

    private fun captureAcks(transport: FakeBluetoothTransport): MutableList<AckPacket> {
        val acks = mutableListOf<AckPacket>()
        transport.onSend = { packet ->
            if (packet is AckPacket) acks.add(packet)
            Result.success(Unit)
        }
        return acks
    }

    private suspend fun encodeEncryptedPacket(
        harness: PairedSessionHarness,
        sequenceNumber: Long,
        payloadBytes: ByteArray,
    ): EncryptedPayloadPacket {
        val ciphertext =
            harness.alice.crypto.encrypt(
                harness.bobPeer.id,
                payloadBytes,
                MessagePayloadCodec.encodeSequenceNumberAad(sequenceNumber),
            ).getOrThrow()
        return EncryptedPayloadPacket(
            sequenceNumber = sequenceNumber,
            salt = ByteArray(0),
            ciphertext = ciphertext,
        )
    }
}
