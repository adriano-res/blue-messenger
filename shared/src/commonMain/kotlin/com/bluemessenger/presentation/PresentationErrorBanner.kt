package com.bluemessenger.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bluemessenger.presentation.constants.PresentationConstants

/**
 * Shared structured error banner displaying permission, adapter, validation, or general errors.
 */
@Composable
internal fun PresentationErrorBanner(
    error: PresentationError,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title =
        when (error) {
            is PresentationError.MissingPermission -> "Permission Required: ${error.permission}"
            is PresentationError.AdapterDisabled -> "Bluetooth Adapter Disabled"
            is PresentationError.Validation -> "Invalid Input"
            is PresentationError.General -> "Error"
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PresentationConstants.CORNER_RADIUS_SM_DP.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(PresentationConstants.BANNER_PADDING_DP.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(PresentationConstants.WEIGHT_FILL),
                verticalArrangement = Arrangement.spacedBy(PresentationConstants.COMPACT_SPACING_DP.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Dismiss",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
