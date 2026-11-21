package com.bluemessenger.domain.usecase.message.util

import com.bluemessenger.domain.constants.SessionConstants
import com.bluemessenger.domain.error.CorruptedFrameError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal data class DecodedMessagePayload(
    val messageId: MessageId,
    val sentAt: Instant,
    val plaintext: String,
)

@OptIn(ExperimentalUuidApi::class)
internal object MessagePayloadCodec {
    fun encode(
        messageId: MessageId,
        sentAt: Instant,
        plaintext: String,
    ): ByteArray {
        val plaintextBytes = plaintext.encodeToByteArray()
        val output = ByteArray(SessionConstants.MESSAGE_PAYLOAD_HEADER_BYTES + plaintextBytes.size)
        messageId.value.toLongs { mostSignificantBits, leastSignificantBits ->
            output.writeLongBigEndian(0, mostSignificantBits)
            output.writeLongBigEndian(SessionConstants.UUID_HALF_SIZE_BYTES, leastSignificantBits)
        }
        output.writeLongBigEndian(SessionConstants.SENT_AT_OFFSET_BYTES, sentAt.epochMillis)
        plaintextBytes.copyInto(output, destinationOffset = SessionConstants.MESSAGE_PAYLOAD_HEADER_BYTES)
        return output
    }

    fun decode(bytes: ByteArray): Result<DecodedMessagePayload> =
        if (bytes.size <= SessionConstants.MESSAGE_PAYLOAD_HEADER_BYTES) {
            CorruptedFrameError(
                "Encrypted message envelope must exceed ${SessionConstants.MESSAGE_PAYLOAD_HEADER_BYTES} bytes, " +
                    "got ${bytes.size}.",
            ).asFailure()
        } else {
            val mostSignificantBits = bytes.readLongBigEndian(0)
            val leastSignificantBits = bytes.readLongBigEndian(SessionConstants.UUID_HALF_SIZE_BYTES)
            val sentAtEpochMillis = bytes.readLongBigEndian(SessionConstants.SENT_AT_OFFSET_BYTES)
            if (sentAtEpochMillis < 0L) {
                CorruptedFrameError("Invalid negative message timestamp: $sentAtEpochMillis.").asFailure()
            } else {
                decodeUtf8Plaintext(bytes).map { plaintext ->
                    DecodedMessagePayload(
                        messageId = MessageId(Uuid.fromLongs(mostSignificantBits, leastSignificantBits)),
                        sentAt = Instant(sentAtEpochMillis),
                        plaintext = plaintext,
                    )
                }
            }
        }

    fun encodeSequenceNumberAad(sequenceNumber: Long): ByteArray =
        ByteArray(SessionConstants.SEQUENCE_NUMBER_SIZE_BYTES).apply {
            writeLongBigEndian(0, sequenceNumber)
        }

    private fun decodeUtf8Plaintext(bytes: ByteArray): Result<String> =
        try {
            val plaintext =
                bytes.decodeToString(
                    startIndex = SessionConstants.MESSAGE_PAYLOAD_HEADER_BYTES,
                    endIndex = bytes.size,
                    throwOnInvalidSequence = true,
                )
            if (plaintext.isBlank()) {
                CorruptedFrameError("Decrypted message plaintext must not be blank.").asFailure()
            } else {
                Result.success(plaintext)
            }
        } catch (exception: CharacterCodingException) {
            CorruptedFrameError("Decrypted message payload contains invalid UTF-8 bytes.").asFailure(exception)
        }

    private fun ByteArray.writeLongBigEndian(
        offset: Int,
        value: Long,
    ) {
        for (byteIndex in 0 until SessionConstants.TIMESTAMP_SIZE_BYTES) {
            val shift = (SessionConstants.TIMESTAMP_SIZE_BYTES - 1 - byteIndex) * SessionConstants.BITS_PER_BYTE
            this[offset + byteIndex] = ((value ushr shift) and SessionConstants.LONG_BYTE_MASK).toByte()
        }
    }

    private fun ByteArray.readLongBigEndian(offset: Int): Long {
        var result = 0L
        for (byteIndex in 0 until SessionConstants.TIMESTAMP_SIZE_BYTES) {
            result =
                (result shl SessionConstants.BITS_PER_BYTE) or
                (this[offset + byteIndex].toLong() and SessionConstants.LONG_BYTE_MASK)
        }
        return result
    }
}
