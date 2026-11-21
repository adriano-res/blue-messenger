package com.bluemessenger.domain.usecase.peer

import com.bluemessenger.crypto.CryptoServiceImpl
import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.StorageError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.model.LocalIdentity
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.usecase.FakeAppLogger
import com.bluemessenger.domain.usecase.FakeKeyStoreProvider
import com.bluemessenger.domain.usecase.InMemoryIdentityRepository
import com.bluemessenger.domain.usecase.InMemoryPeerRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VerifyPeerSafetyNumberUseCaseTest {
    @Test
    fun `computes identical deterministic Safety Number on both peers and marks peer verified`() =
        runTest {
            val now = Instant(1_700_000_000_000L)
            val aliceKeyPair = IdentityKeyPair.generate()
            val bobKeyPair = IdentityKeyPair.generate()
            val alicePeerId = PeerId.generate(now)
            val bobPeerId = PeerId.generate(Instant(now.epochMillis + 1L))

            val alicePeerRepo = InMemoryPeerRepository()
            alicePeerRepo.upsertPeer(
                Peer(bobPeerId, "Bob", BluetoothAddress("11:22:33:44:55:66"), bobKeyPair.publicKey, false, now),
            ).getOrThrow()
            val bobPeerRepo = InMemoryPeerRepository()
            bobPeerRepo.upsertPeer(
                Peer(alicePeerId, "Alice", BluetoothAddress("AA:BB:CC:DD:EE:FF"), aliceKeyPair.publicKey, false, now),
            ).getOrThrow()

            val aliceLogger = FakeAppLogger()
            val aliceUseCase =
                VerifyPeerSafetyNumberUseCase(
                    InMemoryIdentityRepository(LocalIdentity(alicePeerId, "Alice", aliceKeyPair.publicKey, now)),
                    alicePeerRepo,
                    CryptoServiceImpl(FakeKeyStoreProvider(aliceKeyPair)),
                    aliceLogger,
                )
            val bobUseCase =
                VerifyPeerSafetyNumberUseCase(
                    InMemoryIdentityRepository(LocalIdentity(bobPeerId, "Bob", bobKeyPair.publicKey, now)),
                    bobPeerRepo,
                    CryptoServiceImpl(FakeKeyStoreProvider(bobKeyPair)),
                    FakeAppLogger(),
                )

            val aliceSafetyNumber = aliceUseCase.computeSafetyNumber(bobPeerId).getOrThrow()
            val bobSafetyNumber = bobUseCase.computeSafetyNumber(alicePeerId).getOrThrow()
            assertEquals(aliceSafetyNumber, bobSafetyNumber)
            assertEquals(60, aliceSafetyNumber.filter(Char::isDigit).length)

            aliceUseCase.markPeerVerified(bobPeerId, verified = true).getOrThrow()
            assertTrue(alicePeerRepo.getPeerById(bobPeerId).getOrThrow()!!.isVerified)
            aliceLogger.assertNoSensitiveData(
                aliceKeyPair.publicKey.toHex(),
                bobKeyPair.publicKey.toHex(),
                aliceSafetyNumber,
            )
        }

    @Test
    fun `fails fast when local identity is missing, peer is absent, or peer has no identity key`() =
        runTest {
            val now = Instant(1_700_000_000_000L)
            val aliceKeyPair = IdentityKeyPair.generate()
            val missingPeerId = PeerId.generate(now)
            val unkeyedPeerId = PeerId.generate(Instant(now.epochMillis + 1L))

            val emptyIdentityRepo = InMemoryIdentityRepository(null)
            val peerRepo = InMemoryPeerRepository()
            peerRepo.upsertPeer(
                Peer(
                    id = unkeyedPeerId,
                    nickname = "Unkeyed",
                    address = BluetoothAddress("22:33:44:55:66:77"),
                    identityKey = null,
                    isVerified = false,
                    lastSeenAt = now,
                ),
            ).getOrThrow()

            val crypto = CryptoServiceImpl(FakeKeyStoreProvider(aliceKeyPair))
            val noLocalIdentityUseCase =
                VerifyPeerSafetyNumberUseCase(emptyIdentityRepo, peerRepo, crypto, FakeAppLogger())

            assertIs<InvalidStateTransitionError>(
                noLocalIdentityUseCase.computeSafetyNumber(unkeyedPeerId).exceptionOrNull()?.toDomainErrorOrNull(),
            )

            val readyIdentityRepo =
                InMemoryIdentityRepository(
                    LocalIdentity(PeerId.generate(now), "Alice", aliceKeyPair.publicKey, now),
                )
            val useCase = VerifyPeerSafetyNumberUseCase(readyIdentityRepo, peerRepo, crypto, FakeAppLogger())

            assertIs<StorageError>(
                useCase.computeSafetyNumber(missingPeerId).exceptionOrNull()?.toDomainErrorOrNull(),
            )
            assertIs<InvalidStateTransitionError>(
                useCase.computeSafetyNumber(unkeyedPeerId).exceptionOrNull()?.toDomainErrorOrNull(),
            )
            assertIs<StorageError>(
                useCase.markPeerVerified(missingPeerId).exceptionOrNull()?.toDomainErrorOrNull(),
            )
            assertIs<InvalidStateTransitionError>(
                useCase.markPeerVerified(unkeyedPeerId).exceptionOrNull()?.toDomainErrorOrNull(),
            )
        }
}
