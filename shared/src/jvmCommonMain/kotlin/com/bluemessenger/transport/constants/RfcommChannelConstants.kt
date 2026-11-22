package com.bluemessenger.transport.constants

import com.bluemessenger.transport.protocol.constants.FrameConstants

/**
 * Buffering, framing, and logging constants shared across JVM and Android RFCOMM socket channels.
 */
internal object RfcommChannelConstants {
    private const val READ_BUFFER_MULTIPLIER: Int = 4
    const val RFCOMM_READ_BUFFER_SIZE_BYTES: Int = FrameConstants.MAX_FRAME_SIZE_BYTES * READ_BUFFER_MULTIPLIER
    const val EOF_MARKER: Int = -1
    const val TAG_RFCOMM_CHANNEL: String = "RfcommSocketChannel"
}
