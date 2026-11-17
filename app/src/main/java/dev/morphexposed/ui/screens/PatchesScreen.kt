package dev.morphexposed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.morphexposed.data.db.PatchSourceEntity
import dev.morphexposed.domain.source.BundleInfo
import dev.morphexposed.ui.components.EmptyState
import dev.morphexposed.ui.components.StatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchesScreen(
    sources: List<PatchSourceEntity>,
    bundleInfo: Map<Long, BundleInfo>,
    busy: String?,
    onRefresh: () -> Unit,
    onToggleEnabled: (Long, Boolean) -> Unit,
    onTogglePrerelease: (Long, Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Patches") },
            actions = {
                IconButton(onClick = onRefresh, enabled = busy == null) {
                    Icon(Icons.Rounded.Sync, contentDescription = "Check for updates")
                }
            }
        )

        if (sources.isEmpty()) {
            EmptyState(
                title = "No patch sources",
                body = "Add a Morphe-compatible patch bundle source in Settings."
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(sources, key = { it.id }) { source ->
                SourceCard(
                    source = source,
                    info = bundleInfo[source.id],
                    onToggleEnabled = { onToggleEnabled(source.id, it) },
                    onTogglePrerelease = { onTogglePrerelease(source.id, it) }
                )
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: PatchSourceEntity,
    info: BundleInfo?,
    onToggleEnabled: (Boolean) -> Unit,
    onTogglePrerelease: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        source.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${source.repoOwner}/${source.repoName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = source.enabled, onCheckedChange = onToggleEnabled)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (source.installedTag != null) {
                    StatusChip(source.installedTag, MaterialTheme.colorScheme.primary)
                } else {
                    StatusChip("Not downloaded", MaterialTheme.colorScheme.outline)
                }
                // "Outdated" is a state the brief asks for and that the app can only show if it
                // remembers what the source advertises, not just what it has installed.
                val newer = source.latestTag != null && source.latestTag != source.installedTag
                if (newer) {
                    StatusChip(
                        "${source.latestTag} available",
                        MaterialTheme.colorScheme.tertiary
                    )
                }
                if (source.isOfficial) {
                    StatusChip("Official", MaterialTheme.colorScheme.secondary)
                }
                if (source.requiredPatcherVersion != null) {
                    // Showing the required version but never comparing it meant a bundle built
                    // for a newer engine looked fine until a run failed deep inside the patcher.
                    val verdict = dev.morphexposed.domain.source.PatcherCompatibility
                        .check(source.requiredPatcherVersion)
                    StatusChip(
                        if (verdict.canRun) {
                            "Patcher ${source.requiredPatcherVersion}"
                        } else {
                            "Needs newer patcher"
                        },
                        if (verdict.canRun) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }

            if (info != null) {
                val packages = info.supportedPackages.keys
                Text(
                    "${info.patches.size} patches · ${packages.size} apps supported",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (packages.isNotEmpty()) {
                    Text(
                        packages.joinToString(", ") { it.substringAfterLast('.') },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            source.lastError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Checked ${relativeTime(source.lastCheckedAt)}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Pre-releases", style = MaterialTheme.typography.labelSmall)
                Switch(
                    checked = source.allowPrerelease,
                    onCheckedChange = onTogglePrerelease,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

/** Inline progress used while a run is streaming. */
@Composable
fun RunProgressCard(
    stage: String,
    patchProgress: Pair<Int, Int>?,
    taskProgress: Pair<String, Pair<Long, Long>>?
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stage.ifBlank { "Working…" }, style = MaterialTheme.typography.titleSmall)

            val (done, total) = taskProgress?.second ?: (0L to 0L)
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${taskProgress?.first.orEmpty()} $done / $total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            patchProgress?.let { (done, total) ->
                if (total > 0) {
                    Text(
                        "$done of $total patches",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
