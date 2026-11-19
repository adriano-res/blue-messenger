package com.bluemessenger.protocol.model

/**
 * Connection liveness probe and response packets carrying a Unix epoch [timestamp] in
 * milliseconds.
 */
sealed interface KeepAlivePacket : Packet {
    val timestamp: Long

    /**
     * Keep-alive probe packet sent periodically to verify link health.
     */
    data class Ping(
        override val timestamp: Long,
    ) : KeepAlivePacket

    /**
     * Keep-alive response packet acknowledging a received [Ping].
     */
    data class Pong(
        override val timestamp: Long,
    ) : KeepAlivePacket
}
