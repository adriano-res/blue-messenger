package com.bluemessenger.protocol.model

import com.bluemessenger.domain.primitives.MessageId

/**
 * Delivery acknowledgment packet confirming receipt of the message identified by [messageId].
 */
data class AckPacket(
    val messageId: MessageId,
) : Packet
