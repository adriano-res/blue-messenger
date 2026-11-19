package com.bluemessenger.domain.ports.repository

import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import kotlinx.coroutines.flow.Flow

/**
 * Persistence and reactive observation port for chat messages.
 *
 * Thread safety: Implementations must be safe to invoke concurrently from multiple coroutines
 * and perform blocking storage I/O on an injected IO dispatcher rather than the caller's thread.
 *
 * Error handling: Suspend operations return [Result] failures encapsulating typed domain or
 * persistence exceptions; implementations must never swallow cancellation or I/O failures silently.
 */
interface MessageRepository {
    /**
     * Emits ordered, immutable snapshots of all messages belonging to [conversationId].
     */
    fun observeByConversation(conversationId: ConversationId): Flow<List<Message>>

    /**
     * Retrieves a message by [messageId], or `null` if no matching record exists.
     */
    suspend fun getById(messageId: MessageId): Result<Message?>

    /**
     * Returns outgoing messages for [conversationId] in `PENDING` or `FAILED` status for retry.
     */
    suspend fun getPendingOrFailedOutgoing(conversationId: ConversationId): Result<List<Message>>

    /**
     * Persists [message] idempotently by [Message.id].
     *
     * Returns `true` if the message was inserted as a new record, or `false` if a message with the
     * same identifier already existed.
     */
    suspend fun saveIdempotent(message: Message): Result<Boolean>

    /**
     * Updates the [MessageStatus] of [messageId], validating domain transition rules and recording
     * [deliveredAt] when transitioning to [MessageStatus.DELIVERED].
     */
    suspend fun updateStatus(
        messageId: MessageId,
        newStatus: MessageStatus,
        deliveredAt: Instant? = null,
    ): Result<Unit>
}
