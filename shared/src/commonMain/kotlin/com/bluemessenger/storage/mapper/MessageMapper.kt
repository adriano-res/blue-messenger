package com.bluemessenger.storage.mapper

import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.storage.Messages

internal fun Messages.toDomain(): Message =
    Message(
        id = MessageId.parse(id).getOrThrow(),
        conversationId = ConversationId.parse(conversation_id).getOrThrow(),
        plaintext = plaintext,
        direction = MessageDirection.valueOf(direction),
        status = MessageStatus.valueOf(status),
        sentAt = Instant(sent_at),
        deliveredAt = delivered_at?.let { Instant(it) },
    )
