package dev.morphexposed.domain.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.morphexposed.data.prefs.SettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Executes the bundle download and verification path against a real local HTTP server.
 *
 * This is the security-critical half of patch management: every release is checked against its
 * published SHA-256, a bad bundle must be rejected rather than cached, and the cache write must
 * be atomic so a torn download never replaces a working bundle. None of that had been run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundleDownloadVerificationTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsStore
    private lateinit var server: MockWebServer
    private lateinit var bundlesDir: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsStore(context)
        settings.resetToDefaults()
        server = MockWebServer().apply { start() }
        bundlesDir = File(context.cacheDir, "dl-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() = runBlocking {
        server.shutdown()
        bundlesDir.deleteRecursively()
        settings.resetToDefaults()
    }

    /** A bundle-looking archive: a manifest plus a dex, so validation accepts it. */
    private fun bundleBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\r\nPatcher-Version: 2.4.0\r\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(512) { it.toByte() })
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun client() = PatchSourceClient(
        context = context,
        settings = settings,
        apiBaseUrl = server.url("/").toString().trimEnd('/'),
        bundlesDirOverride = bundlesDir
    )

    private fun releaseJson(
        tag: String = "v2.3.0",
        prerelease: Boolean = false,
        assetName: String = "patches-2.3.0.mpp",
        downloadUrl: String,
        digest: String? = null,
        declaredSize: Long = 0
    ): String = buildString {
        append("""[{"tag_name":"$tag","name":"$tag","prerelease":$prerelease,"draft":false,""")
        append(""""published_at":"2026-11-16T13:10:51Z","assets":[{""")
        append(""""name":"$assetName","size":$declaredSize,""")
        append(""""content_type":"application/octet-stream",""")
        append(""""browser_download_url":"$downloadUrl"""")
        if (digest != null) append(""","digest":"sha256:$digest"""")
        append("}]}]")
    }

    // ---- Release resolution -------------------------------------------------------------

    @Test
    fun `resolves the newest release that carries a bundle`() = runBlocking {
        val bytes = bundleBytes()
        server.enqueue(
            MockResponse().setBody(
                releaseJson(
                    downloadUrl = server.url("/dl/patches.mpp").toString(),
                    declaredSize = bytes.size.toLong()
                )
            )
        )

        val release = client().latestRelease("MorpheApp", "morphe-patches", allowPrerelease = false)

        assertEquals("v2.3.0", release.tag)
        assertEquals("patches-2.3.0.mpp", release.assetName)
        assertEquals(bytes.size.toLong(), release.sizeBytes)
    }

    @Test
    fun `a pre-release is skipped unless allowed`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                releaseJson(
                    tag = "v3.0.0-rc1",
                    prerelease = true,
                    downloadUrl = server.url("/dl/rc.mpp").toString()
                )
            )
        )
        val strict = runCatching {
            client().latestRelease("o", "r", allowPrerelease = false)
        }
        assertTrue("a pre-release must not be chosen when disallowed", strict.isFailure)
    }

    @Test
    fun `a release with no bundle asset is reported clearly`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"tag_name":"v1","name":"v1","prerelease":false,"draft":false,
                   "assets":[{"name":"notes.txt","size":1,"content_type":"text/plain",
                   "browser_download_url":"http://x/notes.txt"}]}]"""
            )
        )

        val error = runCatching {
            client().latestRelease("o", "r", allowPrerelease = false)
        }.exceptionOrNull()

        assertNotNull("a release without a bundle must fail", error)
        assertTrue(
            "the message must name the tag, was: ${error!!.message}",
            error.message!!.contains("v1")
        )
    }

    @Test
    fun `a rate-limited response is reported as such`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("X-RateLimit-Reset", "1700000000")
        )

        val error = runCatching {
            client().latestRelease("o", "r", allowPrerelease = false)
        }.exceptionOrNull()

        assertTrue("the failure must be a SourceException", error is SourceException)
        val source = (error as SourceException).error
        assertTrue("it must be reported as rate limiting", source is SourceError.RateLimited)
    }

    @Test
    fun `a 404 is reported as not found`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val error = runCatching {
            client().latestRelease("o", "r", allowPrerelease = false)
        }.exceptionOrNull()

        val source = (error as SourceException).error
        assertTrue("it must be reported as not found", source is SourceError.NotFound)
    }

    // ---- Download and checksum verification ---------------------------------------------

    @Test
    fun `a verified download is cached`() = runBlocking {
        val bytes = bundleBytes()
        val digest = sha256(bytes)
        val url = server.url("/dl/patches.mpp").toString()

        server.enqueue(MockResponse().setBody(releaseJson(downloadUrl = url, digest = digest)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

        val c = client()
        val release = c.latestRelease("o", "r", allowPrerelease = false)
        val downloaded = c.download(sourceId = 42, release = release)

        assertTrue("the bundle must be cached", downloaded.file.isFile)
        assertTrue("the cache file must not be empty", downloaded.file.length() > 0)
        assertEquals("the reported checksum must match", digest, downloaded.sha256)
        assertEquals("the checksum must be over the bytes written", digest, sha256(downloaded.file.readBytes()))
    }

    @Test
    fun `a checksum mismatch is rejected and nothing is cached`() = runBlocking {
        val bytes = bundleBytes()
        val wrongDigest = "0".repeat(64)
        val url = server.url("/dl/patches.mpp").toString()

        server.enqueue(MockResponse().setBody(releaseJson(downloadUrl = url, digest = wrongDigest)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

        val c = client()
        val release = c.latestRelease("o", "r", allowPrerelease = false)

        val error = runCatching { c.download(sourceId = 7, release = release) }.exceptionOrNull()

        assertTrue("a bad checksum must fail the download", error != null)
        val source = (error as SourceException).error
        assertTrue(
            "it must be reported as a checksum mismatch, was $source",
            source is SourceError.ChecksumMismatch
        )

        // Nothing may be left behind: not the staged part-file, not a cache entry.
        val leftovers = bundlesDir.listFiles().orEmpty()
        assertTrue(
            "a rejected download must leave no files, found: ${leftovers.map { it.name }}",
            leftovers.isEmpty()
        )
    }

    @Test
    fun `a non-zip payload is rejected as an invalid bundle`() = runBlocking {
        val junk = "this is definitely not a patch bundle".toByteArray()
        val url = server.url("/dl/patches.mpp").toString()

        // No published digest, so validation of the container is what must catch it.
        server.enqueue(MockResponse().setBody(releaseJson(downloadUrl = url)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(junk)))

        val c = client()
        val release = c.latestRelease("o", "r", allowPrerelease = false)
        val error = runCatching { c.download(sourceId = 8, release = release) }.exceptionOrNull()

        assertNotNull("a non-archive payload must be rejected", error)
        assertTrue("nothing may be cached", bundlesDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a zip without bundle markers is rejected`() = runBlocking {
        // A readable ZIP, but it holds none of the entries a patch bundle must have.
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("hello.txt"))
            zip.write("hi".toByteArray())
            zip.closeEntry()
        }
        val url = server.url("/dl/patches.mpp").toString()

        server.enqueue(MockResponse().setBody(releaseJson(downloadUrl = url)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(out.toByteArray())))

        val c = client()
        val release = c.latestRelease("o", "r", allowPrerelease = false)
        val error = runCatching { c.download(sourceId = 9, release = release) }.exceptionOrNull()

        assertNotNull("an unrelated zip must not be treated as a bundle", error)
        assertTrue("nothing may be cached", bundlesDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a failed download does not clobber a previously cached bundle`() = runBlocking {
        val bytes = bundleBytes()
        val digest = sha256(bytes)
        val url = server.url("/dl/patches.mpp").toString()

        // First: a good download.
        server.enqueue(MockResponse().setBody(releaseJson(downloadUrl = url, digest = digest)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

        val c = client()
        val good = c.latestRelease("o", "r", allowPrerelease = false)
        val cached = c.download(sourceId = 5, release = good)
        val cachedHash = sha256(cached.file.readBytes())

        // Then: a download whose checksum does not match.
        server.enqueue(
            MockResponse().setBody(
                releaseJson(tag = "v2.4.0", downloadUrl = url, digest = "f".repeat(64))
            )
        )
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

        val bad = c.latestRelease("o", "r", allowPrerelease = false)
        runCatching { c.download(sourceId = 5, release = bad) }

        assertEquals(
            "the working bundle must survive a failed refresh",
            cachedHash,
            sha256(cached.file.readBytes())
        )
    }

    @Test
    fun `download progress is reported to the caller`() = runBlocking {
        val bytes = bundleBytes()
        val digest = sha256(bytes)
        val url = server.url("/dl/patches.mpp").toString()

        server.enqueue(MockResponse().setBody(releaseJson(downloadUrl = url, digest = digest)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

        val c = client()
        val release = c.latestRelease("o", "r", allowPrerelease = false)

        var lastRead = -1L
        var lastTotal = -1L
        c.download(sourceId = 11, release = release) { read, total ->
            lastRead = read
            lastTotal = total
        }

        assertEquals("progress must report the full size", bytes.size.toLong(), lastRead)
        assertEquals("progress must report the total", bytes.size.toLong(), lastTotal)
    }

    @Test
    fun `a digest in an unexpected form is ignored rather than trusted`() = runBlocking {
        // A malformed digest must not be treated as a valid expected checksum.
        server.enqueue(
            MockResponse().setBody(
                releaseJson(
                    downloadUrl = server.url("/dl/p.mpp").toString(),
                    digest = "not-a-hex-digest"
                )
            )
        )
        val release = client().latestRelease("o", "r", allowPrerelease = false)
        assertNull("a malformed digest must be dropped", release.sha256)
    }
}
