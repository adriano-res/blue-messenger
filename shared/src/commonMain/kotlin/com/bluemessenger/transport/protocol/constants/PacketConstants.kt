package com.bluemessenger.transport.protocol.constants

/**
 * Binary wire opcodes and field sizes for application-level protocol packets.
 */
object PacketConstants {
    const val PACKET_TYPE_HANDSHAKE: Byte = 0x01
    const val PACKET_TYPE_ENCRYPTED_PAYLOAD: Byte = 0x02
    const val PACKET_TYPE_ACK: Byte = 0x03
    const val PACKET_TYPE_KEEP_ALIVE_PING: Byte = 0x04
    const val PACKET_TYPE_KEEP_ALIVE_PONG: Byte = 0x05

    const val PACKET_TYPE_SIZE_BYTES: Int = 1
    const val HANDSHAKE_STEP_SIZE_BYTES: Int = 1
    const val LENGTH_FIELD_SIZE_BYTES: Int = 4
    const val SEQUENCE_NUMBER_SIZE_BYTES: Int = 8
    const val TIMESTAMP_SIZE_BYTES: Int = 8
    const val UUID_HALF_SIZE_BYTES: Int = 8
    const val UUID_SIZE_BYTES: Int = 16

    const val HANDSHAKE_MIN_BYTES: Int =
        PACKET_TYPE_SIZE_BYTES + HANDSHAKE_STEP_SIZE_BYTES + LENGTH_FIELD_SIZE_BYTES
    const val ENCRYPTED_PAYLOAD_MIN_BYTES: Int =
        PACKET_TYPE_SIZE_BYTES +
            SEQUENCE_NUMBER_SIZE_BYTES +
            LENGTH_FIELD_SIZE_BYTES +
            LENGTH_FIELD_SIZE_BYTES
    const val ACK_PACKET_SIZE_BYTES: Int = PACKET_TYPE_SIZE_BYTES + UUID_SIZE_BYTES
    const val KEEP_ALIVE_PACKET_SIZE_BYTES: Int = PACKET_TYPE_SIZE_BYTES + TIMESTAMP_SIZE_BYTES
}
