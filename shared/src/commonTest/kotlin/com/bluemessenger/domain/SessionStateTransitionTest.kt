package com.bluemessenger.domain

import com.bluemessenger.domain.enums.AggregatedSessionState
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionStateTransitionTest {
    @Test
    fun `allowed lifecycle transitions succeed`() {
        val validTransitions =
            listOf(
                SessionState.DISCONNECTED to SessionState.DISCOVERING,
                SessionState.DISCONNECTED to SessionState.ADVERTISING,
                SessionState.DISCONNECTED to SessionState.CONNECTING,
                SessionState.DISCOVERING to SessionState.CONNECTING,
                SessionState.DISCOVERING to SessionState.DISCONNECTED,
                SessionState.ADVERTISING to SessionState.CONNECTING,
                SessionState.ADVERTISING to SessionState.DISCONNECTED,
                SessionState.CONNECTING to SessionState.HANDSHAKING,
                SessionState.CONNECTING to SessionState.DISCONNECTED,
                SessionState.HANDSHAKING to SessionState.SECURE_ACTIVE,
                SessionState.HANDSHAKING to SessionState.DISCONNECTED,
                SessionState.SECURE_ACTIVE to SessionState.RECONNECTING,
                SessionState.SECURE_ACTIVE to SessionState.DISCONNECTED,
                SessionState.RECONNECTING to SessionState.CONNECTING,
                SessionState.RECONNECTING to SessionState.DISCONNECTED,
            )

        for ((from, to) in validTransitions) {
            assertTrue(from.canTransitionTo(to), "Expected $from -> $to to be allowed.")
            val result = from.transitionTo(to)
            assertTrue(result.isSuccess, "Expected transition $from -> $to to succeed.")
            assertEquals(to, result.getOrThrow())
        }
    }

    @Test
    fun `cannot bypass handshake to reach secure active state`() {
        val illegalPathsToSecureActive =
            listOf(
                SessionState.DISCONNECTED,
                SessionState.DISCOVERING,
                SessionState.ADVERTISING,
                SessionState.CONNECTING,
                SessionState.RECONNECTING,
                SessionState.SECURE_ACTIVE,
            )

        for (from in illegalPathsToSecureActive) {
            assertFalse(
                from.canTransitionTo(SessionState.SECURE_ACTIVE),
                "State $from must not skip directly to SECURE_ACTIVE.",
            )
            val result = from.transitionTo(SessionState.SECURE_ACTIVE)
            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()?.toDomainErrorOrNull()
            val transitionError = assertIs<InvalidStateTransitionError>(error)
            assertEquals(from.name, transitionError.fromState)
            assertEquals(SessionState.SECURE_ACTIVE.name, transitionError.toState)
        }
    }

    @Test
    fun `complete 7x7 transition matrix enforces allowed and disallowed transitions`() {
        val validTransitions =
            setOf(
                SessionState.DISCONNECTED to SessionState.DISCOVERING,
                SessionState.DISCONNECTED to SessionState.ADVERTISING,
                SessionState.DISCONNECTED to SessionState.CONNECTING,
                SessionState.DISCOVERING to SessionState.CONNECTING,
                SessionState.DISCOVERING to SessionState.DISCONNECTED,
                SessionState.ADVERTISING to SessionState.CONNECTING,
                SessionState.ADVERTISING to SessionState.DISCONNECTED,
                SessionState.CONNECTING to SessionState.HANDSHAKING,
                SessionState.CONNECTING to SessionState.DISCONNECTED,
                SessionState.HANDSHAKING to SessionState.SECURE_ACTIVE,
                SessionState.HANDSHAKING to SessionState.DISCONNECTED,
                SessionState.SECURE_ACTIVE to SessionState.RECONNECTING,
                SessionState.SECURE_ACTIVE to SessionState.DISCONNECTED,
                SessionState.RECONNECTING to SessionState.CONNECTING,
                SessionState.RECONNECTING to SessionState.DISCONNECTED,
            )

        var checkedCombinations = 0
        for (from in SessionState.entries) {
            for (to in SessionState.entries) {
                checkedCombinations++
                val isAllowed = (from to to) in validTransitions
                assertEquals(isAllowed, from.canTransitionTo(to), "Unexpected canTransitionTo for $from -> $to.")
                val result = from.transitionTo(to)
                if (isAllowed) {
                    assertTrue(result.isSuccess, "Expected transition $from -> $to to succeed.")
                    assertEquals(to, result.getOrThrow())
                } else {
                    assertTrue(result.isFailure, "Expected transition $from -> $to to fail.")
                    val error = result.exceptionOrNull()?.toDomainErrorOrNull()
                    val transitionError = assertIs<InvalidStateTransitionError>(error)
                    assertEquals("SessionState", transitionError.entity)
                    assertEquals(from.name, transitionError.fromState)
                    assertEquals(to.name, transitionError.toState)
                }
            }
        }
        assertEquals(SessionState.entries.size * SessionState.entries.size, checkedCombinations)
    }

    @Test
    fun `maps all detailed session states to aggregated conversation state`() {
        assertEquals(AggregatedSessionState.DISCONNECTED, SessionState.DISCONNECTED.toAggregatedState())
        assertEquals(AggregatedSessionState.DISCONNECTED, SessionState.DISCOVERING.toAggregatedState())
        assertEquals(AggregatedSessionState.DISCONNECTED, SessionState.ADVERTISING.toAggregatedState())
        assertEquals(AggregatedSessionState.CONNECTING, SessionState.CONNECTING.toAggregatedState())
        assertEquals(AggregatedSessionState.CONNECTING, SessionState.HANDSHAKING.toAggregatedState())
        assertEquals(AggregatedSessionState.CONNECTING, SessionState.RECONNECTING.toAggregatedState())
        assertEquals(AggregatedSessionState.CONNECTED_ENCRYPTED, SessionState.SECURE_ACTIVE.toAggregatedState())
    }

    @Test
    fun `conversation aggregate advances session state cleanly and rejects invalid jumps`() {
        val t0 = Instant(1_000L)
        val t1 = Instant(2_000L)
        val t2 = Instant(3_000L)
        val t3 = Instant(4_000L)

        val peer =
            Peer(
                id = PeerId.generate(t0),
                nickname = "LinuxDesktop",
                address = BluetoothAddress("AA:BB:CC:DD:EE:FF"),
                lastSeenAt = t0,
            )
        val conversation =
            Conversation(
                id = ConversationId.generate(t0),
                peer = peer,
                state = SessionState.DISCONNECTED,
                updatedAt = t0,
            )

        assertEquals(AggregatedSessionState.DISCONNECTED, conversation.aggregatedState)
        assertTrue(conversation.transitionSessionState(SessionState.SECURE_ACTIVE, t1).isFailure)

        val connecting = conversation.transitionSessionState(SessionState.CONNECTING, t1).getOrThrow()
        assertEquals(AggregatedSessionState.CONNECTING, connecting.aggregatedState)

        val handshaking = connecting.transitionSessionState(SessionState.HANDSHAKING, t2).getOrThrow()
        assertEquals(AggregatedSessionState.CONNECTING, handshaking.aggregatedState)

        val active = handshaking.transitionSessionState(SessionState.SECURE_ACTIVE, t3).getOrThrow()
        assertEquals(AggregatedSessionState.CONNECTED_ENCRYPTED, active.aggregatedState)
        assertEquals(t3, active.updatedAt)
    }
}
