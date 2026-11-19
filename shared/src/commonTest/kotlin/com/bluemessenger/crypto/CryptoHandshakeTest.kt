package com.bluemessenger.crypto

import com.bluemessenger.crypto.constants.CryptoConstants
import com.bluemessenger.crypto.model.IdentityKeyPair
import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.DecryptionFailedError
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.toDomainErrorOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoHandshakeTest {
    @Test
    fun `completes 3-way Noise XX handshake with matching root keys and verified identities`() =
        runTest {
            val aliceIdentity = IdentityKeyPair.generate()
            val bobIdentity = IdentityKeyPair.generate()

            val initiator = CryptoHandshake(isInitiator = true, identityKeyPair = aliceIdentity)
            val responder = CryptoHandshake(isInitiator = false, identityKeyPair = bobIdentity)

            val msg1 = initiator.start().getOrThrow()
            assertEquals(CryptoConstants.X25519_KEY_LENGTH_BYTES, msg1.size)

            val step2 = responder.processMessage(msg1).getOrThrow()
            assertNull(step2.completion)
            val msg2 = assertNotNull(step2.outboundMessage)
            assertEquals(CryptoConstants.HANDSHAKE_MSG2_SIZE_BYTES, msg2.size)

            val step3 = initiator.processMessage(msg2).getOrThrow()
            val initiatorCompletion = assertNotNull(step3.completion)
            val msg3 = assertNotNull(step3.outboundMessage)
            assertEquals(CryptoConstants.HANDSHAKE_ENCRYPTED_PROOF_SIZE_BYTES, msg3.size)

            val step4 = responder.processMessage(msg3).getOrThrow()
            val responderCompletion = assertNotNull(step4.completion)
            assertNull(step4.outboundMessage)

            assertEquals(CryptoConstants.SYMMETRIC_KEY_LENGTH_BYTES, initiatorCompletion.rootKey.size)
            assertContentEquals(initiatorCompletion.rootKey, responderCompletion.rootKey)
            assertEquals(bobIdentity.publicKey, initiatorCompletion.remoteIdentityKey)
            assertEquals(aliceIdentity.publicKey, responderCompletion.remoteIdentityKey)
            assertTrue(initiatorCompletion.isInitiator)
            assertFalse(responderCompletion.isInitiator)
            assertNotNull(initiatorCompletion.remoteEphemeralPublicKey)
            assertNotNull(responderCompletion.localEphemeralKeyPair)

            initiatorCompletion.zeroize()
            responderCompletion.zeroize()
            aliceIdentity.zeroize()
            bobIdentity.zeroize()
        }

    @Test
    fun `rejects tampered msg2 with DecryptionFailedError`() =
        runTest {
            val aliceIdentity = IdentityKeyPair.generate()
            val bobIdentity = IdentityKeyPair.generate()

            val initiator = CryptoHandshake(isInitiator = true, identityKeyPair = aliceIdentity)
            val responder = CryptoHandshake(isInitiator = false, identityKeyPair = bobIdentity)

            val msg1 = initiator.start().getOrThrow()
            val msg2 = responder.processMessage(msg1).getOrThrow().outboundMessage!!

            val tamperedMsg2 =
                msg2.copyOf().apply {
                    val lastIndex = size - 1
                    this[lastIndex] = (this[lastIndex].toInt() xor 0x01).toByte()
                }
            val result = initiator.processMessage(tamperedMsg2)
            assertTrue(result.isFailure)
            assertIs<DecryptionFailedError>(result.exceptionOrNull()?.toDomainErrorOrNull())

            aliceIdentity.zeroize()
            bobIdentity.zeroize()
        }

    @Test
    fun `rejects forged Ed25519 signature in msg2 with DecryptionFailedError`() =
        runTest {
            val aliceIdentity = IdentityKeyPair.generate()
            val bobIdentity = IdentityKeyPair.generate()

            val initiator = CryptoHandshake(isInitiator = true, identityKeyPair = aliceIdentity)
            val forgedResponder =
                CryptoHandshake(isInitiator = false, localIdentityPublicKey = bobIdentity.publicKey) {
                    Result.success(ByteArray(CryptoConstants.ED25519_SIGNATURE_LENGTH_BYTES) { 0x5A })
                }

            val msg1 = initiator.start().getOrThrow()
            val forgedMsg2 = forgedResponder.processMessage(msg1).getOrThrow().outboundMessage!!

            val result = initiator.processMessage(forgedMsg2)
            assertTrue(result.isFailure)
            assertIs<DecryptionFailedError>(result.exceptionOrNull()?.toDomainErrorOrNull())

            aliceIdentity.zeroize()
            bobIdentity.zeroize()
        }

    @Test
    fun `rejects tampered msg3 and forged msg3 signature with DecryptionFailedError`() =
        runTest {
            val aliceIdentity = IdentityKeyPair.generate()
            val bobIdentity = IdentityKeyPair.generate()

            val initiator = CryptoHandshake(isInitiator = true, identityKeyPair = aliceIdentity)
            val responder = CryptoHandshake(isInitiator = false, identityKeyPair = bobIdentity)

            val msg1 = initiator.start().getOrThrow()
            val msg2 = responder.processMessage(msg1).getOrThrow().outboundMessage!!
            val msg3 = initiator.processMessage(msg2).getOrThrow().outboundMessage!!

            val tamperedMsg3 = msg3.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
            val tamperedResult = responder.processMessage(tamperedMsg3)
            assertTrue(tamperedResult.isFailure)
            assertIs<DecryptionFailedError>(tamperedResult.exceptionOrNull()?.toDomainErrorOrNull())

            val forgedInitiator =
                CryptoHandshake(isInitiator = true, localIdentityPublicKey = aliceIdentity.publicKey) {
                    Result.success(ByteArray(CryptoConstants.ED25519_SIGNATURE_LENGTH_BYTES) { 0x11 })
                }
            val responder2 = CryptoHandshake(isInitiator = false, identityKeyPair = bobIdentity)
            val fMsg1 = forgedInitiator.start().getOrThrow()
            val fMsg2 = responder2.processMessage(fMsg1).getOrThrow().outboundMessage!!
            val forgedMsg3 = forgedInitiator.processMessage(fMsg2).getOrThrow().outboundMessage!!

            val forgedResult = responder2.processMessage(forgedMsg3)
            assertTrue(forgedResult.isFailure)
            assertIs<DecryptionFailedError>(forgedResult.exceptionOrNull()?.toDomainErrorOrNull())

            aliceIdentity.zeroize()
            bobIdentity.zeroize()
        }

    @Test
    fun `rejects low-order X25519 key truncated packets and invalid state transitions`() =
        runTest {
            val aliceIdentity = IdentityKeyPair.generate()
            val bobIdentity = IdentityKeyPair.generate()

            val responder = CryptoHandshake(isInitiator = false, identityKeyPair = bobIdentity)
            val lowOrderResult = responder.processMessage(ByteArray(CryptoConstants.X25519_KEY_LENGTH_BYTES))
            assertTrue(lowOrderResult.isFailure)
            assertIs<DecryptionFailedError>(lowOrderResult.exceptionOrNull()?.toDomainErrorOrNull())

            val responderAfterFailure = responder.processMessage(ByteArray(CryptoConstants.X25519_KEY_LENGTH_BYTES))
            assertTrue(responderAfterFailure.isFailure)
            assertIs<InvalidStateTransitionError>(responderAfterFailure.exceptionOrNull()?.toDomainErrorOrNull())

            val responder2 = CryptoHandshake(isInitiator = false, identityKeyPair = bobIdentity)
            val truncatedResult = responder2.processMessage(ByteArray(CryptoConstants.X25519_KEY_LENGTH_BYTES - 1))
            assertTrue(truncatedResult.isFailure)
            assertIs<CorruptedFrameError>(truncatedResult.exceptionOrNull()?.toDomainErrorOrNull())

            val initiator = CryptoHandshake(isInitiator = true, identityKeyPair = aliceIdentity)
            assertIs<InvalidStateTransitionError>(
                initiator
                    .processMessage(ByteArray(CryptoConstants.HANDSHAKE_MSG2_SIZE_BYTES))
                    .exceptionOrNull()
                    ?.toDomainErrorOrNull(),
            )
            initiator.start().getOrThrow()
            assertIs<InvalidStateTransitionError>(
                initiator.start().exceptionOrNull()?.toDomainErrorOrNull(),
            )

            aliceIdentity.zeroize()
            bobIdentity.zeroize()
        }
}
