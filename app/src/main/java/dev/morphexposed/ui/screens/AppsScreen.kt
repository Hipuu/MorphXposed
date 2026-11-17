package dev.morphexposed.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.morphexposed.domain.apps.Compatibility
import dev.morphexposed.ui.components.EmptyState
import dev.morphexposed.ui.components.StatusChip
import dev.morphexposed.ui.components.colorOrUnknown
import dev.morphexposed.ui.components.labelOrUnknown
import dev.morphexposed.ui.components.color
import dev.morphexposed.ui.components.label
import dev.morphexposed.ui.viewmodel.AppUiModel

private enum class AppFilter(val label: String) {
    ALL("All"),
    COMPATIBLE("Compatible"),
    OUTDATED("Needs repatch"),
    HIDDEN("Hidden")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    apps: List<AppUiModel>,
    busy: String?,
    onRescan: () -> Unit,
    onPatch: (String) -> Unit,
    onToggleAutoPatch: (String, Boolean) -> Unit,
    onToggleHidden: (String, Boolean) -> Unit,
    onRestore: ((String) -> Unit)? = null,
    onShare: ((String) -> Unit)? = null
) {
    var filter by remember { mutableStateOf(AppFilter.ALL) }
    var detail by remember { mutableStateOf<AppUiModel?>(null) }

    val visible = when (filter) {
        AppFilter.ALL -> apps.filter { !it.app.hidden }
        AppFilter.COMPATIBLE -> apps.filter { !it.app.hidden && it.status == Compatibility.SUPPORTED }
        AppFilter.OUTDATED -> apps.filter { !it.app.hidden && it.needsRepatch }
        AppFilter.HIDDEN -> apps.filter { it.app.hidden }
    }

    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Installed apps") },
            actions = {
                IconButton(onClick = onRescan, enabled = busy == null) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Rescan")
                }
            }
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) }
                )
            }
        }

        if (visible.isEmpty()) {
            EmptyState(
                title = "Nothing here",
                body = if (filter == AppFilter.ALL) {
                    "No supported apps are installed. Rescan after installing a supported app, or " +
                        "add a community patch source in Settings."
                } else {
                    "No apps match this filter."
                }
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visible, key = { it.app.packageName }) { model ->
                    AppRow(
                        model = model,
                        onOpenDetails = { detail = model },
                        onPatch = { onPatch(model.app.packageName) },
                        onToggleAutoPatch = { onToggleAutoPatch(model.app.packageName, it) }
                    )
                }
            }
        }
    }

    detail?.let { model ->
        AppDetailDialog(
            model = model,
            onDismiss = { detail = null },
            onPatch = {
                onPatch(model.app.packageName)
                detail = null
            },
            onToggleAutoPatch = { onToggleAutoPatch(model.app.packageName, it) },
            onToggleHidden = { onToggleHidden(model.app.packageName, it) },
            onRestore = onRestore?.let { restore -> { restore(model.app.packageName) } },
            onShare = onShare?.let { share -> { share(model.app.packageName) } }
        )
    }
}

@Composable
private fun AppRow(
    model: AppUiModel,
    onOpenDetails: () -> Unit,
    onPatch: () -> Unit,
    onToggleAutoPatch: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpenDetails)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        model.app.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${model.app.versionName} · ${model.app.packageName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusChip(model.status.labelOrUnknown(), model.status.colorOrUnknown())
            }

            if (model.report != null && !model.report.reason.isNullOrBlank()) {
                Text(
                    model.report.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (model.needsRepatch) {
                Text(
                    "Installed version changed since the last patch (${model.app.lastPatchedVersion}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onPatch,
                    enabled = model.status == Compatibility.SUPPORTED
                ) { Text(if (model.app.lastPatchedAt > 0) "Repatch" else "Patch") }

                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto", style = MaterialTheme.typography.labelMedium)
                    Switch(
                        checked = model.app.autoPatchEnabled,
                        onCheckedChange = onToggleAutoPatch,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppDetailDialog(
    model: AppUiModel,
    onDismiss: () -> Unit,
    onPatch: () -> Unit,
    onToggleAutoPatch: (Boolean) -> Unit,
    onToggleHidden: (Boolean) -> Unit,
    onRestore: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null
) {
    val report = model.report
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.app.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Package", model.app.packageName)
                DetailRow("Installed", model.app.versionName)
                DetailRow("Status", model.status.labelOrUnknown())
                if (report != null) {
                    if (report.supportedVersions.isNotEmpty()) {
                        DetailRow("Supported", report.supportedVersions.take(5).joinToString(", "))
                    }
                    DetailRow(
                        "Applicable patches",
                        "${report.applicablePatches.size} of " +
                            "${report.applicablePatches.size + report.skippedPatches.size}"
                    )
                }
                if (model.app.lastPatchedAt > 0) {
                    DetailRow("Last patched", relativeTime(model.app.lastPatchedAt))
                    model.app.lastPatchedBundleTag?.let { DetailRow("Bundle", it) }
                }
                DetailRow("Automatic", if (model.app.autoPatchEnabled) "Enabled" else "Disabled")
                if (model.app.splitApkPaths.isNotBlank()) {
                    DetailRow("Split APK", "Yes — modules are merged before patching")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onPatch, enabled = model.status == Compatibility.SUPPORTED) {
                Text("Patch now")
            }
        },
        dismissButton = {
            Row {
                if (onRestore != null && model.app.lastPatchedAt > 0) {
                    TextButton(onClick = onRestore) { Text("Restore") }
                }
                // Makes "the APK was exported" a claim the user can act on: without this the
                // signed APK sits in app-private storage with no way out.
                if (onShare != null && model.app.lastPatchedAt > 0) {
                    TextButton(onClick = onShare) { Text("Share APK") }
                }
                TextButton(onClick = { onToggleHidden(!model.app.hidden) }) {
                    Text(if (model.app.hidden) "Unhide" else "Hide")
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
