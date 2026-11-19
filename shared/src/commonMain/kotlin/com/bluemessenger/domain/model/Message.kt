package com.bluemessenger.domain.model

import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId

/**
 * Immutable chat message entity belonging to a [Conversation].
 */
data class Message(
    val id: MessageId,
    val conversationId: ConversationId,
    val plaintext: String,
    val direction: MessageDirection,
    val status: MessageStatus,
    val sentAt: Instant,
    val deliveredAt: Instant? = null,
) {
    init {
        require(plaintext.isNotBlank()) {
            "Message plaintext must not be blank."
        }
        require(status != MessageStatus.DELIVERED || deliveredAt != null) {
            "A delivered message must have a deliveredAt timestamp."
        }
        require(status == MessageStatus.DELIVERED || deliveredAt == null) {
            "Only a delivered message can have a deliveredAt timestamp."
        }
        require(deliveredAt == null || deliveredAt >= sentAt) {
            "Message deliveredAt ($deliveredAt) cannot precede sentAt ($sentAt)."
        }
    }

    /**
     * Produces an updated [Message] with [targetStatus], enforcing state machine and timestamp invariants.
     */
    fun transitionStatus(
        targetStatus: MessageStatus,
        at: Instant? = null,
    ): Result<Message> {
        val nextStatus =
            status.transitionTo(targetStatus).getOrElse { error ->
                return Result.failure(error)
            }
        return when {
            nextStatus != MessageStatus.DELIVERED ->
                Result.success(copy(status = nextStatus, deliveredAt = null))
            at == null || at < sentAt ->
                InvalidStateTransitionError(
                    entity = "Message",
                    fromState = status.name,
                    toState = targetStatus.name,
                    message = "Delivering a message requires a valid timestamp >= sentAt.",
                ).asFailure()
            else ->
                Result.success(copy(status = nextStatus, deliveredAt = at))
        }
    }
}
