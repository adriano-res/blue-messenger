package com.bluemessenger.storage

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.LocalIdentity
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

private const val KEY_FILL_BYTE: Byte = 7
private const val BASE_EPOCH_MILLIS: Long = 1_700_000_000_000L
private const val ADDRESS_LAST_OCTET_RADIX: Int = 16
private const val ADDRESS_OCTET_LENGTH: Int = 2
private const val MESSAGE_PLAINTEXT: String = "hello"
private const val ADDRESS_PREFIX: String = "AA:BB:CC:DD:EE:"

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.testDispatchers(): CoroutineDispatchers {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    return CoroutineDispatchers(main = dispatcher, io = dispatcher, default = dispatcher)
}

internal fun testPublicKey(fill: Byte = KEY_FILL_BYTE): PublicKey =
    PublicKey.fromBytes(ByteArray(PublicKey.EXPECTED_KEY_LENGTH_BYTES) { fill }).getOrThrow()

internal fun testInstant(offsetMillis: Long = 0L): Instant = Instant(BASE_EPOCH_MILLIS + offsetMillis)

internal fun testPeer(
    index: Int = 1,
    identityKey: PublicKey? = testPublicKey(),
    isVerified: Boolean = false,
    lastSeenOffsetMillis: Long = 0L,
): Peer {
    val lastOctet = index.toString(ADDRESS_LAST_OCTET_RADIX).padStart(ADDRESS_OCTET_LENGTH, '0').uppercase()
    return Peer(
        id = PeerId.generate(testInstant()),
        nickname = "peer-$index",
        address = BluetoothAddress("$ADDRESS_PREFIX$lastOctet"),
        identityKey = identityKey,
        isVerified = isVerified,
        lastSeenAt = testInstant(lastSeenOffsetMillis),
    )
}

internal fun testConversation(
    peer: Peer = testPeer(),
    state: SessionState = SessionState.DISCONNECTED,
    updatedOffsetMillis: Long = 0L,
): Conversation =
    Conversation(
        id = ConversationId.generate(testInstant()),
        peer = peer,
        state = state,
        updatedAt = testInstant(updatedOffsetMillis),
    )

internal fun testLocalIdentity(nickname: String = "me"): LocalIdentity =
    LocalIdentity(
        id = PeerId.generate(testInstant()),
        nickname = nickname,
        identityPublicKey = testPublicKey(),
        createdAt = testInstant(),
    )

internal fun testMessage(
    conversationId: ConversationId,
    direction: MessageDirection = MessageDirection.OUTGOING,
    status: MessageStatus = MessageStatus.PENDING,
    sentOffsetMillis: Long = 0L,
    deliveredOffsetMillis: Long? = null,
): Message =
    Message(
        id = MessageId.generate(testInstant(sentOffsetMillis)),
        conversationId = conversationId,
        plaintext = MESSAGE_PLAINTEXT,
        direction = direction,
        status = status,
        sentAt = testInstant(sentOffsetMillis),
        deliveredAt = deliveredOffsetMillis?.let { testInstant(it) },
    )
