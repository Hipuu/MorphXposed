package dev.morphexposed.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PatchSourceDao {

    @Query("SELECT * FROM patch_sources ORDER BY isOfficial DESC, displayName ASC")
    fun observeAll(): Flow<List<PatchSourceEntity>>

    @Query("SELECT * FROM patch_sources WHERE enabled = 1 ORDER BY isOfficial DESC, displayName ASC")
    suspend fun enabled(): List<PatchSourceEntity>

    @Query("SELECT * FROM patch_sources WHERE id = :id")
    suspend fun byId(id: Long): PatchSourceEntity?

    @Query("SELECT * FROM patch_sources WHERE repoOwner = :owner AND repoName = :name")
    suspend fun byRepo(owner: String, name: String): PatchSourceEntity?

    @Query("SELECT COUNT(*) FROM patch_sources")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: PatchSourceEntity): Long

    @Upsert
    suspend fun upsert(source: PatchSourceEntity)

    @Query("UPDATE patch_sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE patch_sources SET allowPrerelease = :allow WHERE id = :id")
    suspend fun setAllowPrerelease(id: Long, allow: Boolean)

    @Query(
        """
        UPDATE patch_sources SET
            lastCheckedAt = :checkedAt,
            installedTag = :tag,
            cachedFileName = :fileName,
            cachedSha256 = :sha256,
            cachedSize = :size,
            requiredPatcherVersion = :requiredPatcherVersion,
            latestTag = :tag,
            lastError = NULL
        WHERE id = :id
        """
    )
    suspend fun markDownloaded(
        id: Long,
        checkedAt: Long,
        tag: String,
        fileName: String,
        sha256: String,
        size: Long,
        requiredPatcherVersion: String?
    )

    @Query(
        """
        UPDATE patch_sources
        SET lastCheckedAt = :checkedAt, latestTag = :latestTag, lastError = NULL
        WHERE id = :id
        """
    )
    suspend fun markChecked(id: Long, checkedAt: Long, latestTag: String? = null)

    @Query("UPDATE patch_sources SET lastError = :error, lastCheckedAt = :checkedAt WHERE id = :id")
    suspend fun markFailed(id: Long, checkedAt: Long, error: String)

    @Query("DELETE FROM patch_sources WHERE id = :id AND isOfficial = 0")
    suspend fun deleteIfNotOfficial(id: Long)
}

@Dao
interface SupportedAppDao {

    @Query("SELECT * FROM supported_apps ORDER BY label ASC")
    fun observeAll(): Flow<List<SupportedAppEntity>>

    @Query("SELECT * FROM supported_apps WHERE packageName = :packageName")
    fun observe(packageName: String): Flow<SupportedAppEntity?>

    @Query("SELECT * FROM supported_apps WHERE packageName = :packageName")
    suspend fun byPackage(packageName: String): SupportedAppEntity?

    @Query("SELECT * FROM supported_apps")
    suspend fun all(): List<SupportedAppEntity>

    @Query("SELECT * FROM supported_apps WHERE autoPatchEnabled = 1 AND hidden = 0")
    suspend fun autoPatchCandidates(): List<SupportedAppEntity>

    /**
     * Replaces the discovered set while preserving the user-owned columns
     * ([SupportedAppEntity.autoPatchEnabled], [SupportedAppEntity.hidden],
     * [SupportedAppEntity.selectedPatches]) and the last-patch bookkeeping.
     */
    @Transaction
    suspend fun reconcileDiscovered(discovered: List<SupportedAppEntity>, scannedAt: Long) {
        val existing = all().associateBy { it.packageName }
        val merged = discovered.map { fresh ->
            val old = existing[fresh.packageName]
            fresh.copy(
                discoveredAt = scannedAt,
                autoPatchEnabled = old?.autoPatchEnabled ?: fresh.autoPatchEnabled,
                hidden = old?.hidden ?: fresh.hidden,
                selectedPatches = old?.selectedPatches ?: fresh.selectedPatches,
                lastPatchedAt = old?.lastPatchedAt ?: 0,
                lastPatchedVersion = old?.lastPatchedVersion,
                lastPatchedBundleTag = old?.lastPatchedBundleTag,
                lastPatchedBundleSha256 = old?.lastPatchedBundleSha256,
                mounted = old?.mounted ?: false
            )
        }
        val keep = merged.map { it.packageName }.toSet()
        val stale = existing.keys.filterNot { it in keep }
        upsertAll(merged)
        stale.forEach { deleteByPackage(it) }
    }

    @Upsert
    suspend fun upsertAll(apps: List<SupportedAppEntity>)

    @Upsert
    suspend fun upsert(app: SupportedAppEntity)

    @Query("UPDATE supported_apps SET autoPatchEnabled = :enabled WHERE packageName = :packageName")
    suspend fun setAutoPatch(packageName: String, enabled: Boolean)

    @Query("UPDATE supported_apps SET hidden = :hidden WHERE packageName = :packageName")
    suspend fun setHidden(packageName: String, hidden: Boolean)

    @Query("UPDATE supported_apps SET selectedPatches = :patches WHERE packageName = :packageName")
    suspend fun setSelectedPatches(packageName: String, patches: String)

    @Query(
        """
        UPDATE supported_apps SET
            lastPatchedAt = :at,
            lastPatchedVersion = :version,
            lastPatchedBundleTag = :bundleTag,
            lastPatchedBundleSha256 = :bundleSha,
            mounted = :mounted
        WHERE packageName = :packageName
        """
    )
    suspend fun markPatched(
        packageName: String,
        at: Long,
        version: String,
        bundleTag: String?,
        bundleSha: String?,
        mounted: Boolean
    )

    @Query("DELETE FROM supported_apps WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)
}

@Dao
interface PatchRunDao {

    @Query("SELECT * FROM patch_runs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<PatchRunEntity>>

    @Query("SELECT * FROM patch_runs WHERE packageName = :packageName ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestFor(packageName: String): PatchRunEntity?

    @Query("SELECT * FROM patch_runs WHERE packageName = :packageName ORDER BY startedAt DESC")
    fun observeFor(packageName: String): Flow<List<PatchRunEntity>>

    @Query("SELECT * FROM patch_runs WHERE status IN ('QUEUED','PATCHING','SIGNING','INSTALLING') ORDER BY startedAt ASC")
    suspend fun active(): List<PatchRunEntity>

    @Insert
    suspend fun insert(run: PatchRunEntity): Long

    @Query("UPDATE patch_runs SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query(
        """
        UPDATE patch_runs SET
            status = :status, finishedAt = :finishedAt, appliedPatches = :applied,
            failedPatches = :failed, installed = :installed, installStrategy = :strategy,
            log = :log, outputApkPath = :outputPath, error = :error
        WHERE id = :id
        """
    )
    suspend fun complete(
        id: Long,
        status: String,
        finishedAt: Long,
        applied: String,
        failed: String,
        installed: Boolean,
        strategy: String?,
        log: String,
        outputPath: String?,
        error: String?
    )

    @Query("UPDATE patch_runs SET log = :log WHERE id = :id")
    suspend fun updateLog(id: Long, log: String)

    @Query("DELETE FROM patch_runs WHERE id NOT IN (SELECT id FROM patch_runs ORDER BY startedAt DESC LIMIT :keep)")
    suspend fun pruneTo(keep: Int)

    @Query("DELETE FROM patch_runs")
    suspend fun clearAll()
}

@Dao
interface ApkBackupDao {

    @Query("SELECT * FROM apk_backups ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ApkBackupEntity>>

    @Query("SELECT * FROM apk_backups WHERE packageName = :packageName ORDER BY versionCode DESC")
    suspend fun forPackage(packageName: String): List<ApkBackupEntity>

    @Query("SELECT * FROM apk_backups WHERE packageName = :packageName AND versionCode = :versionCode")
    suspend fun forVersion(packageName: String, versionCode: Long): ApkBackupEntity?

    @Query("SELECT * FROM apk_backups WHERE packageName = :packageName ORDER BY versionCode DESC LIMIT 1")
    suspend fun latestFor(packageName: String): ApkBackupEntity?

    @Upsert
    suspend fun upsert(backup: ApkBackupEntity)

    @Query("DELETE FROM apk_backups WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM apk_backups WHERE packageName = :packageName")
    suspend fun deleteForPackage(packageName: String)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM apk_backups")
    suspend fun totalBytes(): Long
}
