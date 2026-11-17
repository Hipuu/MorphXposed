package dev.morphexposed.domain.source

import app.morphe.patcher.patch.loadPatchesFromJar
import dev.morphexposed.domain.apps.Compatibility
import dev.morphexposed.domain.apps.CompatibilityResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Integration test against the **real** official Morphe patch bundle.
 *
 * This is the test that proves the app is wired to Morphe's actual engine rather than a
 * reimplementation: it loads `patches-<version>.mpp` through the official `loadPatchesFromJar`
 * and asserts the metadata the UI depends on.
 *
 * The bundle is not committed (16 MB). Point `MORPHX_TEST_BUNDLE` at one, or place it at
 * `testdata/patches.mpp`; the tests are skipped when it is absent so the suite still runs
 * in a bare checkout.
 */
class RealBundleIntegrationTest {

    private fun bundleFile(): File? {
        val fromEnv = System.getenv("MORPHX_TEST_BUNDLE")?.takeIf { it.isNotBlank() }?.let(::File)
        if (fromEnv?.isFile == true) return fromEnv
        val candidates = listOf(
            File("testdata/patches.mpp"),
            File("../testdata/patches.mpp"),
            File("testdata/patches-2.3.0.mpp"),
            File("../testdata/patches-2.3.0.mpp")
        )
        return candidates.firstOrNull { it.isFile }
    }

    @Test
    fun `loads the official bundle through the official loader`() {
        val file = bundleFile()
        assumeTrue("No Morphe bundle available; set MORPHX_TEST_BUNDLE", file != null)

        val patches = loadPatchesFromJar(setOf(file!!))
        assertTrue("Official bundle should declare many patches", patches.size > 50)

        val named = patches.mapNotNull { it.name }
        assertTrue("Patches should have names", named.isNotEmpty())

        // These are real patch names shipped in the official bundle; if the engine is
        // connected correctly they must be present.
        assertTrue(
            "Expected a 'Hide ads' patch in the official bundle, got: " +
                named.take(20).joinToString(", "),
            named.any { it.contains("ads", ignoreCase = true) }
        )
    }

    @Test
    fun `declares compatibility for the documented apps`() {
        val file = bundleFile()
        assumeTrue("No Morphe bundle available", file != null)

        val patches = loadPatchesFromJar(setOf(file!!))
        val packages = patches
            .flatMap { it.compatibility.orEmpty() }
            .mapNotNull { it.packageName }
            .toSet()

        // The official bundle targets these packages.
        assertTrue(
            "Expected YouTube compatibility, found: $packages",
            packages.contains("com.google.android.youtube")
        )
        assertTrue(
            "Expected YouTube Music compatibility",
            packages.contains("com.google.android.apps.youtube.music")
        )
        assertTrue(
            "Expected Reddit compatibility",
            packages.contains("com.reddit.frontpage")
        )
    }

    @Test
    fun `resolves a supported version for youtube`() {
        val file = bundleFile()
        assumeTrue("No Morphe bundle available", file != null)

        val patches = loadPatchesFromJar(setOf(file!!))
        val ytTargets = patches
            .flatMap { it.compatibility.orEmpty() }
            .filter { it.packageName == "com.google.android.youtube" }
            .flatMap { it.targets }
            .mapNotNull { it.version }

        assertTrue("YouTube patches should declare target versions", ytTargets.isNotEmpty())

        // Any declared version must resolve as SUPPORTED for exactly that version.
        val anyVersion = ytTargets.first()
        val report = CompatibilityResolver.resolve(
            bundle = bundleFrom(file!!, patches),
            packageName = "com.google.android.youtube",
            installedVersion = anyVersion,
        )
        assertEquals(Compatibility.SUPPORTED, report.status)
        assertTrue(report.applicablePatches.isNotEmpty())
    }

    @Test
    fun `rejects a version the bundle does not support`() {
        val file = bundleFile()
        assumeTrue("No Morphe bundle available", file != null)

        val patches = loadPatchesFromJar(setOf(file!!))
        val report = CompatibilityResolver.resolve(
            bundle = bundleFrom(file!!, patches),
            packageName = "com.google.android.youtube",
            installedVersion = "0.0.1-not-a-real-version",
        )
        // The official bundle also ships universal patches (signature/installer spoofing) that
        // apply to any app, so an unsupported version resolves as UNIVERSAL_ONLY rather than
        // NOT_SUPPORTED. Critically it must NOT be SUPPORTED, or automation would patch a
        // version the real patches were never written for.
        assertTrue(
            "An unsupported YouTube version must not be reported as SUPPORTED, got ${report.status}",
            report.status != Compatibility.SUPPORTED
        )
        assertTrue(
            "Expected UNIVERSAL_ONLY or UNSUPPORTED_VERSION, got ${report.status}",
            report.status == Compatibility.UNIVERSAL_ONLY ||
                report.status == Compatibility.UNSUPPORTED_VERSION
        )
        // Automation eligibility is exactly `SUPPORTED`, so this version is not auto-patched.
        assertTrue(
            "App-specific patches must not be offered for an unsupported version",
            report.applicablePatches.none { !it.isUniversal }
        )
    }

    @Test
    fun `inspector reads bundle manifest and patch metadata`() = kotlinx.coroutines.runBlocking {
        val file = bundleFile()
        assumeTrue("No Morphe bundle available", file != null)

        val info = BundleInspector().inspect(file!!, "test")
        assertTrue("Inspector should find patches", info.patches.size > 50)
        assertTrue(
            "Patcher version should be declared, got ${info.patcherVersion}",
            info.patcherVersion != null
        )
        assertTrue("Universal patches should be detected", info.universalPatchCount >= 1)
    }

    private fun bundleFrom(file: File, patches: Set<app.morphe.patcher.patch.Patch>): BundleInfo {
        val supported = mutableMapOf<String, MutableSet<String>>()
        val infos = patches.map { p ->
            val byPkg = linkedMapOf<String, List<String>>()
            var universal = p.compatibility.isNullOrEmpty()
            p.compatibility.orEmpty().forEach { c ->
                val pkg = c.packageName
                if (pkg.isNullOrBlank()) universal = true else {
                    val versions = c.targets.mapNotNull { it.version }
                    byPkg[pkg] = versions
                    supported.getOrPut(pkg) { mutableSetOf() }.addAll(versions)
                }
            }
            PatchInfo(
                name = p.name ?: "(unnamed)",
                description = p.description,
                isDefault = p.default,
                compatibility = byPkg,
                isUniversal = universal,
                options = emptyList()
            )
        }
        return BundleInfo(
            tag = "test",
            patcherVersion = null,
            bundleName = null,
            bundleVersion = null,
            patches = infos,
            supportedPackages = supported.mapValues { it.value.toList() },
            universalPatchCount = infos.count { it.isUniversal }
        )
    }
}
