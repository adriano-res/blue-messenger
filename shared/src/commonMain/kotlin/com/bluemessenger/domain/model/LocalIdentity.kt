package com.bluemessenger.domain.model

import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey

/**
 * Local user profile identity persisted on the device.
 */
data class LocalIdentity(
    val id: PeerId,
    val nickname: String,
    val identityPublicKey: PublicKey,
    val createdAt: Instant,
) {
    init {
        require(nickname.isNotBlank()) {
            "LocalIdentity nickname must not be blank."
        }
    }
}
