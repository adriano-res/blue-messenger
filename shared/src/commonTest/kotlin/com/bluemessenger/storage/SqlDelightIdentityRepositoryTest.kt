package com.bluemessenger.storage

import com.bluemessenger.domain.model.LocalIdentity
import com.bluemessenger.storage.repository.SqlDelightIdentityRepositoryImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SqlDelightIdentityRepositoryTest {
    private fun TestScope.createRepository(): SqlDelightIdentityRepositoryImpl =
        SqlDelightIdentityRepositoryImpl(BlueMessengerDatabase(createInMemoryTestDriver()), testDispatchers())

    @Test
    fun `getLocalIdentity returns null before onboarding`() =
        runTest {
            val repository = createRepository()

            assertNull(repository.getLocalIdentity().getOrThrow())
        }

    @Test
    fun `saveLocalIdentity persists and reloads the identity`() =
        runTest {
            val repository = createRepository()
            val identity = testLocalIdentity()

            repository.saveLocalIdentity(identity).getOrThrow()

            assertEquals(identity, repository.getLocalIdentity().getOrThrow())
        }

    @Test
    fun `saveLocalIdentity overwrites the singleton row`() =
        runTest {
            val repository = createRepository()
            val updated = testLocalIdentity(nickname = "renamed")

            repository.saveLocalIdentity(testLocalIdentity()).getOrThrow()
            repository.saveLocalIdentity(updated).getOrThrow()

            assertEquals(updated, repository.getLocalIdentity().getOrThrow())
        }

    @Test
    fun `observeLocalIdentity emits null then every saved identity`() =
        runTest {
            val repository = createRepository()
            val identity = testLocalIdentity()
            val emissions = mutableListOf<LocalIdentity?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeLocalIdentity().toList(emissions)
            }

            repository.saveLocalIdentity(identity).getOrThrow()

            assertEquals(listOf(null, identity), emissions)
        }
}
