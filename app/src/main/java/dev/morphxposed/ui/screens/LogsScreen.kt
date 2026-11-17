package dev.morphxposed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.morphxposed.data.db.PatchRunEntity
import dev.morphxposed.ui.components.EmptyState
import dev.morphxposed.ui.components.StatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    runs: List<PatchRunEntity>,
    onClear: () -> Unit
) {
    var detail by remember { mutableStateOf<PatchRunEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Logs") },
            actions = {
                IconButton(onClick = { confirmClear = true }, enabled = runs.isNotEmpty()) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear logs")
                }
            }
        )

        if (runs.isEmpty()) {
            EmptyState(
                title = "No runs yet",
                body = "Patch results, errors and the patcher's own log output appear here."
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(runs, key = { it.id }) { run ->
                    RunRow(run) { detail = run }
                }
            }
        }
    }

    detail?.let { run ->
        RunDetailDialog(run) { detail = null }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all logs?") },
            text = { Text("This removes every recorded run. Patched apps are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    onClear()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RunRow(run: PatchRunEntity, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    run.appLabel,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusChip(run.status, runStatusColor(run.status))
            }
            Text(
                "${run.sourceVersionName} · ${run.bundleTag ?: "no bundle"} · ${relativeTime(run.startedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            run.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RunDetailDialog(run: PatchRunEntity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${run.appLabel} · ${run.status}") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Version ${run.sourceVersionName} (${run.sourceVersionCode})\n" +
                        "Bundle ${run.bundleTag ?: "—"}\n" +
                        "Started ${relativeTime(run.startedAt)}\n" +
                        "Applied ${run.appliedPatches.ifBlank { "none" }}\n" +
                        "Installed ${if (run.installed) "yes" else "no"} " +
                        "(${run.installStrategy ?: "n/a"})",
                    style = MaterialTheme.typography.bodySmall
                )
                run.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                // Which patches failed, and why. This was recorded as "" for every run, so a
                // partial failure showed "Applied 2 patches" with no way to learn what the third
                // one hit.
                if (run.failedPatches.isNotBlank()) {
                    Text("Failed patches", style = MaterialTheme.typography.labelMedium)
                    Text(
                        run.failedPatches,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (run.log.isNotBlank()) {
                    Text("Log", style = MaterialTheme.typography.labelMedium)
                    Text(
                        run.log,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
