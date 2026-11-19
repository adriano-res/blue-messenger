package com.bluemessenger.domain

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.error.BluetoothAdapterDisabledError
import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.DecryptionFailedError
import com.bluemessenger.domain.error.DomainError
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.InvalidStateTransitionError
import com.bluemessenger.domain.error.MissingBluetoothPermissionError
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.error.toDomainErrorOrNull
import com.bluemessenger.domain.model.LocalIdentity
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.ports.crypto.HandshakeStepResult
import com.bluemessenger.domain.ports.transport.TransportConstants
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey
import com.bluemessenger.domain.primitives.UuidV7
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)
class DomainModelValidationTest {
    @Test
    fun `protocol constants match canonical specification`() {
        assertEquals("b10e0001-7f1a-4b9e-9c3d-1a2b3c4d5e6f", TransportConstants.CANONICAL_SERVICE_UUID)
        assertEquals(1, TransportConstants.PROTOCOL_VERSION)
    }

    @Test
    fun `bluetooth address validates and normalizes MAC format`() {
        val parsed = BluetoothAddress.parse(" aa:bb:cc:dd:ee:ff ").getOrThrow()
        assertEquals("AA:BB:CC:DD:EE:FF", parsed.value)
        assertEquals(BluetoothAddress("AA:BB:CC:DD:EE:FF"), parsed)

        assertTrue(BluetoothAddress.parse("invalid-mac").isFailure)
        assertTrue(BluetoothAddress.parse("AA:BB:CC:DD:EE").isFailure)
        assertTrue(BluetoothAddress.parse("GG:BB:CC:DD:EE:FF").isFailure)

        assertFailsWith<IllegalArgumentException> {
            BluetoothAddress("not-a-mac")
        }
        assertFailsWith<IllegalArgumentException> {
            BluetoothAddress("aa:bb:cc:dd:ee:ff")
        }
    }

    @Test
    fun `public key validates length and preserves defensive immutability`() {
        val raw = ByteArray(PublicKey.EXPECTED_KEY_LENGTH_BYTES) { index -> (index + 1).toByte() }
        val key = PublicKey.fromBytes(raw).getOrThrow()
        raw[0] = 0

        assertEquals(PublicKey.EXPECTED_KEY_LENGTH_BYTES, key.sizeBytes)
        assertEquals(1.toByte(), key.toByteArray()[0])

        val exported = key.toByteArray()
        exported[0] = 99
        assertEquals(1.toByte(), key.toByteArray()[0])

        val fromHex = PublicKey.fromHex(key.toHex()).getOrThrow()
        assertEquals(key, fromHex)
        assertEquals(key.hashCode(), fromHex.hashCode())

        assertTrue(PublicKey.fromBytes(ByteArray(16)).isFailure)
        assertTrue(PublicKey.fromHex("abcd").isFailure)
        assertTrue(PublicKey.fromHex("z".repeat(64)).isFailure)
        assertTrue(PublicKey.fromHex("-1".repeat(32)).isFailure)
        assertTrue(PublicKey.fromHex("+1".repeat(32)).isFailure)
        assertTrue(PublicKey.fromHex("00".repeat(31) + "-1").isFailure)
        assertTrue(PublicKey.fromHex("00".repeat(31) + "+1").isFailure)
    }

    @Test
    fun `uuid v7 encodes version 7 variant and preserves chronological ordering`() {
        val earlier = UuidV7.generate(Instant(1_700_000_000_000L))
        val later = UuidV7.generate(Instant(1_700_000_005_000L))

        assertTrue(earlier.toString() < later.toString())
        assertNotEquals(earlier, later)

        val versionChar = earlier.toString()[14]
        assertEquals('7', versionChar)

        val variantChar = earlier.toString()[19]
        assertTrue(variantChar in setOf('8', '9', 'a', 'b'))

        val peerId = PeerId.parse(earlier.toString()).getOrThrow()
        assertEquals(earlier, peerId.value)
        assertTrue(PeerId.parse("not-a-uuid").isFailure)
    }

    @Test
    fun `instant rejects negative timestamps and compares chronologically`() {
        assertEquals(0L, Instant.EPOCH.epochMillis)
        assertTrue(Instant(100L) < Instant(200L))
        assertFailsWith<IllegalArgumentException> {
            Instant(-1L)
        }
    }

    @Test
    fun `peer and local identity enforce domain invariants`() {
        val now = Instant(10_000L)
        val key = PublicKey.fromBytes(ByteArray(PublicKey.EXPECTED_KEY_LENGTH_BYTES) { 7 }).getOrThrow()
        val address = BluetoothAddress("11:22:33:44:55:66")

        assertFailsWith<IllegalArgumentException> {
            Peer(
                id = PeerId.generate(now),
                nickname = "   ",
                address = address,
                lastSeenAt = now,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            Peer(
                id = PeerId.generate(now),
                nickname = "Alice",
                address = address,
                identityKey = null,
                isVerified = true,
                lastSeenAt = now,
            )
        }

        val verifiedPeer =
            Peer(
                id = PeerId.generate(now),
                nickname = "Alice",
                address = address,
                identityKey = key,
                isVerified = true,
                lastSeenAt = now,
            )
        assertTrue(verifiedPeer.isVerified)

        assertFailsWith<IllegalArgumentException> {
            LocalIdentity(
                id = PeerId.generate(now),
                nickname = "",
                identityPublicKey = key,
                createdAt = now,
            )
        }
    }

    @Test
    fun `message enforces non blank plaintext and valid delivered invariants`() {
        val sentAt = Instant(5_000L)
        val messageId = MessageId.generate(sentAt)
        val conversationId = ConversationId.generate(sentAt)

        assertFailsWith<IllegalArgumentException> {
            Message(
                id = messageId,
                conversationId = conversationId,
                plaintext = "  ",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.PENDING,
                sentAt = sentAt,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            Message(
                id = messageId,
                conversationId = conversationId,
                plaintext = "Hello",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.DELIVERED,
                sentAt = sentAt,
                deliveredAt = null,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            Message(
                id = messageId,
                conversationId = conversationId,
                plaintext = "Hello",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.DELIVERED,
                sentAt = sentAt,
                deliveredAt = Instant(4_000L),
            )
        }

        val nonDeliveredStatuses = listOf(MessageStatus.PENDING, MessageStatus.SENT, MessageStatus.FAILED)
        for (status in nonDeliveredStatuses) {
            assertFailsWith<IllegalArgumentException> {
                Message(
                    id = messageId,
                    conversationId = conversationId,
                    plaintext = "Hello",
                    direction = MessageDirection.OUTGOING,
                    status = status,
                    sentAt = sentAt,
                    deliveredAt = Instant(6_000L),
                )
            }
        }
    }

    @Test
    fun `handshake step result preserves defensive copies and value equality`() {
        val remoteKey = PublicKey.fromBytes(ByteArray(PublicKey.EXPECTED_KEY_LENGTH_BYTES) { 3 }).getOrThrow()
        val inputPayload = byteArrayOf(1, 2, 3, 4)
        val first =
            HandshakeStepResult(
                responsePayload = inputPayload,
                isComplete = true,
                remoteIdentityKey = remoteKey,
            )
        inputPayload[0] = 99

        assertContentEquals(byteArrayOf(1, 2, 3, 4), first.copyResponsePayload())

        val copiedOut = first.copyResponsePayload()!!
        copiedOut[1] = 88
        assertContentEquals(byteArrayOf(1, 2, 3, 4), first.copyResponsePayload())

        val second =
            HandshakeStepResult(
                responsePayload = byteArrayOf(1, 2, 3, 4),
                isComplete = true,
                remoteIdentityKey = remoteKey,
            )
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())

        val differentPayload =
            HandshakeStepResult(
                responsePayload = byteArrayOf(1, 2, 3, 5),
                isComplete = true,
                remoteIdentityKey = remoteKey,
            )
        val nullPayload =
            HandshakeStepResult(
                responsePayload = null,
                isComplete = false,
                remoteIdentityKey = null,
            )
        assertNull(nullPayload.copyResponsePayload())
        assertNotEquals(first, differentPayload)
        assertNotEquals(first, nullPayload)
        assertNotEquals(
            first,
            HandshakeStepResult(
                responsePayload = byteArrayOf(1, 2, 3, 4),
                isComplete = false,
                remoteIdentityKey = remoteKey,
            ),
        )
    }

    @Test
    fun `domain error subtypes wrap and unwrap cleanly via DomainException`() {
        val errors: List<DomainError> =
            listOf(
                DecryptionFailedError("Invalid AEAD tag."),
                CorruptedFrameError("CRC32 mismatch."),
                MissingBluetoothPermissionError(permission = "android.permission.BLUETOOTH_CONNECT"),
                BluetoothAdapterDisabledError(),
                TransportDisconnectedError(),
                InvalidStateTransitionError(
                    entity = "Message",
                    fromState = "DELIVERED",
                    toState = "PENDING",
                ),
            )

        for (error in errors) {
            val cause = IllegalStateException("root cause")
            val failure = error.asFailure<Unit>(cause)
            assertTrue(failure.isFailure)
            val thrown = failure.exceptionOrNull()
            assertTrue(thrown is DomainException)
            assertEquals(error.message, thrown.message)
            assertEquals(cause, thrown.cause)
            assertEquals(error, thrown.toDomainErrorOrNull())
        }

        assertNull(IllegalArgumentException("non-domain").toDomainErrorOrNull())
    }

    @Test
    fun `coroutine dispatchers accepts test dispatcher injection`() {
        val testDispatcher = StandardTestDispatcher()
        val dispatchers =
            CoroutineDispatchers(
                main = testDispatcher,
                io = testDispatcher,
                default = testDispatcher,
            )
        assertEquals(testDispatcher, dispatchers.io)
        assertEquals(testDispatcher, dispatchers.main)
        assertEquals(testDispatcher, dispatchers.default)
    }
}
