package com.bluemessenger.storage

import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.storage.repository.SqlDelightMessageRepositoryImpl
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SqlDelightMessageRepositoryTest {
    private class Fixture(
        val messages: SqlDelightMessageRepositoryImpl,
        val conversation: Conversation,
    )

    private suspend fun TestScope.createFixture(): Fixture {
        val database = BlueMessengerDatabase(createInMemoryTestDriver())
        val dispatchers = testDispatchers()
        val conversation = testConversation()
        SqlDelightPeerRepositoryImpl(database, dispatchers).upsertConversation(conversation).getOrThrow()
        return Fixture(SqlDelightMessageRepositoryImpl(database, dispatchers), conversation)
    }

    @Test
    fun `saveIdempotent stores a new message and returns true`() =
        runTest {
            val fixture = createFixture()
            val message = testMessage(fixture.conversation.id)

            assertTrue(fixture.messages.saveIdempotent(message).getOrThrow())

            assertEquals(message, fixture.messages.getById(message.id).getOrThrow())
        }

    @Test
    fun `saveIdempotent keeps exactly one row and returns false for repeated message id`() =
        runTest {
            val fixture = createFixture()
            val message = testMessage(fixture.conversation.id)
            val duplicate = message.copy(plaintext = "overwrite attempt")

            assertTrue(fixture.messages.saveIdempotent(message).getOrThrow())
            assertEquals(false, fixture.messages.saveIdempotent(duplicate).getOrThrow())

            assertEquals(listOf(message), fixture.messages.observeByConversation(fixture.conversation.id).first())
        }

    @Test
    fun `saveIdempotent fails with storage error for unknown conversation`() =
        runTest {
            val fixture = createFixture()
            val orphan = testMessage(testConversation().id)

            val failure = fixture.messages.saveIdempotent(orphan).exceptionOrNull()

            assertIs<StorageError>(assertIs<DomainException>(failure).error)
        }

    @Test
    fun `getById returns null for unknown message`() =
        runTest {
            val fixture = createFixture()

            assertNull(fixture.messages.getById(MessageId.generate(testInstant())).getOrThrow())
        }

    @Test
    fun `observeByConversation emits chronological snapshots`() =
        runTest {
            val fixture = createFixture()
            val first = testMessage(fixture.conversation.id, sentOffsetMillis = 1)
            val second = testMessage(fixture.conversation.id, sentOffsetMillis = 2)
            val emissions = mutableListOf<List<Message>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.messages.observeByConversation(fixture.conversation.id).toList(emissions)
            }

            fixture.messages.saveIdempotent(second).getOrThrow()
            fixture.messages.saveIdempotent(first).getOrThrow()

            assertEquals(listOf(emptyList(), listOf(second), listOf(first, second)), emissions)
        }

    @Test
    fun `getPendingOrFailedOutgoing returns only retryable outgoing messages in chronological order`() =
        runTest {
            val fixture = createFixture()
            val conversationId = fixture.conversation.id
            val failedLate = testMessage(conversationId, status = MessageStatus.FAILED, sentOffsetMillis = 30)
            val pendingEarly = testMessage(conversationId, status = MessageStatus.PENDING, sentOffsetMillis = 10)
            val pendingMiddle = testMessage(conversationId, status = MessageStatus.PENDING, sentOffsetMillis = 20)
            val sent = testMessage(conversationId, status = MessageStatus.SENT, sentOffsetMillis = 5)
            val delivered =
                testMessage(
                    conversationId,
                    status = MessageStatus.DELIVERED,
                    sentOffsetMillis = 6,
                    deliveredOffsetMillis = 7,
                )
            val incomingPending =
                testMessage(
                    conversationId,
                    direction = MessageDirection.INCOMING,
                    status = MessageStatus.PENDING,
                    sentOffsetMillis = 8,
                )
            listOf(failedLate, pendingEarly, pendingMiddle, sent, delivered, incomingPending).forEach { message ->
                fixture.messages.saveIdempotent(message).getOrThrow()
            }

            val retryable = fixture.messages.getPendingOrFailedOutgoing(conversationId).getOrThrow()

            assertEquals(listOf(pendingEarly, pendingMiddle, failedLate), retryable)
        }

    @Test
    fun `updateStatus applies valid transitions through delivery`() =
        runTest {
            val fixture = createFixture()
            val message = testMessage(fixture.conversation.id)
            fixture.messages.saveIdempotent(message).getOrThrow()

            fixture.messages.updateStatus(message.id, MessageStatus.SENT).getOrThrow()
            assertEquals(MessageStatus.SENT, fixture.messages.getById(message.id).getOrThrow()?.status)

            val deliveredAt = testInstant(100)
            fixture.messages.updateStatus(message.id, MessageStatus.DELIVERED, deliveredAt).getOrThrow()
            val stored = fixture.messages.getById(message.id).getOrThrow()
            assertEquals(MessageStatus.DELIVERED, stored?.status)
            assertEquals(deliveredAt, stored?.deliveredAt)
        }

    @Test
    fun `updateStatus moves failed messages back to pending for retry`() =
        runTest {
            val fixture = createFixture()
            val message = testMessage(fixture.conversation.id, status = MessageStatus.FAILED)
            fixture.messages.saveIdempotent(message).getOrThrow()

            fixture.messages.updateStatus(message.id, MessageStatus.PENDING).getOrThrow()

            assertEquals(
                listOf(message.copy(status = MessageStatus.PENDING)),
                fixture.messages.getPendingOrFailedOutgoing(fixture.conversation.id).getOrThrow(),
            )
        }

    @Test
    fun `updateStatus rejects regression from delivered`() =
        runTest {
            val fixture = createFixture()
            val delivered =
                testMessage(
                    fixture.conversation.id,
                    status = MessageStatus.DELIVERED,
                    deliveredOffsetMillis = 10,
                )
            fixture.messages.saveIdempotent(delivered).getOrThrow()

            MessageStatus.entries.forEach { target ->
                val failure = fixture.messages.updateStatus(delivered.id, target, testInstant(20)).exceptionOrNull()
                assertIs<InvalidStateTransitionError>(assertIs<DomainException>(failure).error)
            }

            assertEquals(delivered, fixture.messages.getById(delivered.id).getOrThrow())
        }

    @Test
    fun `updateStatus rejects delivery timestamp earlier than sentAt`() =
        runTest {
            val fixture = createFixture()
            val message = testMessage(fixture.conversation.id, status = MessageStatus.SENT, sentOffsetMillis = 50)
            fixture.messages.saveIdempotent(message).getOrThrow()

            val tooEarly = testInstant(49)

            val failure = fixture.messages.updateStatus(message.id, MessageStatus.DELIVERED, tooEarly).exceptionOrNull()

            assertIs<InvalidStateTransitionError>(assertIs<DomainException>(failure).error)
            assertEquals(message, fixture.messages.getById(message.id).getOrThrow())
        }

    @Test
    fun `updateStatus rejects delivery without timestamp`() =
        runTest {
            val fixture = createFixture()
            val message = testMessage(fixture.conversation.id, status = MessageStatus.SENT)
            fixture.messages.saveIdempotent(message).getOrThrow()

            val failure = fixture.messages.updateStatus(message.id, MessageStatus.DELIVERED, null).exceptionOrNull()

            assertIs<InvalidStateTransitionError>(assertIs<DomainException>(failure).error)
        }

    @Test
    fun `updateStatus fails with storage error for unknown message`() =
        runTest {
            val fixture = createFixture()

            val unknownId = MessageId.generate(testInstant())

            val failure = fixture.messages.updateStatus(unknownId, MessageStatus.SENT).exceptionOrNull()

            assertIs<StorageError>(assertIs<DomainException>(failure).error)
        }
}
