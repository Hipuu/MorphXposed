package dev.morphxposed.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.morphxposed.data.prefs.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises real `WorkManager` scheduling.
 *
 * This is one of the paths that was previously only compiled: it verifies that unique work is
 * enqueued, that the constraints the app declares are actually attached, that rescheduling
 * replaces rather than duplicates, and that cancellation removes the work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkSchedulingTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            // Run work inline so scheduling is observable without a real scheduler.
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `periodic update check is enqueued with a network constraint`() {
        UpdateCheckWorker.schedule(context, intervalHours = 6)

        val infos = workManager.getWorkInfosForUniqueWork(UpdateCheckWorker.NAME).get()
        assertEquals("exactly one unique periodic work", 1, infos.size)

        val info = infos.first()
        assertNotNull(info)
        assertEquals(WorkInfo.State.ENQUEUED, info.state)

        val constraints = info.constraints
        assertEquals(
            "the check must require connectivity",
            NetworkType.CONNECTED,
            constraints.requiredNetworkType
        )
    }

    @Test
    fun `rescheduling does not create duplicates`() {
        UpdateCheckWorker.schedule(context, 6)
        UpdateCheckWorker.schedule(context, 3)
        UpdateCheckWorker.schedule(context, 12)

        val infos = workManager.getWorkInfosForUniqueWork(UpdateCheckWorker.NAME).get()
        assertEquals("rescheduling must replace, not duplicate", 1, infos.size)
    }

    @Test
    fun `cancelling removes the periodic work`() {
        UpdateCheckWorker.schedule(context, 12)
        assertEquals(1, workManager.getWorkInfosForUniqueWork(UpdateCheckWorker.NAME).get().size)

        UpdateCheckWorker.cancel(context)

        val infos = workManager.getWorkInfosForUniqueWork(UpdateCheckWorker.NAME).get()
        assertTrue(
            "after cancel, work must be cancelled or gone",
            infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED }
        )
    }

    @Test
    fun `an absurd interval is clamped into WorkManager's range`() {
        // WorkManager rejects intervals below 15 minutes; the app clamps to hours, so 0 must not crash.
        UpdateCheckWorker.schedule(context, intervalHours = 0)
        val infos = workManager.getWorkInfosForUniqueWork(UpdateCheckWorker.NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
    }

    @Test
    fun `auto patch work is enqueued per package`() {
        AutoPatchWorker.enqueue(context, "com.google.android.youtube", TriggerReason.APP_UPDATED)

        val infos = workManager
            .getWorkInfosForUniqueWork("morphxposed_autopatch_com.google.android.youtube")
            .get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
        assertEquals(
            "auto-patch must wait for connectivity",
            NetworkType.CONNECTED,
            infos.first().constraints.requiredNetworkType
        )
    }

    @Test
    fun `auto patch work is per-package, not global`() {
        AutoPatchWorker.enqueue(context, "com.google.android.youtube", TriggerReason.APP_INSTALLED)
        AutoPatchWorker.enqueue(context, "com.reddit.frontpage", TriggerReason.APP_INSTALLED)

        val yt = workManager
            .getWorkInfosForUniqueWork("morphxposed_autopatch_com.google.android.youtube").get()
        val reddit = workManager
            .getWorkInfosForUniqueWork("morphxposed_autopatch_com.reddit.frontpage").get()

        assertEquals(1, yt.size)
        assertEquals(1, reddit.size)
    }

    @Test
    fun `re-enqueuing the same package replaces rather than stacks`() {
        AutoPatchWorker.enqueue(context, "com.google.android.youtube", TriggerReason.APP_INSTALLED)
        AutoPatchWorker.enqueue(context, "com.google.android.youtube", TriggerReason.APP_UPDATED)
        AutoPatchWorker.enqueue(context, "com.google.android.youtube", TriggerReason.PATCHES_UPDATED)

        val infos = workManager
            .getWorkInfosForUniqueWork("morphxposed_autopatch_com.google.android.youtube")
            .get()
        assertEquals("unique work must not stack up", 1, infos.size)
    }

    @Test
    fun `enabling the periodic check and disabling it round-trips`() = kotlinx.coroutines.runBlocking {
        val settings = SettingsStore(context)
        settings.resetToDefaults()

        // Mirrors what the Settings screen does when the toggle changes.
        settings.setAutoCheckForPatchUpdates(true)
        UpdateCheckWorker.schedule(context, settings.snapshot().updateCheckIntervalHours)
        assertEquals(1, workManager.getWorkInfosForUniqueWork(UpdateCheckWorker.NAME).get().size)

        settings.setAutoCheckForPatchUpdates(false)
        UpdateCheckWorker.cancel(context)
        val after = workManager.getWorkInfosForUniqueWork(UpdateCheckWorker.NAME).get()
        assertTrue(after.isEmpty() || after.all { it.state == WorkInfo.State.CANCELLED })
    }
}
