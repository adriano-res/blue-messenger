package com.bluemessenger.domain.usecase.session

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.constants.SessionConstants
import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.ports.transport.model.KeepAlivePacket
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.domain.usecase.FakeAppLogger
import com.bluemessenger.domain.usecase.PairedSessionHarness
import com.bluemessenger.domain.usecase.PeerSideFixture
import com.bluemessenger.domain.usecase.createPairedActiveSession
import com.bluemessenger.domain.usecase.message.FlushPendingMessagesUseCase
import com.bluemessenger.domain.usecase.message.ObserveIncomingFramesUseCase
import com.bluemessenger.domain.usecase.testDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ManageKeepAliveAndReconnectUseCaseTest {
    private val baseTimeMillis: Long = 1_700_000_000_000L

    @Test
    fun `keeps session active across periodic PING and PONG exchanges`() =
        runTest {
            val harness = createPairedActiveSession(baseTimeMillis)
            val dispatchers = testDispatchers(testScheduler)
            val clock: () -> Instant = { Instant(testScheduler.currentTime + baseTimeMillis) }
            val keepAliveUseCase = buildKeepAliveUseCase(harness, dispatchers, clock)
            val aliceObserver = buildObserver(harness.alice, dispatchers, clock)
            val bobObserver = buildObserver(harness.bob, dispatchers, clock)

            val aliceObsJob =
                aliceObserver(
                    this,
                    harness.bobPeer.id,
                    harness.aliceConversation.id,
                    onPongReceived = keepAliveUseCase::onPongReceived,
                )
            val bobObsJob = bobObserver(this, harness.alicePeer.id, harness.bobConversation.id)
            val keepAliveJob = keepAliveUseCase.start(this, harness.bobPeer, harness.aliceConversation.id)

            advanceTimeBy(SessionConstants.KEEP_ALIVE_INTERVAL_MS * 2 + 1_000L)
            runCurrent()

            val pingsSent =
                harness.alice.transport.sentPackets
                    .filterIsInstance<KeepAlivePacket.Ping>()
            assertEquals(2, pingsSent.size)
            assertEquals(
                SessionState.SECURE_ACTIVE,
                harness.alice.peerRepo.getConversationByPeer(harness.bobPeer.id).getOrThrow()?.state,
            )
            assertEquals(0, harness.alice.transport.disconnectCount)

            keepAliveJob.cancel()
            aliceObsJob.cancel()
            bobObsJob.cancel()
        }

    @Test
    fun `detects PONG timeout, reconnects with exponential backoff, and flushes pending messages`() =
        runTest {
            val harness = createPairedActiveSession(baseTimeMillis)
            val dispatchers = testDispatchers(testScheduler)
            val clock: () -> Instant = { Instant(testScheduler.currentTime + baseTimeMillis) }
            harness.alice.transport.onSend = { Result.success(Unit) }

            val pendingMsg =
                Message(
                    id = MessageId.generate(Instant(baseTimeMillis + 10L)),
                    conversationId = harness.aliceConversation.id,
                    plaintext = "Queued before reconnect",
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.PENDING,
                    sentAt = Instant(baseTimeMillis + 10L),
                )
            harness.alice.messageRepo.saveIdempotent(pendingMsg).getOrThrow()

            val keepAliveUseCase = buildKeepAliveUseCase(harness, dispatchers, clock)
            val initialConnects = harness.alice.transport.connectAttempts
            configureReconnectOnThirdAttempt(this, harness, initialConnects)

            val keepAliveJob = keepAliveUseCase.start(this, harness.bobPeer, harness.aliceConversation.id)
            advanceTimeBy(SessionConstants.KEEP_ALIVE_INTERVAL_MS + SessionConstants.KEEP_ALIVE_TIMEOUT_MS)
            runCurrent()
            assertEquals(
                SessionState.RECONNECTING,
                harness.alice.peerRepo.getConversationByPeer(harness.bobPeer.id).getOrThrow()?.state,
            )

            advanceTimeBy(1_000L + 2_000L + 4_000L)
            runCurrent()

            assertEquals(initialConnects + 3, harness.alice.transport.connectAttempts)
            assertEquals(
                SessionState.SECURE_ACTIVE,
                harness.alice.peerRepo.getConversationByPeer(harness.bobPeer.id).getOrThrow()?.state,
            )
            assertEquals(MessageStatus.SENT, harness.alice.messageRepo.getById(pendingMsg.id).getOrThrow()?.status)
            keepAliveJob.cancel()
        }

    @Test
    fun `transitions to DISCONNECTED and preserves PENDING messages after exhausting 5 reconnect attempts`() =
        runTest {
            val harness = createPairedActiveSession(baseTimeMillis)
            val dispatchers = testDispatchers(testScheduler)
            val clock: () -> Instant = { Instant(testScheduler.currentTime + baseTimeMillis) }
            harness.alice.transport.onSend = { Result.success(Unit) }

            val pendingMsg =
                Message(
                    id = MessageId.generate(Instant(baseTimeMillis + 20L)),
                    conversationId = harness.aliceConversation.id,
                    plaintext = "Must stay pending",
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.PENDING,
                    sentAt = Instant(baseTimeMillis + 20L),
                )
            harness.alice.messageRepo.saveIdempotent(pendingMsg).getOrThrow()

            val keepAliveUseCase = buildKeepAliveUseCase(harness, dispatchers, clock)
            val initialConnects = harness.alice.transport.connectAttempts
            harness.alice.transport.nextConnectOutcome = TransportDisconnectedError().asFailure()

            val keepAliveJob = keepAliveUseCase.start(this, harness.bobPeer, harness.aliceConversation.id)
            advanceTimeBy(SessionConstants.KEEP_ALIVE_INTERVAL_MS + SessionConstants.KEEP_ALIVE_TIMEOUT_MS)
            runCurrent()
            assertEquals(
                SessionState.RECONNECTING,
                harness.alice.peerRepo.getConversationByPeer(harness.bobPeer.id).getOrThrow()?.state,
            )

            for (delayMs in listOf(1_000L, 2_000L, 4_000L, 8_000L)) {
                advanceTimeBy(delayMs)
                runCurrent()
                assertEquals(
                    SessionState.RECONNECTING,
                    harness.alice.peerRepo.getConversationByPeer(harness.bobPeer.id).getOrThrow()?.state,
                )
            }

            advanceTimeBy(16_000L)
            runCurrent()

            assertEquals(
                initialConnects + SessionConstants.MAX_RECONNECT_ATTEMPTS,
                harness.alice.transport.connectAttempts,
            )
            assertEquals(
                SessionState.DISCONNECTED,
                harness.alice.peerRepo.getConversationByPeer(harness.bobPeer.id).getOrThrow()?.state,
            )
            assertEquals(MessageStatus.PENDING, harness.alice.messageRepo.getById(pendingMsg.id).getOrThrow()?.status)
            assertTrue(keepAliveJob.isCompleted)
            assertTrue(harness.alice.logger.entries.any { it.level == FakeAppLogger.Level.ERROR })
        }

    private fun buildKeepAliveUseCase(
        harness: PairedSessionHarness,
        dispatchers: CoroutineDispatchers,
        clock: () -> Instant,
    ): ManageKeepAliveAndReconnectUseCase {
        val flushUseCase =
            FlushPendingMessagesUseCase(
                harness.alice.messageRepo,
                harness.alice.peerRepo,
                harness.alice.transport,
                harness.alice.packetSender,
                dispatchers,
                harness.alice.logger,
            )
        return ManageKeepAliveAndReconnectUseCase(
            harness.alice.transport,
            harness.alice.crypto,
            harness.alice.peerRepo,
            harness.alice.establish,
            flushUseCase,
            dispatchers,
            harness.alice.logger,
            clock,
        )
    }

    private fun configureReconnectOnThirdAttempt(
        scope: CoroutineScope,
        harness: PairedSessionHarness,
        initialConnects: Int,
    ) {
        harness.alice.transport.onConnect = {
            val attemptIndex = harness.alice.transport.connectAttempts - initialConnects
            if (attemptIndex < 3) {
                TransportDisconnectedError().asFailure()
            } else {
                harness.wireDuplexTransports()
                scope.async(start = CoroutineStart.UNDISPATCHED) {
                    harness.bob.establish.acceptAndEstablish(harness.alicePeer)
                }
                yield()
                Result.success(Unit)
            }
        }
    }

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
}
