package com.bluemessenger.storage

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.StorageError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val UNKNOWN_CAUSE: String = "unknown cause"

internal suspend fun <T> CoroutineDispatchers.runStorage(
    operation: String,
    block: () -> T,
): Result<T> {
    val outcome = withContext(io) { runCatching(block) }
    val failure = outcome.exceptionOrNull() ?: return outcome
    return Result.failure(failure.toStorageException(operation))
}

internal fun <T> Flow<T>.mapStorageErrors(operation: String): Flow<T> =
    catch { cause ->
        throw cause.toStorageException(operation)
    }

internal fun Throwable.toStorageException(operation: String): Throwable =
    when (this) {
        is CancellationException -> throw this
        is DomainException -> this
        else ->
            DomainException(
                StorageError("Storage operation '$operation' failed: ${message ?: UNKNOWN_CAUSE}."),
                this,
            )
    }
