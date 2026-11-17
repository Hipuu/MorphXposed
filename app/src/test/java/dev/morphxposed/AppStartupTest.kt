package dev.morphxposed

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.morphxposed.automation.Notifications
import dev.morphxposed.automation.UpdateCheckWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Covers what the app must have done by the time it is usable.
 *
 * These are the steps that run once at launch and, if skipped, break something far away: no
 * notification channels means every notification is dropped on Android 8+; no seeded source means
 * an empty Patches screen; no scheduled work means automation never runs at all.
 *
 * The startup work was originally one `runCatching`, so a failure in any step skipped the rest —
 * including scheduling the periodic check, which is meant to be the guaranteed path. The steps are
 * now isolated, and this test pins that each one happens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStartupTest {

    private lateinit var context: Context
    private lateinit var app: MorphXposedApp

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context.applicationContext as MorphXposedApp
        // onCreate runs during application creation; drain the IO work it queued.
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() = runBlocking {
        app.settings.resetToDefaults()
    }

    private fun await(timeoutMs: Long = 8_000, block: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(block).getOrDefault(false)) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun `the notification channels exist after startup`() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(
            "the ongoing channel must exist or progress notifications are dropped",
            manager.getNotificationChannel(Notifications.CHANNEL_ONGOING) != null
        )
        assertTrue(
            "the result channel must exist",
            manager.getNotificationChannel(Notifications.CHANNEL_RESULT) != null
        )
        assertTrue(
            "the action channel must exist",
            manager.getNotificationChannel(Notifications.CHANNEL_ACTION) != null
        )
    }

    @Test
    fun `the official source is seeded by startup`() {
        val seeded = await {
            runBlocking { app.database.patchSourceDao().enabled().isNotEmpty() }
        }
        assertTrue("the official Morphe source must be seeded on first run", seeded)

        val official = runBlocking { app.database.patchSourceDao().enabled() }.firstOrNull { it.isOfficial }
        assertNotNull("there must be an official source", official)
        assertTrue(
            "it must point at the Morphe patches repository",
            official!!.repoOwner.equals("MorpheApp", ignoreCase = true)
        )
    }

    @Test
    fun `a failing step does not prevent the others`() {
        // This is the point of isolating the startup steps. In this test the Application is
        // created before WorkManager can be initialised, so the scheduling step fails - and the
        // two steps around it must still have completed. Before the change they were wrapped in
        // one runCatching, so a failure here would have skipped seeding *and* scheduling, which
        // is why the periodic check is asserted directly in WorkSchedulingTest instead.
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(
            "channels must survive a later step failing",
            manager.getNotificationChannel(Notifications.CHANNEL_RESULT)
        )
        assertTrue(
            "seeding must survive a later step failing",
            runBlocking { app.database.patchSourceDao().enabled().isNotEmpty() }
        )
    }

    @Test
    fun `the settings and database are reachable after startup`() {
        // Koin must be started by the time the first screen asks for anything.
        assertNotNull("the database must be resolvable", app.database)
        assertNotNull("the settings store must be resolvable", app.settings)
        assertNotNull("the coordinator must be resolvable", app.coordinator)
        assertNotNull("the source repository must be resolvable", app.sourceRepository)
    }

    @Test
    fun `starting Koin twice does not crash`() {
        // The Application can be recreated; the guard in onCreate exists for that, and a second
        // creation must not throw.
        val second = MorphXposedApp()
        second.onCreate()
        assertNotNull("a recreated Application must still resolve the graph", second.database)
    }
}
