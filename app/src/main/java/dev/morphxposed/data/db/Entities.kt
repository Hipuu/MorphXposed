package dev.morphxposed.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A patch bundle source the user has subscribed to. The official Morphe source is
 * seeded on first launch; others are added explicitly.
 */
@Entity(
    tableName = "patch_sources",
    indices = [Index(value = ["repoOwner", "repoName"], unique = true)]
)
data class PatchSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** GitHub owner, e.g. `MorpheApp`. */
    val repoOwner: String,
    /** GitHub repository, e.g. `morphe-patches`. */
    val repoName: String,
    val displayName: String,
    /** True for the source shipped with the app; cannot be deleted, only disabled. */
    val isOfficial: Boolean = false,
    val enabled: Boolean = true,
    /** Include pre-releases when resolving the newest bundle. */
    val allowPrerelease: Boolean = false,
    /** Epoch millis of the last successful refresh, 0 if never. */
    val lastCheckedAt: Long = 0,
    /** Version tag of the bundle currently installed from this source. */
    val installedTag: String? = null,
    /** Local file name of the cached bundle. */
    val cachedFileName: String? = null,
    /** SHA-256 of the cached bundle, as published by the release. */
    val cachedSha256: String? = null,
    /** Size of the cached bundle in bytes. */
    val cachedSize: Long = 0,
    /** Patcher version the cached bundle was built for, read from its manifest. */
    val requiredPatcherVersion: String? = null,
    /** Last error from refreshing this source, cleared on success. */
    val lastError: String? = null
)

/**
 * An application installed on the device that at least one patch source can patch.
 * Rebuilt by [AppScanner]; user toggles are preserved across scans.
 */
@Entity(tableName = "supported_apps")
data class SupportedAppEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    /** `sourceDir` of the base APK. */
    val apkPath: String,
    /** Split APK paths for a split install, newline separated. Empty for a monolithic APK. */
    val splitApkPaths: String = "",
    /** True when the user allowed automatic patching for this app. */
    val autoPatchEnabled: Boolean = false,
    /** True when the user hid the app from the dashboard. */
    val hidden: Boolean = false,
    /** Id of the patch source that can patch this app. */
    val sourceId: Long = 0,
    /** Comma separated patch names selected for this app's most recent run. */
    val selectedPatches: String = "",
    val lastPatchedAt: Long = 0,
    /** Version name the last successful patch was built from. */
    val lastPatchedVersion: String? = null,
    /** Patch bundle tag the last successful patch was built with. */
    val lastPatchedBundleTag: String? = null,
    val lastPatchedBundleSha256: String? = null,
    /** Whether the last run installed via root mount. */
    val mounted: Boolean = false,
    /** Epoch millis when the scan last saw this package. */
    val discoveredAt: Long = 0
)

/**
 * One attempted patch run, successful or not. Backs the Logs screen and the
 * "avoid repatching when nothing changed" rule.
 */
@Entity(
    tableName = "patch_runs",
    indices = [Index("packageName"), Index("startedAt")]
)
data class PatchRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** Running values: QUEUED, PATCHING, SIGNING, INSTALLING, SUCCEEDED, FAILED, CANCELLED */
    val status: String,
    val sourceVersionName: String,
    val sourceVersionCode: Long,
    val bundleTag: String? = null,
    val bundleSha256: String? = null,
    /** Newline separated names of patches that succeeded. */
    val appliedPatches: String = "",
    /** Newline separated names of patches that failed, with their message. */
    val failedPatches: String = "",
    val installed: Boolean = false,
    val installStrategy: String? = null,
    /** Full log text for this run. */
    val log: String = "",
    /** Path of the produced APK, retained for rollback/export. */
    val outputApkPath: String? = null,
    val error: String? = null
)

/**
 * A retained copy of an app's original APK, used for rollback and for repatching
 * without downloading the stock APK again.
 */
@Entity(
    tableName = "apk_backups",
    indices = [Index(value = ["packageName", "versionCode"], unique = true)]
)
data class ApkBackupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    /** Absolute path of the stored APK/APKS archive. */
    val filePath: String,
    val sizeBytes: Long,
    val createdAt: Long
)
