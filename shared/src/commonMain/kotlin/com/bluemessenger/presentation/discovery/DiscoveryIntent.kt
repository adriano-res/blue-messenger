package com.bluemessenger.presentation.discovery

import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.Peer

/**
 * User and lifecycle intents handled by [DiscoveryViewModel].
 */
sealed interface DiscoveryIntent {
    /**
     * Loads the persisted local identity if one already exists.
     */
    data object LoadInitialState : DiscoveryIntent

    /**
     * Updates the nickname draft while configuring the local identity.
     */
    data class UpdateNicknameInput(
        val value: String,
    ) : DiscoveryIntent

    /**
     * Validates and initializes the local identity using the current nickname input.
     */
    data object SubmitIdentity : DiscoveryIntent

    /**
     * Starts scanning for nearby Bluetooth peers running Blue Messenger.
     */
    data object StartDiscovery : DiscoveryIntent

    /**
     * Stops an active Bluetooth discovery scan.
     */
    data object StopDiscovery : DiscoveryIntent

    /**
     * Starts advertising the local Blue Messenger service using the local nickname.
     */
    data object StartAdvertising : DiscoveryIntent

    /**
     * Stops advertising the local Blue Messenger service.
     */
    data object StopAdvertising : DiscoveryIntent

    /**
     * Selects a newly discovered [peer] to open its conversation screen.
     */
    data class SelectPeer(
        val peer: Peer,
    ) : DiscoveryIntent

    /**
     * Selects an existing [conversation] to open its encrypted chat thread.
     */
    data class SelectConversation(
        val conversation: Conversation,
    ) : DiscoveryIntent

    /**
     * Clears the currently displayed error banner.
     */
    data object DismissError : DiscoveryIntent
}
