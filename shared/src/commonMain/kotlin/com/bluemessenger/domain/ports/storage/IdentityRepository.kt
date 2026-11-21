package com.bluemessenger.domain.ports.storage

import com.bluemessenger.domain.model.LocalIdentity
import kotlinx.coroutines.flow.Flow

/**
 * Persistence port for the local device user profile ([LocalIdentity]).
 *
 * Thread safety: Implementations must support safe concurrent reads and serialized writes across
 * coroutine dispatchers.
 *
 * Error handling: Suspend operations return [Result.failure] on storage errors without swallowing
 * underlying exceptions or coroutine cancellations.
 */
interface IdentityRepository {
    /**
     * Emits the current [LocalIdentity], or `null` before initial onboarding completes.
     */
    fun observeLocalIdentity(): Flow<LocalIdentity?>

    /**
     * Loads the persisted [LocalIdentity], or `null` if not yet initialized.
     */
    suspend fun getLocalIdentity(): Result<LocalIdentity?>

    /**
     * Persists or updates the [LocalIdentity] atomically.
     */
    suspend fun saveLocalIdentity(identity: LocalIdentity): Result<Unit>
}
