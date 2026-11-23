package com.bluemessenger.domain.ports.transport.model

import com.bluemessenger.domain.primitives.BluetoothAddress

/**
 * Metadata describing an accepted inbound Bluetooth transport connection from a remote peer.
 */
data class IncomingPeerConnection(
    val address: BluetoothAddress,
    val name: String? = null,
)
