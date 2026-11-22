package com.bluemessenger.presentation.chat

import androidx.compose.runtime.Immutable
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.primitives.ConversationId
import com.bluemessenger.presentation.PresentationError

/**
 * Immutable UI state for the out-of-band Safety Number verification dialog.
 */
@Immutable
data class SafetyNumberDialogState(
    val safetyNumber: String = "",
    val qrCodeMatrix: QrCodeMatrix? = null,
    val isVerified: Boolean = false,
    val isLoading: Boolean = false,
    val isVerifying: Boolean = false,
    val error: PresentationError? = null,
)

/**
 * Immutable UI state snapshot for an active or historical peer chat thread.
 */
@Immutable
data class ChatUiState(
    val peer: Peer? = null,
    val conversationId: ConversationId? = null,
    val sessionState: SessionState = SessionState.DISCONNECTED,
    val messages: List<Message> = emptyList(),
    val messageInput: String = "",
    val isConnecting: Boolean = false,
    val isSending: Boolean = false,
    val safetyNumberDialog: SafetyNumberDialogState? = null,
    val error: PresentationError? = null,
)
