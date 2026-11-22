package com.bluemessenger.domain.usecase.peer

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.error.InvalidInputError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.model.LocalIdentity
import com.bluemessenger.domain.ports.crypto.KeyStoreProvider
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.storage.IdentityRepository
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey
import kotlinx.coroutines.withContext

/**
 * Initializes or loads the local user's [LocalIdentity] and hardware-backed identity key pair.
 *
 * Thread safety: Safe for concurrent calls; keystore mutations and repository writes rely on the
 * underlying thread-safe ports and execute via injected [CoroutineDispatchers].
 *
 * Failure behavior: Fails fast with [InvalidInputError] on a blank nickname when initializing a new
 * identity, logs structured errors, and propagates keystore or repository failures without falling
 * back silently.
 */
class InitializeIdentityUseCase(
    private val identityRepository: IdentityRepository,
    private val keyStoreProvider: KeyStoreProvider,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val clock: () -> Instant,
) {
    /**
     * Returns the existing verified [LocalIdentity] if present and consistent with the keystore, or
     * creates and persists a new identity using [nickname].
     */
    suspend operator fun invoke(nickname: String): Result<LocalIdentity> =
        withContext(dispatchers.default) {
            val (existingIdentity, hasKeyPair) =
                loadIdentityState().getOrElse { return@withContext Result.failure(it) }
            val reusable = resolveExistingIdentity(existingIdentity, hasKeyPair)
            reusable ?: createAndPersistIdentity(nickname, hasKeyPair)
        }

    /**
     * Loads and verifies an existing [LocalIdentity] against the hardware keystore without
     * creating a new profile or requiring a nickname. Returns `null` when no valid identity exists.
     */
    suspend fun loadExisting(): Result<LocalIdentity?> =
        withContext(dispatchers.default) {
            val (existingIdentity, hasKeyPair) =
                loadIdentityState().getOrElse { return@withContext Result.failure(it) }
            resolveExistingIdentity(existingIdentity, hasKeyPair) ?: Result.success(null)
        }

    private suspend fun loadIdentityState(): Result<Pair<LocalIdentity?, Boolean>> =
        identityRepository.getLocalIdentity().fold(
            onSuccess = { existingIdentity ->
                keyStoreProvider.hasIdentityKeyPair().fold(
                    onSuccess = { hasKeyPair -> Result.success(existingIdentity to hasKeyPair) },
                    onFailure = { error ->
                        logger.error(
                            tag = TAG,
                            message = "Failed to verify identity key pair in keystore.",
                            throwable = error,
                        )
                        Result.failure(error)
                    },
                )
            },
            onFailure = { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to load persisted local identity.",
                    throwable = error,
                )
                Result.failure(error)
            },
        )

    private suspend fun resolveExistingIdentity(
        existingIdentity: LocalIdentity?,
        hasKeyPair: Boolean,
    ): Result<LocalIdentity>? {
        var resolved: Result<LocalIdentity>? = null
        when {
            existingIdentity == null -> Unit
            !hasKeyPair ->
                logger.warn(
                    tag = TAG,
                    message = "Persisted local identity is missing keystore key pair; re-initializing.",
                    context = mapOf("peerId" to existingIdentity.id.toString()),
                )
            else ->
                keyStoreProvider.getIdentityPublicKey().fold(
                    onSuccess = { storedPublicKey ->
                        if (storedPublicKey == existingIdentity.identityPublicKey) {
                            logger.info(
                                tag = TAG,
                                message = "Loaded existing local identity.",
                                context = mapOf("peerId" to existingIdentity.id.toString()),
                            )
                            resolved = Result.success(existingIdentity)
                        } else {
                            logger.warn(
                                tag = TAG,
                                message =
                                    "Stored identity public key does not match persisted profile; re-initializing.",
                                context = mapOf("peerId" to existingIdentity.id.toString()),
                            )
                        }
                    },
                    onFailure = { error ->
                        logger.error(
                            tag = TAG,
                            message = "Failed to read stored identity public key.",
                            throwable = error,
                        )
                        resolved = Result.failure(error)
                    },
                )
        }
        return resolved
    }

    private suspend fun createAndPersistIdentity(
        nickname: String,
        hasKeyPair: Boolean,
    ): Result<LocalIdentity> {
        val normalizedNickname = nickname.trim()
        if (normalizedNickname.isBlank()) {
            logger.warn(tag = TAG, message = "Rejected local identity initialization with blank nickname.")
            return InvalidInputError("Nickname must not be blank.").asFailure()
        }
        return obtainOrGeneratePublicKey(hasKeyPair).fold(
            onSuccess = { publicKey ->
                val now = clock()
                val identity =
                    LocalIdentity(
                        id = PeerId.generate(now),
                        nickname = normalizedNickname,
                        identityPublicKey = publicKey,
                        createdAt = now,
                    )
                identityRepository.saveLocalIdentity(identity).fold(
                    onSuccess = {
                        logger.info(
                            tag = TAG,
                            message = "Initialized local identity.",
                            context = mapOf("peerId" to identity.id.toString()),
                        )
                        Result.success(identity)
                    },
                    onFailure = { error ->
                        logger.error(
                            tag = TAG,
                            message = "Failed to persist local identity.",
                            throwable = error,
                        )
                        Result.failure(error)
                    },
                )
            },
            onFailure = { error ->
                logger.error(
                    tag = TAG,
                    message = "Failed to obtain or generate identity key pair.",
                    throwable = error,
                )
                Result.failure(error)
            },
        )
    }

    private suspend fun obtainOrGeneratePublicKey(hasKeyPair: Boolean): Result<PublicKey> =
        if (hasKeyPair) {
            keyStoreProvider.getIdentityPublicKey().fold(
                onSuccess = { key ->
                    key?.let(Result.Companion::success) ?: keyStoreProvider.generateIdentityKeyPair()
                },
                onFailure = { Result.failure(it) },
            )
        } else {
            keyStoreProvider.generateIdentityKeyPair()
        }

    private companion object {
        private const val TAG: String = "InitializeIdentityUseCase"
    }
}
