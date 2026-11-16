package dev.morphxposed.ui.viewmodel

import dev.morphxposed.automation.BatchSummary
import dev.morphxposed.automation.TriggerReason
import dev.morphxposed.domain.patch.PatchEvent
import dev.morphxposed.domain.patch.StageName

/** Live state of a patch run, for the progress sheet. */
data class RunProgress(
    val active: Boolean = false,
    val packageName: String? = null,
    val stage: String = "",
    val patchProgress: Pair<Int, Int>? = null,
    val taskProgress: Pair<String, Pair<Long, Long>>? = null,
    val logLines: List<String> = emptyList(),
    /** A patched APK was produced. */
    val finished: Boolean = false,
    /** The patched APK reached the device. */
    val installed: Boolean = false,
    /** The platform is waiting for the user to confirm the install. */
    val pendingInstall: Boolean = false,
    val failed: String? = null
)

/**
 * Turns domain results into the text and state the UI shows.
 *
 * Extracted from the ViewModel so it can be tested without an Application, a database, or a
 * running coroutine scope. These two functions decide every user-facing message and the whole
 * contents of the progress sheet, so they are the most-looked-at logic in the app and the
 * cheapest to get subtly wrong.
 */
object RunPresentation {

    /** How many log lines the progress sheet keeps. */
    const val MAX_LOG_LINES = 400

    /**
     * One line summarising a batch, shown in the snackbar after a run.
     *
     * A failure always wins over a success, because a user who sees "Patched 2 apps" while a
     * third failed would reasonably assume everything worked.
     */
    fun summarise(summary: BatchSummary): String = when {
        summary.total == 0 -> "Nothing to patch."
        summary.failed.isNotEmpty() ->
            "${summary.failed.size} failed: ${summary.failed.first().second}"
        summary.patched.isNotEmpty() ->
            "Patched ${summary.patched.size} app(s)."
        else -> summary.skipped.firstOrNull()?.second ?: "Nothing to patch."
    }

    /**
     * Folds a [PatchEvent] into the observable run state.
     *
     * `finished` means the run produced a patched APK; it is not the same as "installed", which
     * is what the sheet's title and the coordinator both key off.
     */
    fun reduce(state: RunProgress, event: PatchEvent): RunProgress = when (event) {
        is PatchEvent.Stage -> state.copy(
            stage = event.stage.name + if (event.detail.isNotEmpty()) ": ${event.detail}" else ""
        )

        is PatchEvent.TaskProgress -> state.copy(
            taskProgress = event.task to (event.done to event.total)
        )

        is PatchEvent.PatchExecuting -> state.append("> ${event.name}")

        is PatchEvent.PatchSucceeded -> state.copy(
            patchProgress = (state.patchProgress?.first ?: 0).plus(1) to
                (state.patchProgress?.second ?: 0),
            logLines = appendLines(state.logLines, "OK ${event.name}")
        )

        is PatchEvent.PatchFailed -> state.append("FAILED ${event.name}: ${event.message}")

        is PatchEvent.Log -> state.append(event.line)

        is PatchEvent.Error -> state.copy(
            failed = event.message,
            logLines = appendLines(state.logLines, "ERROR: ${event.message}")
        )

        is PatchEvent.Done -> state.copy(
            finished = true,
            installed = event.installed,
            pendingInstall = event.pendingInstall,
            logLines = appendLines(
                state.logLines,
                when {
                    event.installed -> "Done: patched and installed."
                    event.pendingInstall -> "Done: patched; waiting for install confirmation."
                    else -> "Done: patched. APK exported, not installed."
                }
            )
        )
    }

    /** Applies a sequence of events in order. */
    fun reduceAll(state: RunProgress, events: Iterable<PatchEvent>): RunProgress =
        events.fold(state) { acc, event -> reduce(acc, event) }

    /**
     * Title for the progress sheet, which must distinguish patched from installed.
     *
     * The outcome flags are checked before [RunProgress.active]: a run that has emitted `Done`
     * or `Error` is over as far as the user is concerned, even if the caller has not yet
     * cleared the `active` flag. Checking `active` first left the sheet saying "Patching..."
     * after the run had already finished.
     */
    fun sheetTitle(progress: RunProgress): String = when {
        progress.failed != null -> "Run failed"
        progress.installed -> "Patched and installed"
        progress.pendingInstall -> "Patched - confirm install"
        progress.finished -> "Patched - saved to device storage"
        else -> "Patching..."
    }

    private fun RunProgress.append(line: String) = copy(logLines = appendLines(logLines, line))

    private fun appendLines(existing: List<String>, line: String): List<String> =
        (existing + line).takeLast(MAX_LOG_LINES)

    /** Convenience for building a state in tests and previews. */
    fun initial(packageName: String? = null) = RunProgress(active = true, packageName = packageName)

    /** A stage name for a run that has not started reporting yet. */
    val STARTING_STAGE: String = StageName.PREPARING.name

    /** Trigger labels, used by the logs screen. */
    val triggerLabels: Map<TriggerReason, String> = mapOf(
        TriggerReason.MANUAL to "Manual",
        TriggerReason.APP_INSTALLED to "App installed",
        TriggerReason.APP_UPDATED to "App updated",
        TriggerReason.PATCHES_UPDATED to "Patches updated",
        TriggerReason.PERIODIC to "Scheduled"
    )
}
