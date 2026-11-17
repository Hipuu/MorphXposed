package dev.morphexposed.ui.viewmodel

import dev.morphexposed.automation.BatchSummary
import dev.morphexposed.automation.TriggerReason
import dev.morphexposed.domain.patch.PatchEvent
import dev.morphexposed.domain.patch.StageName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers the two functions that decide everything the user reads: the batch summary shown in
 * the snackbar, and the folding of patcher events into the progress sheet.
 *
 * These are pure functions precisely so they can be tested exhaustively; they were private
 * methods on a ViewModel bound to an `Application`, which meant the app's most-read text was
 * the least verified.
 */
class RunPresentationTest {

    private fun summary(
        patched: List<String> = emptyList(),
        failed: List<Pair<String, String>> = emptyList(),
        skipped: List<Pair<String, String>> = emptyList()
    ) = BatchSummary(TriggerReason.MANUAL, patched, failed, skipped)

    // ---- Batch summary ------------------------------------------------------------------

    @Test
    fun `an empty batch says there is nothing to patch`() {
        assertEquals("Nothing to patch.", RunPresentation.summarise(summary()))
    }

    @Test
    fun `a successful batch reports how many apps were patched`() {
        assertEquals(
            "Patched 2 app(s).",
            RunPresentation.summarise(summary(patched = listOf("YouTube", "Reddit")))
        )
    }

    @Test
    fun `a failure always wins over a success`() {
        // Showing "Patched 2 apps" while a third failed would let the user believe the batch
        // was clean.
        val text = RunPresentation.summarise(
            summary(
                patched = listOf("YouTube", "Reddit"),
                failed = listOf("Music" to "fingerprint not found")
            )
        )
        assertEquals("1 failed: fingerprint not found", text)
        assertFalse("a failure must not be reported as a success", text.startsWith("Patched"))
    }

    @Test
    fun `a failure names its reason`() {
        val text = RunPresentation.summarise(
            summary(failed = listOf("YouTube" to "version unsupported"))
        )
        assertTrue("the reason must be visible", text.contains("version unsupported"))
    }

    @Test
    fun `a fully skipped batch reports why`() {
        val text = RunPresentation.summarise(
            summary(skipped = listOf("YouTube" to "Automatic patching is off for this app"))
        )
        assertEquals("Automatic patching is off for this app", text)
    }

    // ---- Event folding ------------------------------------------------------------------

    private val initial = RunProgress(active = true)

    @Test
    fun `a stage event sets the stage text`() {
        val state = RunPresentation.reduce(initial, PatchEvent.Stage(StageName.PATCHING))
        assertTrue("the stage must be recorded", state.stage.isNotEmpty())
        assertTrue("the stage name must appear", state.stage.contains("PATCHING"))
    }

    @Test
    fun `a stage with detail includes it`() {
        val state = RunPresentation.reduce(
            initial,
            PatchEvent.Stage(StageName.LOADING_BUNDLE, "patches.mpp")
        )
        assertTrue("the detail must appear", state.stage.contains("patches.mpp"))
    }

    @Test
    fun `successful patches are counted and logged`() {
        var state = RunPresentation.reduce(initial, PatchEvent.PatchSucceeded("Hide ads"))
        state = RunPresentation.reduce(state, PatchEvent.PatchSucceeded("SponsorBlock"))

        assertEquals("both patches must be counted", 2, state.patchProgress?.first)
        assertTrue(state.logLines.any { it.contains("Hide ads") })
        assertTrue(state.logLines.any { it.contains("SponsorBlock") })
    }

    @Test
    fun `a failed patch is logged with its reason`() {
        val state = RunPresentation.reduce(
            initial,
            PatchEvent.PatchFailed("Hide ads", "fingerprint not found")
        )
        assertTrue(
            "the log must carry the failure and its reason",
            state.logLines.any { it.contains("Hide ads") && it.contains("fingerprint not found") }
        )
    }

    @Test
    fun `an error event is recorded as the failure`() {
        val state = RunPresentation.reduce(initial, PatchEvent.Error("bundle could not be read"))
        assertEquals("bundle could not be read", state.failed)
    }

    @Test
    fun `done without an install is not reported as installed`() {
        // The exact defect this guards: an exported-only run must not look like a completed
        // install, or the user believes the patched app is live.
        val state = RunPresentation.reduce(
            initial,
            PatchEvent.Done(File("out.apk"), listOf("Hide ads"), installed = false)
        )

        assertTrue("the run finished", state.finished)
        assertFalse("it must not be reported as installed", state.installed)
        assertTrue(
            "the log must say the APK was exported",
            state.logLines.any { it.contains("exported") }
        )
        assertEquals("Patched - saved to device storage", RunPresentation.sheetTitle(state))
    }

    @Test
    fun `done with an install is reported as installed`() {
        val state = RunPresentation.reduce(
            initial,
            PatchEvent.Done(File("out.apk"), listOf("Hide ads"), installed = true)
        )

        assertTrue(state.installed)
        assertEquals("Patched and installed", RunPresentation.sheetTitle(state))
    }

    @Test
    fun `a pending install is distinguished from a completed one`() {
        val state = RunPresentation.reduce(
            initial,
            PatchEvent.Done(File("out.apk"), listOf("Hide ads"), pendingInstall = true)
        )

        assertFalse("a pending install is not installed", state.installed)
        assertTrue(state.pendingInstall)
        assertEquals("Patched - confirm install", RunPresentation.sheetTitle(state))
    }

    @Test
    fun `a failure takes precedence in the sheet title`() {
        var state = RunPresentation.reduce(initial, PatchEvent.Error("boom"))
        state = RunPresentation.reduce(state, PatchEvent.Done(File("o.apk"), emptyList(), installed = true))
        assertEquals("Run failed", RunPresentation.sheetTitle(state))
    }

    @Test
    fun `an active run is titled as patching`() {
        assertEquals("Patching...", RunPresentation.sheetTitle(RunProgress(active = true)))
    }

    @Test
    fun `the log is bounded so a long run cannot exhaust memory`() {
        var state = initial
        repeat(RunPresentation.MAX_LOG_LINES * 3) { i ->
            state = RunPresentation.reduce(state, PatchEvent.Log("line $i"))
        }
        assertEquals(
            "the log must stay bounded",
            RunPresentation.MAX_LOG_LINES,
            state.logLines.size
        )
        // The newest lines must be the ones retained.
        assertTrue(state.logLines.last().contains("line ${RunPresentation.MAX_LOG_LINES * 3 - 1}"))
    }

    @Test
    fun `events fold in order`() {
        val state = RunPresentation.reduceAll(
            initial,
            listOf(
                PatchEvent.Stage(StageName.PATCHING),
                PatchEvent.PatchSucceeded("One"),
                PatchEvent.PatchSucceeded("Two"),
                PatchEvent.Done(File("o.apk"), listOf("One", "Two"), installed = true)
            )
        )

        assertEquals(2, state.patchProgress?.first)
        assertTrue(state.installed)
        assertNull("no failure expected", state.failed)
        assertNotNull(state.stage)
    }

    @Test
    fun `task progress is exposed for the progress bar`() {
        val state = RunPresentation.reduce(
            initial,
            PatchEvent.TaskProgress("INDEXING_CLASSES", done = 40, total = 100)
        )
        assertEquals("INDEXING_CLASSES", state.taskProgress?.first)
        assertEquals(40L to 100L, state.taskProgress?.second)
    }

    @Test
    fun `every trigger has a label`() {
        // The logs screen renders these; a missing entry would show a raw enum name.
        TriggerReason.entries.forEach { reason ->
            assertNotNull(
                "no label for $reason",
                RunPresentation.triggerLabels[reason]
            )
        }
    }
}
