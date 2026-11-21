package com.bluemessenger.storage.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.ports.storage.MessageRepository
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.storage.BlueMessengerDatabase
import com.bluemessenger.storage.mapStorageErrors
import com.bluemessenger.storage.mapper.toDomain
import com.bluemessenger.storage.runStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val OPERATION_OBSERVE_MESSAGES: String = "observeByConversation"
private const val OPERATION_GET_MESSAGE_BY_ID: String = "getById"
private const val OPERATION_GET_PENDING_OR_FAILED: String = "getPendingOrFailedOutgoing"
private const val OPERATION_SAVE_IDEMPOTENT: String = "saveIdempotent"
private const val OPERATION_UPDATE_STATUS: String = "updateStatus"

private val RETRYABLE_STATUS_NAMES: List<String> =
    listOf(MessageStatus.PENDING.name, MessageStatus.FAILED.name)

/**
 * SQLDelight-backed [MessageRepository] enforcing `messageId` idempotency and status transitions.
 *
 * Thread safety: blocking SQLite calls run on [CoroutineDispatchers.io]; multi-statement mutations
 * execute inside a single SQLite transaction.
 */
class SqlDelightMessageRepositoryImpl(
    private val database: BlueMessengerDatabase,
    private val dispatchers: CoroutineDispatchers,
) : MessageRepository {
    private val queries = database.blueMessengerDatabaseQueries

    override fun observeByConversation(conversationId: ConversationId): Flow<List<Message>> =
        queries
            .selectMessagesByConversation(conversationId.toString())
            .asFlow()
            .mapToList(dispatchers.io)
            .map { rows -> rows.map { row -> row.toDomain() } }
            .mapStorageErrors(OPERATION_OBSERVE_MESSAGES)

    override suspend fun getById(messageId: MessageId): Result<Message?> =
        dispatchers.runStorage(OPERATION_GET_MESSAGE_BY_ID) {
            queries.selectMessageById(messageId.toString()).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun getPendingOrFailedOutgoing(conversationId: ConversationId): Result<List<Message>> =
        dispatchers.runStorage(OPERATION_GET_PENDING_OR_FAILED) {
            queries
                .selectMessagesByConversationDirectionAndStatuses(
                    conversationId = conversationId.toString(),
                    direction = MessageDirection.OUTGOING.name,
                    statuses = RETRYABLE_STATUS_NAMES,
                ).executeAsList()
                .map { row -> row.toDomain() }
        }

    override suspend fun saveIdempotent(message: Message): Result<Boolean> =
        dispatchers.runStorage(OPERATION_SAVE_IDEMPOTENT) {
            database.transactionWithResult {
                queries.insertMessageIgnoringDuplicate(
                    id = message.id.toString(),
                    conversationId = message.conversationId.toString(),
                    plaintext = message.plaintext,
                    direction = message.direction.name,
                    status = message.status.name,
                    sentAt = message.sentAt.epochMillis,
                    deliveredAt = message.deliveredAt?.epochMillis,
                )
                queries.selectChangedRowCount().executeAsOne() > 0L
            }
        }

    override suspend fun updateStatus(
        messageId: MessageId,
        newStatus: MessageStatus,
        deliveredAt: Instant?,
    ): Result<Unit> =
        dispatchers.runStorage(OPERATION_UPDATE_STATUS) {
            database.transaction {
                val existing =
                    queries.selectMessageById(messageId.toString()).executeAsOneOrNull()?.toDomain()
                        ?: throw DomainException(StorageError("Message $messageId was not found."))
                val updated = existing.transitionStatus(newStatus, deliveredAt).getOrThrow()
                queries.updateMessageStatus(
                    status = updated.status.name,
                    deliveredAt = updated.deliveredAt?.epochMillis,
                    id = messageId.toString(),
                )
            }
        }
}
