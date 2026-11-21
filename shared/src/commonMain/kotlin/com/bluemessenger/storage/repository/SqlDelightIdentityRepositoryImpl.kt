package com.bluemessenger.storage.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.model.LocalIdentity
import com.bluemessenger.domain.ports.storage.IdentityRepository
import com.bluemessenger.storage.BlueMessengerDatabase
import com.bluemessenger.storage.SINGLETON_IDENTITY_ROW_ID
import com.bluemessenger.storage.mapStorageErrors
import com.bluemessenger.storage.mapper.toDomain
import com.bluemessenger.storage.runStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val OPERATION_OBSERVE_IDENTITY: String = "observeLocalIdentity"
private const val OPERATION_GET_IDENTITY: String = "getLocalIdentity"
private const val OPERATION_SAVE_IDENTITY: String = "saveLocalIdentity"

/**
 * SQLDelight-backed [IdentityRepository] storing the singleton local profile row.
 *
 * Thread safety: blocking SQLite calls run on [CoroutineDispatchers.io]; SQLite serializes writes.
 */
class SqlDelightIdentityRepositoryImpl(
    private val database: BlueMessengerDatabase,
    private val dispatchers: CoroutineDispatchers,
) : IdentityRepository {
    private val queries = database.blueMessengerDatabaseQueries

    override fun observeLocalIdentity(): Flow<LocalIdentity?> =
        queries
            .selectLocalIdentity(SINGLETON_IDENTITY_ROW_ID)
            .asFlow()
            .mapToOneOrNull(dispatchers.io)
            .map { row -> row?.toDomain() }
            .mapStorageErrors(OPERATION_OBSERVE_IDENTITY)

    override suspend fun getLocalIdentity(): Result<LocalIdentity?> =
        dispatchers.runStorage(OPERATION_GET_IDENTITY) {
            queries.selectLocalIdentity(SINGLETON_IDENTITY_ROW_ID).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun saveLocalIdentity(identity: LocalIdentity): Result<Unit> =
        dispatchers.runStorage(OPERATION_SAVE_IDENTITY) {
            queries.upsertLocalIdentity(
                rowId = SINGLETON_IDENTITY_ROW_ID,
                id = identity.id.toString(),
                nickname = identity.nickname,
                identityPublicKey = identity.identityPublicKey.toByteArray(),
                createdAt = identity.createdAt.epochMillis,
            )
        }
}
