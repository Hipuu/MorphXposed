package dev.morphexposed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.morphexposed.data.prefs.InstallStrategy
import dev.morphexposed.ui.viewmodel.SettingsUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(
    settings: SettingsUiState,
    onAutoPatchOnInstall: (Boolean) -> Unit,
    onAutoPatchOnUpdate: (Boolean) -> Unit,
    onAutoCheck: (Boolean) -> Unit,
    onIntervalHours: (Int) -> Unit,
    onRunUpdateCheck: () -> Unit,
    onPatchAllNow: () -> Unit
) {
    var showIntervalDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        TopAppBar(title = { Text("Automation") })

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("When apps change", style = MaterialTheme.typography.titleMedium)
                        ToggleRow(
                            title = "Patch newly installed apps",
                            subtitle = "When a supported app is installed, patch it automatically. " +
                                "Only apps you enabled for automation are affected.",
                            checked = settings.autoPatchOnInstall,
                            onChange = onAutoPatchOnInstall
                        )
                        ToggleRow(
                            title = "Repatch updated apps",
                            subtitle = "When a patched app updates from the store, reapply the same " +
                                "patches to the new version.",
                            checked = settings.autoPatchOnUpdate,
                            onChange = onAutoPatchOnUpdate
                        )
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Patch updates", style = MaterialTheme.typography.titleMedium)
                        ToggleRow(
                            title = "Check for new patch bundles",
                            subtitle = "A newer bundle can fix patch bugs without the app changing.",
                            checked = settings.autoCheckForPatchUpdates,
                            onChange = onAutoCheck
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Check interval", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Every ${settings.updateCheckIntervalHours} hour(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { showIntervalDialog = true }) { Text("Change") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onRunUpdateCheck) { Text("Check now") }
                            TextButton(onClick = onPatchAllNow) { Text("Patch enabled apps") }
                        }
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("How the triggers work", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Android stopped delivering install and update broadcasts to ordinary " +
                                "apps in 8.0. MorpheXposed therefore uses two independent signals:\n\n" +
                                "• A scheduled check that rescans installed apps and refreshes patch " +
                                "sources. This always works.\n" +
                                "• Optional real-time events from the MorpheXposed Events LSPosed module, " +
                                "which sees package changes immediately.\n\n" +
                                "Install the module if you want updates picked up the moment they " +
                                "happen instead of at the next scheduled check.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Installer", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (settings.installStrategy) {
                                InstallStrategy.SESSION ->
                                    "Session installer. Works without root. Because the patched APK " +
                                        "is signed with MorpheXposed's key, the stock app must be " +
                                        "uninstalled first, which clears its data."
                                InstallStrategy.ROOT_MOUNT ->
                                    "Root mount. The patched APK is mounted over the stock app at " +
                                        "boot, so the stock signature and app data both survive. " +
                                        "This is the recommended path on a rooted device."
                                InstallStrategy.SHIZUKU ->
                                    "Shizuku. Installs through Shizuku when it is running; falls " +
                                        "back to the session installer otherwise."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showIntervalDialog) {
        AlertDialog(
            onDismissRequest = { showIntervalDialog = false },
            title = { Text("Check interval") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6, 12, 24).forEach { hours ->
                            FilterChip(
                                selected = settings.updateCheckIntervalHours == hours,
                                onClick = {
                                    onIntervalHours(hours)
                                    showIntervalDialog = false
                                },
                                label = { Text("${hours}h") }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(48, 72, 168).forEach { hours ->
                            FilterChip(
                                selected = settings.updateCheckIntervalHours == hours,
                                onClick = {
                                    onIntervalHours(hours)
                                    showIntervalDialog = false
                                },
                                label = { Text(if (hours == 168) "1 week" else "${hours}h") }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIntervalDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
