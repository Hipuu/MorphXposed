package dev.morphexposed.domain.source

import dev.morphexposed.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the check that a bundle matches the patcher this APK ships.
 *
 * The Patches screen displayed the bundle's required patcher version but never compared it, so a
 * bundle built for a newer engine looked fine until a run failed somewhere inside the patcher with
 * a message a long way from the cause. Morphe's engine rejects such a bundle; the information is
 * available at download time, so the app can say so first.
 *
 * The direction of the comparison matters: refusing a bundle that would actually work is worse
 * than allowing one that fails, because the user loses a working feature silently.
 */
class PatcherCompatibilityTest {

    @Test
    fun `the bundled version is compiled in, not guessed`() {
        // Sourced from the same version catalog the dependency uses, so it cannot drift.
        val bundled = PatcherCompatibility.bundledVersion()
        assertTrue("the bundled patcher version must be present, was '$bundled'", bundled.isNotBlank())
        assertTrue(
            "it must look like a version, was '$bundled'",
            bundled.first().isDigit()
        )
        assertEquals(BuildConfig.MORPHE_PATCHER_VERSION, bundled)
    }

    @Test
    fun `a bundle needing an older patcher is compatible`() {
        val verdict = PatcherCompatibility.check("2.0.0", bundled = "2.4.0")
        assertEquals(PatcherCompatibility.Verdict.Compatible, verdict)
        assertTrue(verdict.canRun)
    }

    @Test
    fun `a bundle needing exactly the bundled patcher is compatible`() {
        assertTrue(PatcherCompatibility.check("2.4.0", bundled = "2.4.0").canRun)
    }

    @Test
    fun `a bundle needing a newer patcher is refused`() {
        val verdict = PatcherCompatibility.check("2.5.0", bundled = "2.4.0")
        assertFalse("a newer requirement must be refused", verdict.canRun)
        assertTrue(verdict is PatcherCompatibility.Verdict.RequiresNewerPatcher)

        val refusal = verdict as PatcherCompatibility.Verdict.RequiresNewerPatcher
        assertEquals("2.5.0", refusal.required)
        assertEquals("2.4.0", refusal.bundled)
    }

    @Test
    fun `the comparison is numeric, not alphabetical`() {
        // "2.10.0" sorts before "2.9.0" as a string; comparing text would let an unsupported
        // bundle through.
        assertFalse(
            "2.10.0 is newer than 2.9.0",
            PatcherCompatibility.check("2.10.0", bundled = "2.9.0").canRun
        )
        assertTrue(
            "2.9.0 is older than 2.10.0",
            PatcherCompatibility.check("2.9.0", bundled = "2.10.0").canRun
        )
    }

    @Test
    fun `a bundle declaring no patcher version is allowed`() {
        // Morphe treats a missing attribute as "no constraint"; refusing would break usable
        // bundles.
        assertEquals(
            PatcherCompatibility.Verdict.Unknown,
            PatcherCompatibility.check(null, bundled = "2.4.0")
        )
        assertEquals(
            PatcherCompatibility.Verdict.Unknown,
            PatcherCompatibility.check("   ", bundled = "2.4.0")
        )
        assertTrue(PatcherCompatibility.check(null).canRun)
    }

    @Test
    fun `a malformed requirement does not block the bundle`() {
        // VersionComparator falls back to alphabetical ordering for unparseable values, under
        // which "not-a-version" outranks "2.4.0". Comparing would therefore wrongly block a
        // bundle that probably works, so a non-version is reported as unknown instead.
        assertEquals(
            PatcherCompatibility.Verdict.Unknown,
            PatcherCompatibility.check("not-a-version", bundled = "2.4.0")
        )
        assertTrue(PatcherCompatibility.check("not-a-version", bundled = "2.4.0").canRun)
    }

    @Test
    fun `a suffixed version is still compared`() {
        // Real bundles may tag a release; the suffix must not disable the check.
        assertFalse(
            PatcherCompatibility.check("2.5.0-beta1", bundled = "2.4.0").canRun
        )
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertTrue(PatcherCompatibility.check("  2.3.0  ", bundled = "2.4.0").canRun)
        assertFalse(PatcherCompatibility.check(" 2.9.0 ", bundled = "2.4.0").canRun)
    }

    @Test
    fun `every verdict describes itself`() {
        val verdicts = listOf(
            PatcherCompatibility.Verdict.Compatible,
            PatcherCompatibility.Verdict.Unknown,
            PatcherCompatibility.Verdict.RequiresNewerPatcher("3.0.0", "2.4.0")
        )
        verdicts.forEach {
            assertTrue("$it must describe itself", it.describe().isNotBlank())
        }
        // The refusal must name both versions so the user knows what to do.
        val refusal = PatcherCompatibility.Verdict.RequiresNewerPatcher("3.0.0", "2.4.0").describe()
        assertTrue("must name the required version, was: $refusal", refusal.contains("3.0.0"))
        assertTrue("must name the bundled version, was: $refusal", refusal.contains("2.4.0"))
    }

    @Test
    fun `the real bundled version accepts a requirement for itself`() {
        // Guards against an off-by-one that would refuse every current bundle.
        val bundled = PatcherCompatibility.bundledVersion()
        assertTrue(PatcherCompatibility.check(bundled, bundled = bundled).canRun)
    }
}
