package com.bluemessenger.crypto

import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.crypto.model.EphemeralKeyPair
import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.crypto.model.RatchetHeader
import com.bluemessenger.crypto.util.ByteArrayZeroizer
import com.bluemessenger.domain.error.DecryptionFailedError
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.ports.crypto.KeyStoreProvider
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CryptoSessionTest {
    @Test
    fun `exchanges multi-turn messages with forward secrecy break-in recovery and bounded overhead`() =
        runTest {
            val (alice, bob) = establishPairedSessions()

            val ad = "session-aad".encodeToByteArray()
            val plaintext = "Hello from Alice".encodeToByteArray()
            val enc1 = alice.encrypt(plaintext, ad).getOrThrow()
            val enc2 = alice.encrypt(plaintext, ad).getOrThrow()

            val overhead = enc1.size - plaintext.size
            assertEquals(CryptoConstants.RATCHET_OVERHEAD_BYTES, overhead)
            assertTrue(overhead <= CryptoConstants.MAX_CRYPTO_OVERHEAD_BYTES)
            assertFalse(enc1.contentEquals(enc2))

            assertContentEquals(plaintext, bob.decrypt(enc1, ad).getOrThrow())
            assertContentEquals(plaintext, bob.decrypt(enc2, ad).getOrThrow())

            val aliceDh0 = RatchetHeader.decode(enc1).getOrThrow().dhPublicKey

            val reply1 = bob.encrypt("Reply 1 from Bob".encodeToByteArray(), ad).getOrThrow()
            val bobDh0 = RatchetHeader.decode(reply1).getOrThrow().dhPublicKey
            assertContentEquals("Reply 1 from Bob".encodeToByteArray(), alice.decrypt(reply1, ad).getOrThrow())

            val enc3 = alice.encrypt("Turn 2 from Alice".encodeToByteArray(), ad).getOrThrow()
            val aliceDh1 = RatchetHeader.decode(enc3).getOrThrow().dhPublicKey
            assertNotEquals(aliceDh0, aliceDh1)
            assertContentEquals("Turn 2 from Alice".encodeToByteArray(), bob.decrypt(enc3, ad).getOrThrow())

            val reply2 = bob.encrypt("Turn 2 from Bob".encodeToByteArray(), ad).getOrThrow()
            val bobDh1 = RatchetHeader.decode(reply2).getOrThrow().dhPublicKey
            assertNotEquals(bobDh0, bobDh1)
            assertContentEquals("Turn 2 from Bob".encodeToByteArray(), alice.decrypt(reply2, ad).getOrThrow())

            alice.zeroize()
            bob.zeroize()
        }

    @Test
    fun `supports responder sending first before receiving from initiator`() =
        runTest {
            val (alice, bob) = establishPairedSessions()

            val bobFirst = bob.encrypt("Bob speaks first".encodeToByteArray()).getOrThrow()
            assertContentEquals("Bob speaks first".encodeToByteArray(), alice.decrypt(bobFirst).getOrThrow())

            val aliceReply = alice.encrypt("Alice responds".encodeToByteArray()).getOrThrow()
            assertContentEquals("Alice responds".encodeToByteArray(), bob.decrypt(aliceReply).getOrThrow())

            alice.zeroize()
            bob.zeroize()
        }

    @Test
    fun `handles out-of-order messages within chain and across DH turns and rejects replay`() =
        runTest {
            val (alice, bob) = establishPairedSessions()

            val m0 = alice.encrypt("msg-0".encodeToByteArray()).getOrThrow()
            val m1 = alice.encrypt("msg-1".encodeToByteArray()).getOrThrow()
            val m2 = alice.encrypt("msg-2".encodeToByteArray()).getOrThrow()

            assertContentEquals("msg-2".encodeToByteArray(), bob.decrypt(m2).getOrThrow())
            assertContentEquals("msg-0".encodeToByteArray(), bob.decrypt(m0).getOrThrow())

            val bobReply = bob.encrypt("bob-turn".encodeToByteArray()).getOrThrow()
            assertContentEquals("bob-turn".encodeToByteArray(), alice.decrypt(bobReply).getOrThrow())

            val m3AfterTurn = alice.encrypt("msg-3-new-turn".encodeToByteArray()).getOrThrow()
            assertContentEquals("msg-3-new-turn".encodeToByteArray(), bob.decrypt(m3AfterTurn).getOrThrow())

            assertContentEquals("msg-1".encodeToByteArray(), bob.decrypt(m1).getOrThrow())

            val replaySkipped = bob.decrypt(m1)
            assertTrue(replaySkipped.isFailure)
            assertIs<DecryptionFailedError>(replaySkipped.exceptionOrNull()?.toDomainErrorOrNull())

            val replaySequential = bob.decrypt(m3AfterTurn)
            assertTrue(replaySequential.isFailure)
            assertIs<DecryptionFailedError>(replaySequential.exceptionOrNull()?.toDomainErrorOrNull())

            alice.zeroize()
            bob.zeroize()
        }

    @Test
    fun `tampered header ciphertext tag or AD fails with DecryptionFailedError without corrupting session`() =
        runTest {
            val (alice, bob) = establishPairedSessions()
            val ad = "auth-data".encodeToByteArray()

            val msg1 = alice.encrypt("First valid message".encodeToByteArray(), ad).getOrThrow()
            val msg2 = alice.encrypt("Second valid message".encodeToByteArray(), ad).getOrThrow()

            val tamperedHeaderDh = msg1.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
            assertDecryptionFailed(bob.decrypt(tamperedHeaderDh, ad))

            val tamperedCounter =
                msg1.copyOf().apply {
                    val counterByteIndex = CryptoConstants.RATCHET_HEADER_SIZE_BYTES - 1
                    this[counterByteIndex] = (this[counterByteIndex].toInt() xor 0x01).toByte()
                }
            assertDecryptionFailed(bob.decrypt(tamperedCounter, ad))

            val tamperedCiphertext =
                msg1.copyOf().apply {
                    val ciphertextIndex = CryptoConstants.RATCHET_HEADER_SIZE_BYTES
                    this[ciphertextIndex] = (this[ciphertextIndex].toInt() xor 0x01).toByte()
                }
            assertDecryptionFailed(bob.decrypt(tamperedCiphertext, ad))

            val tamperedTag =
                msg1.copyOf().apply {
                    val tagIndex = size - 1
                    this[tagIndex] = (this[tagIndex].toInt() xor 0x01).toByte()
                }
            assertDecryptionFailed(bob.decrypt(tamperedTag, ad))

            val tamperedAd = ad.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
            assertDecryptionFailed(bob.decrypt(msg1, tamperedAd))

            assertContentEquals("First valid message".encodeToByteArray(), bob.decrypt(msg1, ad).getOrThrow())
            assertContentEquals("Second valid message".encodeToByteArray(), bob.decrypt(msg2, ad).getOrThrow())

            alice.zeroize()
            bob.zeroize()
        }

    @Test
    fun `rejects excessive skipped message count and calls after zeroize`() =
        runTest {
            val (alice, bob) = establishPairedSessions()
            val excessiveSkipLimit = 2
            val smallWindowRoot = CryptoEngine.randomBytes(CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES)
            val responderPair = EphemeralKeyPair.generate()
            val smallAlice =
                CryptoSession.createInitiator(
                    smallWindowRoot,
                    responderPair.publicKey,
                    excessiveSkipLimit,
                ).getOrThrow()
            val smallBob =
                CryptoSession.createResponder(
                    smallWindowRoot,
                    responderPair,
                    excessiveSkipLimit,
                ).getOrThrow()

            smallAlice.encrypt("0".encodeToByteArray()).getOrThrow()
            smallAlice.encrypt("1".encodeToByteArray()).getOrThrow()
            smallAlice.encrypt("2".encodeToByteArray()).getOrThrow()
            val skippedTooFar = smallAlice.encrypt("3".encodeToByteArray()).getOrThrow()

            assertDecryptionFailed(smallBob.decrypt(skippedTooFar))

            alice.zeroize()
            assertIs<InvalidStateTransitionError>(
                alice.encrypt("after-zeroize".encodeToByteArray()).exceptionOrNull()?.toDomainErrorOrNull(),
            )
            assertIs<InvalidStateTransitionError>(
                alice
                    .decrypt(ByteArray(CryptoConstants.RATCHET_OVERHEAD_BYTES))
                    .exceptionOrNull()
                    ?.toDomainErrorOrNull(),
            )

            bob.zeroize()
            smallAlice.zeroize()
            smallBob.zeroize()
        }

    @Test
    fun `CryptoServiceImpl performs end-to-end handshake messaging safety number and session cleanup`() =
        runTest {
            val aliceKeyStore = InMemoryFakeKeyStoreProvider().apply { generateIdentityKeyPair().getOrThrow() }
            val bobKeyStore = InMemoryFakeKeyStoreProvider().apply { generateIdentityKeyPair().getOrThrow() }
            val aliceCrypto = CryptoServiceImpl(aliceKeyStore)
            val bobCrypto = CryptoServiceImpl(bobKeyStore)

            val alicePeerIdForBob = PeerId.generate(Instant(1_000L))
            val bobPeerIdForAlice = PeerId.generate(Instant(2_000L))

            assertIs<InvalidStateTransitionError>(
                aliceCrypto.encrypt(bobPeerIdForAlice, "early".encodeToByteArray())
                    .exceptionOrNull()?.toDomainErrorOrNull(),
            )

            val msg1 = aliceCrypto.initializeHandshake(bobPeerIdForAlice, isInitiator = true).getOrThrow()
            val responderInit = bobCrypto.initializeHandshake(alicePeerIdForBob, isInitiator = false).getOrThrow()
            assertEquals(0, responderInit.size)

            val bobStep1 = bobCrypto.processHandshakeStep(alicePeerIdForBob, msg1).getOrThrow()
            assertFalse(bobStep1.isComplete)
            val msg2 = bobStep1.copyResponsePayload()!!

            val aliceStep2 = aliceCrypto.processHandshakeStep(bobPeerIdForAlice, msg2).getOrThrow()
            assertTrue(aliceStep2.isComplete)
            assertEquals(bobKeyStore.getIdentityPublicKey().getOrThrow(), aliceStep2.remoteIdentityKey)
            val msg3 = aliceStep2.copyResponsePayload()!!

            val bobStep3 = bobCrypto.processHandshakeStep(alicePeerIdForBob, msg3).getOrThrow()
            assertTrue(bobStep3.isComplete)
            assertEquals(aliceKeyStore.getIdentityPublicKey().getOrThrow(), bobStep3.remoteIdentityKey)

            val alicePub = aliceKeyStore.getIdentityPublicKey().getOrThrow()!!
            val bobPub = bobKeyStore.getIdentityPublicKey().getOrThrow()!!
            assertEquals(
                aliceCrypto.computeSafetyNumber(alicePub, bobPub).getOrThrow(),
                bobCrypto.computeSafetyNumber(bobPub, alicePub).getOrThrow(),
            )

            val associatedData = "packet-v1".encodeToByteArray()
            val encrypted =
                aliceCrypto.encrypt(bobPeerIdForAlice, "Blue Messenger E2EE!".encodeToByteArray(), associatedData)
                    .getOrThrow()
            assertContentEquals(
                "Blue Messenger E2EE!".encodeToByteArray(),
                bobCrypto.decrypt(alicePeerIdForBob, encrypted, associatedData).getOrThrow(),
            )

            aliceCrypto.clearSession(bobPeerIdForAlice).getOrThrow()
            assertIs<InvalidStateTransitionError>(
                aliceCrypto.encrypt(bobPeerIdForAlice, "after-clear".encodeToByteArray())
                    .exceptionOrNull()?.toDomainErrorOrNull(),
            )

            val uninitializedCrypto = CryptoServiceImpl(InMemoryFakeKeyStoreProvider())
            assertIs<InvalidStateTransitionError>(
                uninitializedCrypto.initializeHandshake(bobPeerIdForAlice, isInitiator = true)
                    .exceptionOrNull()?.toDomainErrorOrNull(),
            )
        }

    private suspend fun establishPairedSessions(): Pair<CryptoSession, CryptoSession> {
        val aliceIdentity = IdentityKeyPair.generate()
        val bobIdentity = IdentityKeyPair.generate()
        val initiator = CryptoHandshake(isInitiator = true, identityKeyPair = aliceIdentity)
        val responder = CryptoHandshake(isInitiator = false, identityKeyPair = bobIdentity)

        val msg1 = initiator.start().getOrThrow()
        val msg2 = responder.processMessage(msg1).getOrThrow().outboundMessage!!
        val aliceStep = initiator.processMessage(msg2).getOrThrow()
        val bobStep = responder.processMessage(aliceStep.outboundMessage!!).getOrThrow()

        val aliceCompletion = aliceStep.completion!!
        val bobCompletion = bobStep.completion!!

        val aliceSession =
            CryptoSession.createInitiator(
                aliceCompletion.rootKey,
                aliceCompletion.remoteEphemeralPublicKey!!,
            ).getOrThrow()
        val bobSession =
            CryptoSession.createResponder(
                bobCompletion.rootKey,
                bobCompletion.localEphemeralKeyPair!!,
            ).getOrThrow()

        ByteArrayZeroizer.zeroize(aliceCompletion.rootKey, bobCompletion.rootKey)
        aliceIdentity.zeroize()
        bobIdentity.zeroize()
        return aliceSession to bobSession
    }

    private fun assertDecryptionFailed(result: Result<ByteArray>) {
        assertTrue(result.isFailure)
        assertIs<DecryptionFailedError>(result.exceptionOrNull()?.toDomainErrorOrNull())
    }

    private class InMemoryFakeKeyStoreProvider : KeyStoreProvider {
        private var identityKeyPair: IdentityKeyPair? = null

        override suspend fun hasIdentityKeyPair(): Result<Boolean> = Result.success(identityKeyPair != null)

        override suspend fun generateIdentityKeyPair(): Result<PublicKey> {
            identityKeyPair?.zeroize()
            val generated = IdentityKeyPair.generate()
            identityKeyPair = generated
            return Result.success(generated.publicKey)
        }

        override suspend fun getIdentityPublicKey(): Result<PublicKey?> = Result.success(identityKeyPair?.publicKey)

        override suspend fun signWithIdentityKey(data: ByteArray): Result<ByteArray> =
            identityKeyPair?.sign(data)
                ?: InvalidStateTransitionError("FakeKeyStore", "EMPTY", "SIGN").asFailure()

        override suspend fun deleteIdentityKeyPair(): Result<Unit> {
            identityKeyPair?.zeroize()
            identityKeyPair = null
            return Result.success(Unit)
        }

        private fun InvalidStateTransitionError.asFailure(): Result<ByteArray> =
            Result.failure(com.bluemessenger.domain.error.DomainException(this))
    }
}
