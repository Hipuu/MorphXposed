package dev.morphxposed.ui.screens

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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.morphxposed.data.prefs.InstallStrategy
import dev.morphxposed.data.prefs.ThemeMode
import dev.morphxposed.data.db.PatchSourceEntity
import dev.morphxposed.ui.viewmodel.SettingsUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsUiState,
    themeMode: ThemeMode,
    sources: List<PatchSourceEntity>,
    onTheme: (ThemeMode) -> Unit,
    onInstallStrategy: (InstallStrategy) -> Unit,
    onNotifySuccess: (Boolean) -> Unit,
    onNotifyFailure: (Boolean) -> Unit,
    onKeepBackups: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onVerboseLogging: (Boolean) -> Unit,
    onAddSource: (String) -> Unit,
    onRemoveSource: (Long) -> Unit,
    onGithubToken: (String) -> Unit
) {
    var showAddSource by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        TopAppBar(title = { Text("Settings") })

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- Appearance ----
            item {
                SettingsCard("Appearance") {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onTheme(mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "System"
                                            ThemeMode.LIGHT -> "Light"
                                            ThemeMode.DARK -> "Dark"
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // ---- Installer ----
            item {
                SettingsCard("Installer") {
                    Text(
                        "How patched apps are installed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = settings.installStrategy == InstallStrategy.SESSION,
                            onClick = { onInstallStrategy(InstallStrategy.SESSION) },
                            label = { Text("Session installer (no root)") }
                        )
                        FilterChip(
                            selected = settings.installStrategy == InstallStrategy.ROOT_MOUNT,
                            onClick = { onInstallStrategy(InstallStrategy.ROOT_MOUNT) },
                            label = { Text("Root mount (recommended with root)") }
                        )
                        FilterChip(
                            selected = settings.installStrategy == InstallStrategy.SHIZUKU,
                            onClick = { onInstallStrategy(InstallStrategy.SHIZUKU) },
                            label = { Text("Shizuku") }
                        )
                    }
                    Text(
                        if (settings.installStrategy == InstallStrategy.ROOT_MOUNT) {
                            "Root mount generates a root-manager module that bind-mounts the " +
                                "patched APK over the stock one at boot, so the stock signature and " +
                                "app data are preserved. Root is requested only when you patch."
                        } else {
                            "Without root the stock app must be uninstalled before installing the " +
                                "patched build, which clears its data. Use root mount to avoid that."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- Notifications ----
            item {
                SettingsCard("Notifications") {
                    ToggleRow(
                        title = "Notify on success",
                        subtitle = "Tell me when a patch is applied.",
                        checked = settings.notifyOnSuccess,
                        onChange = onNotifySuccess
                    )
                    ToggleRow(
                        title = "Notify on failure",
                        subtitle = "Tell me when a patch needs attention.",
                        checked = settings.notifyOnFailure,
                        onChange = onNotifyFailure
                    )
                }
            }

            // ---- Patch sources ----
            item {
                SettingsCard("Patch sources") {
                    Text(
                        "Add any Morphe-compatible bundle repository as owner/repo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    sources.forEach { source ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(source.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${source.repoOwner}/${source.repoName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!source.isOfficial) {
                                TextButton(onClick = { onRemoveSource(source.id) }) { Text("Remove") }
                            } else {
                                Text(
                                    "Built in",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    TextButton(onClick = { showAddSource = true }) { Text("Add source") }
                }
            }

            // ---- Storage & network ----
            item {
                SettingsCard("Storage and network") {
                    ToggleRow(
                        title = "Keep original APK backups",
                        subtitle = "Required for rollback and for repatching without re-downloading.",
                        checked = true,
                        onChange = onKeepBackups,
                        enabled = false
                    )
                    ToggleRow(
                        title = "Download over Wi-Fi only",
                        subtitle = "Bundle downloads can be large.",
                        checked = false,
                        onChange = onWifiOnly,
                        enabled = false
                    )
                    ToggleRow(
                        title = "Verbose logs",
                        subtitle = "Include the patcher's full output in run logs.",
                        checked = false,
                        onChange = onVerboseLogging,
                        enabled = false
                    )
                }
            }

            // ---- Advanced ----
            item {
                SettingsCard("Advanced") {
                    Text(
                        "GitHub token",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Optional. Raises the anonymous GitHub API rate limit when checking for " +
                            "patch bundles. It is only ever sent to api.github.com.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { showToken = true }) { Text("Set token") }
                }
            }

            // ---- About ----
            item {
                SettingsCard("About") {
                    Text(
                        "MorphXposed patches apps with the official Morphe patching engine " +
                            "(app.morphe:morphe-patcher). Patches are applied by rewriting the APK's " +
                            "bytecode and resources, exactly as Morphe itself does — not by runtime " +
                            "hooks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "The optional MorphXposed Events LSPosed module only forwards package " +
                            "install/update events so automation reacts immediately. It never " +
                            "modifies apps and is not required.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showAddSource) {
        var spec by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddSource = false },
            title = { Text("Add patch source") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = spec,
                        onValueChange = { spec = it },
                        label = { Text("owner/repo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "The repository must publish a Morphe patch bundle (.mpp) as a release " +
                            "asset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddSource(spec)
                        showAddSource = false
                    },
                    enabled = spec.isNotBlank()
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddSource = false }) { Text("Cancel") }
            }
        )
    }

    if (showToken) {
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showToken = false },
            title = { Text("GitHub token") },
            text = {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("ghp_…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onGithubToken(token)
                    showToken = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showToken = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
