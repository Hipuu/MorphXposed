package dev.morphexposed.automation

import dev.morphexposed.data.db.SupportedAppEntity
import dev.morphexposed.data.prefs.InstallStrategy
import dev.morphexposed.data.prefs.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the gate in front of all automatic patching.
 *
 * Both failure directions matter: too permissive patches an app the user never opted into; too
 * strict means automation silently never runs and the user sees nothing happen with no reason
 * why. Every trigger/flag combination is checked here.
 */
class AutoPatchPolicyTest {

    private fun app(
        pkg: String = "com.google.android.youtube",
        autoPatch: Boolean = false,
        hidden: Boolean = false
    ) = SupportedAppEntity(
        packageName = pkg,
        label = pkg,
        versionName = "1.0",
        versionCode = 1,
        apkPath = "/data/app/base.apk",
        autoPatchEnabled = autoPatch,
        hidden = hidden,
        sourceId = 1
    )

    private fun snapshot(
        autoInstall: Boolean = false,
        autoUpdate: Boolean = false,
        autoChecks: Boolean = true
    ) = SettingsStore.Snapshot(
        installStrategy = InstallStrategy.SESSION,
        autoPatchOnInstall = autoInstall,
        autoPatchOnUpdate = autoUpdate,
        autoCheckForPatchUpdates = autoChecks,
        updateCheckIntervalHours = 12,
        notifyOnSuccess = true,
        notifyOnFailure = true,
        keepOriginalBackup = true,
        wifiOnlyDownloads = false,
        verboseLogging = false,
        lsposedBridgeEnabled = true,
        githubToken = ""
    )

    // ---- The safe default ---------------------------------------------------------------

    @Test
    fun `nothing is patched automatically with default settings`() {
        // This is the property the whole automation design rests on: a fresh install patches
        // nothing until the user opts in.
        val default = snapshot()
        val unopted = app(autoPatch = false)

        listOf(
            TriggerReason.APP_INSTALLED,
            TriggerReason.APP_UPDATED,
            TriggerReason.PATCHES_UPDATED,
            TriggerReason.PERIODIC
        ).forEach { reason ->
            val decision = AutoPatchPolicy.decide(unopted, reason, default)
            assertFalse(
                "a default install must not patch on $reason, got ${decision.describe()}",
                decision.isAllowed
            )
        }
    }

    // ---- Manual consent ----------------------------------------------------------------

    @Test
    fun `a manual run is allowed even when automation is off`() {
        val decision = AutoPatchPolicy.decide(
            app(autoPatch = false),
            TriggerReason.MANUAL,
            snapshot(autoInstall = false, autoUpdate = false)
        )
        assertTrue("pressing Patch is consent", decision.isAllowed)
    }

    @Test
    fun `a manual run is still refused for a hidden app`() {
        val decision = AutoPatchPolicy.decide(app(autoPatch = true, hidden = true), TriggerReason.MANUAL, snapshot())
        assertEquals(AutoPatchPolicy.Decision.Hidden, decision)
        assertFalse("a hidden app must not be patched", decision.isAllowed)
    }

    // ---- Install trigger ---------------------------------------------------------------

    @Test
    fun `install trigger requires both the global switch and the per-app opt-in`() {
        val on = snapshot(autoInstall = true)

        assertTrue(
            "both on must allow",
            AutoPatchPolicy.decide(app(autoPatch = true), TriggerReason.APP_INSTALLED, on).isAllowed
        )
        assertEquals(
            "global off must block even if the app is opted in",
            AutoPatchPolicy.Decision.TriggerDisabled(TriggerReason.APP_INSTALLED),
            AutoPatchPolicy.decide(app(autoPatch = true), TriggerReason.APP_INSTALLED, snapshot(autoInstall = false))
        )
        assertEquals(
            "per-app off must block even if the global switch is on",
            AutoPatchPolicy.Decision.AppNotOptedIn,
            AutoPatchPolicy.decide(app(autoPatch = false), TriggerReason.APP_INSTALLED, on)
        )
    }

    @Test
    fun `the install switch does not enable the update trigger`() {
        // Two independent switches: enabling one must not silently enable the other.
        val installOnly = snapshot(autoInstall = true, autoUpdate = false)
        val decision = AutoPatchPolicy.decide(app(autoPatch = true), TriggerReason.APP_UPDATED, installOnly)
        assertEquals(
            AutoPatchPolicy.Decision.TriggerDisabled(TriggerReason.APP_UPDATED),
            decision
        )
    }

    // ---- Update trigger ----------------------------------------------------------------

    @Test
    fun `update trigger requires both the global switch and the per-app opt-in`() {
        val on = snapshot(autoUpdate = true)

        assertTrue(
            AutoPatchPolicy.decide(app(autoPatch = true), TriggerReason.APP_UPDATED, on).isAllowed
        )
        assertEquals(
            AutoPatchPolicy.Decision.TriggerDisabled(TriggerReason.APP_UPDATED),
            AutoPatchPolicy.decide(app(autoPatch = true), TriggerReason.APP_UPDATED, snapshot(autoUpdate = false))
        )
        assertEquals(
            AutoPatchPolicy.Decision.AppNotOptedIn,
            AutoPatchPolicy.decide(app(autoPatch = false), TriggerReason.APP_UPDATED, on)
        )
    }

    // ---- Opt-in-only triggers ----------------------------------------------------------

    @Test
    fun `patch-update and periodic triggers depend only on the per-app opt-in`() {
        // These run over the opted-in set; there is no separate global switch for them.
        val any = snapshot()
        listOf(TriggerReason.PATCHES_UPDATED, TriggerReason.PERIODIC).forEach { reason ->
            assertTrue(
                "$reason must run for an opted-in app",
                AutoPatchPolicy.decide(app(autoPatch = true), reason, any).isAllowed
            )
            assertEquals(
                "$reason must skip an app that is not opted in",
                AutoPatchPolicy.Decision.AppNotOptedIn,
                AutoPatchPolicy.decide(app(autoPatch = false), reason, any)
            )
        }
    }

    // ---- Hiding ------------------------------------------------------------------------

    @Test
    fun `a hidden app is refused for every trigger`() {
        val generous = snapshot(autoInstall = true, autoUpdate = true)
        TriggerReason.entries.forEach { reason ->
            val decision = AutoPatchPolicy.decide(app(autoPatch = true, hidden = true), reason, generous)
            assertEquals("$reason must skip a hidden app", AutoPatchPolicy.Decision.Hidden, decision)
        }
    }

    // ---- Batch selection ---------------------------------------------------------------

    @Test
    fun `eligible selects exactly the apps the trigger may act on`() {
        val apps = listOf(
            app("com.a.one", autoPatch = true),
            app("com.b.two", autoPatch = false),
            app("com.c.three", autoPatch = true, hidden = true),
            app("com.d.four", autoPatch = true)
        )

        val periodic = AutoPatchPolicy.eligible(apps, TriggerReason.PERIODIC, snapshot())
        assertEquals(
            "only visible opted-in apps",
            listOf("com.a.one", "com.d.four"),
            periodic.map { it.packageName }
        )

        // A manual batch is broader: every visible app, opted in or not.
        val manual = AutoPatchPolicy.eligible(apps, TriggerReason.MANUAL, snapshot())
        assertEquals(
            listOf("com.a.one", "com.b.two", "com.d.four"),
            manual.map { it.packageName }
        )
    }

    @Test
    fun `an install trigger selects nothing when the global switch is off`() {
        val apps = listOf(app("com.a.one", autoPatch = true), app("com.b.two", autoPatch = true))
        val eligible = AutoPatchPolicy.eligible(
            apps,
            TriggerReason.APP_INSTALLED,
            snapshot(autoInstall = false)
        )
        assertTrue("nothing may be eligible", eligible.isEmpty())
    }

    // ---- Explanations ------------------------------------------------------------------

    @Test
    fun `every refusal explains itself`() {
        // The run summary shows these; an empty string would tell the user nothing.
        val refusals = listOf(
            AutoPatchPolicy.Decision.Hidden,
            AutoPatchPolicy.Decision.AppNotOptedIn,
            AutoPatchPolicy.Decision.TriggerDisabled(TriggerReason.APP_INSTALLED),
            AutoPatchPolicy.Decision.TriggerDisabled(TriggerReason.APP_UPDATED),
            AutoPatchPolicy.Decision.TriggerDisabled(TriggerReason.PATCHES_UPDATED),
            AutoPatchPolicy.Decision.TriggerDisabled(TriggerReason.PERIODIC),
            AutoPatchPolicy.Decision.TriggerDisabled(TriggerReason.MANUAL)
        )
        refusals.forEach {
            assertTrue("$it must describe itself", it.describe().isNotBlank())
        }
        assertEquals("Allowed", AutoPatchPolicy.Decision.Allowed.describe())
    }
}
