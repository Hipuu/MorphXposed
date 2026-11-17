package dev.morphxposed.ui.viewmodel

import dev.morphxposed.domain.backup.RollbackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the restore confirmation and its wording.
 *
 * `RollbackService.availability()` computed `losesAppData` from the start and `RollbackServiceTest`
 * asserted it, but nothing in the UI ever called it: the Restore button acted immediately, so a
 * session-installed app could be restored with **all of its data cleared** and no warning. The
 * information existed, was tested, and never reached a screen.
 *
 * These tests are on the pure builder rather than the ViewModel, so the wording that carries the
 * data-loss warning is covered without a database or a coroutine.
 */
class RestorePresentationTest {

    private fun availability(
        canRollback: Boolean = true,
        losesAppData: Boolean = true,
        reason: String = "Re-installs the original APK 1.0.0."
    ) = RollbackService.Availability(
        canRollback = canRollback,
        backup = null,
        losesAppData = losesAppData,
        reason = reason
    )

    // ---- When a prompt is raised at all -------------------------------------------------

    @Test
    fun `no prompt when the restore is impossible`() {
        // Nothing to confirm: the user has to be told, not asked.
        assertNull(
            RestorePresentation.prompt("com.example.app", "Example", availability(canRollback = false))
        )
    }

    @Test
    fun `no prompt when there is no availability at all`() {
        assertNull(RestorePresentation.prompt("com.example.app", "Example", null))
    }

    @Test
    fun `a possible restore raises a prompt naming the app`() {
        val prompt = RestorePresentation.prompt("com.example.app", "Example App", availability())
        assertNotNull(prompt)
        assertEquals("com.example.app", prompt!!.packageName)
        assertEquals("Example App", prompt.appLabel)
    }

    @Test
    fun `a missing label falls back to the package name`() {
        // Better than an empty dialog title.
        assertEquals(
            "com.example.app",
            RestorePresentation.prompt("com.example.app", null, availability())!!.appLabel
        )
        assertEquals(
            "com.example.app",
            RestorePresentation.prompt("com.example.app", "   ", availability())!!.appLabel
        )
    }

    // ---- The warning itself -------------------------------------------------------------

    @Test
    fun `a destructive restore carries the data-loss flag`() {
        val prompt = RestorePresentation.prompt("p", "App", availability(losesAppData = true))!!
        assertTrue(prompt.losesAppData)
    }

    @Test
    fun `a mounted restore does not claim data loss`() {
        // A mount never replaced the app, so nothing is at risk and saying otherwise would be
        // needlessly alarming.
        val prompt = RestorePresentation.prompt("p", "App", availability(losesAppData = false))!!
        assertFalse(prompt.losesAppData)
    }

    @Test
    fun `the destructive body says the data will go and that it is irreversible`() {
        val prompt = RestorePresentation.prompt("p", "App", availability(losesAppData = true))!!
        val body = RestorePresentation.body(prompt)

        assertTrue("must warn about data, was: $body", body.contains("data", ignoreCase = true))
        assertTrue("must say it cannot be undone, was: $body", body.contains("cannot be undone"))
        // The service's own explanation is preserved, not replaced.
        assertTrue("must keep the reason, was: $body", body.contains(prompt.reason))
    }

    @Test
    fun `the non-destructive body does not claim data loss`() {
        val prompt = RestorePresentation.prompt(
            "p", "App",
            availability(losesAppData = false, reason = "App data is kept.")
        )!!
        val body = RestorePresentation.body(prompt)

        assertFalse("must not claim data loss, was: $body", body.contains("cannot be undone"))
        assertTrue("must explain what happens, was: $body", body.isNotBlank())
    }

    @Test
    fun `the confirm label does not make a destructive action look routine`() {
        val destructive = RestorePresentation.prompt("p", "App", availability(losesAppData = true))!!
        val safe = RestorePresentation.prompt("p", "App", availability(losesAppData = false))!!

        assertTrue(
            "the destructive confirmation must mention the data, was " +
                "'${RestorePresentation.confirmLabel(destructive)}'",
            RestorePresentation.confirmLabel(destructive).contains("data", ignoreCase = true)
        )
        assertEquals("Restore", RestorePresentation.confirmLabel(safe))
    }

    @Test
    fun `the title names the app`() {
        val prompt = RestorePresentation.prompt("p", "YouTube", availability())!!
        assertEquals("Restore YouTube?", RestorePresentation.title(prompt))
    }

    @Test
    fun `there is always a way out`() {
        assertEquals("Cancel", RestorePresentation.dismissLabel())
    }

    @Test
    fun `every prompt produces non-empty copy`() {
        listOf(true, false).forEach { loses ->
            val prompt = RestorePresentation.prompt("p", "App", availability(losesAppData = loses))!!
            assertTrue(RestorePresentation.title(prompt).isNotBlank())
            assertTrue(RestorePresentation.body(prompt).isNotBlank())
            assertTrue(RestorePresentation.confirmLabel(prompt).isNotBlank())
        }
    }
}
