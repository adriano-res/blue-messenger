package com.bluemessenger.presentation

import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.BluetoothAdapterDisabledError
import com.bluemessenger.domain.error.MissingBluetoothPermissionError
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.LocalIdentity
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.storage.IdentityRepository
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.usecase.FakeAppLogger
import com.bluemessenger.domain.usecase.FakeBluetoothScanner
import com.bluemessenger.domain.usecase.FakeKeyStoreProvider
import com.bluemessenger.domain.usecase.InMemoryIdentityRepository
import com.bluemessenger.domain.usecase.InMemoryPeerRepository
import com.bluemessenger.domain.usecase.peer.DiscoverPeersUseCase
import com.bluemessenger.domain.usecase.peer.InitializeIdentityUseCase
import com.bluemessenger.domain.usecase.peer.ObserveConversationsUseCase
import com.bluemessenger.domain.usecase.testDispatchers
import com.bluemessenger.presentation.discovery.DiscoveryIntent
import com.bluemessenger.presentation.discovery.DiscoveryViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelTest {
    @Test
    fun `loads existing identity when already persisted and skips setup`() =
        runTest {
            val now = Instant(1_700_000_000_000L)
            val keyPair = IdentityKeyPair.generate()
            val existingIdentity =
                LocalIdentity(
                    id = PeerId.generate(now),
                    nickname = "Alice",
                    identityPublicKey = keyPair.publicKey,
                    createdAt = now,
                )
            val viewModel =
                buildViewModel(
                    identityRepo = InMemoryIdentityRepository(existingIdentity),
                    keyStore = FakeKeyStoreProvider(keyPair),
                )

            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(existingIdentity, state.localIdentity)
            assertEquals("Alice", state.nicknameInput)
            assertFalse(state.isLoadingIdentity)
            assertNull(state.error)
        }

    @Test
    fun `validates blank nickname and initializes local identity on valid submit`() =
        runTest {
            val viewModel = buildViewModel()
            runCurrent()
            assertNull(viewModel.uiState.value.localIdentity)

            viewModel.onIntent(DiscoveryIntent.UpdateNicknameInput("   "))
            viewModel.onIntent(DiscoveryIntent.SubmitIdentity)
            runCurrent()

            assertIs<PresentationError.Validation>(viewModel.uiState.value.error)
            assertNull(viewModel.uiState.value.localIdentity)

            viewModel.onIntent(DiscoveryIntent.UpdateNicknameInput("  Alice  "))
            assertNull(viewModel.uiState.value.error)

            viewModel.onIntent(DiscoveryIntent.SubmitIdentity)
            runCurrent()

            val created = viewModel.uiState.value.localIdentity
            assertNotNull(created)
            assertEquals("Alice", created.nickname)
            assertFalse(viewModel.uiState.value.isInitializingIdentity)
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun `observes scanning, advertising, discovered peers, conversations, and permission or adapter errors`() =
        runTest {
            val scanner = FakeBluetoothScanner()
            val peerRepo = InMemoryPeerRepository()
            val now = Instant(1_700_000_000_000L)
            val viewModel = buildViewModel(scanner = scanner, peerRepo = peerRepo)

            viewModel.onIntent(DiscoveryIntent.UpdateNicknameInput("Alice"))
            viewModel.onIntent(DiscoveryIntent.SubmitIdentity)
            runCurrent()

            viewModel.onIntent(DiscoveryIntent.StartDiscovery)
            viewModel.onIntent(DiscoveryIntent.StartAdvertising)
            runCurrent()
            assertTrue(viewModel.uiState.value.isScanning)
            assertTrue(viewModel.uiState.value.isAdvertising)

            val bob = Peer(PeerId.generate(now), "Bob", BluetoothAddress("11:22:33:44:55:66"), lastSeenAt = now)
            scanner.emitPeer(bob)
            val conv = Conversation(ConversationId.generate(now), bob, SessionState.DISCONNECTED, now)
            peerRepo.upsertConversation(conv).getOrThrow()
            runCurrent()

            assertEquals(listOf(bob), viewModel.uiState.value.discoveredPeers)
            assertEquals(listOf(conv), viewModel.uiState.value.conversations)

            viewModel.onIntent(DiscoveryIntent.SelectPeer(bob))
            assertEquals(bob.id, viewModel.uiState.value.selectedPeerId)

            viewModel.onIntent(DiscoveryIntent.StopDiscovery)
            viewModel.onIntent(DiscoveryIntent.StopAdvertising)
            runCurrent()
            assertFalse(viewModel.uiState.value.isScanning)
            assertFalse(viewModel.uiState.value.isAdvertising)

            scanner.startDiscoveryError = MissingBluetoothPermissionError("BLUETOOTH_SCAN")
            viewModel.onIntent(DiscoveryIntent.StartDiscovery)
            runCurrent()
            val permError = assertIs<PresentationError.MissingPermission>(viewModel.uiState.value.error)
            assertEquals("BLUETOOTH_SCAN", permError.permission)

            viewModel.onIntent(DiscoveryIntent.DismissError)
            assertNull(viewModel.uiState.value.error)

            scanner.startAdvertisingError = BluetoothAdapterDisabledError()
            viewModel.onIntent(DiscoveryIntent.StartAdvertising)
            runCurrent()
            assertIs<PresentationError.AdapterDisabled>(viewModel.uiState.value.error)
        }

    @Test
    fun `ignores reentrant SubmitIdentity and prevents slow loadExisting from overwriting created identity`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val baseRepo = InMemoryIdentityRepository()
            val gatedRepo =
                object : IdentityRepository {
                    override fun observeLocalIdentity() = baseRepo.observeLocalIdentity()

                    override suspend fun getLocalIdentity(): Result<LocalIdentity?> {
                        gate.await()
                        return baseRepo.getLocalIdentity()
                    }

                    override suspend fun saveLocalIdentity(identity: LocalIdentity): Result<Unit> {
                        return baseRepo.saveLocalIdentity(identity)
                    }
                }
            val viewModel = buildViewModel(identityRepo = gatedRepo)
            runCurrent()
            assertTrue(viewModel.uiState.value.isLoadingIdentity)

            viewModel.onIntent(DiscoveryIntent.UpdateNicknameInput("Alice"))
            viewModel.onIntent(DiscoveryIntent.SubmitIdentity)
            assertTrue(viewModel.uiState.value.isInitializingIdentity)

            viewModel.onIntent(DiscoveryIntent.UpdateNicknameInput("Bob"))
            viewModel.onIntent(DiscoveryIntent.SubmitIdentity)

            gate.complete(Unit)
            runCurrent()

            assertEquals("Alice", viewModel.uiState.value.localIdentity?.nickname)
            assertFalse(viewModel.uiState.value.isInitializingIdentity)
        }

    @Test
    fun `clear cancels managed scope and stops further scanner or conversation updates`() =
        runTest {
            val scanner = FakeBluetoothScanner()
            val dispatchers = testDispatchers(testScheduler)
            val ownedScope = CoroutineScope(SupervisorJob() + dispatchers.main)
            val logger = FakeAppLogger()
            val viewModel =
                DiscoveryViewModel(
                    initializeIdentityUseCase =
                        InitializeIdentityUseCase(
                            identityRepository = InMemoryIdentityRepository(),
                            keyStoreProvider = FakeKeyStoreProvider(),
                            dispatchers = dispatchers,
                            logger = logger,
                            clock = { Instant(1_700_000_000_000L) },
                        ),
                    discoverPeersUseCase = DiscoverPeersUseCase(scanner, logger),
                    observeConversationsUseCase = ObserveConversationsUseCase(InMemoryPeerRepository(), logger),
                    dispatchers = dispatchers,
                    logger = logger,
                    scope = ownedScope,
                )
            runCurrent()

            viewModel.clear()
            assertFalse(ownedScope.isActive)

            val bob =
                Peer(
                    PeerId.generate(Instant(1_700_000_000_000L)),
                    "Bob",
                    BluetoothAddress("11:22:33:44:55:66"),
                    lastSeenAt = Instant(1_700_000_000_000L),
                )
            scanner.emitPeer(bob)
            runCurrent()

            assertTrue(viewModel.uiState.value.discoveredPeers.isEmpty())
            assertNull(ownedScope.coroutineContext[Job]?.takeIf { it.isActive })
        }

    private fun kotlinx.coroutines.test.TestScope.buildViewModel(
        identityRepo: IdentityRepository = InMemoryIdentityRepository(),
        keyStore: FakeKeyStoreProvider = FakeKeyStoreProvider(),
        scanner: FakeBluetoothScanner = FakeBluetoothScanner(),
        peerRepo: InMemoryPeerRepository = InMemoryPeerRepository(),
    ): DiscoveryViewModel {
        val logger = FakeAppLogger()
        val dispatchers = testDispatchers(testScheduler)
        val clock = { Instant(testScheduler.currentTime + 1_700_000_000_000L) }
        return DiscoveryViewModel(
            initializeIdentityUseCase =
                InitializeIdentityUseCase(
                    identityRepository = identityRepo,
                    keyStoreProvider = keyStore,
                    dispatchers = dispatchers,
                    logger = logger,
                    clock = clock,
                ),
            discoverPeersUseCase = DiscoverPeersUseCase(scanner, logger),
            observeConversationsUseCase = ObserveConversationsUseCase(peerRepo, logger),
            dispatchers = dispatchers,
            logger = logger,
            scope = backgroundScope,
        )
    }
}
