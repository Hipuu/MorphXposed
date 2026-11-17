package dev.morphexposed.automation

import dev.morphexposed.data.prefs.SettingsStore

/**
 * The decisions a background check makes, separated from the work it then performs.
 *
 * `UpdateCheckWorker.doWork` reads the settings and decides whether there is anything to do
 * before it touches the network or the patcher. That decision is what a scheduler actually
 * depends on — running the check when the user switched it off wastes battery and data — and it
 * is reachable only through a `WorkManager` worker, so it was untested.
 */
object UpdateCheckPlan {

    /** What a scheduled check should do. */
    sealed interface Action {
        /** The user has automatic checking off; do nothing at all. */
        data object DoNothing : Action

        /** Re-scan installed apps and refresh patch sources. */
        data object ScanAndRefresh : Action
    }

    /** Decides whether a scheduled check should run. */
    fun decide(snapshot: SettingsStore.Snapshot): Action =
        if (snapshot.autoCheckForPatchUpdates) Action.ScanAndRefresh else Action.DoNothing

    /**
     * Whether a failed check should be retried.
     *
     * Bounded: a check that keeps failing (no network, GitHub down) must not retry forever and
     * drain the battery. After [MAX_ATTEMPTS] the run gives up and waits for the next schedule.
     */
    const val MAX_ATTEMPTS = 3

    fun shouldRetry(attemptCount: Int): Boolean = attemptCount < MAX_ATTEMPTS
}
