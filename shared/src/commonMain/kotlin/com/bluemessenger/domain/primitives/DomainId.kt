package com.bluemessenger.domain.primitives

import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pure Kotlin generator for time-ordered RFC 9562 UUIDv7 identifiers.
 */
@OptIn(ExperimentalUuidApi::class)
object UuidV7 {
    private const val TIMESTAMP_MASK_48_BITS: Long = 0x0000_FFFF_FFFF_FFFFL
    private const val TIMESTAMP_SHIFT_BITS: Int = 16
    private const val VERSION_7_BITS: Long = 0x0000_0000_0000_7000L
    private const val RAND_A_MASK: Long = 0x0000_0000_0000_0FFFL
    private const val VARIANT_CLEAR_MASK: Long = 0x3FFF_FFFF_FFFF_FFFFL
    private const val VARIANT_RFC9562_BITS: Long = Long.MIN_VALUE

    /**
     * Generates a time-ordered UUIDv7 using the supplied Unix epoch [timestamp].
     */
    fun generate(timestamp: Instant): Uuid = generate(timestamp.epochMillis)

    /**
     * Generates a time-ordered UUIDv7 using the supplied [epochMillis] timestamp.
     */
    fun generate(epochMillis: Long): Uuid {
        require(epochMillis >= 0L) {
            "UUIDv7 timestamp must be non-negative, got $epochMillis."
        }
        return Uuid.random().toLongs { randomMsb, randomLsb ->
            val timestampPart = (epochMillis and TIMESTAMP_MASK_48_BITS) shl TIMESTAMP_SHIFT_BITS
            val randA = randomMsb and RAND_A_MASK
            val msb = timestampPart or VERSION_7_BITS or randA
            val lsb = (randomLsb and VARIANT_CLEAR_MASK) or VARIANT_RFC9562_BITS
            Uuid.fromLongs(msb, lsb)
        }
    }

    /**
     * Parses a canonical UUID string into a [Uuid], returning [Result.failure] on invalid syntax.
     */
    fun parse(raw: String): Result<Uuid> =
        try {
            Result.success(Uuid.parse(raw.trim()))
        } catch (exception: IllegalArgumentException) {
            Result.failure(exception)
        }
}

/**
 * Unique identifier for a local or remote [Peer] / [LocalIdentity].
 */
@OptIn(ExperimentalUuidApi::class)
@JvmInline
value class PeerId(val value: Uuid) {
    override fun toString(): String = value.toString()

    companion object {
        fun generate(timestamp: Instant): PeerId = PeerId(UuidV7.generate(timestamp))

        fun parse(raw: String): Result<PeerId> = UuidV7.parse(raw).map(::PeerId)
    }
}

/**
 * Unique identifier for a [Conversation].
 */
@OptIn(ExperimentalUuidApi::class)
@JvmInline
value class ConversationId(val value: Uuid) {
    override fun toString(): String = value.toString()

    companion object {
        fun generate(timestamp: Instant): ConversationId = ConversationId(UuidV7.generate(timestamp))

        fun parse(raw: String): Result<ConversationId> = UuidV7.parse(raw).map(::ConversationId)
    }
}

/**
 * Unique identifier for a [Message], used for delivery acknowledgements and idempotency.
 */
@OptIn(ExperimentalUuidApi::class)
@JvmInline
value class MessageId(val value: Uuid) {
    override fun toString(): String = value.toString()

    companion object {
        fun generate(timestamp: Instant): MessageId = MessageId(UuidV7.generate(timestamp))

        fun parse(raw: String): Result<MessageId> = UuidV7.parse(raw).map(::MessageId)
    }
}
