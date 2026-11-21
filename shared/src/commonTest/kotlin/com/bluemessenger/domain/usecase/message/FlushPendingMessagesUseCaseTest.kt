package com.bluemessenger.domain.usecase.message

import com.bluemessenger.crypto.CryptoServiceImpl
import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.usecase.FakeAppLogger
import com.bluemessenger.domain.usecase.FakeBluetoothTransport
import com.bluemessenger.domain.usecase.FakeKeyStoreProvider
import com.bluemessenger.domain.usecase.InMemoryMessageRepository
import com.bluemessenger.domain.usecase.InMemoryPeerRepository
import com.bluemessenger.domain.usecase.createPairedActiveSession
import com.bluemessenger.domain.usecase.message.util.EncryptedPacketSender
import com.bluemessenger.domain.usecase.testDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FlushPendingMessagesUseCaseTest {
    @Test
    fun `fails fast with TransportDisconnectedError when session is not SECURE_ACTIVE`() =
        runTest {
            val dispatchers = testDispatchers(testScheduler)
            val messageRepo = InMemoryMessageRepository()
            val peerRepo = InMemoryPeerRepository()
            val transport = FakeBluetoothTransport(SessionState.DISCONNECTED)
            val crypto = CryptoServiceImpl(FakeKeyStoreProvider(IdentityKeyPair.generate()))
            val packetSender = EncryptedPacketSender(crypto, transport)
            val flushUseCase =
                FlushPendingMessagesUseCase(
                    messageRepo,
                    peerRepo,
                    transport,
                    packetSender,
                    dispatchers,
                    FakeAppLogger(),
                )
            val now = Instant(1_700_000_000_000L)

            val result = flushUseCase(PeerId.generate(now), ConversationId.generate(now))
            assertTrue(result.isFailure)
            assertIs<TransportDisconnectedError>(result.exceptionOrNull()?.toDomainErrorOrNull())
        }

    @Test
    fun `flushes only PENDING outgoing messages in chronological order and skips FAILED or DELIVERED`() =
        runTest {
            val harness = createPairedActiveSession()
            val dispatchers = testDispatchers(testScheduler)
            val now = Instant(1_700_000_000_000L)
            val bobObserver =
                ObserveIncomingFramesUseCase(
                    harness.bob.transport,
                    harness.bob.crypto,
                    harness.bob.messageRepo,
                    dispatchers,
                    harness.bob.logger,
                ) { now }
            val bobJob = bobObserver(this, harness.alicePeer.id, harness.bobConversation.id)
            runCurrent()

            val msgLate = outgoingMessage(harness.aliceConversation.id, "Second pending", now.epochMillis + 30L)
            val msgEarly = outgoingMessage(harness.aliceConversation.id, "First pending", now.epochMillis + 10L)
            val msgFailed =
                outgoingMessage(
                    harness.aliceConversation.id,
                    "Already failed",
                    now.epochMillis + 20L,
                    status = MessageStatus.FAILED,
                )
            harness.alice.messageRepo.saveIdempotent(msgLate).getOrThrow()
            harness.alice.messageRepo.saveIdempotent(msgEarly).getOrThrow()
            harness.alice.messageRepo.saveIdempotent(msgFailed).getOrThrow()

            val flushUseCase =
                FlushPendingMessagesUseCase(
                    harness.alice.messageRepo,
                    harness.alice.peerRepo,
                    harness.alice.transport,
                    harness.alice.packetSender,
                    dispatchers,
                    harness.alice.logger,
                )
            val flushedCount = flushUseCase(harness.bobPeer.id, harness.aliceConversation.id).getOrThrow()
            runCurrent()

            assertEquals(2, flushedCount)
            assertEquals(MessageStatus.SENT, harness.alice.messageRepo.getById(msgEarly.id).getOrThrow()?.status)
            assertEquals(MessageStatus.SENT, harness.alice.messageRepo.getById(msgLate.id).getOrThrow()?.status)
            assertEquals(MessageStatus.FAILED, harness.alice.messageRepo.getById(msgFailed.id).getOrThrow()?.status)
            assertEquals(
                listOf("First pending", "Second pending"),
                harness.bob.messageRepo.allMessages().sortedBy { it.sentAt }.map { it.plaintext },
            )
            bobJob.cancel()
        }

    @Test
    fun `stops flush immediately on first transport error and leaves remaining messages PENDING`() =
        runTest {
            val harness = createPairedActiveSession()
            val dispatchers = testDispatchers(testScheduler)
            val now = Instant(1_700_000_000_000L)
            harness.alice.transport.failSendAfterCount = harness.alice.transport.sentPackets.size + 1

            val msg1 = outgoingMessage(harness.aliceConversation.id, "msg 1", now.epochMillis + 1L)
            val msg2 = outgoingMessage(harness.aliceConversation.id, "msg 2", now.epochMillis + 2L)
            val msg3 = outgoingMessage(harness.aliceConversation.id, "msg 3", now.epochMillis + 3L)
            harness.alice.messageRepo.saveIdempotent(msg1).getOrThrow()
            harness.alice.messageRepo.saveIdempotent(msg2).getOrThrow()
            harness.alice.messageRepo.saveIdempotent(msg3).getOrThrow()

            val flushUseCase =
                FlushPendingMessagesUseCase(
                    harness.alice.messageRepo,
                    harness.alice.peerRepo,
                    harness.alice.transport,
                    harness.alice.packetSender,
                    dispatchers,
                    harness.alice.logger,
                )
            val result = flushUseCase(harness.bobPeer.id, harness.aliceConversation.id)

            assertTrue(result.isFailure)
            assertIs<TransportDisconnectedError>(result.exceptionOrNull()?.toDomainErrorOrNull())
            assertEquals(MessageStatus.SENT, harness.alice.messageRepo.getById(msg1.id).getOrThrow()?.status)
            assertEquals(MessageStatus.PENDING, harness.alice.messageRepo.getById(msg2.id).getOrThrow()?.status)
            assertEquals(MessageStatus.PENDING, harness.alice.messageRepo.getById(msg3.id).getOrThrow()?.status)
            assertTrue(harness.alice.logger.entries.any { it.level == FakeAppLogger.Level.WARN })
        }

    private fun outgoingMessage(
        conversationId: ConversationId,
        plaintext: String,
        epochMillis: Long,
        status: MessageStatus = MessageStatus.PENDING,
    ): Message =
        Message(
            id = MessageId.generate(Instant(epochMillis)),
            conversationId = conversationId,
            plaintext = plaintext,
            direction = MessageDirection.OUTGOING,
            status = status,
            sentAt = Instant(epochMillis),
        )
}
