package dev.morphxposed.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.morphxposed.MorphXposedApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reacts to app install, update and removal.
 *
 * `PACKAGE_ADDED` / `PACKAGE_REPLACED` are **not** delivered to manifest-declared receivers of
 * ordinary apps since Android 8.0, so this receiver only fires when it is invoked explicitly —
 * by the LSPosed events module, or by a foreground component. The periodic
 * [UpdateCheckWorker] is the guaranteed path, this is the low-latency one.
 */
class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        if (packageName == context.packageName) return

        val app = context.applicationContext as? MorphXposedApp ?: return
        val action = intent.action ?: return

        Log.i(TAG, "Package event $action for $packageName")

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    Intent.ACTION_PACKAGE_ADDED -> {
                        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        handle(app, packageName, replacing)
                    }

                    Intent.ACTION_PACKAGE_REPLACED -> handle(app, packageName, true)

                    Intent.ACTION_PACKAGE_REMOVED -> {
                        if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                            // Drop the row so a stale entry never triggers a run.
                            app.database.supportedAppDao().deleteByPackage(packageName)
                            Log.i(TAG, "Removed $packageName from the supported list")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to handle $action for $packageName", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(app: MorphXposedApp, packageName: String, isUpdate: Boolean) {
        // Re-scan so the row reflects the version just installed.
        val supported = app.sourceRepository.supportedPackages()
        if (packageName !in supported) return

        app.coordinator.refreshInstalledApps()

        val row = app.database.supportedAppDao().byPackage(packageName) ?: return
        val snap = app.settings.snapshot()

        val want = if (isUpdate) snap.autoPatchOnUpdate else snap.autoPatchOnInstall
        if (!want || !row.autoPatchEnabled) {
            Log.i(TAG, "Automatic patching disabled for $packageName; not queuing")
            return
        }

        AutoPatchWorker.enqueue(
            app,
            packageName,
            if (isUpdate) TriggerReason.APP_UPDATED else TriggerReason.APP_INSTALLED
        )
    }

    companion object {
        private const val TAG = "MorphXPackageReceiver"

        /**
         * Broadcast delivered by the LSPosed events module, which sees package changes inside
         * `system_server` and forwards them here in real time.
         */
        const val ACTION_LSPOSED_EVENT = "dev.morphxposed.LSPOSED_PACKAGE_EVENT"
        const val EXTRA_ACTION = "action"
    }
}
