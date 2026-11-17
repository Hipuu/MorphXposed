package dev.morphexposed.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Covers the notifications the app raises.
 *
 * `actionRequired` existed from the first version and was **never called** — the brief asks the
 * app to notify when a patch "requires intervention", and the capability was dead code. A
 * defined-but-unreachable notification is indistinguishable from a missing one, so this pins
 * that each one actually reaches the platform.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationsTest {

    private lateinit var context: Context
    private lateinit var notifications: Notifications

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notifications = Notifications(context)
        notifications.ensureChannels()
    }

    private fun shadow() =
        org.robolectric.Shadows.shadowOf(
            context.getSystemService(android.app.NotificationManager::class.java)
        ) as ShadowNotificationManager

    @Test
    fun `the channels are created`() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertNotNull("the ongoing channel must exist", manager.getNotificationChannel(Notifications.CHANNEL_ONGOING))
        assertNotNull("the result channel must exist", manager.getNotificationChannel(Notifications.CHANNEL_RESULT))
        assertNotNull("the action channel must exist", manager.getNotificationChannel(Notifications.CHANNEL_ACTION))
    }

    @Test
    fun `the ongoing channel is silent`() {
        // It accompanies a foreground service; a noisy ongoing notification would be wrong.
        val channel = context.getSystemService(android.app.NotificationManager::class.java)
            .getNotificationChannel(Notifications.CHANNEL_ONGOING)
        assertTrue(
            "patching progress must not buzz",
            channel.importance <= android.app.NotificationManager.IMPORTANCE_LOW
        )
    }

    @Test
    fun `the action channel is the loudest`() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val action = manager.getNotificationChannel(Notifications.CHANNEL_ACTION)
        val ongoing = manager.getNotificationChannel(Notifications.CHANNEL_ONGOING)
        assertTrue(
            "something needing attention must outrank progress",
            action.importance > ongoing.importance
        )
    }

    @Test
    fun `a successful patch is notified`() {
        notifications.patchSucceeded("YouTube", patchCount = 3, installed = true)
        assertTrue(
            "the user must be told a patch finished",
            shadow().allNotifications.isNotEmpty()
        )
    }

    @Test
    fun `a failed patch is notified`() {
        // This is the case the brief calls "requires intervention", and the one a user is most
        // likely to miss without a notification.
        notifications.patchFailed("YouTube", "fingerprint not found")
        assertTrue("a failure must be notified", shadow().allNotifications.isNotEmpty())
    }

    @Test
    fun `an intervention request is notified`() {
        notifications.actionRequired("YouTube", "Patched but not installed.")
        assertTrue(
            "a patch that needs the user to act must be notified",
            shadow().allNotifications.isNotEmpty()
        )
    }

    @Test
    fun `available updates are notified`() {
        notifications.updatesAvailable(2, "official v2.4.0")
        assertTrue(
            "a newer bundle must be announced",
            shadow().allNotifications.isNotEmpty()
        )
    }

    @Test
    fun `every notification type reaches the platform`() {
        // Each call is exercised in sequence: a notification method that silently does nothing
        // is the failure mode this guards.
        notifications.patchSucceeded("A", 1, true)
        val afterSuccess = shadow().allNotifications.size
        notifications.patchFailed("B", "why")
        val afterFailure = shadow().allNotifications.size
        notifications.actionRequired("C", "act")
        val afterAction = shadow().allNotifications.size

        assertTrue("a success must add a notification", afterSuccess >= 1)
        assertTrue("a failure must add one", afterFailure > afterSuccess)
        assertTrue("an intervention must add one", afterAction > afterFailure)
    }
}
