package dev.morphxposed.domain.source

import android.util.Log
import dev.morphxposed.data.db.MorphDatabase
import dev.morphxposed.data.db.PatchSourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/** Identifies the official Morphe patch source, seeded on first launch. */
object OfficialSource {
    const val OWNER = "MorpheApp"
    const val REPO = "morphe-patches"
    const val DISPLAY_NAME = "Morphe (official)"
}

/** Outcome of refreshing one source. */
sealed interface RefreshResult {
    data class Updated(val tag: String, val patchCount: Int) : RefreshResult
    data object AlreadyCurrent : RefreshResult
    data class Failed(val message: String) : RefreshResult
}

/**
 * Owns the patch-source lifecycle: seeding defaults, refreshing releases, downloading and
 * verifying bundles, and exposing the metadata the UI and the patcher need.
 */
class PatchSourceRepository(
    private val db: MorphDatabase,
    private val client: PatchSourceClient,
    private val inspector: BundleInspector
) {

    val sources: Flow<List<PatchSourceEntity>> = db.patchSourceDao().observeAll()

    /** Adds the official source the first time the app runs. */
    suspend fun seedDefaults() = withContext(Dispatchers.IO) {
        if (db.patchSourceDao().count() > 0) return@withContext
        db.patchSourceDao().insert(
            PatchSourceEntity(
                repoOwner = OfficialSource.OWNER,
                repoName = OfficialSource.REPO,
                displayName = OfficialSource.DISPLAY_NAME,
                isOfficial = true,
                enabled = true
            )
        )
        Log.i(TAG, "Seeded official patch source")
    }

    /**
     * Adds a source by `owner/repo`.
     *
     * @throws IllegalArgumentException when the spec is malformed.
     */
    suspend fun addSource(spec: String, displayName: String? = null): Long =
        withContext(Dispatchers.IO) {
            val (owner, repo) = parseSpec(spec)
            db.patchSourceDao().byRepo(owner, repo)?.let {
                throw IllegalArgumentException("${owner}/${repo} is already added.")
            }
            db.patchSourceDao().insert(
                PatchSourceEntity(
                    repoOwner = owner,
                    repoName = repo,
                    displayName = displayName?.takeIf { it.isNotBlank() } ?: "$owner/$repo",
                    isOfficial = false
                )
            )
        }

    suspend fun removeSource(id: Long) = withContext(Dispatchers.IO) {
        val source = db.patchSourceDao().byId(id) ?: return@withContext
        if (source.isOfficial) throw IllegalArgumentException("The official source cannot be removed.")
        db.patchSourceDao().deleteIfNotOfficial(id)
        source.cachedFileName?.let { runCatching { File(bundlesDir(), it).delete() } }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = db.patchSourceDao().setEnabled(id, enabled)

    suspend fun setAllowPrerelease(id: Long, allow: Boolean) =
        db.patchSourceDao().setAllowPrerelease(id, allow)

    /**
     * Checks [source] for a newer bundle and downloads it when one exists.
     *
     * A bundle is only replaced once it has been downloaded, checksum-verified, and internally
     * validated, so a failed refresh leaves the previously working bundle in place.
     */
    suspend fun refresh(
        source: PatchSourceEntity,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): RefreshResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val release = client.latestRelease(
                owner = source.repoOwner,
                repo = source.repoName,
                allowPrerelease = source.allowPrerelease
            )

            val unchanged = release.tag == source.installedTag &&
                release.sha256 != null &&
                release.sha256.equals(source.cachedSha256, ignoreCase = true) &&
                source.cachedFileName != null &&
                File(bundlesDir(), source.cachedFileName).isFile

            if (unchanged) {
                db.patchSourceDao().markChecked(source.id, now)
                return@withContext RefreshResult.AlreadyCurrent
            }

            val downloaded = client.download(source.id, release, onProgress)

            // Inspect before committing: a bundle this patcher cannot load is rejected here
            // rather than failing later mid-patch.
            val info = runCatching { inspector.inspect(downloaded.file, downloaded.tag) }
                .getOrElse { t ->
                    downloaded.file.delete()
                    db.patchSourceDao().markFailed(source.id, now, t.message ?: "Unreadable bundle")
                    return@withContext RefreshResult.Failed(
                        "Downloaded bundle could not be read: ${t.message}"
                    )
                }

            db.patchSourceDao().markDownloaded(
                id = source.id,
                checkedAt = now,
                tag = downloaded.tag,
                fileName = downloaded.file.name,
                sha256 = downloaded.sha256,
                size = downloaded.size,
                requiredPatcherVersion = info.patcherVersion
            )
            Log.i(TAG, "Updated ${source.displayName} to ${downloaded.tag} (${info.patches.size} patches)")
            RefreshResult.Updated(downloaded.tag, info.patches.size)
        } catch (t: Throwable) {
            val message = t.toSourceMessage()
            db.patchSourceDao().markFailed(source.id, now, message)
            Log.w(TAG, "Refresh failed for ${source.displayName}: $message")
            RefreshResult.Failed(message)
        }
    }

    /** Refreshes every enabled source. */
    fun refreshAll(): Flow<Pair<PatchSourceEntity, RefreshResult>> = flow {
        for (source in db.patchSourceDao().enabled()) {
            val result = refresh(source)
            emit(source to result)
        }
    }.flowOn(Dispatchers.IO)

    /** Reads the metadata of a source's cached bundle, or null when nothing is cached. */
    suspend fun inspect(source: PatchSourceEntity): BundleInfo? = withContext(Dispatchers.IO) {
        val name = source.cachedFileName ?: return@withContext null
        val file = File(bundlesDir(), name)
        if (!file.isFile) return@withContext null
        runCatching { inspector.inspect(file, source.installedTag ?: "") }
            .onFailure { Log.w(TAG, "Inspect failed for ${source.displayName}: ${it.message}") }
            .getOrNull()
    }

    /** Metadata for every enabled source, paired with the source id. */
    suspend fun inspectEnabled(): List<Pair<Long, BundleInfo>> = withContext(Dispatchers.IO) {
        db.patchSourceDao().enabled().mapNotNull { source ->
            inspect(source)?.let { source.id to it }
        }
    }

    /** Package name to source id, for apps any enabled source can patch. */
    suspend fun supportedPackages(): Map<String, Long> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, Long>()
        for ((id, info) in inspectEnabled()) {
            for (pkg in info.supportedPackages.keys) {
                result.putIfAbsent(pkg, id)
            }
        }
        result
    }

    private fun bundlesDir(): File = client.bundleDirectory()

    private fun parseSpec(spec: String): Pair<String, String> {
        val cleaned = spec.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("github.com/")
            .trimEnd('/')
            .removeSuffix(".git")
        val parts = cleaned.split('/').filter { it.isNotBlank() }
        require(parts.size >= 2) { "Use the form owner/repo, e.g. ${OfficialSource.OWNER}/${OfficialSource.REPO}" }
        return parts[0] to parts[1]
    }

    private companion object {
        const val TAG = "MorphXSourceRepo"
    }
}
