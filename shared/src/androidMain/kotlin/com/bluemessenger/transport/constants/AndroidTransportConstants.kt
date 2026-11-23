package com.bluemessenger.transport.constants

/**
 * Runtime, buffering, timing, and logging constants for Android Bluetooth transport adapters.
 */
internal object AndroidTransportConstants {
    const val INCOMING_PACKET_BUFFER_CAPACITY: Int = 64
    const val INCOMING_CONNECTION_BUFFER_CAPACITY: Int = 8
    const val CONNECTION_ACCEPT_TIMEOUT_MS: Long = 30_000L
    const val POST_DISCOVERY_SDP_WINDOW_MS: Long = 8_000L
    const val SERVICE_NAME_BLUE_MESSENGER: String = "BlueMessenger"
    const val RFCOMM_FALLBACK_CHANNEL: Int = 6
    const val METHOD_CREATE_INSECURE_RFCOMM_SOCKET: String = "createInsecureRfcommSocket"
    const val METHOD_CREATE_RFCOMM_SOCKET: String = "createRfcommSocket"

    const val PEER_ID_SEED_PREFIX: String = "bluemessenger:peer:"
    const val PEER_ID_BYTES_MSB_COUNT: Int = 8
    const val BITS_PER_BYTE: Int = 8
    const val BYTE_MASK: Long = 0xFFL
    const val MAC_COLON_CHAR: Char = ':'
    const val MAC_HYPHEN_CHAR: Char = '-'

    const val TAG_PERMISSION_CHECKER: String = "AndroidBluetoothPermissionChecker"
    const val TAG_SCANNER: String = "AndroidBluetoothScannerImpl"
    const val TAG_TRANSPORT: String = "AndroidBluetoothTransportImpl"
}
