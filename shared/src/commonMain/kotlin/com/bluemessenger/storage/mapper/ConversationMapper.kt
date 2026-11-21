package com.bluemessenger.storage.mapper

import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.storage.Conversations
import com.bluemessenger.storage.Peers
import com.bluemessenger.storage.SelectConversationsWithPeer

internal fun Conversations.toDomain(peer: Peer): Conversation =
    Conversation(
        id = ConversationId.parse(id).getOrThrow(),
        peer = peer,
        state = SessionState.valueOf(state),
        updatedAt = Instant(updated_at),
    )

internal fun SelectConversationsWithPeer.toDomain(): Conversation =
    Conversations(
        id = conversation_id,
        peer_id = peer_id,
        state = conversation_state,
        updated_at = conversation_updated_at,
    ).toDomain(
        peer =
            Peers(
                id = peer_id,
                nickname = peer_nickname,
                bluetooth_address = peer_bluetooth_address,
                identity_key = peer_identity_key,
                is_verified = peer_is_verified,
                last_seen_at = peer_last_seen_at,
            ).toDomain(),
    )
