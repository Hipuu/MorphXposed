package dev.morphxposed.automation

import android.content.Context
import android.util.Log
import dev.morphxposed.data.db.MorphDatabase
import dev.morphxposed.data.prefs.SettingsStore
import dev.morphxposed.domain.apps.AppScanner
import dev.morphxposed.domain.patch.PatchEvent
import dev.morphxposed.domain.patch.PatchOrchestrator
import dev.morphxposed.domain.source.PatchSourceRepository
import dev.morphxposed.domain.source.RefreshResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/** What caused a batch to run; recorded so the Logs screen can explain itself. */
enum class TriggerReason { MANUAL, APP_INSTALLED, APP_UPDATED, PATCHES_UPDATED, PERIODIC }

data class BatchSummary(
    val reason: TriggerReason,
    val patched: List<String>,
    val failed: List<Pair<String, String>>,
    val skipped: List<Pair<String, String>>
) {
    val total: Int get() = patched.size + failed.size + skipped.size
    val hadWork: Boolean get() = total > 0
}

/**
 * Central coordinator for automatic patching.
 *
 * Responsibilities are deliberately narrow: decide **which** apps are eligible, then run the
 * orchestrator for each one sequentially. Patching is never parallelised, because each run is
 * memory-heavy and holds the patcher's own working state.
 *
 * A single [Mutex] guarantees no two batches overlap, whichever trigger fired.
 */
class PatchCoordinator(
    private val context: Context,
    private val db: MorphDatabase,
    private val settings: SettingsStore,
    private val scanner: AppScanner,
    private val orchestrator: PatchOrchestrator,
    private val sourceRepository: PatchSourceRepository,
    private val notifications: Notifications
) {

    private val batchLock = Mutex()

    /** The run currently emitting events, so the UI can attach to it. */
    private val currentEvents = AtomicReference<((PatchEvent) -> Unit)?>(null)

    fun observeCurrentRun(sink: (PatchEvent) -> Unit) {
        currentEvents.set(sink)
    }

    fun clearCurrentRun() {
        currentEvents.set(null)
    }

    /**
     * Patches every eligible app.
     *
     * @param packageFilter when non-null, only this package is considered.
     * @param force when true, apps already patched with the current bundle and version are redone.
     */
    suspend fun runBatch(
        reason: TriggerReason,
        packageFilter: String? = null,
        force: Boolean = false
    ): BatchSummary = batchLock.withLock {
        val patched = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        val skipped = mutableListOf<Pair<String, String>>()
        val snap = settings.snapshot()

        val candidates = if (packageFilter != null) {
            listOfNotNull(db.supportedAppDao().byPackage(packageFilter))
        } else {
            db.supportedAppDao().all()
        }

        for (app in candidates) {
            if (app.hidden) {
                skipped += app.label to "Hidden"
                continue
            }

            // Whether this trigger is allowed to touch this app. The rule lives in
            // AutoPatchPolicy so every combination is tested; this call site only reports it.
            val decision = AutoPatchPolicy.decide(app, reason, snap)
            if (!decision.isAllowed) {
                skipped += app.label to decision.describe()
                continue
            }

            val result = runOne(app.packageName, force = force)
            when (result) {
                is RunOne.Patched -> {
                    patched += app.label
                    if (snap.notifyOnSuccess) {
                        notifications.patchSucceeded(app.label, result.patches, result.installed)
                    }
                }

                is RunOne.Failed -> {
                    failed += app.label to result.message
                    if (snap.notifyOnFailure) {
                        notifications.patchFailed(app.label, result.message)
                    }
                }

                is RunOne.Skipped -> skipped += app.label to result.reason
            }
        }

        notifications.cancelOngoing()

        BatchSummary(reason, patched, failed, skipped).also {
            Log.i(
                TAG,
                "Batch ($reason): ${it.patched.size} patched, ${it.failed.size} failed, " +
                    "${it.skipped.size} skipped"
            )
        }
    }

    private suspend fun runOne(packageName: String, force: Boolean): RunOne {
        var applied = 0
        var installed = false
        var failure: String? = null
        var skippedReason: String? = null

        orchestrator.run(packageName = packageName, forceInstall = force).collect { event ->
            currentEvents.get()?.invoke(event)
            when (event) {
                is PatchEvent.PatchSucceeded -> applied++
                is PatchEvent.Error -> failure = event.message
                is PatchEvent.Log -> if (event.line.startsWith("Nothing to do")) {
                    skippedReason = event.line
                }
                // Whether the APK reached the device is what `installed` reports; a run that
                // only exported an APK must not be announced as an install.
                is PatchEvent.Done -> installed = event.installed
                else -> Unit
            }
        }

        return when {
            failure != null -> RunOne.Failed(failure!!)
            skippedReason != null -> RunOne.Skipped(skippedReason!!)
            applied > 0 -> RunOne.Patched(applied, installed)
            else -> RunOne.Skipped("No patches applied")
        }
    }

    /**
     * Refreshes patch sources, optionally patching apps whose bundle changed.
     */
    suspend fun checkForPatchUpdates(patchAfterwards: Boolean): BatchSummary {
        var updated = 0
        val updatedTags = mutableListOf<String>()

        sourceRepository.refreshAll().collect { (source, result) ->
            if (result is RefreshResult.Updated) {
                updated++
                updatedTags += "${source.displayName} ${result.tag}"
            }
        }

        val snap = settings.snapshot()
        if (updated > 0) {
            settings.markAutoCheckNow()
            if (snap.notifyOnSuccess) {
                notifications.updatesAvailable(updated, updatedTags.joinToString(", "))
            }
        }

        return if (patchAfterwards && updated > 0) {
            runBatch(TriggerReason.PATCHES_UPDATED)
        } else {
            BatchSummary(TriggerReason.PERIODIC, emptyList(), emptyList(), emptyList())
        }
    }

    /** Re-scans installed apps and reconciles the database. */
    suspend fun refreshInstalledApps(): Int {
        sourceRepository.seedDefaults()
        val supported = sourceRepository.supportedPackages()
        val scan = scanner.scan(supported)
        db.supportedAppDao().reconcileDiscovered(scan.apps, System.currentTimeMillis())
        return scan.apps.size
    }

    sealed interface RunOne {
        data class Patched(val patches: Int, val installed: Boolean) : RunOne
        data class Failed(val message: String) : RunOne
        data class Skipped(val reason: String) : RunOne
    }

    private companion object {
        const val TAG = "MorphXCoordinator"
    }
}
