package com.bluemessenger.presentation.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bluemessenger.domain.enums.MessageDirection
import com.bluemessenger.domain.enums.MessageStatus
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.model.Message
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.domain.primitives.MessageId
import com.bluemessenger.presentation.PresentationErrorBanner
import com.bluemessenger.presentation.constants.PresentationConstants
import com.bluemessenger.presentation.discovery.VerifiedChip

/**
 * Stateless Compose screen for an encrypted peer chat thread, displaying session status, message
 * delivery indicators with retry actions, and Safety Number verification.
 */
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = false,
    onBack: () -> Unit = {},
) {
    val peer = uiState.peer
    if (peer == null) {
        EmptyChatPlaceholder(modifier = modifier)
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        ChatHeader(
            peer = peer,
            uiState = uiState,
            showBackButton = showBackButton,
            onBack = onBack,
            onIntent = onIntent,
        )
        HorizontalDivider(thickness = PresentationConstants.DIVIDER_THICKNESS_DP.dp)
        uiState.error?.let { error ->
            PresentationErrorBanner(
                error = error,
                onDismiss = { onIntent(ChatIntent.DismissError) },
                modifier = Modifier.padding(PresentationConstants.ITEM_SPACING_DP.dp),
            )
        }
        MessageList(
            messages = uiState.messages,
            onRetry = { messageId -> onIntent(ChatIntent.RetryFailedMessage(messageId)) },
            modifier = Modifier.weight(PresentationConstants.WEIGHT_FILL),
        )
        HorizontalDivider(thickness = PresentationConstants.DIVIDER_THICKNESS_DP.dp)
        MessageComposerBar(
            messageInput = uiState.messageInput,
            isSending = uiState.isSending,
            onInputChange = { onIntent(ChatIntent.UpdateMessageInput(it)) },
            onSend = { onIntent(ChatIntent.SendMessage) },
        )
    }

    uiState.safetyNumberDialog?.let { dialogState ->
        SafetyNumberDialog(
            peerNickname = peer.nickname,
            state = dialogState,
            onMarkVerified = { onIntent(ChatIntent.MarkPeerVerified) },
            onDismiss = { onIntent(ChatIntent.DismissSafetyNumberDialog) },
        )
    }
}

@Composable
private fun EmptyChatPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(PresentationConstants.SCREEN_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Select a peer or conversation to open an encrypted chat.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatHeader(
    peer: Peer,
    uiState: ChatUiState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onIntent: (ChatIntent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(PresentationConstants.SCREEN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PresentationConstants.ITEM_SPACING_DP.dp),
    ) {
        ChatHeaderTitleRow(
            peer = peer,
            sessionState = uiState.sessionState,
            showBackButton = showBackButton,
            onBack = onBack,
        )
        ChatHeaderActionsRow(
            sessionState = uiState.sessionState,
            isConnecting = uiState.isConnecting,
            onConnect = { onIntent(ChatIntent.Connect) },
            onDisconnect = { onIntent(ChatIntent.Disconnect) },
            onOpenSafetyNumber = { onIntent(ChatIntent.OpenSafetyNumberDialog) },
        )
    }
}

@Composable
private fun ChatHeaderTitleRow(
    peer: Peer,
    sessionState: SessionState,
    showBackButton: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(PresentationConstants.ITEM_SPACING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBackButton) {
                TextButton(onClick = onBack) {
                    Text("Back")
                }
            }
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PresentationConstants.ITEM_SPACING_DP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = peer.nickname,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (peer.isVerified) {
                        VerifiedChip()
                    }
                }
                Text(
                    text = peer.address.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SessionStateBadge(state = sessionState)
    }
}

@Composable
private fun ChatHeaderActionsRow(
    sessionState: SessionState,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSafetyNumber: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PresentationConstants.ITEM_SPACING_DP.dp),
    ) {
        val isActiveOrRecovering =
            sessionState == SessionState.SECURE_ACTIVE || sessionState == SessionState.RECONNECTING
        if (isActiveOrRecovering) {
            OutlinedButton(onClick = onDisconnect) {
                Text("Disconnect")
            }
        } else {
            FilledTonalButton(
                onClick = onConnect,
                enabled = !isConnecting,
            ) {
                Text(if (isConnecting) "Connecting..." else "Connect")
            }
        }
        OutlinedButton(onClick = onOpenSafetyNumber) {
            Text("Safety Number")
        }
    }
}

@Composable
private fun SessionStateBadge(state: SessionState) {
    val (label, badgeColor) =
        when (state) {
            SessionState.DISCONNECTED ->
                "DISCONNECTED" to Color(PresentationConstants.COLOR_STATUS_DISCONNECTED)
            SessionState.DISCOVERING ->
                "DISCOVERING" to Color(PresentationConstants.COLOR_STATUS_CONNECTING)
            SessionState.ADVERTISING ->
                "ADVERTISING" to Color(PresentationConstants.COLOR_STATUS_CONNECTING)
            SessionState.CONNECTING ->
                "CONNECTING" to Color(PresentationConstants.COLOR_STATUS_CONNECTING)
            SessionState.HANDSHAKING ->
                "HANDSHAKING" to Color(PresentationConstants.COLOR_STATUS_CONNECTING)
            SessionState.SECURE_ACTIVE ->
                "SECURE_ACTIVE" to Color(PresentationConstants.COLOR_STATUS_ACTIVE)
            SessionState.RECONNECTING ->
                "RECONNECTING" to Color(PresentationConstants.COLOR_STATUS_CONNECTING)
        }
    Surface(
        shape = RoundedCornerShape(PresentationConstants.CORNER_RADIUS_SM_DP.dp),
        color = badgeColor.copy(alpha = PresentationConstants.BADGE_CONTAINER_ALPHA),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier.padding(
                    horizontal = PresentationConstants.BADGE_PADDING_H_DP.dp,
                    vertical = PresentationConstants.BADGE_PADDING_V_DP.dp,
                ),
        )
    }
}

@Composable
private fun MessageList(
    messages: List<Message>,
    onRetry: (MessageId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().padding(PresentationConstants.SCREEN_PADDING_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No messages yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = PresentationConstants.SCREEN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PresentationConstants.ITEM_SPACING_DP.dp),
    ) {
        items(messages, key = { it.id.toString() }) { message ->
            MessageBubble(message = message, onRetry = { onRetry(message.id) })
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    onRetry: () -> Unit,
) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    val bubbleColor =
        if (isOutgoing) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = PresentationConstants.COMPACT_SPACING_DP.dp),
        horizontalAlignment = alignment,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = PresentationConstants.MESSAGE_BUBBLE_MAX_WIDTH_DP.dp),
            shape = RoundedCornerShape(PresentationConstants.CORNER_RADIUS_MD_DP.dp),
            color = bubbleColor,
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = PresentationConstants.MESSAGE_BUBBLE_PADDING_H_DP.dp,
                        vertical = PresentationConstants.MESSAGE_BUBBLE_PADDING_V_DP.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(PresentationConstants.COMPACT_SPACING_DP.dp),
            ) {
                Text(
                    text = message.plaintext,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isOutgoing) {
                    MessageStatusFooter(status = message.status, onRetry = onRetry)
                }
            }
        }
    }
}

@Composable
private fun MessageStatusFooter(
    status: MessageStatus,
    onRetry: () -> Unit,
) {
    val statusText =
        when (status) {
            MessageStatus.PENDING -> "PENDING"
            MessageStatus.SENT -> "SENT"
            MessageStatus.DELIVERED -> "DELIVERED ✓✓"
            MessageStatus.FAILED -> "FAILED"
        }
    val statusColor =
        if (status == MessageStatus.FAILED) {
            Color(PresentationConstants.COLOR_FAILED_TEXT)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        horizontalArrangement = Arrangement.spacedBy(PresentationConstants.ITEM_SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            fontWeight = FontWeight.SemiBold,
        )
        if (status == MessageStatus.FAILED) {
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun MessageComposerBar(
    messageInput: String,
    isSending: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(PresentationConstants.SCREEN_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(PresentationConstants.ITEM_SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = messageInput,
            onValueChange = onInputChange,
            modifier = Modifier.weight(PresentationConstants.WEIGHT_FILL),
            placeholder = { Text("Type an encrypted message...") },
            maxLines = PresentationConstants.INPUT_MAX_LINES_CHAT,
        )
        Button(
            onClick = onSend,
            enabled = !isSending && messageInput.isNotBlank(),
        ) {
            Text("Send")
        }
    }
}
