package com.bluemessenger.presentation.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluemessenger.presentation.PresentationError
import com.bluemessenger.presentation.constants.PresentationConstants
import com.bluemessenger.presentation.discovery.VerifiedChip

/**
 * Formats a 60-digit Safety Number string into rows of fixed-size digit groups for verification.
 */
internal fun formatSafetyNumberRows(safetyNumber: String): List<List<String>> =
    safetyNumber
        .filter { !it.isWhitespace() }
        .chunked(PresentationConstants.SAFETY_NUMBER_GROUP_SIZE)
        .chunked(PresentationConstants.SAFETY_NUMBER_GROUPS_PER_ROW)

/**
 * Modal dialog presenting a peer's 60-digit Safety Number, QR code matrix, verification badge,
 * and actions to mark the peer verified or dismiss.
 */
@Composable
fun SafetyNumberDialog(
    peerNickname: String,
    state: SafetyNumberDialogState,
    onMarkVerified: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            SafetyNumberDialogTitle(peerNickname = peerNickname, isVerified = state.isVerified)
        },
        text = {
            SafetyNumberDialogBody(state = state)
        },
        confirmButton = {
            Button(
                onClick = onMarkVerified,
                enabled = !state.isVerified && !state.isVerifying && state.safetyNumber.isNotBlank(),
            ) {
                Text(if (state.isVerified) "Verified" else "Mark as Verified")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun SafetyNumberDialogTitle(
    peerNickname: String,
    isVerified: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Safety Number · $peerNickname",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (isVerified) {
            VerifiedChip()
        }
    }
}

@Composable
private fun SafetyNumberDialogBody(state: SafetyNumberDialogState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PresentationConstants.SECTION_SPACING_DP.dp),
    ) {
        Text(
            text = "Compare these digits or scan the QR code in person to verify E2EE identity keys.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(PresentationConstants.PROGRESS_INDICATOR_SIZE_DP.dp),
                strokeWidth = PresentationConstants.PROGRESS_STROKE_WIDTH_DP.dp,
            )
        }
        state.error?.let { error ->
            SafetyNumberErrorText(error = error)
        }
        state.qrCodeMatrix?.let { matrix ->
            SafetyNumberQrCanvas(matrix = matrix)
        }
        if (state.safetyNumber.isNotBlank()) {
            SafetyNumberDigitsGrid(safetyNumber = state.safetyNumber)
        }
    }
}

@Composable
private fun SafetyNumberErrorText(error: PresentationError) {
    Text(
        text = error.message,
        style = MaterialTheme.typography.bodySmall,
        color = Color(PresentationConstants.COLOR_FAILED_TEXT),
    )
}

@Composable
private fun SafetyNumberQrCanvas(matrix: QrCodeMatrix) {
    Surface(
        shape = RoundedCornerShape(PresentationConstants.CORNER_RADIUS_SM_DP.dp),
        color = Color(PresentationConstants.COLOR_QR_BACKGROUND),
    ) {
        Canvas(
            modifier =
                Modifier
                    .padding(PresentationConstants.ITEM_SPACING_DP.dp)
                    .size(PresentationConstants.QR_CANVAS_SIZE_DP.dp),
        ) {
            drawRect(color = Color(PresentationConstants.COLOR_QR_BACKGROUND))
            val quietZone = PresentationConstants.QR_QUIET_ZONE_MODULES
            val totalModules = matrix.size + quietZone * PresentationConstants.QR_QUIET_ZONE_SIDES
            val moduleSize = size.minDimension / totalModules.toFloat()
            val foreground = Color(PresentationConstants.COLOR_QR_FOREGROUND)
            for (row in 0 until matrix.size) {
                for (col in 0 until matrix.size) {
                    if (matrix.isDark(row, col)) {
                        drawRect(
                            color = foreground,
                            topLeft = Offset((col + quietZone) * moduleSize, (row + quietZone) * moduleSize),
                            size = Size(moduleSize, moduleSize),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyNumberDigitsGrid(safetyNumber: String) {
    val rows = formatSafetyNumberRows(safetyNumber)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PresentationConstants.COMPACT_SPACING_DP.dp),
    ) {
        for (rowGroups in rows) {
            Text(
                text = rowGroups.joinToString(separator = "  "),
                fontFamily = FontFamily.Monospace,
                fontSize = PresentationConstants.SAFETY_CODE_FONT_SIZE_SP.sp,
                lineHeight = PresentationConstants.SAFETY_CODE_LINE_HEIGHT_SP.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
