package com.bluemessenger.presentation.chat

import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.domain.primitives.MessageId

/**
 * User and lifecycle intents handled by [ChatViewModel].
 */
sealed interface ChatIntent {
    /**
     * Opens a chat thread for [peer], optionally pre-bound to [conversationId] and [sessionState].
     */
    data class OpenPeer(
        val peer: Peer,
        val conversationId: ConversationId? = null,
        val sessionState: SessionState = SessionState.DISCONNECTED,
    ) : ChatIntent

    /**
     * Initiates Bluetooth RFCOMM connection and Noise XX E2EE handshake with the open peer.
     */
    data object Connect : ChatIntent

    /**
     * Closes the active Bluetooth session and clears ephemeral cryptographic state.
     */
    data object Disconnect : ChatIntent

    /**
     * Updates the outgoing message draft text.
     */
    data class UpdateMessageInput(
        val value: String,
    ) : ChatIntent

    /**
     * Encrypts and sends (or queues as `PENDING` if offline) the current outgoing message draft.
     */
    data object SendMessage : ChatIntent

    /**
     * Retries sending a previously failed outgoing message identified by [messageId].
     */
    data class RetryFailedMessage(
        val messageId: MessageId,
    ) : ChatIntent

    /**
     * Computes the Safety Number and QR matrix and opens the verification dialog.
     */
    data object OpenSafetyNumberDialog : ChatIntent

    /**
     * Closes the Safety Number verification dialog.
     */
    data object DismissSafetyNumberDialog : ChatIntent

    /**
     * Marks the open peer as out-of-band verified.
     */
    data object MarkPeerVerified : ChatIntent

    /**
     * Dismisses the currently displayed error banner.
     */
    data object DismissError : ChatIntent
}
