package dev.morphxposed.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.morphxposed.MorphXposedApp
import dev.morphxposed.automation.BatchSummary
import dev.morphxposed.automation.TriggerReason
import dev.morphxposed.automation.UpdateCheckWorker
import dev.morphxposed.data.db.PatchRunEntity
import dev.morphxposed.data.db.PatchSourceEntity
import dev.morphxposed.data.db.SupportedAppEntity
import dev.morphxposed.data.prefs.InstallStrategy
import dev.morphxposed.data.prefs.SettingsStore
import dev.morphxposed.data.prefs.ThemeMode
import dev.morphxposed.domain.apps.Compatibility
import dev.morphxposed.domain.apps.CompatibilityReport
import dev.morphxposed.domain.apps.CompatibilityResolver
import dev.morphxposed.domain.patch.PatchEvent
import dev.morphxposed.domain.source.BundleInfo
import dev.morphxposed.domain.source.RefreshResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A supported app plus its compatibility verdict, as the list renders it. */
data class AppUiModel(
    val app: SupportedAppEntity,
    val report: CompatibilityReport?
) {
    val status: Compatibility get() = report?.status ?: Compatibility.NOT_SUPPORTED
    val needsRepatch: Boolean
        get() = app.lastPatchedVersion != null && app.lastPatchedVersion != app.versionName
}

/** Live state of a patch run, for the progress sheet. */
data class RunProgress(
    val active: Boolean = false,
    val packageName: String? = null,
    val stage: String = "",
    val patchProgress: Pair<Int, Int>? = null,
    val taskProgress: Pair<String, Pair<Long, Long>>? = null,
    val logLines: List<String> = emptyList(),
    val finished: Boolean = false,
    val failed: String? = null
)

/**
 * Single ViewModel for the whole app.
 *
 * A single ViewModel is deliberate here: the screens are facets of one workflow (a patch run
 * moves through Dashboard → Apps → Logs), and splitting it would mean duplicating the run state
 * and the bundle metadata cache across several owners.
 */
class MainViewModel(private val app: MorphXposedApp) : ViewModel() {

    private val db = app.database
    private val settings = app.settings
    private val coordinator = app.coordinator
    private val sourceRepository = app.sourceRepository
    private val scanner = dev.morphxposed.domain.apps.AppScanner(app)
    private val inspector = dev.morphxposed.domain.source.BundleInspector()

    // ---- Observable state ----------------------------------------------------------------

    val sources: StateFlow<List<PatchSourceEntity>> =
        sourceRepository.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val themeMode: StateFlow<ThemeMode> =
        settings.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val runs: StateFlow<List<PatchRunEntity>> =
        db.patchRunDao().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _runProgress = MutableStateFlow(RunProgress())
    val runProgress: StateFlow<RunProgress> = _runProgress.asStateFlow()

    /** Bundle metadata per source, refreshed whenever sources change. */
    private val _bundleInfo = MutableStateFlow<Map<Long, BundleInfo>>(emptyMap())
    val bundleInfo: StateFlow<Map<Long, BundleInfo>> = _bundleInfo.asStateFlow()

    private val _apps = MutableStateFlow<List<AppUiModel>>(emptyList())
    val apps: StateFlow<List<AppUiModel>> = _apps.asStateFlow()

    /** Published settings, mirrored so Compose can read them synchronously. */
    val settingsState: StateFlow<SettingsUiState> = settings.snapshotFlow
        .map { snap ->
            SettingsUiState(
                installStrategy = snap.installStrategy,
                autoPatchOnInstall = snap.autoPatchOnInstall,
                autoPatchOnUpdate = snap.autoPatchOnUpdate,
                autoCheckForPatchUpdates = snap.autoCheckForPatchUpdates,
                updateCheckIntervalHours = snap.updateCheckIntervalHours,
                notifyOnSuccess = snap.notifyOnSuccess,
                notifyOnFailure = snap.notifyOnFailure
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SettingsUiState(
                installStrategy = InstallStrategy.SESSION,
                autoPatchOnInstall = false,
                autoPatchOnUpdate = false,
                autoCheckForPatchUpdates = true,
                updateCheckIntervalHours = SettingsStore.DEFAULT_INTERVAL_HOURS,
                notifyOnSuccess = true,
                notifyOnFailure = true
            )
        )

    init {
        viewModelScope.launch {
            runCatching { coordinator.refreshInstalledApps() }
            reloadApps()
        }
        viewModelScope.launch {
            sources.collect { reloadApps() }
        }
        viewModelScope.launch {
            sources.collect { list -> reloadBundleInfo(list) }
        }
    }

    // ---- Actions -------------------------------------------------------------------------

    fun dismissMessage() {
        _message.value = null
    }

    /** Re-scans installed apps. */
    fun refreshApps() {
        viewModelScope.launch {
            _busy.value = "Scanning installed apps…"
            runCatching { coordinator.refreshInstalledApps() }
                .onFailure { _message.value = it.message }
            reloadApps()
            _busy.value = null
        }
    }

    /** Refreshes every enabled patch source. */
    fun refreshSources() {
        viewModelScope.launch {
            _busy.value = "Checking patch sources…"
            var updated = 0
            var failed = 0
            sourceRepository.refreshAll().collect { (_, result) ->
                when (result) {
                    is RefreshResult.Updated -> updated++
                    is RefreshResult.Failed -> failed++
                    RefreshResult.AlreadyCurrent -> Unit
                }
            }
            reloadBundleInfo(sources.value)
            reloadApps()
            _message.value = when {
                failed > 0 && updated > 0 -> "Updated $updated source(s); $failed failed."
                failed > 0 -> "$failed source(s) failed to update."
                updated > 0 -> "Updated $updated patch source(s)."
                else -> "All patch sources are up to date."
            }
            _busy.value = null
        }
    }

    fun setSourceEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { sourceRepository.setEnabled(id, enabled) }
    }

    fun setAllowPrerelease(id: Long, allow: Boolean) {
        viewModelScope.launch { sourceRepository.setAllowPrerelease(id, allow) }
    }

    fun addSource(spec: String) {
        viewModelScope.launch {
            _busy.value = "Adding source…"
            val result = runCatching { sourceRepository.addSource(spec) }
            result.onSuccess { newId: Long ->
                val added = sources.value.firstOrNull { it.id == newId }
                if (added != null) {
                    runCatching { sourceRepository.refresh(added) }
                }
                refreshSources()
            }
            result.onFailure { error -> _message.value = error.message }
            _busy.value = null
        }
    }

    fun removeSource(id: Long) {
        viewModelScope.launch {
            runCatching { sourceRepository.removeSource(id) }
                .onFailure { _message.value = it.message }
        }
    }

    /** Patches one app now, streaming progress into [runProgress]. */
    fun patchApp(packageName: String, force: Boolean = false) {
        if (_runProgress.value.active) {
            _message.value = "A patch run is already in progress."
            return
        }
        viewModelScope.launch {
            _runProgress.value = RunProgress(active = true, packageName = packageName)
            coordinator.observeCurrentRun { event ->
                _runProgress.update { it.reduce(event) }
            }
            val summary = runCatching {
                coordinator.runBatch(TriggerReason.MANUAL, packageFilter = packageName, force = force)
            }.getOrElse {
                BatchSummary(TriggerReason.MANUAL, emptyList(), listOf(packageName to (it.message ?: "failed")), emptyList())
            }
            coordinator.clearCurrentRun()
            _runProgress.update { it.copy(active = false, finished = true, failed = summary.failed.firstOrNull()?.second) }
            _message.value = summarise(summary)
            reloadApps()
        }
    }

    /** Patches every app with automation enabled. */
    fun patchAllAutoEnabled() {
        if (_runProgress.value.active) return
        viewModelScope.launch {
            _runProgress.value = RunProgress(active = true)
            coordinator.observeCurrentRun { event -> _runProgress.update { it.reduce(event) } }
            val summary = coordinator.runBatch(TriggerReason.MANUAL, force = false)
            coordinator.clearCurrentRun()
            _runProgress.update { it.copy(active = false, finished = true) }
            _message.value = summarise(summary)
            reloadApps()
        }
    }

    fun clearRunProgress() {
        _runProgress.value = RunProgress()
    }

    fun setAutoPatch(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            db.supportedAppDao().setAutoPatch(packageName, enabled)
            reloadApps()
        }
    }

    fun setHidden(packageName: String, hidden: Boolean) {
        viewModelScope.launch {
            db.supportedAppDao().setHidden(packageName, hidden)
            reloadApps()
        }
    }

    fun runUpdateCheckNow() {
        UpdateCheckWorker.runNow(app)
        _message.value = "Update check queued."
    }

    fun clearLogs() {
        viewModelScope.launch {
            db.patchRunDao().clearAll()
        }
    }

    // ---- Settings ------------------------------------------------------------------------

    fun setTheme(mode: ThemeMode) = launchSetting { settings.setThemeMode(mode) }
    fun setInstallStrategy(strategy: InstallStrategy) = launchSetting { settings.setInstallStrategy(strategy) }
    fun setAutoPatchOnInstall(v: Boolean) = launchSetting { settings.setAutoPatchOnInstall(v) }
    fun setAutoPatchOnUpdate(v: Boolean) = launchSetting { settings.setAutoPatchOnUpdate(v) }
    fun setAutoCheck(v: Boolean) = launchSetting {
        settings.setAutoCheckForPatchUpdates(v)
        if (v) UpdateCheckWorker.schedule(app, settings.snapshot().updateCheckIntervalHours)
        else UpdateCheckWorker.cancel(app)
    }
    fun setIntervalHours(v: Int) = launchSetting {
        settings.setUpdateCheckIntervalHours(v)
        if (settings.snapshot().autoCheckForPatchUpdates) {
            UpdateCheckWorker.schedule(app, settings.snapshot().updateCheckIntervalHours)
        }
    }
    fun setNotifySuccess(v: Boolean) = launchSetting { settings.setNotifyOnSuccess(v) }
    fun setNotifyFailure(v: Boolean) = launchSetting { settings.setNotifyOnFailure(v) }
    fun setKeepBackups(v: Boolean) = launchSetting { settings.setKeepOriginalBackup(v) }
    fun setWifiOnly(v: Boolean) = launchSetting { settings.setWifiOnlyDownloads(v) }
    fun setVerboseLogging(v: Boolean) = launchSetting { settings.setVerboseLogging(v) }
    fun setGithubToken(v: String) = launchSetting { settings.setGithubToken(v) }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { _message.value = it.message }
        }
    }

    // ---- Internals -----------------------------------------------------------------------

    private suspend fun reloadApps() {
        val rows = db.supportedAppDao().all()
        val info = _bundleInfo.value
        val models = rows.map { row ->
            val bundle = info.values.firstOrNull { it.supportedPackages.containsKey(row.packageName) }
            val report = bundle?.let {
                CompatibilityResolver.resolve(
                    bundle, row.packageName, row.versionName, android.os.Build.VERSION.SDK_INT
                )
            }
            AppUiModel(row, report)
        }
        _apps.value = models
    }

    private suspend fun reloadBundleInfo(list: List<PatchSourceEntity>) {
        val map = LinkedHashMap<Long, BundleInfo>()
        for (source in list) {
            sourceRepository.inspect(source)?.let { map[source.id] = it }
        }
        _bundleInfo.value = map
        reloadApps()
    }

    private fun summarise(summary: BatchSummary): String = when {
        summary.total == 0 -> "Nothing to patch."
        summary.failed.isEmpty() && summary.patched.isNotEmpty() ->
            "Patched ${summary.patched.size} app(s)."
        summary.failed.isNotEmpty() ->
            "${summary.failed.size} failed: ${summary.failed.first().second}"
        else -> summary.skipped.firstOrNull()?.second ?: "Nothing to patch."
    }

    /** Folds a [PatchEvent] into the observable run state. */
    private fun RunProgress.reduce(event: PatchEvent): RunProgress = when (event) {
        is PatchEvent.Stage -> copy(stage = event.stage.name + if (event.detail.isNotEmpty()) ": ${event.detail}" else "")
        is PatchEvent.TaskProgress -> copy(
            taskProgress = event.task to (event.done to event.total)
        )
        is PatchEvent.PatchSucceeded -> copy(
            patchProgress = (patchProgress?.first ?: 0).plus(1) to (patchProgress?.second ?: 0),
            logLines = (logLines + "✓ ${event.name}").takeLast(400)
        )
        is PatchEvent.PatchFailed -> copy(
            logLines = (logLines + "✗ ${event.name}: ${event.message}").takeLast(400)
        )
        is PatchEvent.Log -> copy(logLines = (logLines + event.line).takeLast(400))
        is PatchEvent.Error -> copy(failed = event.message, logLines = (logLines + "ERROR: ${event.message}").takeLast(400))
        is PatchEvent.Done -> copy(finished = true, logLines = (logLines + "Done").takeLast(400))
        is PatchEvent.PatchExecuting -> copy(logLines = (logLines + "→ ${event.name}").takeLast(400))
    }
}

data class SettingsUiState(
    val installStrategy: InstallStrategy,
    val autoPatchOnInstall: Boolean,
    val autoPatchOnUpdate: Boolean,
    val autoCheckForPatchUpdates: Boolean,
    val updateCheckIntervalHours: Int,
    val notifyOnSuccess: Boolean,
    val notifyOnFailure: Boolean
)
