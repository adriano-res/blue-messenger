package com.bluemessenger.presentation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bluemessenger.domain.enums.SessionState
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.presentation.chat.ChatIntent
import com.bluemessenger.presentation.chat.ChatScreen
import com.bluemessenger.presentation.chat.ChatUiState
import com.bluemessenger.presentation.chat.ChatViewModel
import com.bluemessenger.presentation.constants.PresentationConstants
import com.bluemessenger.presentation.discovery.DiscoveryIntent
import com.bluemessenger.presentation.discovery.DiscoveryScreen
import com.bluemessenger.presentation.discovery.DiscoveryUiState
import com.bluemessenger.presentation.discovery.DiscoveryViewModel

/**
 * Adaptive root Compose Multiplatform entry point rendering a master-detail split layout on wide
 * viewports (`>= 840.dp`) and two-screen navigation on compact viewports.
 */
@Composable
fun BlueMessengerApp(
    discoveryViewModel: DiscoveryViewModel,
    chatViewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val discoveryState by discoveryViewModel.uiState.collectAsState()
    val chatState by chatViewModel.uiState.collectAsState()
    var isCompactChatOpen by remember { mutableStateOf(false) }

    val onPeerSelected: (Peer) -> Unit = { peer ->
        val existingConversation = discoveryState.conversations.firstOrNull { it.peer.id == peer.id }
        chatViewModel.onIntent(
            ChatIntent.OpenPeer(
                peer = peer,
                conversationId = existingConversation?.id,
                sessionState = existingConversation?.state ?: SessionState.DISCONNECTED,
            ),
        )
        isCompactChatOpen = true
    }

    MaterialTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth >= PresentationConstants.DESKTOP_SPLIT_BREAKPOINT_DP.dp) {
                    SplitMessengerLayout(
                        discoveryState = discoveryState,
                        chatState = chatState,
                        onDiscoveryIntent = discoveryViewModel::onIntent,
                        onChatIntent = chatViewModel::onIntent,
                        onPeerSelected = onPeerSelected,
                    )
                } else if (isCompactChatOpen && chatState.peer != null) {
                    ChatScreen(
                        uiState = chatState,
                        onIntent = chatViewModel::onIntent,
                        showBackButton = true,
                        onBack = { isCompactChatOpen = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    DiscoveryScreen(
                        uiState = discoveryState,
                        onIntent = discoveryViewModel::onIntent,
                        onPeerSelected = onPeerSelected,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitMessengerLayout(
    discoveryState: DiscoveryUiState,
    chatState: ChatUiState,
    onDiscoveryIntent: (DiscoveryIntent) -> Unit,
    onChatIntent: (ChatIntent) -> Unit,
    onPeerSelected: (Peer) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        DiscoveryScreen(
            uiState = discoveryState,
            onIntent = onDiscoveryIntent,
            onPeerSelected = onPeerSelected,
            modifier =
                Modifier
                    .weight(PresentationConstants.WEIGHT_MASTER_PANE)
                    .fillMaxHeight(),
        )
        VerticalDivider(thickness = PresentationConstants.DIVIDER_THICKNESS_DP.dp)
        ChatScreen(
            uiState = chatState,
            onIntent = onChatIntent,
            modifier =
                Modifier
                    .weight(PresentationConstants.WEIGHT_DETAIL_PANE)
                    .fillMaxHeight(),
        )
    }
}
