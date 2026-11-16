package dev.morphxposed.automation

import dev.morphxposed.data.prefs.InstallStrategy
import dev.morphxposed.data.prefs.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the decisions a scheduled update check makes.
 *
 * These were reachable only by executing a `WorkManager` worker, which meant the two things a
 * scheduler depends on — don't run when switched off, don't retry forever — were never checked.
 */
class UpdateCheckPlanTest {

    private fun snapshot(autoChecks: Boolean) = SettingsStore.Snapshot(
        installStrategy = InstallStrategy.SESSION,
        autoPatchOnInstall = false,
        autoPatchOnUpdate = false,
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

    @Test
    fun `a disabled check does nothing at all`() {
        // Not merely "no patching": no rescan and no network, so a user who switched checking
        // off never has background traffic.
        assertEquals(
            UpdateCheckPlan.Action.DoNothing,
            UpdateCheckPlan.decide(snapshot(autoChecks = false))
        )
    }

    @Test
    fun `an enabled check scans and refreshes`() {
        assertEquals(
            UpdateCheckPlan.Action.ScanAndRefresh,
            UpdateCheckPlan.decide(snapshot(autoChecks = true))
        )
    }

    @Test
    fun `a failing check retries a bounded number of times`() {
        assertTrue("the first failure must retry", UpdateCheckPlan.shouldRetry(0))
        assertTrue("the second failure must retry", UpdateCheckPlan.shouldRetry(1))
        assertTrue("the third failure must retry", UpdateCheckPlan.shouldRetry(2))
        assertFalse(
            "after the limit it must give up rather than retry forever",
            UpdateCheckPlan.shouldRetry(UpdateCheckPlan.MAX_ATTEMPTS)
        )
        assertFalse("well past the limit must also give up", UpdateCheckPlan.shouldRetry(99))
    }

    @Test
    fun `the retry limit is small enough to matter`() {
        // A retry policy that allows many attempts drains the battery on a flaky connection.
        assertTrue(
            "the limit must be a small number, was ${UpdateCheckPlan.MAX_ATTEMPTS}",
            UpdateCheckPlan.MAX_ATTEMPTS in 1..5
        )
    }
}
