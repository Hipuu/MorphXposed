package dev.morphxposed.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import dev.morphxposed.ui.ApkSharing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.morphxposed.MorphXposedApp
import dev.morphxposed.ui.screens.AppsScreen
import dev.morphxposed.ui.screens.AutomationScreen
import dev.morphxposed.ui.screens.DashboardScreen
import dev.morphxposed.ui.screens.LogsScreen
import dev.morphxposed.ui.screens.PatchesScreen
import dev.morphxposed.ui.screens.SettingsScreen
import dev.morphxposed.ui.theme.MorphXposedTheme
import dev.morphxposed.ui.viewmodel.MainViewModel
import dev.morphxposed.ui.viewmodel.RestorePresentation

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Home", Icons.Rounded.Dashboard),
    APPS("Apps", Icons.Rounded.Apps),
    PATCHES("Patches", Icons.Rounded.Extension),
    AUTOMATION("Auto", Icons.Rounded.SmartToy),
    LOGS("Logs", Icons.Rounded.Description),
    SETTINGS("Settings", Icons.Rounded.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as MorphXposedApp
            val vm: MainViewModel = viewModel { MainViewModel(app) }
            MorphXposedApp(app = app, vm = vm)
        }
    }
}

@Composable
private fun MorphXposedApp(app: MorphXposedApp, vm: MainViewModel) {
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val bundleInfo by vm.bundleInfo.collectAsStateWithLifecycle()
    val runProgress by vm.runProgress.collectAsStateWithLifecycle()
    val lsposed by vm.lsposedState.collectAsStateWithLifecycle()
    val lsposedStatus = lsposed.describe()
    val lsposedActive = lsposed.bridgeObserved
    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val bridgeEnabled by vm.bridgeEnabled.collectAsStateWithLifecycle()
    val restorePrompt by vm.restorePrompt.collectAsStateWithLifecycle()


    val snackbar = remember { SnackbarHostState() }
    // Sharing needs the Activity context and a chooser, so it is wired here rather than in the
    // ViewModel; the file itself comes from the recorded run.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val onShareApk: (String) -> Unit = { packageName ->
        scope.launch {
            val apk = vm.shareableApk(packageName)
            val uri = ApkSharing.contentUri(context, apk)
            if (uri == null) {
                snackbar.showSnackbar("No patched APK is on disk for this app.")
            } else {
                runCatching {
                    context.startActivity(ApkSharing.chooser(uri, ApkSharing.displayName(apk!!)))
                }.onFailure {
                    snackbar.showSnackbar("No app available to share with.")
                }
            }
        }
    }
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    MorphXposedTheme(themeMode = themeMode) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) }
                        )
                    }
                }
            }
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                when (tab) {
                    Tab.DASHBOARD -> DashboardScreen(
                        apps = apps,
                        sources = sources,
                        runs = runs,
                        busy = busy,
                        onRefreshSources = vm::refreshSources,
                        onRefreshApps = vm::refreshApps,
                        onPatchAll = vm::patchAllAutoEnabled,
                        onOpenApp = {
                            tab = Tab.APPS
                        }
                    )

                    Tab.APPS -> AppsScreen(
                        apps = apps,
                        busy = busy,
                        onRescan = vm::refreshApps,
                        onPatch = { vm.patchApp(it) },
                        onToggleAutoPatch = vm::setAutoPatch,
                        onToggleHidden = vm::setHidden,
                        onRestore = vm::requestRestore,
                        onShare = onShareApk
                    )

                    Tab.PATCHES -> PatchesScreen(
                        sources = sources,
                        bundleInfo = bundleInfo,
                        busy = busy,
                        onRefresh = vm::refreshSources,
                        onToggleEnabled = vm::setSourceEnabled,
                        onTogglePrerelease = vm::setAllowPrerelease
                    )

                    Tab.AUTOMATION -> AutomationScreen(
                        settings = settings,
                        onAutoPatchOnInstall = vm::setAutoPatchOnInstall,
                        onAutoPatchOnUpdate = vm::setAutoPatchOnUpdate,
                        onAutoCheck = vm::setAutoCheck,
                        onIntervalHours = vm::setIntervalHours,
                        onRunUpdateCheck = vm::runUpdateCheckNow,
                        onPatchAllNow = vm::patchAllAutoEnabled
                    )

                    Tab.LOGS -> LogsScreen(runs = runs, onClear = vm::clearLogs)

                    Tab.SETTINGS -> SettingsScreen(
                        settings = settings,
                        themeMode = themeMode,
                        sources = sources,
                        onTheme = vm::setTheme,
                        onInstallStrategy = vm::setInstallStrategy,
                        onNotifySuccess = vm::setNotifySuccess,
                        onNotifyFailure = vm::setNotifyFailure,
                        onKeepBackups = vm::setKeepBackups,
                        onWifiOnly = vm::setWifiOnly,
                        onVerboseLogging = vm::setVerboseLogging,
                        onAddSource = vm::addSource,
                        onRemoveSource = vm::removeSource,
                        onGithubToken = vm::setGithubToken,
                        lsposedStatus = lsposedStatus,
                        lsposedActive = lsposedActive,
                        onRefreshLsposed = vm::refreshLsposed,
                        permissions = permissions,
                        bridgeEnabled = bridgeEnabled,
                        onSetBridgeEnabled = vm::setBridgeEnabled
                    )
                }
            }

            RunSheet(progress = runProgress, onDismiss = vm::clearRunProgress)

            // A restore that would clear the app's data must be confirmed, not one tap away.
            restorePrompt?.let { prompt ->
                AlertDialog(
                    onDismissRequest = vm::dismissRestore,
                    title = { Text(RestorePresentation.title(prompt)) },
                    text = { Text(RestorePresentation.body(prompt)) },
                    confirmButton = {
                        TextButton(onClick = vm::confirmRestore) {
                            Text(RestorePresentation.confirmLabel(prompt))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = vm::dismissRestore) {
                            Text(RestorePresentation.dismissLabel())
                        }
                    }
                )
            }
        }
    }
}
