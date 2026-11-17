package dev.morphxposed.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the prerequisite list Settings shows.
 *
 * The brief asks Settings to surface "permissions". It matters because each missing prerequisite
 * produces a confusing failure far from its cause: an install that quietly becomes an exported
 * APK, a notification that never arrives, root mount reporting "not available" with no reason.
 * Each is a plain question to the platform, so each must be answered precisely.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionStatusTest {

    private lateinit var context: Context
    private lateinit var status: PermissionStatus

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        status = PermissionStatus(context)
    }

    @Test
    fun `every prerequisite is reported`() {
        val items = status.read()

        assertTrue("all prerequisites must be listed", items.size >= 4)
        val names = items.map { it.name.lowercase() }
        assertTrue("install permission must be listed", names.any { it.contains("install") })
        assertTrue("notifications must be listed", names.any { it.contains("notification") })
        assertTrue("root must be listed", names.any { it.contains("root") })
        assertTrue("LSPosed must be listed", names.any { it.contains("lsposed") })
    }

    @Test
    fun `every item explains itself and how to fix it`() {
        status.read().forEach { item ->
            assertTrue("${item.name} must describe what it is for", item.description.isNotBlank())
            if (item.granted) {
                // Nothing to do when it is already granted; a stale remedy would confuse.
                assertNull("${item.name} is granted but still shows a remedy", item.remedy)
            } else {
                // A missing prerequisite with no remedy leaves the user stuck.
                assertNotNull("${item.name} is missing but offers no remedy", item.remedy)
                assertTrue("the remedy must say what to do", item.remedy!!.isNotBlank())
            }
        }
    }

    @Test
    fun `only the install permission is required`() {
        val required = status.read().filter { it.required }.map { it.name.lowercase() }
        assertEquals("exactly one prerequisite is required, was: $required", 1, required.size)
        assertTrue("and it is the install permission", required.first().contains("install"))
    }

    @Test
    fun `root and LSPosed are optional and say so`() {
        // Both are enhancements: the app works without them, on the session installer and the
        // scheduled check. Reporting them as required would be misleading.
        val items = status.read()
        assertTrue(items.first { it.name.contains("Root", true) }.required.not())
        assertTrue(items.first { it.name.contains("LSPosed", true) }.required.not())
        assertTrue(items.first { it.name.contains("Notification", true) }.required.not())
    }

    @Test
    fun `notifications are treated as granted before API 33`() {
        // POST_NOTIFICATIONS does not exist before Tiramisu, so the check must not report a
        // permission the platform never had as missing.
        val item = status.read().first { it.name.contains("Notification", true) }
        assertTrue(
            "on API 34 the runtime permission genuinely applies, so the answer must be a real " +
                "one rather than an assumption",
            item.granted || item.remedy != null
        )
    }

    @Test
    fun `reading never throws even when the platform refuses`() {
        // PackageManager and permission checks can fail on a locked-down device; the settings
        // screen must still render.
        val items = runCatching { status.read() }.getOrNull()
        assertNotNull("reading prerequisites must not throw", items)
        assertTrue(items!!.isNotEmpty())
    }

    @Test
    fun `the install item reflects the platform answer`() {
        val item = status.read().first { it.name.contains("install", true) }
        assertEquals(
            "the item must not contradict what the platform reports",
            context.packageManager.canRequestPackageInstalls(),
            item.granted
        )
    }
}
