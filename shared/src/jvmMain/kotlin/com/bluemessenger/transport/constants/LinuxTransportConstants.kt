package com.bluemessenger.transport.constants

/**
 * Runtime, buffering, timing, and logging constants for Linux Bluetooth transport adapters.
 */
internal object LinuxTransportConstants {
    const val INCOMING_PACKET_BUFFER_CAPACITY: Int = 64
    const val INCOMING_CONNECTION_BUFFER_CAPACITY: Int = 8
    const val CONNECTION_ACCEPT_TIMEOUT_MS: Long = 30_000L

    const val PEER_ID_SEED_PREFIX: String = "bluemessenger:peer:"
    const val PEER_ID_BYTES_MSB_COUNT: Int = 8
    const val BITS_PER_BYTE: Int = 8
    const val BYTE_MASK: Long = 0xFFL
    const val BLUEZ_DEVICE_PATH_PREFIX: String = "dev_"
    const val MAC_COLON_CHAR: Char = ':'
    const val MAC_HYPHEN_CHAR: Char = '-'
    const val MAC_UNDERSCORE_CHAR: Char = '_'
    const val RFCOMM_AUTO_CHANNEL: Int = 0
    const val RFCOMM_SERVER_CHANNEL: Int = 6

    const val TAG_PERMISSION_CHECKER: String = "LinuxBluetoothPermissionChecker"
    const val TAG_SCANNER: String = "LinuxBluetoothScannerImpl"
    const val TAG_TRANSPORT: String = "LinuxBluetoothTransportImpl"
}
