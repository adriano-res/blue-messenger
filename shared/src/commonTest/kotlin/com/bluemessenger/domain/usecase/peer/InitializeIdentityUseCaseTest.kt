package com.bluemessenger.domain.usecase.peer

import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.domain.error.InvalidInputError
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.usecase.FakeAppLogger
import com.bluemessenger.domain.usecase.FakeKeyStoreProvider
import com.bluemessenger.domain.usecase.InMemoryIdentityRepository
import com.bluemessenger.domain.usecase.testDispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InitializeIdentityUseCaseTest {
    @Test
    fun `initializes and persists new local identity on first boot`() =
        runTest {
            val identityRepo = InMemoryIdentityRepository()
            val keyStore = FakeKeyStoreProvider()
            val logger = FakeAppLogger()
            val now = Instant(1_700_000_000_000L)
            val useCase =
                InitializeIdentityUseCase(
                    identityRepository = identityRepo,
                    keyStoreProvider = keyStore,
                    dispatchers = testDispatchers(testScheduler),
                    logger = logger,
                    clock = { now },
                )

            val created = useCase("  Alice  ").getOrThrow()

            assertEquals("Alice", created.nickname)
            assertEquals(now, created.createdAt)
            assertEquals(keyStore.getIdentityPublicKey().getOrThrow(), created.identityPublicKey)
            assertEquals(created, identityRepo.getLocalIdentity().getOrThrow())
        }

    @Test
    fun `returns existing identity without regenerating key when already initialized`() =
        runTest {
            val existingKeyPair = IdentityKeyPair.generate()
            val keyStore = FakeKeyStoreProvider(existingKeyPair)
            val identityRepo = InMemoryIdentityRepository()
            val logger = FakeAppLogger()
            var currentTime = 1_700_000_000_000L
            val useCase =
                InitializeIdentityUseCase(
                    identityRepository = identityRepo,
                    keyStoreProvider = keyStore,
                    dispatchers = testDispatchers(testScheduler),
                    logger = logger,
                    clock = { Instant(currentTime) },
                )

            val first = useCase("Alice").getOrThrow()
            currentTime += 60_000L
            val second = useCase("DifferentNickname").getOrThrow()

            assertEquals(first, second)
            assertEquals(first.id, second.id)
            assertEquals(existingKeyPair.publicKey, second.identityPublicKey)
        }

    @Test
    fun `re-initializes identity when keystore key differs from persisted profile`() =
        runTest {
            val identityRepo = InMemoryIdentityRepository()
            val keyStore = FakeKeyStoreProvider()
            val logger = FakeAppLogger()
            var currentTime = 1_700_000_000_000L
            val useCase =
                InitializeIdentityUseCase(
                    identityRepository = identityRepo,
                    keyStoreProvider = keyStore,
                    dispatchers = testDispatchers(testScheduler),
                    logger = logger,
                    clock = { Instant(currentTime) },
                )

            val first = useCase("Alice").getOrThrow()
            val rotatedKeyPair = IdentityKeyPair.generate()
            keyStore.setKeyPair(rotatedKeyPair)
            currentTime += 5_000L

            val refreshed = useCase("Alice").getOrThrow()
            assertNotEquals(first.id, refreshed.id)
            assertEquals(rotatedKeyPair.publicKey, refreshed.identityPublicKey)
            assertTrue(logger.entries.any { it.level == FakeAppLogger.Level.WARN })
        }

    @Test
    fun `rejects blank nickname fail-fast without generating keys or persisting profile`() =
        runTest {
            val identityRepo = InMemoryIdentityRepository()
            val keyStore = FakeKeyStoreProvider()
            val logger = FakeAppLogger()
            val useCase =
                InitializeIdentityUseCase(
                    identityRepository = identityRepo,
                    keyStoreProvider = keyStore,
                    dispatchers = testDispatchers(testScheduler),
                    logger = logger,
                    clock = { Instant(1_000L) },
                )

            val result = useCase("   ")
            assertTrue(result.isFailure)
            assertIs<InvalidInputError>(result.exceptionOrNull()?.toDomainErrorOrNull())
            assertNull(identityRepo.getLocalIdentity().getOrThrow())
            assertEquals(false, keyStore.hasIdentityKeyPair().getOrThrow())
        }

    @Test
    fun `propagates repository read or write failure fail-fast`() =
        runTest {
            val identityRepo = InMemoryIdentityRepository()
            identityRepo.saveFailure = StorageError("disk full")
            val keyStore = FakeKeyStoreProvider()
            val logger = FakeAppLogger()
            val useCase =
                InitializeIdentityUseCase(
                    identityRepository = identityRepo,
                    keyStoreProvider = keyStore,
                    dispatchers = testDispatchers(testScheduler),
                    logger = logger,
                    clock = { Instant(1_000L) },
                )

            val result = useCase("Alice")
            assertTrue(result.isFailure)
            assertIs<StorageError>(result.exceptionOrNull()?.toDomainErrorOrNull())
            assertNotNull(logger.entries.firstOrNull { it.level == FakeAppLogger.Level.ERROR })
        }

    @Test
    fun `short-circuits on initial identity read failure and logs warn when keystore key missing`() =
        runTest {
            val failingRepo = InMemoryIdentityRepository().apply { getFailure = StorageError("db locked") }
            val keyStore = FakeKeyStoreProvider()
            val errorLogger = FakeAppLogger()
            val failingUseCase =
                InitializeIdentityUseCase(
                    failingRepo,
                    keyStore,
                    testDispatchers(testScheduler),
                    errorLogger,
                ) { Instant(1_000L) }

            val readFailure = failingUseCase("Alice")
            assertIs<StorageError>(readFailure.exceptionOrNull()?.toDomainErrorOrNull())
            assertEquals(0, keyStore.hasKeyPairChecks)
            assertTrue(errorLogger.entries.any { it.level == FakeAppLogger.Level.ERROR })

            val activeRepo = InMemoryIdentityRepository()
            val warnLogger = FakeAppLogger()
            var nowMs = 1_000L
            val activeUseCase =
                InitializeIdentityUseCase(
                    activeRepo,
                    keyStore,
                    testDispatchers(testScheduler),
                    warnLogger,
                ) { Instant(nowMs) }

            val first = activeUseCase("Alice").getOrThrow()
            keyStore.deleteIdentityKeyPair().getOrThrow()
            nowMs += 5_000L
            val reinitialized = activeUseCase("Alice").getOrThrow()

            assertNotEquals(first.id, reinitialized.id)
            assertTrue(warnLogger.entries.any { it.level == FakeAppLogger.Level.WARN })
        }
}
