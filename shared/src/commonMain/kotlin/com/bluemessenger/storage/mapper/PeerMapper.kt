package com.bluemessenger.storage.mapper

import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.primitives.BluetoothAddress
import com.bluemessenger.domain.primitives.Instant
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.domain.primitives.PublicKey
import com.bluemessenger.storage.Peers
import com.bluemessenger.storage.SQLITE_FALSE
import com.bluemessenger.storage.SQLITE_TRUE

internal fun Peers.toDomain(): Peer =
    Peer(
        id = PeerId.parse(id).getOrThrow(),
        nickname = nickname,
        address = BluetoothAddress.parse(bluetooth_address).getOrThrow(),
        identityKey = identity_key?.let { keyBytes -> PublicKey.fromBytes(keyBytes).getOrThrow() },
        isVerified = is_verified == SQLITE_TRUE,
        lastSeenAt = Instant(last_seen_at),
    )

internal fun Boolean.toSqliteLong(): Long = if (this) SQLITE_TRUE else SQLITE_FALSE
