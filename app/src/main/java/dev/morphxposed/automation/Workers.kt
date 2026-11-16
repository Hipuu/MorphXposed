package dev.morphxposed.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.workDataOf
import dev.morphxposed.MorphXposedApp
import java.util.concurrent.TimeUnit

/**
 * Re-scans installed apps and refreshes patch sources on a schedule.
 *
 * This is the dependable signal source for automatic patching: it does not depend on package
 * broadcasts, which Android stops delivering to manifest receivers from 8.0 onward, nor on the
 * optional LSPosed accelerator being installed.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MorphXposedApp
        val coordinator = app.coordinator
        val settings = app.settings

        return runCatching {
            val snap = settings.snapshot()
            if (!snap.autoCheckForPatchUpdates) return Result.success()

            val count = coordinator.refreshInstalledApps()
            val summary = coordinator.checkForPatchUpdates(patchAfterwards = false)
            Result.success(
                workDataOf(
                    "supported_apps" to count,
                    "batch_total" to summary.total
                )
            )
        }.getOrElse { t ->
            android.util.Log.w(TAG, "Update check failed", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "MorphXUpdateWorker"
        const val NAME = "morphxposed_update_check"

        fun schedule(context: Context, intervalHours: Int) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                intervalHours.toLong().coerceIn(1, 168),
                TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        /** One-off check the user can trigger from the UI. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${NAME}_once",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }
}

/**
 * Patches a single app in the background, used when a supported app is installed or updated.
 *
 * Runs as expedited work where the platform allows it, so the user is not left waiting for a
 * scheduled slot after installing an app.
 */
class AutoPatchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MorphXposedApp
        val packageName = inputData.getString(KEY_PACKAGE) ?: return Result.failure()
        val reason = runCatching {
            TriggerReason.valueOf(inputData.getString(KEY_REASON) ?: TriggerReason.APP_INSTALLED.name)
        }.getOrDefault(TriggerReason.APP_INSTALLED)

        return runCatching {
            val summary = app.coordinator.runBatch(reason, packageFilter = packageName)
            Result.success(
                workDataOf(
                    "patched" to summary.patched.size,
                    "failed" to summary.failed.size
                )
            )
        }.getOrElse { t ->
            android.util.Log.w(TAG, "Auto patch failed for $packageName", t)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "MorphXAutoPatchWorker"
        private const val KEY_PACKAGE = "package"
        private const val KEY_REASON = "reason"
        private const val UNIQUE_PREFIX = "morphxposed_autopatch_"

        fun enqueue(context: Context, packageName: String, reason: TriggerReason) {
            val request = OneTimeWorkRequestBuilder<AutoPatchWorker>()
                .setInputData(
                    workDataOf(
                        KEY_PACKAGE to packageName,
                        KEY_REASON to reason.name
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + packageName,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
