package com.bluemessenger.domain.usecase.message

import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.storage.MessageRepository
import com.bluemessenger.domain.primitives.ConversationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Observes persisted chat history and live message updates for a conversation.
 *
 * Thread safety: Stateless; delegates reactive emission to the thread-safe [MessageRepository] port.
 *
 * Failure behavior: Logs structured errors with `conversationId` without exposing message
 * plaintext, preserves [CancellationException], and rethrows failures fail-fast.
 */
class ObserveMessagesUseCase(
    private val messageRepository: MessageRepository,
    private val logger: AppLogger,
) {
    /**
     * Emits chronological snapshots of all messages belonging to [conversationId], deduplicating
     * consecutive identical lists.
     */
    operator fun invoke(conversationId: ConversationId): Flow<List<Message>> =
        messageRepository.observeByConversation(conversationId)
            .distinctUntilChanged()
            .catch { cause ->
                if (cause is CancellationException) throw cause
                logger.error(
                    tag = TAG,
                    message = "Failed to observe conversation messages.",
                    throwable = cause,
                    context = mapOf("conversationId" to conversationId.toString()),
                )
                throw cause
            }

    private companion object {
        private const val TAG: String = "ObserveMessagesUseCase"
    }
}
