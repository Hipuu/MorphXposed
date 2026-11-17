package dev.morphexposed.domain.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Covers split-APK handling, which a Play-distributed app needs for both directions:
 * merging splits into one patchable APK, and packing them into a single archive for rollback.
 *
 * Plain JVM: this is pure `java.util.zip` work with no Android dependency, so running it
 * without Robolectric keeps it fast and keeps the failure signals honest.
 */
class SplitArchivesTest {

    private lateinit var work: File

    @Before
    fun setUp() {
        work = File("build/split-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    private fun apk(name: String, vararg entries: Pair<String, String>): File {
        val file = File(work, name)
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entryName, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `packing a split install keeps every module`() {
        val base = apk("base.apk", "AndroidManifest.xml" to "manifest")
        val split1 = apk("split_config.arm64_v8a.apk", "lib/arm64/libx.so" to "lib")
        val split2 = apk("split_config.en.apk", "resources.arsc" to "lang")
        val archive = File(work, "backup.apks")

        SplitArchives.pack(baseApk = base, splitApks = listOf(split1, split2), output = archive)

        assertTrue(archive.isFile && archive.length() > 0)
        ZipFile(archive).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals("all three modules must be present", 3, names.size)
            assertTrue(names.contains("base.apk"))
            assertTrue(names.contains("split_config.arm64_v8a.apk"))
            assertTrue(names.contains("split_config.en.apk"))
        }
    }

    @Test
    fun `packed modules are stored uncompressed`() {
        // APKs are already compressed, and .apks readers expect STORED entries.
        val base = apk("base.apk", "AndroidManifest.xml" to "manifest")
        val archive = File(work, "backup.apks")

        SplitArchives.pack(baseApk = base, splitApks = emptyList(), output = archive)

        ZipFile(archive).use { zip ->
            val entry = zip.getEntry("base.apk")
            assertEquals(
                "modules must be stored, not deflated",
                java.util.zip.ZipEntry.STORED,
                entry.method
            )
        }
    }

    @Test
    fun `merging splits produces one apk without duplicate entries`() {
        val base = apk(
            "mbase.apk",
            "AndroidManifest.xml" to "manifest-base",
            "classes.dex" to "dex-base"
        )
        val split = apk(
            "msplit.apk",
            // Duplicates the base; the base must win.
            "AndroidManifest.xml" to "manifest-split",
            // Unique to the split; must be carried over.
            "split-only.txt" to "extra"
        )
        val merged = File(work, "merged.apk")

        SplitArchives.mergeToApk(baseApk = base, splitApks = listOf(split), output = merged)

        ZipFile(merged).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals("a ZIP with duplicate names is invalid", names.size, names.toSet().size)
            assertTrue(names.contains("AndroidManifest.xml"))
            assertTrue(names.contains("classes.dex"))
            assertTrue("the split's unique entry must survive", names.contains("split-only.txt"))

            // The base copy must win.
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))
                .bufferedReader().readText()
            assertEquals("manifest-base", manifest)
        }
    }

    @Test
    fun `merging tolerates a missing split file`() {
        val base = apk("base2.apk", "AndroidManifest.xml" to "manifest")
        val missing = File(work, "does-not-exist.apk")
        val merged = File(work, "merged2.apk")

        SplitArchives.mergeToApk(baseApk = base, splitApks = listOf(missing), output = merged)

        assertTrue("a missing split must not abort the merge", merged.isFile && merged.length() > 0)
        ZipFile(merged).use { zip ->
            assertTrue(zip.entries().toList().any { it.name == "AndroidManifest.xml" })
        }
    }

    @Test
    fun `detects split archives by extension and content`() {
        val apks = File(work, "bundle.apks").apply { writeText("x") }
        assertTrue(".apks is a split archive", SplitArchives.isSplitArchive(apks))

        val apkm = File(work, "bundle.apkm").apply { writeText("x") }
        assertTrue(".apkm is a split archive", SplitArchives.isSplitArchive(apkm))

        val single = apk("single.apk", "AndroidManifest.xml" to "m")
        assertTrue("a single-module apk is not a split archive", !SplitArchives.isSplitArchive(single))

        val multi = File(work, "multi.zip")
        java.util.zip.ZipOutputStream(multi.outputStream()).use { zip ->
            listOf("a.apk", "b.apk").forEach {
                zip.putNextEntry(java.util.zip.ZipEntry(it))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        assertTrue("a zip holding several apks is a split archive", SplitArchives.isSplitArchive(multi))
    }
}
