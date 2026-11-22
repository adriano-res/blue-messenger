package com.bluemessenger.transport.constants

/**
 * Runtime, buffering, timing, and logging constants for Android Bluetooth transport adapters.
 */
internal object AndroidTransportConstants {
    const val INCOMING_PACKET_BUFFER_CAPACITY: Int = 64
    const val CONNECTION_ACCEPT_TIMEOUT_MS: Long = 30_000L
    const val SERVICE_NAME_BLUE_MESSENGER: String = "BlueMessenger"

    const val PEER_ID_SEED_PREFIX: String = "bluemessenger:peer:"
    const val PEER_ID_BYTES_MSB_COUNT: Int = 8
    const val BITS_PER_BYTE: Int = 8
    const val BYTE_MASK: Long = 0xFFL

    const val TAG_PERMISSION_CHECKER: String = "AndroidBluetoothPermissionChecker"
    const val TAG_SCANNER: String = "AndroidBluetoothScannerImpl"
    const val TAG_TRANSPORT: String = "AndroidBluetoothTransportImpl"
}
