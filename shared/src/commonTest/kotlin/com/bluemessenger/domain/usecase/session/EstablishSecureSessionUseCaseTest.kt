package com.bluemessenger.domain.usecase.session

import com.bluemessenger.crypto.CryptoServiceImpl
import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.domain.constants.SessionConstants
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.error.BluetoothAdapterDisabledError
import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.HandshakeTimeoutError
import com.bluemessenger.domain.error.InvalidInputError
import com.bluemessenger.domain.error.MissingBluetoothPermissionError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.transport.model.HandshakePacket
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.usecase.FakeAppLogger
import com.bluemessenger.domain.usecase.FakeBluetoothScanner
import com.bluemessenger.domain.usecase.FakeBluetoothTransport
import com.bluemessenger.domain.usecase.FakeKeyStoreProvider
import com.bluemessenger.domain.usecase.InMemoryPeerRepository
import com.bluemessenger.domain.usecase.createPairedActiveSession
import com.bluemessenger.domain.usecase.createPeerSideFixture
import com.bluemessenger.domain.usecase.message.util.MessagePayloadCodec
import com.bluemessenger.domain.usecase.peer.DiscoverPeersUseCase
import com.bluemessenger.domain.usecase.testDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EstablishSecureSessionUseCaseTest {
    @Test
    fun `discover peers use case exposes scanning advertising and propagates permission errors`() =
        runTest {
            val scanner = FakeBluetoothScanner()
            val discoverUseCase = DiscoverPeersUseCase(scanner, FakeAppLogger())

            assertTrue(discoverUseCase.startDiscovery().isSuccess)
            assertTrue(discoverUseCase.isScanning.value)
            val peer =
                Peer(
                    id = PeerId.generate(Instant(1_000L)),
                    nickname = "Bob",
                    address = BluetoothAddress("AA:BB:CC:DD:EE:01"),
                    lastSeenAt = Instant(1_000L),
                )
            scanner.emitPeer(peer)
            assertEquals(listOf(peer), discoverUseCase.discoveredPeers.value)
            assertTrue(discoverUseCase.stopDiscovery().isSuccess)
            assertFalse(discoverUseCase.isScanning.value)

            val blankResult = discoverUseCase.startAdvertising("   ")
            assertIs<InvalidInputError>(blankResult.exceptionOrNull()?.toDomainErrorOrNull())

            assertTrue(discoverUseCase.startAdvertising("Alice").isSuccess)
            assertTrue(discoverUseCase.isAdvertising.value)
            assertTrue(discoverUseCase.stopAdvertising().isSuccess)
            assertFalse(discoverUseCase.isAdvertising.value)

            scanner.startDiscoveryError = MissingBluetoothPermissionError("BLUETOOTH_SCAN")
            assertIs<MissingBluetoothPermissionError>(
                discoverUseCase.startDiscovery().exceptionOrNull()?.toDomainErrorOrNull(),
            )

            scanner.startAdvertisingError = BluetoothAdapterDisabledError()
            assertIs<BluetoothAdapterDisabledError>(
                discoverUseCase.startAdvertising("Alice").exceptionOrNull()?.toDomainErrorOrNull(),
            )
        }

    @Test
    fun `completes 3-way Noise XX handshake between initiator and responder and activates Double Ratchet`() =
        runTest {
            val harness = createPairedActiveSession()

            assertEquals(SessionState.SECURE_ACTIVE, harness.aliceConversation.state)
            assertEquals(SessionState.SECURE_ACTIVE, harness.bobConversation.state)
            assertEquals(
                harness.bob.keyStore.getIdentityPublicKey().getOrThrow(),
                harness.aliceConversation.peer.identityKey,
            )
            assertEquals(
                harness.alice.keyStore.getIdentityPublicKey().getOrThrow(),
                harness.bobConversation.peer.identityKey,
            )
            assertEquals(
                listOf(SessionState.CONNECTING, SessionState.HANDSHAKING, SessionState.SECURE_ACTIVE),
                harness.alice.peerRepo.conversationStateHistory,
            )

            val aad = MessagePayloadCodec.encodeSequenceNumberAad(0L)
            val ciphertext =
                harness.alice.crypto.encrypt(harness.bobPeer.id, "hello".encodeToByteArray(), aad).getOrThrow()
            val decrypted = harness.bob.crypto.decrypt(harness.alicePeer.id, ciphertext, aad).getOrThrow()
            assertContentEquals("hello".encodeToByteArray(), decrypted)
        }

    @Test
    fun `resets isVerified when remote peer identity key changes and preserves when unchanged`() =
        runTest {
            val harness = createPairedActiveSession()
            harness.alice.peerRepo.markPeerVerified(harness.bobPeer.id, verified = true).getOrThrow()

            val sameKeyResp = async { harness.bob.establish.acceptAndEstablish(harness.alicePeer) }
            runCurrent()
            val sameKeyConv =
                harness.alice.establish.connectAndEstablish(
                    harness.bobPeer.copy(identityKey = null, isVerified = false),
                ).getOrThrow()
            sameKeyResp.await().getOrThrow()
            assertTrue(sameKeyConv.peer.isVerified)

            harness.bob.keyStore.setKeyPair(IdentityKeyPair.generate())
            val rotatedKeyResp = async { harness.bob.establish.acceptAndEstablish(harness.alicePeer) }
            runCurrent()
            val rotatedKeyConv =
                harness.alice.establish.connectAndEstablish(
                    harness.bobPeer.copy(identityKey = null, isVerified = false),
                ).getOrThrow()
            rotatedKeyResp.await().getOrThrow()

            assertFalse(rotatedKeyConv.peer.isVerified)
            assertTrue(harness.alice.logger.entries.any { it.level == FakeAppLogger.Level.WARN })
        }

    @Test
    fun `preserves revoked verification and detects key rotation for peer without prior conversation`() =
        runTest {
            val harness = createPairedActiveSession()
            harness.alice.peerRepo.markPeerVerified(harness.bobPeer.id, verified = false).getOrThrow()

            val staleVerifiedPeer =
                harness.bobPeer.copy(
                    identityKey = harness.bob.keyStore.getIdentityPublicKey().getOrThrow(),
                    isVerified = true,
                )
            val revokedResp = async { harness.bob.establish.acceptAndEstablish(harness.alicePeer) }
            runCurrent()
            val revokedConv = harness.alice.establish.connectAndEstablish(staleVerifiedPeer).getOrThrow()
            revokedResp.await().getOrThrow()
            assertFalse(revokedConv.peer.isVerified)

            val freshPeerSide = createPeerSideFixture { Instant(1_700_000_000_000L) }
            val bobSide = createPeerSideFixture { Instant(1_700_000_000_000L) }
            freshPeerSide.transport.onSend = { packet ->
                bobSide.transport.emitIncomingPacket(packet)
                Result.success(Unit)
            }
            bobSide.transport.onSend = { packet ->
                freshPeerSide.transport.emitIncomingPacket(packet)
                Result.success(Unit)
            }
            val oldKeyPeer =
                harness.bobPeer.copy(
                    identityKey = IdentityKeyPair.generate().publicKey,
                    isVerified = true,
                )
            freshPeerSide.peerRepo.upsertPeer(oldKeyPeer).getOrThrow()

            val bobResp = async { bobSide.establish.acceptAndEstablish(harness.alicePeer) }
            runCurrent()
            val rotatedWithoutPriorConv =
                freshPeerSide.establish.connectAndEstablish(
                    oldKeyPeer.copy(identityKey = null, isVerified = false),
                ).getOrThrow()
            bobResp.await().getOrThrow()

            assertFalse(rotatedWithoutPriorConv.peer.isVerified)
            assertTrue(freshPeerSide.logger.entries.any { it.level == FakeAppLogger.Level.WARN })
        }

    @Test
    fun `clears crypto session, disconnects transport, and persists DISCONNECTED on handshake timeout`() =
        runTest {
            val dispatchers = testDispatchers(testScheduler)
            val aliceCrypto = CryptoServiceImpl(FakeKeyStoreProvider(IdentityKeyPair.generate()))
            val transport = FakeBluetoothTransport()
            val peerRepo = InMemoryPeerRepository()
            val now = Instant(1_700_000_000_000L)
            val useCase =
                EstablishSecureSessionUseCase(
                    transport,
                    aliceCrypto,
                    peerRepo,
                    dispatchers,
                    FakeAppLogger(),
                ) { now }
            val bob = Peer(PeerId.generate(now), "Bob", BluetoothAddress("11:22:33:44:55:66"), lastSeenAt = now)

            val deferred = async { useCase.connectAndEstablish(bob) }
            advanceTimeBy(SessionConstants.HANDSHAKE_TIMEOUT_MS)
            runCurrent()
            val result = deferred.await()

            assertTrue(result.isFailure)
            assertIs<HandshakeTimeoutError>(result.exceptionOrNull()?.toDomainErrorOrNull())
            assertEquals(1, transport.disconnectCount)
            assertEquals(SessionState.DISCONNECTED, peerRepo.getConversationByPeer(bob.id).getOrThrow()?.state)
        }

    @Test
    fun `fails fast with CorruptedFrameError and cleans up on malformed handshake frame`() =
        runTest {
            val dispatchers = testDispatchers(testScheduler)
            val aliceCrypto = CryptoServiceImpl(FakeKeyStoreProvider(IdentityKeyPair.generate()))
            val transport = FakeBluetoothTransport()
            val peerRepo = InMemoryPeerRepository()
            val now = Instant(1_700_000_000_000L)
            transport.onSend = {
                transport.emitIncomingPacket(HandshakePacket(step = 9, payload = byteArrayOf(1, 2)))
                Result.success(Unit)
            }
            val useCase =
                EstablishSecureSessionUseCase(
                    transport,
                    aliceCrypto,
                    peerRepo,
                    dispatchers,
                    FakeAppLogger(),
                ) { now }
            val bob = Peer(PeerId.generate(now), "Bob", BluetoothAddress("11:22:33:44:55:66"), lastSeenAt = now)

            val result = useCase.connectAndEstablish(bob)
            assertTrue(result.isFailure)
            assertIs<CorruptedFrameError>(result.exceptionOrNull()?.toDomainErrorOrNull())
            assertEquals(1, transport.disconnectCount)
            assertEquals(SessionState.DISCONNECTED, peerRepo.getConversationByPeer(bob.id).getOrThrow()?.state)
        }

    @Test
    fun `disconnect clears crypto session, closes transport, and persists DISCONNECTED conversation`() =
        runTest {
            val harness = createPairedActiveSession()
            val unknownPeerId = PeerId.generate(Instant(1_700_000_000_500L))
            assertTrue(harness.alice.establish.disconnect(unknownPeerId).isSuccess)

            val disconnectBefore = harness.alice.transport.disconnectCount
            harness.alice.establish.disconnect(harness.bobPeer.id).getOrThrow()

            assertEquals(disconnectBefore + 1, harness.alice.transport.disconnectCount)
            assertEquals(
                SessionState.DISCONNECTED,
                harness.alice.peerRepo.getConversationByPeer(harness.bobPeer.id).getOrThrow()?.state,
            )
            assertEquals(SessionState.DISCONNECTED, harness.alice.transport.sessionState.value)
        }
}
