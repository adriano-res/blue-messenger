package com.bluemessenger.domain.usecase.message

import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.domain.usecase.FakeAppLogger
import com.bluemessenger.domain.usecase.InMemoryMessageRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveMessagesUseCaseTest {
    @Test
    fun `emits historical messages in chronological order and live message and status updates`() =
        runTest {
            val baseMs = 1_700_000_000_000L
            val messageRepo = InMemoryMessageRepository()
            val logger = FakeAppLogger()
            val useCase = ObserveMessagesUseCase(messageRepo, logger)
            val targetConvId = ConversationId.generate(Instant(baseMs))
            val otherConvId = ConversationId.generate(Instant(baseMs + 1L))

            val later =
                message(targetConvId, baseMs + 20L, "Second", MessageDirection.INCOMING, MessageStatus.DELIVERED)
            val earlier =
                message(targetConvId, baseMs + 10L, "First", MessageDirection.OUTGOING, MessageStatus.DELIVERED)
            val other =
                message(otherConvId, baseMs + 15L, "Other", MessageDirection.INCOMING, MessageStatus.DELIVERED)
            messageRepo.saveIdempotent(later).getOrThrow()
            messageRepo.saveIdempotent(earlier).getOrThrow()
            messageRepo.saveIdempotent(other).getOrThrow()

            val emissions = mutableListOf<List<Message>>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { useCase(targetConvId).collect(emissions::add) }
            runCurrent()
            assertEquals(listOf(earlier.id, later.id), emissions.single().map { it.id })

            val live =
                message(targetConvId, baseMs + 30L, "Live message", MessageDirection.OUTGOING, MessageStatus.PENDING)
            messageRepo.saveIdempotent(live).getOrThrow()
            runCurrent()
            assertEquals(MessageStatus.PENDING, emissions[1].last().status)

            messageRepo.updateStatus(live.id, MessageStatus.SENT).getOrThrow()
            runCurrent()
            assertEquals(MessageStatus.SENT, emissions[2].last().status)

            messageRepo.updateStatus(live.id, MessageStatus.DELIVERED, Instant(baseMs + 35L)).getOrThrow()
            runCurrent()
            assertEquals(4, emissions.size)
            assertEquals(MessageStatus.DELIVERED, emissions[3].last().status)

            job.cancel()
            assertTrue(logger.entries.isEmpty())
        }

    @Test
    fun `filters duplicate consecutive emissions when another conversation changes`() =
        runTest {
            val baseMs = 1_700_000_000_000L
            val messageRepo = InMemoryMessageRepository()
            val useCase = ObserveMessagesUseCase(messageRepo, FakeAppLogger())
            val targetConvId = ConversationId.generate(Instant(baseMs))
            val otherConvId = ConversationId.generate(Instant(baseMs + 1L))

            val targetMsg = message(targetConvId, baseMs + 10L, "Target message")
            messageRepo.saveIdempotent(targetMsg).getOrThrow()

            val emissions = mutableListOf<List<Message>>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { useCase(targetConvId).collect(emissions::add) }
            runCurrent()
            assertEquals(1, emissions.size)

            val otherMsg = message(otherConvId, baseMs + 20L, "Other noise", status = MessageStatus.PENDING)
            messageRepo.saveIdempotent(otherMsg).getOrThrow()
            runCurrent()
            messageRepo.updateStatus(otherMsg.id, MessageStatus.SENT).getOrThrow()
            runCurrent()

            assertEquals(1, emissions.size)
            assertEquals(listOf(targetMsg), emissions.single())
            job.cancel()
        }

    @Test
    fun `logs structured error without plaintext on stream failure and ignores normal cancellation`() =
        runTest {
            val baseMs = 1_700_000_000_000L
            val messageRepo = InMemoryMessageRepository()
            val logger = FakeAppLogger()
            val useCase = ObserveMessagesUseCase(messageRepo, logger)
            val conversationId = ConversationId.generate(Instant(baseMs))
            val secretPlaintext = "Sensitive chat text"

            messageRepo.saveIdempotent(message(conversationId, baseMs + 5L, secretPlaintext)).getOrThrow()

            messageRepo.observeFailure = StorageError("SQLite read failure")
            val thrown =
                assertFailsWith<DomainException> {
                    useCase(conversationId).collect { }
                }

            assertIs<StorageError>(thrown.error)
            assertEquals(1, logger.entries.size)
            val errorEntry = logger.entries.single()
            assertEquals(FakeAppLogger.Level.ERROR, errorEntry.level)
            assertEquals(conversationId.toString(), errorEntry.context["conversationId"])
            assertEquals(thrown, errorEntry.throwable)
            logger.assertNoSensitiveData(secretPlaintext)

            messageRepo.observeFailure = null
            val activeJob = launch(start = CoroutineStart.UNDISPATCHED) { useCase(conversationId).collect { } }
            runCurrent()
            activeJob.cancel()
            runCurrent()

            assertEquals(1, logger.entries.size)
        }

    private fun message(
        conversationId: ConversationId,
        sentAtMs: Long,
        plaintext: String,
        direction: MessageDirection = MessageDirection.OUTGOING,
        status: MessageStatus = MessageStatus.SENT,
    ): Message =
        Message(
            id = MessageId.generate(Instant(sentAtMs)),
            conversationId = conversationId,
            plaintext = plaintext,
            direction = direction,
            status = status,
            sentAt = Instant(sentAtMs),
            deliveredAt = if (status == MessageStatus.DELIVERED) Instant(sentAtMs + 1L) else null,
        )
}
