package dev.morphexposed.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.morphexposed.MorpheXposedApp
import dev.morphexposed.automation.BatchSummary
import dev.morphexposed.automation.TriggerReason
import dev.morphexposed.automation.UpdateCheckWorker
import dev.morphexposed.data.db.PatchRunEntity
import dev.morphexposed.data.db.PatchSourceEntity
import dev.morphexposed.data.db.SupportedAppEntity
import dev.morphexposed.data.prefs.InstallStrategy
import dev.morphexposed.data.prefs.SettingsStore
import dev.morphexposed.data.prefs.ThemeMode
import dev.morphexposed.domain.apps.Compatibility
import dev.morphexposed.domain.apps.CompatibilityReport
import dev.morphexposed.domain.apps.CompatibilityResolver
import dev.morphexposed.domain.patch.PatchEvent
import dev.morphexposed.domain.source.BundleInfo
import dev.morphexposed.domain.source.RefreshResult
import org.koin.java.KoinJavaComponent.getKoin
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
    /**
     * The verdict, or null when it has not been computed yet.
     *
     * This used to fall back to [Compatibility.NOT_SUPPORTED], which turned "not evaluated" into
     * a positive claim. On a device the Dashboard showed "Not supported" for YouTube while the
     * Apps screen showed "Universal only" for the same app at the same moment, because only one of
     * them had the report. Absence of a verdict is not a verdict.
     */
    val status: Compatibility? get() = report?.status
    val needsRepatch: Boolean
        get() = app.lastPatchedVersion != null && app.lastPatchedVersion != app.versionName
}

/**
 * Single ViewModel for the whole app.
 *
 * A single ViewModel is deliberate here: the screens are facets of one workflow (a patch run
 * moves through Dashboard → Apps → Logs), and splitting it would mean duplicating the run state
 * and the bundle metadata cache across several owners.
 */
class MainViewModel(private val app: MorpheXposedApp) : ViewModel() {

    private val db = app.database
    private val settings = app.settings
    private val coordinator = app.coordinator
    private val sourceRepository = app.sourceRepository
    private val scanner = dev.morphexposed.domain.apps.AppScanner(app)
    private val inspector = dev.morphexposed.domain.source.BundleInspector()

    // ---- Observable state ----------------------------------------------------------------

    val sources: StateFlow<List<PatchSourceEntity>> =
        sourceRepository.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val themeMode: StateFlow<ThemeMode> =
        settings.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val runs: StateFlow<List<PatchRunEntity>> =
        db.patchRunDao().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** LSPosed / events-bridge status, for the settings row. */
    private val lsposedStatusReader by lazy { dev.morphexposed.automation.LsposedStatus(app) }
    private val _lsposed = MutableStateFlow(dev.morphexposed.automation.LsposedStatus.State(false, null, false, null, false))
    val lsposedState: StateFlow<dev.morphexposed.automation.LsposedStatus.State> = _lsposed.asStateFlow()

    /** Prerequisites the app depends on, shown in Settings. */
    private val permissionReader by lazy { getKoin().get<dev.morphexposed.automation.PermissionStatus>() }
    private val _permissions = MutableStateFlow(emptyList<dev.morphexposed.automation.PermissionStatus.Item>())
    val permissions: StateFlow<List<dev.morphexposed.automation.PermissionStatus.Item>> =
        _permissions.asStateFlow()

    /**
     * Re-reads what the platform will report about LSPosed, and the prerequisite list with it:
     * granting a permission elsewhere and returning here should update both.
     */
    fun refreshLsposed() {
        _lsposed.value = lsposedStatusReader.read()
        _permissions.value = runCatching { permissionReader.read() }.getOrDefault(emptyList())
    }

    /** Rollback support, reachable from the app detail dialog. */
    private val rollbackService by lazy { getKoin().get<dev.morphexposed.domain.backup.RollbackService>() }

    /**
     * A restore the user has asked for but not yet confirmed.
     *
     * Restoring a session-installed app re-installs the original APK, which changes the
     * signature back and makes Android clear the app's data. That is unavoidable, so the user
     * must be told before it happens rather than afterwards.
     */
    data class RestorePrompt(
        val packageName: String,
        val appLabel: String,
        val losesAppData: Boolean,
        val reason: String
    )

    private val _restorePrompt = MutableStateFlow<RestorePrompt?>(null)
    val restorePrompt: StateFlow<RestorePrompt?> = _restorePrompt.asStateFlow()

    /** Describes what a rollback for [packageName] would do, or null when none is possible. */
    suspend fun rollbackAvailability(packageName: String): dev.morphexposed.domain.backup.RollbackService.Availability? =
        runCatching {
            val row = db.supportedAppDao().byPackage(packageName) ?: return@runCatching null
            rollbackService.availability(packageName, row.mounted)
        }.getOrNull()

    /**
     * Asks the user to confirm a restore, showing whether their data is at risk.
     *
     * A restore that would clear the app's data must never be one tap away with no warning: the
     * information existed in `RollbackService.Availability` and was shown nowhere.
     */
    fun requestRestore(packageName: String) {
        viewModelScope.launch {
            val availability = rollbackAvailability(packageName)
            if (availability == null || !availability.canRollback) {
                _message.value = availability?.reason
                    ?: "This app has no saved original, so it cannot be restored."
                return@launch
            }
            val row = db.supportedAppDao().byPackage(packageName)
            _restorePrompt.value = RestorePresentation.prompt(packageName, row?.label, availability)
        }
    }

    /** Abandons a pending restore. */
    fun dismissRestore() {
        _restorePrompt.value = null
    }

    /** Carries out the restore the user confirmed. */
    fun confirmRestore() {
        val prompt = _restorePrompt.value ?: return
        _restorePrompt.value = null
        rollback(prompt.packageName)
    }

    /** Restores [packageName] from its saved original APK. */
    fun rollback(packageName: String) {
        viewModelScope.launch {
            _busy.value = "Restoring $packageName…"
            val row = db.supportedAppDao().byPackage(packageName)
            val result = rollbackService.rollback(packageName, row?.mounted ?: false)
            _message.value = when (result) {
                is dev.morphexposed.domain.backup.RollbackService.Result.Restored ->
                    "Restored $packageName to ${result.versionName}."
                is dev.morphexposed.domain.backup.RollbackService.Result.Failed ->
                    result.message
            }
            _busy.value = null
            reloadApps()
        }
    }

    /** The most recent patched APK for [packageName], if it is still on disk. */
    suspend fun shareableApk(packageName: String): java.io.File? = runCatching {
        val run = db.patchRunDao().latestFor(packageName) ?: return@runCatching null
        val path = run.outputApkPath ?: return@runCatching null
        java.io.File(path).takeIf { it.isFile && it.length() > 0 }
    }.getOrNull()

    /** Mirrors settings.lsposedBridgeEnabled so the toggle can render its state. */
    val bridgeEnabled: StateFlow<Boolean> = settings.lsposedBridgeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setBridgeEnabled(value: Boolean) = launchSetting { settings.setLsposedBridgeEnabled(value) }

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
        refreshLsposed()
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
                    bundle, row.packageName, row.versionName
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

    /** Delegates to [RunPresentation] so the wording and the rules live in one tested place. */
    private fun summarise(summary: BatchSummary): String = RunPresentation.summarise(summary)

    /** Delegates to [RunPresentation] so the folding rules live in one tested place. */
    private fun RunProgress.reduce(event: PatchEvent): RunProgress =
        RunPresentation.reduce(this, event)
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
