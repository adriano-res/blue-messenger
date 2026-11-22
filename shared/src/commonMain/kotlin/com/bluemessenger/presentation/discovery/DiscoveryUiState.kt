package com.bluemessenger.presentation.discovery

import androidx.compose.runtime.Immutable
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.LocalIdentity
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.primitives.PeerId
import com.bluemessenger.presentation.PresentationError

/**
 * Immutable UI state snapshot for identity setup, Bluetooth peer discovery, and known
 * conversations.
 */
@Immutable
data class DiscoveryUiState(
    val conversations: List<Conversation> = emptyList(),
    val discoveredPeers: List<Peer> = emptyList(),
    val localIdentity: LocalIdentity? = null,
    val nicknameInput: String = "",
    val isLoadingIdentity: Boolean = false,
    val isInitializingIdentity: Boolean = false,
    val isScanning: Boolean = false,
    val isAdvertising: Boolean = false,
    val selectedPeerId: PeerId? = null,
    val error: PresentationError? = null,
)
