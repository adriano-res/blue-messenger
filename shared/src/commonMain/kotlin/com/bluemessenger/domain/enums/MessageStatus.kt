package com.bluemessenger.domain.enums

import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.asFailure

/**
 * Lifecycle states of a message with strict transition validation.
 */
enum class MessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    FAILED,
    ;

    /**
     * Returns whether transitioning from this status to [target] is permitted by domain rules.
     */
    fun canTransitionTo(target: MessageStatus): Boolean = target in ALLOWED_TRANSITIONS.getValue(this)

    /**
     * Validates and performs a transition to [target], failing fast with [InvalidStateTransitionError].
     */
    fun transitionTo(target: MessageStatus): Result<MessageStatus> =
        if (canTransitionTo(target)) {
            Result.success(target)
        } else {
            InvalidStateTransitionError(
                entity = "MessageStatus",
                fromState = name,
                toState = target.name,
            ).asFailure()
        }

    companion object {
        private val ALLOWED_TRANSITIONS: Map<MessageStatus, Set<MessageStatus>> =
            mapOf(
                PENDING to setOf(SENT, FAILED),
                SENT to setOf(DELIVERED, FAILED),
                FAILED to setOf(PENDING),
                DELIVERED to emptySet(),
            )
    }
}
