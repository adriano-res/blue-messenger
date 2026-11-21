package com.bluemessenger.domain.constants

/**
 * Timing, retry, handshake, and internal message-envelope constants for domain use cases.
 */
object SessionConstants {
    const val KEEP_ALIVE_INTERVAL_MS: Long = 15_000L
    const val KEEP_ALIVE_TIMEOUT_MS: Long = 5_000L
    const val MAX_RECONNECT_ATTEMPTS: Int = 5
    const val INITIAL_RECONNECT_BACKOFF_MS: Long = 1_000L
    const val ACK_TIMEOUT_MS: Long = 5_000L
    const val HANDSHAKE_TIMEOUT_MS: Long = 10_000L

    const val HANDSHAKE_STEP_INITIATOR_HELLO: Byte = 1
    const val HANDSHAKE_STEP_RESPONDER_HELLO: Byte = 2
    const val HANDSHAKE_STEP_INITIATOR_FINISH: Byte = 3

    const val BACKOFF_BASE_OFFSET: Int = 1

    const val UUID_HALF_SIZE_BYTES: Int = 8
    const val TIMESTAMP_SIZE_BYTES: Int = 8
    const val SEQUENCE_NUMBER_SIZE_BYTES: Int = 8
    const val SENT_AT_OFFSET_BYTES: Int = UUID_HALF_SIZE_BYTES + UUID_HALF_SIZE_BYTES
    const val MESSAGE_PAYLOAD_HEADER_BYTES: Int = SENT_AT_OFFSET_BYTES + TIMESTAMP_SIZE_BYTES
    const val BITS_PER_BYTE: Int = 8
    const val LONG_BYTE_MASK: Long = 0xFFL
    const val INITIAL_OUTGOING_SEQUENCE: Long = 0L
    const val PONG_SIGNAL_BUFFER_CAPACITY: Int = 16
}
