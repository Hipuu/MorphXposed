package dev.morphxposed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.morphxposed.ui.viewmodel.RunPresentation
import dev.morphxposed.ui.viewmodel.RunProgress

/**
 * Bottom sheet showing a live patch run.
 *
 * It states plainly that Morphe's patcher is doing the work, and streams that patcher's own log
 * so a failure can be diagnosed instead of guessed at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunSheet(progress: RunProgress, onDismiss: () -> Unit) {
    if (!progress.active && !progress.finished) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { if (!progress.active) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                // Single source of truth: the title logic is tested in RunPresentation, which
                // also guarantees a finished run is not still labelled "Patching...".
                RunPresentation.sheetTitle(progress),
                style = MaterialTheme.typography.titleMedium
            )

            if (progress.stage.isNotBlank()) {
                Text(
                    progress.stage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val task = progress.taskProgress
            if (task != null && task.second.second > 0) {
                LinearProgressIndicator(
                    progress = {
                        (task.second.first.toFloat() / task.second.second).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${task.first} ${task.second.first}/${task.second.second}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (progress.active) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            progress.patchProgress?.let { (done, total) ->
                if (total > 0) {
                    Text(
                        "$done of $total patches",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            progress.failed?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (progress.logLines.isNotEmpty()) {
                Text("Patcher log", style = MaterialTheme.typography.labelMedium)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        progress.logLines.takeLast(120).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !progress.active) {
                    Text("Close")
                }
            }
        }
    }
}
