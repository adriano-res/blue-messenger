package com.bluemessenger.domain.enums

import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.asFailure

/**
 * Coarse session state exposed at the [Conversation] aggregate boundary.
 */
enum class AggregatedSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED_ENCRYPTED,
}

/**
 * Detailed Bluetooth and E2EE session lifecycle state machine.
 */
enum class SessionState {
    DISCONNECTED,
    DISCOVERING,
    ADVERTISING,
    CONNECTING,
    HANDSHAKING,
    SECURE_ACTIVE,
    RECONNECTING,
    ;

    /**
     * Returns whether transitioning from this state to [target] is permitted by session lifecycle rules.
     */
    fun canTransitionTo(target: SessionState): Boolean = target in ALLOWED_TRANSITIONS.getValue(this)

    /**
     * Validates and performs a transition to [target], failing fast with [InvalidStateTransitionError].
     */
    fun transitionTo(target: SessionState): Result<SessionState> =
        if (canTransitionTo(target)) {
            Result.success(target)
        } else {
            InvalidStateTransitionError(
                entity = "SessionState",
                fromState = name,
                toState = target.name,
            ).asFailure()
        }

    /**
     * Maps this fine-grained transport state to the coarse [AggregatedSessionState] used by [Conversation].
     */
    fun toAggregatedState(): AggregatedSessionState =
        when (this) {
            DISCONNECTED, DISCOVERING, ADVERTISING -> AggregatedSessionState.DISCONNECTED
            CONNECTING, HANDSHAKING, RECONNECTING -> AggregatedSessionState.CONNECTING
            SECURE_ACTIVE -> AggregatedSessionState.CONNECTED_ENCRYPTED
        }

    companion object {
        private val ALLOWED_TRANSITIONS: Map<SessionState, Set<SessionState>> =
            mapOf(
                DISCONNECTED to setOf(DISCOVERING, ADVERTISING, CONNECTING),
                DISCOVERING to setOf(CONNECTING, DISCONNECTED),
                ADVERTISING to setOf(CONNECTING, DISCONNECTED),
                CONNECTING to setOf(HANDSHAKING, DISCONNECTED),
                HANDSHAKING to setOf(SECURE_ACTIVE, DISCONNECTED),
                SECURE_ACTIVE to setOf(RECONNECTING, DISCONNECTED),
                RECONNECTING to setOf(CONNECTING, DISCONNECTED),
            )
    }
}
