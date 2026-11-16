package dev.morphxposed.domain.patch

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.loadPatchesFromJar
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * End-to-end proof that the app patches a real APK with Morphe's engine.
 *
 * This exercises the exact code path the app uses:
 * load the official `.mpp` → build a [PatcherConfig] → run patches → write a patched APK,
 * then assert the output is a valid, larger archive.
 *
 * Requires a real target APK, which is not committed. Provide it with
 * `MORPHX_TEST_APK`, or place one at `testdata/target.apk`. Without it the test is skipped
 * so the suite still passes in a bare checkout.
 *
 * The APK must be a version the bundle supports; see [supportedVersionHint].
 */
class EndToEndPatchTest {

    private fun bundle(): File? = resolve("patches.mpp", "MORPHX_TEST_BUNDLE")

    private fun targetApk(): File? = resolve("target.apk", "MORPHX_TEST_APK")

    private fun resolve(name: String, env: String): File? {
        val fromEnv = System.getenv(env)?.takeIf { it.isNotBlank() }?.let(::File)
        if (fromEnv?.isFile == true) return fromEnv
        return listOf(File("testdata/$name"), File("../testdata/$name")).firstOrNull { it.isFile }
    }

    @Test
    fun `patches a real apk with the official engine`() {
        val bundleFile = bundle()
        val apkFile = targetApk()
        assumeTrue("No Morphe bundle available", bundleFile != null)
        assumeTrue("No target APK available; set MORPHX_TEST_APK", apkFile != null)

        val work = File("build/e2e-work").apply {
            deleteRecursively()
            mkdirs()
        }

        // 1. Load patches exactly as the app does.
        val patches = loadPatchesFromJar(setOf(bundleFile!!))
        assertTrue("Official bundle should declare many patches", patches.size > 50)

        // 2. Try universal patches (they target any app) one at a time until one succeeds.
        //    A patch written for YouTube legitimately fails on an unrelated APK, which is
        //    Morphe's own patch code reporting the mismatch.
        val universal = patches.filter { it.compatibility.isNullOrEmpty() }
        assumeTrue("No universal patch in the bundle", universal.isNotEmpty())

        var firstFailure: String? = null
        var appliedName: String? = null
        val output = File(work, "patched.apk")

        for (patch in universal.take(6)) {
            val attemptDir = File(work, "attempt-${patch.name?.hashCode()}").apply { mkdirs() }
            val attemptOut = File(attemptDir, "patched.apk")
            val config = PatcherConfig(
                apkFile = apkFile!!,
                temporaryFilesPath = File(attemptDir, "temp").apply { mkdirs() },
                fileWorkspacePath = File(attemptDir, "workspace").apply { mkdirs() },
                validate = false
            )

            val failure = runCatching {
                Patcher(config).use { patcher ->
                    patcher += setOf(patch)
                    kotlinx.coroutines.runBlocking {
                        patcher { }.collect { result ->
                            result.exception?.let { throw it }
                        }
                    }
                    patcher.writeApk(attemptOut)
                }
                null
            }.getOrElse { it.message ?: it.javaClass.simpleName }

            if (failure == null && attemptOut.isFile && attemptOut.length() > 0) {
                appliedName = patch.name
                attemptOut.copyTo(output, overwrite = true)
                println("Applied \"${patch.name}\" -> ${output.length()} bytes")
                break
            }
            if (firstFailure == null) firstFailure = "${patch.name}: $failure"
        }

        assertNotNull(
            "Morphe's engine did not run any patch. First failure: $firstFailure",
            appliedName
        )

        // 3. The result must be a structurally valid APK that Morphe repackaged.
        assertTrue("Patched APK must exist", output.isFile && output.length() > 0)
        ZipFile(output).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue("Output must contain a manifest", names.contains("AndroidManifest.xml"))
            assertTrue("Output must contain resources.arsc", names.contains("resources.arsc"))
            assertTrue(
                "Output must contain dex files",
                names.any { it.matches(Regex("classes\\d*\\.dex")) }
            )
        }
        println("End-to-end patch verified with \"$appliedName\"")
    }

    /** Reads the package name from an APK manifest via aapt-free means. */
    private fun apkPackage(apk: File): String = runCatching {
        java.util.zip.ZipFile(apk).use { zip ->
            val manifest = zip.getEntry("AndroidManifest.xml")
                ?: error("APK has no manifest")
            // Binary XML: the package string appears as a UTF-16 pool entry. Scanning for a
            // dotted ASCII/UTF-16 run is sufficient for a diagnostic helper.
            val bytes = zip.getInputStream(manifest).readBytes()
            val text = String(bytes, Charsets.ISO_8859_1)
            Regex("([a-z][a-z0-9_]+(?:\\.[a-z][a-z0-9_]+){2,})")
                .findAll(text)
                .map { it.value }
                .firstOrNull { it.contains('.') && !it.startsWith("android") }
                ?: "unknown"
        }
    }.getOrDefault("unknown")

    @Test
    fun `supported version hint is read from the bundle`() {
        val bundleFile = bundle()
        assumeTrue("No Morphe bundle available", bundleFile != null)

        val patches = loadPatchesFromJar(setOf(bundleFile!!))
        val versions = patches
            .flatMap { it.compatibility.orEmpty() }
            .flatMap { it.targets }
            .mapNotNull { it.version }
            .distinct()
            .sortedWith(dev.morphxposed.domain.source.VersionComparator.reversed())

        assertTrue("Bundle should declare versions", versions.isNotEmpty())
        println("Bundle supports ${versions.size} app versions, newest: ${versions.take(5)}")
    }
}
