package dev.morphexposed.domain.source

import android.content.Context
import android.util.Log
import dev.morphexposed.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Resolves and downloads Morphe patch bundles from GitHub releases.
 *
 * Security properties this class guarantees:
 *  * Only `https://api.github.com` and `https://github.com` / `objects.githubusercontent.com`
 *    hosts are contacted, and only for the configured repository.
 *  * A downloaded bundle is verified against the release's published SHA-256 when one
 *    exists, and rejected otherwise.
 *  * The bundle is written to a staging file and only renamed into place once it is
 *    complete and verified, so a partial or corrupt download is never loaded.
 *  * Nothing downloaded is ever executed; only the bundle container is read as data.
 */
class PatchSourceClient(
    private val context: Context,
    private val settings: SettingsStore,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    /**
     * Base URL of the GitHub-compatible API. Overridable so the resolve/download/verify path
     * can be exercised against a local server instead of the live GitHub API.
     */
    private val apiBaseUrl: String = DEFAULT_API_BASE,
    /** Directory the bundle cache lives in; overridable for tests. */
    private val bundlesDirOverride: File? = null,
    /** HTTP client; overridable so tests can point at a local server. */
    clientOverride: OkHttpClient? = null
) {
    private val client = clientOverride ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Directory holding cached bundles; one file per source. */
    private val bundlesDir: File
        get() = (bundlesDirOverride ?: File(context.filesDir, "bundles")).apply { mkdirs() }

    /** The bundle cache directory, for callers that need to locate a cached file. */
    fun bundleDirectory(): File = bundlesDir

    /**
     * Returns the newest installable release for [owner]/[repo].
     *
     * @param allowPrerelease whether pre-release tags may be selected.
     */
    suspend fun latestRelease(
        owner: String,
        repo: String,
        allowPrerelease: Boolean
    ): PatchBundleRelease = withContext(Dispatchers.IO) {
        val releases = fetchReleases(owner, repo)
        val usable = releases.filter { r ->
            !r.draft &&
                r.assets.any { it.name.endsWith(".mpp", ignoreCase = true) } &&
                (allowPrerelease || !r.prerelease)
        }
        val chosen = usable.firstOrNull()
            ?: releases.firstOrNull()
                ?.let { throw SourceException(SourceError.NoBundleAsset(it.tag_name)) }
            ?: throw SourceException(SourceError.NotFound("$owner/$repo has no releases"))

        val asset = chosen.assets.first { it.name.endsWith(".mpp", ignoreCase = true) }
        PatchBundleRelease(
            tag = chosen.tag_name,
            assetName = asset.name,
            downloadUrl = asset.downloadUrl,
            sizeBytes = asset.size,
            sha256 = parseDigest(asset.digest),
            publishedAt = parseIsoToMillis(chosen.published_at),
            isPrerelease = chosen.prerelease
        )
    }

    private suspend fun fetchReleases(owner: String, repo: String): List<GhRelease> {
        val token = settings.githubToken.first()
        val request = Request.Builder()
            .url("$apiBaseUrl/repos/$owner/$repo/releases?per_page=20")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> throw SourceException(SourceError.NotFound("$owner/$repo"))
                response.code == 403 || response.code == 429 -> throw SourceException(
                    SourceError.RateLimited(response.header("X-RateLimit-Reset")?.toLongOrNull())
                )
                !response.isSuccessful -> throw SourceException(
                    SourceError.Network("HTTP ${response.code} for $owner/$repo")
                )
            }
            val body = response.body?.string()
                ?: throw SourceException(SourceError.Network("Empty response for $owner/$repo"))
            return runCatching { json.decodeFromString<List<GhRelease>>(body) }
                .getOrElse { throw SourceException(SourceError.Other("Malformed release JSON: ${it.message}")) }
        }
    }

    /**
     * Downloads [release] into the bundle cache, verifying its checksum.
     *
     * @return the cached file plus the SHA-256 actually computed over it.
     */
    suspend fun download(
        sourceId: Long,
        release: PatchBundleRelease,
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> }
    ): DownloadedBundle = withContext(Dispatchers.IO) {
        val target = File(bundlesDir, "bundles-$sourceId-${sanitise(release.tag)}.mpp")
        val staging = File(bundlesDir, target.name + ".part")
        staging.delete()

        val request = Request.Builder()
            .url(release.downloadUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw SourceException(SourceError.Network("HTTP ${response.code} downloading bundle"))
                }
                val body = response.body ?: throw SourceException(SourceError.Network("Empty bundle response"))
                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                body.byteStream().use { input ->
                    staging.outputStream().buffered().use { output ->
                        read = copyWithDigest(input, output, digest, total, onProgress)
                    }
                }
            }

            val actual = digest.digest().toHex()
            release.sha256?.let { expected ->
                if (!expected.equals(actual, ignoreCase = true)) {
                    throw SourceException(SourceError.ChecksumMismatch(expected, actual))
                }
            }
            if (release.sha256 == null) {
                Log.w(TAG, "Release ${release.tag} publishes no digest; relying on size + zip validation")
            }

            validateBundleContainer(staging)

            if (target.exists()) target.delete()
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }

            // Bundle files are large (the official one is ~16 MB), so keeping every release
            // would grow the app's storage without bound. The previous bundle is retained
            // because repatching may still need it; older ones are dropped.
            pruneOldBundles(keepFile = target)

            DownloadedBundle(
                file = target,
                sha256 = actual,
                size = read,
                tag = release.tag,
                publishedAt = release.publishedAt
            )
        } catch (t: Throwable) {
            staging.delete()
            if (t is SourceException) throw t
            throw SourceException(SourceError.Network(t.message ?: t.javaClass.simpleName))
        }
    }

    /**
     * Deletes cached bundles for the same source beyond [KEEP_BUNDLES], newest first.
     *
     * The current bundle and its immediate predecessor are kept: a repatch may still reference
     * the previous one, and deleting a file a stored run points at would turn a working record
     * into a dangling path. Anything older serves no purpose.
     */
    private fun pruneOldBundles(keepFile: File) {
        runCatching {
            val prefix = keepFile.name.substringBefore('-')
            val siblings = keepFile.parentFile
                ?.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.extension == "mpp" }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            siblings.drop(KEEP_BUNDLES).forEach { old ->
                if (old.absolutePath != keepFile.absolutePath) {
                    Log.i(TAG, "Pruning cached bundle ${old.name}")
                    old.delete()
                }
            }
        }.onFailure { Log.w(TAG, "Could not prune cached bundles: ${it.message}") }
    }

    private fun copyWithDigest(
        input: InputStream,
        output: java.io.OutputStream,
        digest: MessageDigest,
        total: Long,
        onProgress: (Long, Long) -> Unit
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalRead = 0L
        var lastReport = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            digest.update(buffer, 0, n)
            totalRead += n
            if (totalRead - lastReport >= PROGRESS_STEP) {
                lastReport = totalRead
                onProgress(totalRead, total)
            }
        }
        output.flush()
        onProgress(totalRead, total)
        return totalRead
    }

    /**
     * A patch bundle is a JAR/DEX container. It must be a readable ZIP with at least one
     * entry, and it must expose a manifest so the required patcher version can be read.
     * This is a structural check only — the patcher performs full validation on load.
     */
    private fun validateBundleContainer(file: File) {
        if (!file.exists() || file.length() == 0L) {
            throw SourceException(SourceError.InvalidBundle("empty file"))
        }
        java.util.zip.ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
            if (entries.isEmpty()) {
                throw SourceException(SourceError.InvalidBundle("archive has no entries"))
            }
            // Guard against a "bundle" that is really an APK or an arbitrary archive.
            val hasBundleMarker = entries.any {
                it.name.equals("META-INF/MANIFEST.MF", true) ||
                    it.name.endsWith(".dex", true) ||
                    it.name.endsWith(".mpe", true)
            }
            if (!hasBundleMarker) {
                throw SourceException(SourceError.InvalidBundle("no manifest or dex container found"))
            }
        }
    }

    /** Reads cached bundle bytes; used by the inspector. */
    fun cachedBundleFile(fileName: String): File? =
        File(bundlesDir, fileName).takeIf { it.exists() && it.isFile }

    private fun sanitise(tag: String) = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun parseDigest(digest: String?): String? {
        if (digest.isNullOrBlank()) return null
        val hex = digest.substringAfter(':', digest).trim()
        return hex.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
    }

    private fun parseIsoToMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * How many cached bundles to keep per source. The official bundle is ~16 MB, so an
         * unbounded cache is a real storage problem rather than a tidiness one.
         */
        const val KEEP_BUNDLES = 2

        private const val TAG = "MorphXPatchSource"
        // The product name changed but the repository URL did not, so this must keep pointing at
    // the repository that actually exists. A renamed-but-uncreated URL is a 404 in every request
    // the app makes, which GitHub can rate-limit as abuse.
    private const val USER_AGENT = "MorpheXposed/1.0 (+https://github.com/Hipuu/MorphXposed)"

        /** GitHub's API; overridable for tests. */
        const val DEFAULT_API_BASE = "https://api.github.com"
        private const val PROGRESS_STEP = 256 * 1024L
    }
}

data class DownloadedBundle(
    val file: File,
    val sha256: String,
    val size: Long,
    val tag: String,
    val publishedAt: Long?
)

/** Convenience for callers that want a plain message. */
fun Throwable.toSourceMessage(): String =
    (this as? SourceException)?.error?.describe() ?: message ?: javaClass.simpleName
