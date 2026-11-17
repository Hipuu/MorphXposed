package dev.morphxposed.domain.apps

import dev.morphxposed.domain.source.BundleInfo
import dev.morphxposed.domain.source.PatchInfo
import dev.morphxposed.domain.source.VersionComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the rule that decides whether an app may be patched.
 *
 * This is the safety-critical decision in the app: a false positive here means patching a
 * version the patch was not written for, which produces a broken or crashing build. The
 * semantics mirror Morphe's own `Compatibility` declarations.
 */
class CompatibilityResolverTest {

    private fun patch(
        name: String,
        defaultsOn: Boolean = true,
        universal: Boolean = false,
        vararg compat: Pair<String, List<String>>
    ) = PatchInfo(
        name = name,
        description = null,
        isDefault = defaultsOn,
        compatibility = if (universal) emptyMap() else compat.toMap(),
        isUniversal = universal,
        options = emptyList()
    )

    private fun bundle(vararg patches: PatchInfo): BundleInfo {
        val supported = mutableMapOf<String, MutableSet<String>>()
        patches.forEach { p ->
            p.compatibility.forEach { (pkg, versions) -> supported.getOrPut(pkg) { mutableSetOf() }.addAll(versions) }
        }
        return BundleInfo(
            tag = "v1",
            patcherVersion = "2.4.0",
            bundleName = "test",
            bundleVersion = "1",
            patches = patches.toList(),
            supportedPackages = supported.mapValues { it.value.toList() },
            universalPatchCount = patches.count { it.isUniversal }
        )
    }

    @Test
    fun `supported when installed version is declared`() {
        val b = bundle(patch("Hide ads", compat = arrayOf("com.example.app" to listOf("1.0.0", "1.1.0"))))
        val report = CompatibilityResolver.resolve(b, "com.example.app", "1.1.0")

        assertEquals(Compatibility.SUPPORTED, report.status)
        assertEquals(1, report.applicablePatches.size)
        assertTrue(report.hasApplicablePatches)
    }

    @Test
    fun `unsupported when version is not declared`() {
        val b = bundle(patch("Hide ads", compat = arrayOf("com.example.app" to listOf("1.0.0"))))
        val report = CompatibilityResolver.resolve(b, "com.example.app", "9.9.9")

        assertEquals(Compatibility.UNSUPPORTED_VERSION, report.status)
        assertFalse(report.hasApplicablePatches)
        assertTrue(report.reason.contains("9.9.9"))
    }

    @Test
    fun `patch with no versions supports any version of that package`() {
        val b = bundle(patch("Universal tweak", compat = arrayOf("com.example.app" to emptyList())))
        val report = CompatibilityResolver.resolve(b, "com.example.app", "42.0.0")

        assertEquals(Compatibility.SUPPORTED, report.status)
        assertEquals(1, report.applicablePatches.size)
    }

    @Test
    fun `universal patch alone does not make an unsupported version patchable`() {
        // This is the bug the real bundle exposed: universal patches (signature spoofing)
        // apply to any app, but they must not report a genuinely unsupported version as
        // SUPPORTED, or automation would patch a version the real patches were never
        // written for.
        val b = bundle(
            patch("Spoof signature", universal = true),
            patch("Hide ads", compat = arrayOf("com.example.app" to listOf("1.0.0")))
        )
        val report = CompatibilityResolver.resolve(b, "com.example.app", "9.9.9")

        assertEquals(Compatibility.UNIVERSAL_ONLY, report.status)
        assertTrue(
            "Only universal patches may be offered",
            report.applicablePatches.all { it.isUniversal }
        )
    }

    @Test
    fun `universal patch on a package no patch targets is universal only`() {
        val b = bundle(patch("Spoof signature", universal = true))
        val report = CompatibilityResolver.resolve(b, "com.anything.at.all", "1.0")

        assertEquals(Compatibility.UNIVERSAL_ONLY, report.status)
        assertEquals(1, report.applicablePatches.size)
    }

    @Test
    fun `universal patch applies alongside a supported version`() {
        val b = bundle(
            patch("Spoof signature", universal = true),
            patch("Hide ads", compat = arrayOf("com.example.app" to listOf("1.0.0")))
        )
        val report = CompatibilityResolver.resolve(b, "com.example.app", "1.0.0")

        assertEquals(Compatibility.SUPPORTED, report.status)
        assertEquals(2, report.applicablePatches.size)
    }

    @Test
    fun `not supported when no patch mentions the package`() {
        val b = bundle(patch("Hide ads", compat = arrayOf("com.other.app" to listOf("1.0.0"))))
        val report = CompatibilityResolver.resolve(b, "com.example.app", "1.0.0")

        assertEquals(Compatibility.NOT_SUPPORTED, report.status)
    }

    @Test
    fun `partially supported bundle applies only matching patches`() {
        val b = bundle(
            patch("Old only", compat = arrayOf("com.example.app" to listOf("1.0.0"))),
            patch("Current", compat = arrayOf("com.example.app" to listOf("2.0.0")))
        )
        val report = CompatibilityResolver.resolve(b, "com.example.app", "2.0.0")

        assertEquals(Compatibility.SUPPORTED, report.status)
        assertEquals(listOf("Current"), report.applicablePatches.map { it.name })
        assertEquals(listOf("Old only"), report.skippedPatches.map { it.name })
    }

    @Test
    fun `default selection prefers patches that default on`() {
        val b = bundle(
            patch("On", defaultsOn = true, compat = arrayOf("com.example.app" to listOf("1.0"))),
            patch("Off", defaultsOn = false, compat = arrayOf("com.example.app" to listOf("1.0")))
        )
        val report = CompatibilityResolver.resolve(b, "com.example.app", "1.0")

        assertEquals(listOf("On"), CompatibilityResolver.defaultSelection(report))
    }

    @Test
    fun `default selection falls back to all patches when none default on`() {
        val b = bundle(
            patch("A", defaultsOn = false, compat = arrayOf("com.example.app" to listOf("1.0"))),
            patch("B", defaultsOn = false, compat = arrayOf("com.example.app" to listOf("1.0")))
        )
        val report = CompatibilityResolver.resolve(b, "com.example.app", "1.0")

        assertEquals(listOf("A", "B"), CompatibilityResolver.defaultSelection(report).sorted())
    }

    @Test
    fun `best bundle prefers one that supports the installed version`() {
        val unsupported = CompatibilityResolver.resolve(
            bundle(patch("X", compat = arrayOf("p" to listOf("1.0")))), "p", "3.0"
        )
        val supported = CompatibilityResolver.resolve(
            bundle(patch("Y", compat = arrayOf("p" to listOf("3.0")))), "p", "3.0"
        )

        val best = CompatibilityResolver.best(listOf(1L to unsupported, 2L to supported))
        assertEquals(2L, best?.first)
        assertEquals(Compatibility.SUPPORTED, best?.second?.status)
    }
}

/**
 * Morphe version strings are not always semantic (`21.44.528`, `2026.46.0`), so the comparator
 * must order by numeric segments and never compare lexically.
 */
class VersionComparatorTest {

    @Test
    fun `orders numerically not lexically`() {
        val versions = listOf("21.9.0", "21.10.0", "21.44.528", "21.4.1")
        val sorted = versions.sortedWith(VersionComparator)

        assertEquals(listOf("21.4.1", "21.9.0", "21.10.0", "21.44.528"), sorted)
    }

    @Test
    fun `handles four segment rubix versions`() {
        assertTrue(VersionComparator.compare("9.46.51", "9.45.51") > 0)
        assertTrue(VersionComparator.compare("2026.10.0", "2026.9.0") > 0)
    }

    @Test
    fun `equal versions compare as zero`() {
        assertEquals(0, VersionComparator.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun `non numeric versions fall back to case insensitive text order`() {
        val sorted = listOf("beta", "Alpha", "gamma").sortedWith(VersionComparator)
        assertEquals(listOf("Alpha", "beta", "gamma"), sorted)
    }
}
