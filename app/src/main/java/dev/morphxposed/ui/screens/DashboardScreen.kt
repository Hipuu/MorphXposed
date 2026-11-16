package dev.morphxposed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.morphxposed.automation.TriggerReason
import dev.morphxposed.data.db.PatchRunEntity
import dev.morphxposed.data.db.PatchSourceEntity
import dev.morphxposed.domain.apps.Compatibility
import dev.morphxposed.ui.components.EmptyState
import dev.morphxposed.ui.components.MetricTile
import dev.morphxposed.ui.components.StatusChip
import dev.morphxposed.ui.components.color
import dev.morphxposed.ui.components.label
import dev.morphxposed.ui.viewmodel.AppUiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    apps: List<AppUiModel>,
    sources: List<PatchSourceEntity>,
    runs: List<PatchRunEntity>,
    busy: String?,
    onRefreshSources: () -> Unit,
    onRefreshApps: () -> Unit,
    onPatchAll: () -> Unit,
    onOpenApp: (String) -> Unit
) {
    val supported = apps.count { it.status == Compatibility.SUPPORTED }
    val outdated = apps.count { it.needsRepatch }
    val lastRun = runs.firstOrNull()
    val readySources = sources.count { it.enabled && it.installedTag != null }

    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("MorphXposed") },
            actions = {
                IconButton(onClick = onRefreshSources, enabled = busy == null) {
                    Icon(Icons.Rounded.Sync, contentDescription = "Check for patch updates")
                }
                IconButton(onClick = onRefreshApps, enabled = busy == null) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Rescan installed apps")
                }
            }
        )

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (busy != null) {
                item {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )) {
                        Text(busy, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(supported.toString(), "Patchable apps", Modifier.weight(1f))
                    MetricTile(
                        outdated.toString(),
                        "Need repatch",
                        Modifier.weight(1f),
                        accent = if (outdated > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                    MetricTile(readySources.toString(), "Active sources", Modifier.weight(1f))
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Last activity", style = MaterialTheme.typography.titleMedium)
                        if (lastRun == null) {
                            Text(
                                "No patch runs yet. Open Apps to patch one.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(lastRun.appLabel, style = MaterialTheme.typography.bodyLarge)
                                StatusChip(
                                    text = lastRun.status.lowercase().replaceFirstChar { it.uppercase() },
                                    color = runStatusColor(lastRun.status)
                                )
                            }
                            Text(
                                "${lastRun.sourceVersionName} · ${relativeTime(lastRun.startedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            lastRun.error?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Automation", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Automatic patching runs for each app you enable on the Apps screen, " +
                                "after the app is installed or updated, and when a patch source " +
                                "publishes a newer bundle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onPatchAll) { Text("Patch enabled apps") }
                        }
                    }
                }
            }

            if (apps.isNotEmpty()) {
                item {
                    Text(
                        "Supported apps",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(apps.filter { !it.app.hidden }.take(8), key = { it.app.packageName }) { model ->
                    DashboardAppRow(model) { onOpenApp(model.app.packageName) }
                }
            } else {
                item {
                    EmptyState(
                        title = "No supported apps found",
                        body = "Install a supported app (YouTube, YouTube Music or Reddit), then " +
                            "rescan. Add more patch sources in Settings if you use community patches."
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardAppRow(model: AppUiModel, onClick: () -> Unit) {
    Card(onClick = onClick, Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(model.app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    "${model.app.packageName} · ${model.app.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusChip(model.status.label(), model.status.color())
        }
    }
}

internal fun runStatusColor(status: String): androidx.compose.ui.graphics.Color = when (status) {
    "SUCCEEDED" -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
    "FAILED" -> androidx.compose.ui.graphics.Color(0xFFC62828)
    "INSTALLING", "PATCHING", "SIGNING", "QUEUED" -> androidx.compose.ui.graphics.Color(0xFF1565C0)
    else -> androidx.compose.ui.graphics.Color(0xFF757575)
}

internal fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "never"
    val delta = System.currentTimeMillis() - epochMillis
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

internal val triggerLabel = mapOf(
    TriggerReason.MANUAL to "Manual",
    TriggerReason.APP_INSTALLED to "App installed",
    TriggerReason.APP_UPDATED to "App updated",
    TriggerReason.PATCHES_UPDATED to "Patches updated",
    TriggerReason.PERIODIC to "Scheduled"
)
