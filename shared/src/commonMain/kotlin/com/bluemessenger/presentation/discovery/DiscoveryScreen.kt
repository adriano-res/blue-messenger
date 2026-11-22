package com.bluemessenger.presentation.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bluemessenger.domain.enums.AggregatedSessionState
import com.bluemessenger.domain.model.Conversation
import com.bluemessenger.domain.model.LocalIdentity
import com.bluemessenger.domain.model.Peer
import com.bluemessenger.presentation.PresentationErrorBanner
import com.bluemessenger.presentation.constants.PresentationConstants

/**
 * Stateless Compose screen for local profile setup, Bluetooth peer discovery/advertising, and
 * selecting known or nearby conversations.
 */
@Composable
fun DiscoveryScreen(
    uiState: DiscoveryUiState,
    onIntent: (DiscoveryIntent) -> Unit,
    onPeerSelected: (Peer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(PresentationConstants.SCREEN_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PresentationConstants.SECTION_SPACING_DP.dp),
    ) {
        Text(
            text = "Blue Messenger",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        uiState.error?.let { error ->
            PresentationErrorBanner(
                error = error,
                onDismiss = { onIntent(DiscoveryIntent.DismissError) },
            )
        }
        IdentitySection(uiState = uiState, onIntent = onIntent)
        if (uiState.localIdentity != null) {
            RadarControlsSection(uiState = uiState, onIntent = onIntent)
            DiscoveryListsSection(
                uiState = uiState,
                onIntent = onIntent,
                onPeerSelected = onPeerSelected,
                modifier = Modifier.weight(PresentationConstants.WEIGHT_FILL),
            )
        }
    }
}

@Composable
private fun IdentitySection(
    uiState: DiscoveryUiState,
    onIntent: (DiscoveryIntent) -> Unit,
) {
    when {
        uiState.isLoadingIdentity ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PresentationConstants.ITEM_SPACING_DP.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(PresentationConstants.PROGRESS_INDICATOR_SIZE_DP.dp),
                    strokeWidth = PresentationConstants.PROGRESS_STROKE_WIDTH_DP.dp,
                )
                Text(
                    text = "Loading local identity...",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        uiState.localIdentity != null -> ActiveIdentityCard(identity = uiState.localIdentity)
        else ->
            IdentitySetupCard(
                nicknameInput = uiState.nicknameInput,
                isSubmitting = uiState.isInitializingIdentity,
                onNicknameChange = { onIntent(DiscoveryIntent.UpdateNicknameInput(it)) },
                onSubmit = { onIntent(DiscoveryIntent.SubmitIdentity) },
            )
    }
}

@Composable
private fun ActiveIdentityCard(identity: LocalIdentity) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PresentationConstants.CORNER_RADIUS_MD_DP.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(PresentationConstants.CARD_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(PresentationConstants.COMPACT_SPACING_DP.dp),
        ) {
            Text(
                text = "Local Profile: ${identity.nickname}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "ID: ${identity.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = PresentationConstants.MAX_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun IdentitySetupCard(
    nicknameInput: String,
    isSubmitting: Boolean,
    onNicknameChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PresentationConstants.CORNER_RADIUS_MD_DP.dp),
        tonalElevation = PresentationConstants.COMPACT_SPACING_DP.dp,
        border = BorderStroke(PresentationConstants.BORDER_WIDTH_DP.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(PresentationConstants.CARD_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(PresentationConstants.ITEM_SPACING_DP.dp),
        ) {
            Text(
                text = "Create Local Identity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Choose a visible nickname to generate your hardware-backed E2EE identity keys.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = nicknameInput,
                onValueChange = onNicknameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nickname") },
                singleLine = true,
                maxLines = PresentationConstants.INPUT_SINGLE_LINE,
                enabled = !isSubmitting,
            )
            Button(
                onClick = onSubmit,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isSubmitting) "Generating Keys..." else "Initialize Identity")
            }
        }
    }
}

@Composable
private fun RadarControlsSection(
    uiState: DiscoveryUiState,
    onIntent: (DiscoveryIntent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PresentationConstants.ITEM_SPACING_DP.dp),
    ) {
        val scanIntent = if (uiState.isScanning) DiscoveryIntent.StopDiscovery else DiscoveryIntent.StartDiscovery
        FilledTonalButton(
            onClick = { onIntent(scanIntent) },
            modifier = Modifier.weight(PresentationConstants.WEIGHT_FILL),
        ) {
            Text(if (uiState.isScanning) "Stop Scan" else "Scan Nearby")
        }

        val advIntent =
            if (uiState.isAdvertising) DiscoveryIntent.StopAdvertising else DiscoveryIntent.StartAdvertising
        OutlinedButton(
            onClick = { onIntent(advIntent) },
            modifier = Modifier.weight(PresentationConstants.WEIGHT_FILL),
        ) {
            Text(if (uiState.isAdvertising) "Stop Advertising" else "Advertise")
        }
    }
}

@Composable
private fun DiscoveryListsSection(
    uiState: DiscoveryUiState,
    onIntent: (DiscoveryIntent) -> Unit,
    onPeerSelected: (Peer) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PresentationConstants.ITEM_SPACING_DP.dp),
    ) {
        item {
            SectionHeader(title = "Conversations (${uiState.conversations.size})")
        }
        if (uiState.conversations.isEmpty()) {
            item {
                EmptySectionMessage(text = "No conversations yet. Scan for nearby peers to start chatting.")
            }
        } else {
            items(uiState.conversations, key = { it.id.toString() }) { conversation ->
                ConversationListItem(
                    conversation = conversation,
                    isSelected = conversation.peer.id == uiState.selectedPeerId,
                    onClick = {
                        onIntent(DiscoveryIntent.SelectConversation(conversation))
                        onPeerSelected(conversation.peer)
                    },
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(PresentationConstants.COMPACT_SPACING_DP.dp))
            HorizontalDivider(thickness = PresentationConstants.DIVIDER_THICKNESS_DP.dp)
            Spacer(modifier = Modifier.height(PresentationConstants.COMPACT_SPACING_DP.dp))
            SectionHeader(title = "Nearby Radar (${uiState.discoveredPeers.size})")
        }
        if (uiState.discoveredPeers.isEmpty()) {
            item {
                val hint =
                    if (uiState.isScanning) {
                        "Scanning for Bluetooth devices running Blue Messenger..."
                    } else {
                        "Tap Scan Nearby to discover devices in range."
                    }
                EmptySectionMessage(text = hint)
            }
        } else {
            items(uiState.discoveredPeers, key = { it.id.toString() }) { peer ->
                DiscoveredPeerListItem(
                    peer = peer,
                    isSelected = peer.id == uiState.selectedPeerId,
                    onClick = {
                        onIntent(DiscoveryIntent.SelectPeer(peer))
                        onPeerSelected(peer)
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EmptySectionMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = PresentationConstants.COMPACT_SPACING_DP.dp),
    )
}

@Composable
private fun ConversationListItem(
    conversation: Conversation,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(PresentationConstants.CORNER_RADIUS_SM_DP.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(PresentationConstants.CARD_PADDING_DP.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerSummaryColumn(
                peer = conversation.peer,
                modifier = Modifier.weight(PresentationConstants.WEIGHT_FILL),
            )
            AggregatedSessionBadge(state = conversation.aggregatedState)
        }
    }
}

@Composable
private fun DiscoveredPeerListItem(
    peer: Peer,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor =
        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(PresentationConstants.CORNER_RADIUS_SM_DP.dp),
        border = BorderStroke(PresentationConstants.BORDER_WIDTH_DP.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(PresentationConstants.CARD_PADDING_DP.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerSummaryColumn(
                peer = peer,
                modifier = Modifier.weight(PresentationConstants.WEIGHT_FILL),
            )
            Text(
                text = "Open",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PeerSummaryColumn(
    peer: Peer,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PresentationConstants.COMPACT_SPACING_DP.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(PresentationConstants.ITEM_SPACING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = peer.nickname,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
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

@Composable
private fun AggregatedSessionBadge(state: AggregatedSessionState) {
    val (label, badgeColor) =
        when (state) {
            AggregatedSessionState.CONNECTED_ENCRYPTED ->
                "E2EE Active" to Color(PresentationConstants.COLOR_STATUS_ACTIVE)
            AggregatedSessionState.CONNECTING ->
                "Connecting" to Color(PresentationConstants.COLOR_STATUS_CONNECTING)
            AggregatedSessionState.DISCONNECTED ->
                "Disconnected" to Color(PresentationConstants.COLOR_STATUS_DISCONNECTED)
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
internal fun VerifiedChip() {
    val verifiedColor = Color(PresentationConstants.COLOR_VERIFIED_BADGE)
    Surface(
        shape = RoundedCornerShape(PresentationConstants.CORNER_RADIUS_SM_DP.dp),
        color = verifiedColor.copy(alpha = PresentationConstants.BADGE_CONTAINER_ALPHA),
    ) {
        Text(
            text = "Verified",
            style = MaterialTheme.typography.labelSmall,
            color = verifiedColor,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier.padding(
                    horizontal = PresentationConstants.BADGE_PADDING_H_DP.dp,
                    vertical = PresentationConstants.BADGE_PADDING_V_DP.dp,
                ),
        )
    }
}
