package com.bluemessenger.storage.mapper

import com.bluemessenger.domain.model.LocalIdentity
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey
import com.bluemessenger.storage.Local_identity

internal fun Local_identity.toDomain(): LocalIdentity =
    LocalIdentity(
        id = PeerId.parse(id).getOrThrow(),
        nickname = nickname,
        identityPublicKey = PublicKey.fromBytes(identity_public_key).getOrThrow(),
        createdAt = Instant(created_at),
    )
