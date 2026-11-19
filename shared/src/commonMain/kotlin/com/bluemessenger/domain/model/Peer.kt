package com.bluemessenger.domain.model

import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey

/**
 * Remote Bluetooth device and cryptographic peer identity.
 */
data class Peer(
    val id: PeerId,
    val nickname: String,
    val address: BluetoothAddress,
    val identityKey: PublicKey? = null,
    val isVerified: Boolean = false,
    val lastSeenAt: Instant,
) {
    init {
        require(nickname.isNotBlank()) {
            "Peer nickname must not be blank."
        }
        require(!isVerified || identityKey != null) {
            "A peer cannot be marked verified without an identity public key."
        }
    }
}
