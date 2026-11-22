package com.bluemessenger.transport.constants

import com.bluemessenger.transport.protocol.constants.FrameConstants

/**
 * Runtime, buffering, timing, and logging constants for Linux Bluetooth transport adapters.
 */
internal object LinuxTransportConstants {
    private const val READ_BUFFER_MULTIPLIER: Int = 4
    const val RFCOMM_READ_BUFFER_SIZE_BYTES: Int = FrameConstants.MAX_FRAME_SIZE_BYTES * READ_BUFFER_MULTIPLIER
    const val INCOMING_PACKET_BUFFER_CAPACITY: Int = 64
    const val CONNECTION_ACCEPT_TIMEOUT_MS: Long = 30_000L

    const val PEER_ID_SEED_PREFIX: String = "bluemessenger:peer:"
    const val PEER_ID_BYTES_MSB_COUNT: Int = 8
    const val BITS_PER_BYTE: Int = 8
    const val BYTE_MASK: Long = 0xFFL
    const val BLUEZ_DEVICE_PATH_PREFIX: String = "dev_"
    const val MAC_COLON_CHAR: Char = ':'
    const val MAC_UNDERSCORE_CHAR: Char = '_'
    const val EOF_MARKER: Int = -1

    const val TAG_RFCOMM_CHANNEL: String = "RfcommSocketChannel"
    const val TAG_PERMISSION_CHECKER: String = "LinuxBluetoothPermissionChecker"
    const val TAG_SCANNER: String = "LinuxBluetoothScannerImpl"
    const val TAG_TRANSPORT: String = "LinuxBluetoothTransportImpl"
}
