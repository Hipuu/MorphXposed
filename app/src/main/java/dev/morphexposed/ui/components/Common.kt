package dev.morphexposed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.morphexposed.domain.apps.Compatibility

/** Semantic colour for each compatibility verdict, used by chips and status dots. */
fun Compatibility.color(): Color = when (this) {
    Compatibility.SUPPORTED -> Color(0xFF2E7D32)
    Compatibility.UNIVERSAL_ONLY -> Color(0xFFE6A700)
    Compatibility.UNSUPPORTED_VERSION -> Color(0xFFE6A700)
    Compatibility.NOT_SUPPORTED -> Color(0xFF757575)
}

fun Compatibility.label(): String = when (this) {
    Compatibility.SUPPORTED -> "Compatible"
    Compatibility.UNIVERSAL_ONLY -> "Universal only"
    Compatibility.UNSUPPORTED_VERSION -> "Version unsupported"
    Compatibility.NOT_SUPPORTED -> "Not supported"
}

/** Small status pill with a leading dot. */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Centred empty/zero state used across screens. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Full-screen spinner with a caption. */
@Composable
fun LoadingState(caption: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(caption, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A labelled metric, used on the dashboard. */
@Composable
fun MetricTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = accent)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
    }
}

/**
 * Label for a verdict that may not have been computed yet.
 *
 * A null verdict means "not evaluated", never "not supported". Showing a compatibility claim the
 * app has not made is how the Dashboard and the Apps screen came to disagree about the same app
 * at the same moment on a real device.
 */
fun Compatibility?.labelOrUnknown(): String = this?.label() ?: "Checking…"

/** Colour for [labelOrUnknown]: neutral while the verdict is unknown. */
fun Compatibility?.colorOrUnknown(): Color =
    this?.color() ?: Color(0xFF757575)