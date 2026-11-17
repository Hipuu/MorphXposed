package dev.morphexposed.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Install strategy the user picked for patched apps. */
enum class InstallStrategy {
    /** Android session installer; prompts the user, no root needed. */
    SESSION,

    /** Bind-mount the patched APK over the stock app via a Magisk-style module. */
    ROOT_MOUNT,

    /** Install through Shizuku without a confirmation dialog where supported. */
    SHIZUKU
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Strongly typed wrapper over DataStore. Every user-facing automation switch lives
 * here so the UI and the workers read one source of truth.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val installStrategy = stringPreferencesKey("install_strategy")
        val autoPatchOnInstall = booleanPreferencesKey("auto_patch_on_install")
        val autoPatchOnUpdate = booleanPreferencesKey("auto_patch_on_update")
        val autoCheckForPatchUpdates = booleanPreferencesKey("auto_check_patch_updates")
        val updateCheckIntervalHours = intPreferencesKey("update_check_interval_hours")
        val notifyOnSuccess = booleanPreferencesKey("notify_on_success")
        val notifyOnFailure = booleanPreferencesKey("notify_on_failure")
        val keepOriginalBackup = booleanPreferencesKey("keep_original_backup")
        val wifiOnlyDownloads = booleanPreferencesKey("wifi_only_downloads")
        val verboseLogging = booleanPreferencesKey("verbose_logging")
        val lsposedBridgeEnabled = booleanPreferencesKey("lsposed_bridge_enabled")
        val lastAutoCheckAt = longPreferencesKey("last_auto_check_at")
        val githubToken = stringPreferencesKey("github_token")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[Keys.themeMode] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    val installStrategy: Flow<InstallStrategy> = context.dataStore.data.map { prefs ->
        runCatching { InstallStrategy.valueOf(prefs[Keys.installStrategy] ?: InstallStrategy.SESSION.name) }
            .getOrDefault(InstallStrategy.SESSION)
    }

    val autoPatchOnInstall: Flow<Boolean> = bool(Keys.autoPatchOnInstall, false)
    val autoPatchOnUpdate: Flow<Boolean> = bool(Keys.autoPatchOnUpdate, false)
    val autoCheckForPatchUpdates: Flow<Boolean> = bool(Keys.autoCheckForPatchUpdates, true)
    val notifyOnSuccess: Flow<Boolean> = bool(Keys.notifyOnSuccess, true)
    val notifyOnFailure: Flow<Boolean> = bool(Keys.notifyOnFailure, true)
    val keepOriginalBackup: Flow<Boolean> = bool(Keys.keepOriginalBackup, true)
    val wifiOnlyDownloads: Flow<Boolean> = bool(Keys.wifiOnlyDownloads, false)
    val verboseLogging: Flow<Boolean> = bool(Keys.verboseLogging, false)
    val lsposedBridgeEnabled: Flow<Boolean> = bool(Keys.lsposedBridgeEnabled, true)

    /** Hours between automatic patch-source checks. Clamped to a sane range. */
    val updateCheckIntervalHours: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[Keys.updateCheckIntervalHours] ?: DEFAULT_INTERVAL_HOURS).coerceIn(1, 168)
    }

    val lastAutoCheckAt: Flow<Long> = context.dataStore.data.map { it[Keys.lastAutoCheckAt] ?: 0L }

    /**
     * Every setting at once. Consumers that need several values read this rather than
     * combining individual flows, which keeps the DataStore read to a single stream.
     */
    val snapshotFlow: Flow<Snapshot> = context.dataStore.data.map { prefs ->
        Snapshot(
            installStrategy = runCatching {
                InstallStrategy.valueOf(prefs[Keys.installStrategy] ?: InstallStrategy.SESSION.name)
            }.getOrDefault(InstallStrategy.SESSION),
            autoPatchOnInstall = prefs[Keys.autoPatchOnInstall] ?: false,
            autoPatchOnUpdate = prefs[Keys.autoPatchOnUpdate] ?: false,
            autoCheckForPatchUpdates = prefs[Keys.autoCheckForPatchUpdates] ?: true,
            updateCheckIntervalHours =
                (prefs[Keys.updateCheckIntervalHours] ?: DEFAULT_INTERVAL_HOURS).coerceIn(1, 168),
            notifyOnSuccess = prefs[Keys.notifyOnSuccess] ?: true,
            notifyOnFailure = prefs[Keys.notifyOnFailure] ?: true,
            keepOriginalBackup = prefs[Keys.keepOriginalBackup] ?: true,
            wifiOnlyDownloads = prefs[Keys.wifiOnlyDownloads] ?: false,
            verboseLogging = prefs[Keys.verboseLogging] ?: false,
            lsposedBridgeEnabled = prefs[Keys.lsposedBridgeEnabled] ?: true,
            githubToken = prefs[Keys.githubToken].orEmpty()
        )
    }

    /**
     * Optional GitHub token used to raise the anonymous rate limit when resolving
     * releases. Never used for anything else, and never sent anywhere but GitHub.
     */
    val githubToken: Flow<String> = context.dataStore.data.map { it[Keys.githubToken].orEmpty() }

    private fun bool(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        context.dataStore.data.map { it[key] ?: default }

    suspend fun setThemeMode(mode: ThemeMode) = put(Keys.themeMode, mode.name)
    suspend fun setInstallStrategy(strategy: InstallStrategy) = put(Keys.installStrategy, strategy.name)
    suspend fun setAutoPatchOnInstall(value: Boolean) = put(Keys.autoPatchOnInstall, value)
    suspend fun setAutoPatchOnUpdate(value: Boolean) = put(Keys.autoPatchOnUpdate, value)
    suspend fun setAutoCheckForPatchUpdates(value: Boolean) = put(Keys.autoCheckForPatchUpdates, value)
    suspend fun setNotifyOnSuccess(value: Boolean) = put(Keys.notifyOnSuccess, value)
    suspend fun setNotifyOnFailure(value: Boolean) = put(Keys.notifyOnFailure, value)
    suspend fun setKeepOriginalBackup(value: Boolean) = put(Keys.keepOriginalBackup, value)
    suspend fun setWifiOnlyDownloads(value: Boolean) = put(Keys.wifiOnlyDownloads, value)
    suspend fun setVerboseLogging(value: Boolean) = put(Keys.verboseLogging, value)
    suspend fun setLsposedBridgeEnabled(value: Boolean) = put(Keys.lsposedBridgeEnabled, value)
    suspend fun setGithubToken(value: String) = put(Keys.githubToken, value.trim())

    suspend fun setUpdateCheckIntervalHours(hours: Int) =
        put(Keys.updateCheckIntervalHours, hours.coerceIn(1, 168))

    suspend fun markAutoCheckNow() = put(Keys.lastAutoCheckAt, System.currentTimeMillis())

    /**
     * Clears every stored setting, returning the app to its shipped defaults.
     *
     * Used by tests, and by the in-app reset action.
     */
    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }

    suspend fun snapshot(): Snapshot = Snapshot(
        installStrategy = installStrategy.first(),
        autoPatchOnInstall = autoPatchOnInstall.first(),
        autoPatchOnUpdate = autoPatchOnUpdate.first(),
        autoCheckForPatchUpdates = autoCheckForPatchUpdates.first(),
        updateCheckIntervalHours = updateCheckIntervalHours.first(),
        notifyOnSuccess = notifyOnSuccess.first(),
        notifyOnFailure = notifyOnFailure.first(),
        keepOriginalBackup = keepOriginalBackup.first(),
        wifiOnlyDownloads = wifiOnlyDownloads.first(),
        verboseLogging = verboseLogging.first(),
        lsposedBridgeEnabled = lsposedBridgeEnabled.first(),
        githubToken = githubToken.first()
    )

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    data class Snapshot(
        val installStrategy: InstallStrategy,
        val autoPatchOnInstall: Boolean,
        val autoPatchOnUpdate: Boolean,
        val autoCheckForPatchUpdates: Boolean,
        val updateCheckIntervalHours: Int,
        val notifyOnSuccess: Boolean,
        val notifyOnFailure: Boolean,
        val keepOriginalBackup: Boolean,
        val wifiOnlyDownloads: Boolean,
        val verboseLogging: Boolean,
        val lsposedBridgeEnabled: Boolean,
        val githubToken: String
    )

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 12
    }
}
