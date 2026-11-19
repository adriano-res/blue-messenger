package com.bluemessenger.domain.model

import com.bluemessenger.domain.enums.AggregatedSessionState
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant

/**
 * Aggregate representing a 1:1 encrypted conversation thread with a remote [Peer].
 */
data class Conversation(
    val id: ConversationId,
    val peer: Peer,
    val state: SessionState,
    val updatedAt: Instant,
) {
    val aggregatedState: AggregatedSessionState
        get() = state.toAggregatedState()

    /**
     * Transitions the conversation session state to [targetState], enforcing [SessionState] rules.
     */
    fun transitionSessionState(
        targetState: SessionState,
        updatedAt: Instant,
    ): Result<Conversation> =
        state.transitionTo(targetState).map { nextState ->
            copy(state = nextState, updatedAt = updatedAt)
        }
}
