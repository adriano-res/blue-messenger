package com.bluemessenger.domain

import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageStatusTransitionTest {
    @Test
    fun `allowed transitions succeed`() {
        val validTransitions =
            listOf(
                MessageStatus.PENDING to MessageStatus.SENT,
                MessageStatus.PENDING to MessageStatus.FAILED,
                MessageStatus.SENT to MessageStatus.DELIVERED,
                MessageStatus.SENT to MessageStatus.FAILED,
                MessageStatus.FAILED to MessageStatus.PENDING,
            )

        for ((from, to) in validTransitions) {
            assertTrue(from.canTransitionTo(to), "Expected $from -> $to to be allowed.")
            val result = from.transitionTo(to)
            assertTrue(result.isSuccess, "Expected transition $from -> $to to succeed.")
            assertEquals(to, result.getOrThrow())
        }
    }

    @Test
    fun `delivered is strictly terminal and cannot transition to any state`() {
        for (target in MessageStatus.entries) {
            assertFalse(
                MessageStatus.DELIVERED.canTransitionTo(target),
                "DELIVERED must not transition to $target.",
            )
            val result = MessageStatus.DELIVERED.transitionTo(target)
            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()?.toDomainErrorOrNull()
            val transitionError = assertIs<InvalidStateTransitionError>(error)
            assertEquals(MessageStatus.DELIVERED.name, transitionError.fromState)
            assertEquals(target.name, transitionError.toState)
        }
    }

    @Test
    fun `disallowed transitions fail with InvalidStateTransitionError`() {
        val invalidTransitions =
            listOf(
                MessageStatus.PENDING to MessageStatus.PENDING,
                MessageStatus.PENDING to MessageStatus.DELIVERED,
                MessageStatus.SENT to MessageStatus.PENDING,
                MessageStatus.SENT to MessageStatus.SENT,
                MessageStatus.FAILED to MessageStatus.SENT,
                MessageStatus.FAILED to MessageStatus.DELIVERED,
                MessageStatus.FAILED to MessageStatus.FAILED,
            )

        for ((from, to) in invalidTransitions) {
            assertFalse(from.canTransitionTo(to), "Expected $from -> $to to be rejected.")
            val result = from.transitionTo(to)
            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()?.toDomainErrorOrNull()
            assertIs<InvalidStateTransitionError>(error)
        }
    }

    @Test
    fun `message entity enforces lifecycle and delivery timestamp invariants`() {
        val sentAt = Instant(1_000L)
        val deliveredAt = Instant(1_250L)
        val pending =
            Message(
                id = MessageId.generate(sentAt),
                conversationId = ConversationId.generate(sentAt),
                plaintext = "Encrypted hello",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.PENDING,
                sentAt = sentAt,
            )

        val sent = pending.transitionStatus(MessageStatus.SENT).getOrThrow()
        assertEquals(MessageStatus.SENT, sent.status)
        assertNull(sent.deliveredAt)

        val missingTimestampResult = sent.transitionStatus(MessageStatus.DELIVERED, at = null)
        assertTrue(missingTimestampResult.isFailure)
        assertIs<InvalidStateTransitionError>(missingTimestampResult.exceptionOrNull()?.toDomainErrorOrNull())

        val earlierTimestampResult = sent.transitionStatus(MessageStatus.DELIVERED, at = Instant(900L))
        assertTrue(earlierTimestampResult.isFailure)
        assertIs<InvalidStateTransitionError>(earlierTimestampResult.exceptionOrNull()?.toDomainErrorOrNull())

        val delivered = sent.transitionStatus(MessageStatus.DELIVERED, at = deliveredAt).getOrThrow()
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(deliveredAt, delivered.deliveredAt)

        val rollbackResult = delivered.transitionStatus(MessageStatus.PENDING)
        assertTrue(rollbackResult.isFailure)
        assertIs<InvalidStateTransitionError>(rollbackResult.exceptionOrNull()?.toDomainErrorOrNull())
    }

    @Test
    fun `failed message can be retried to pending`() {
        val sentAt = Instant(2_000L)
        val pending =
            Message(
                id = MessageId.generate(sentAt),
                conversationId = ConversationId.generate(sentAt),
                plaintext = "Retry payload",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.PENDING,
                sentAt = sentAt,
            )

        val failed = pending.transitionStatus(MessageStatus.FAILED).getOrThrow()
        val retried = failed.transitionStatus(MessageStatus.PENDING).getOrThrow()
        assertEquals(MessageStatus.PENDING, retried.status)
        assertNull(retried.deliveredAt)
    }
}
