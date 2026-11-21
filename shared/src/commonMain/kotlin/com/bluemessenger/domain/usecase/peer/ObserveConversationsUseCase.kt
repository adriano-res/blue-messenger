package com.bluemessenger.domain.usecase.peer

import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.storage.PeerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Observes persisted historical and active conversations along with peer metadata and session state.
 *
 * Thread safety: Stateless; delegates reactive emission to the thread-safe [PeerRepository] port.
 *
 * Failure behavior: Logs structured errors, preserves [CancellationException], and rethrows
 * failures fail-fast.
 */
class ObserveConversationsUseCase(
    private val peerRepository: PeerRepository,
    private val logger: AppLogger,
) {
    /**
     * Emits ordered snapshots of all conversations, deduplicating consecutive identical lists.
     */
    operator fun invoke(): Flow<List<Conversation>> =
        peerRepository.observeConversations()
            .distinctUntilChanged()
            .catch { cause ->
                if (cause is CancellationException) throw cause
                logger.error(
                    tag = TAG,
                    message = "Failed to observe conversations.",
                    throwable = cause,
                )
                throw cause
            }

    private companion object {
        private const val TAG: String = "ObserveConversationsUseCase"
    }
}
