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
 * Two sources feed this receiver, and they are deliberately handled differently:
 *
 *  * **Platform broadcasts** (`PACKAGE_ADDED` / `PACKAGE_REPLACED` / `PACKAGE_REMOVED`) carry a
 *    `package:` data URI. Android 8.0+ does not deliver these to manifest receivers of ordinary
 *    apps, so in practice this path only fires for the app's own packages or on older releases.
 *  * **The LSPosed bridge** ([ACTION_LSPOSED_EVENT]) carries the package name in an extra and no
 *    data URI. It comes from the optional events module, which observes `PackageManagerService`
 *    inside `system_server` and is the only reliable real-time source on modern Android.
 *
 * The bridge is protected by a signature-level permission, so only an APK signed with this app's
 * own key (in practice, the events module) can drive it. Anything else is rejected by the
 * platform before `onReceive` runs.
 */
class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // Route by source. The bridge is identified by its action, not by a data URI, because the
        // intent is not a package broadcast and carries no scheme.
        val event: PackageEvent = when (action) {
            ACTION_LSPOSED_EVENT -> fromBridge(intent) ?: return
            Intent.ACTION_PACKAGE_ADDED -> {
                val pkg = packageFromData(intent) ?: return
                PackageEvent(
                    packageName = pkg,
                    kind = if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        Kind.UPDATED
                    } else {
                        Kind.INSTALLED
                    },
                    replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                )
            }

            Intent.ACTION_PACKAGE_REPLACED -> {
                val pkg = packageFromData(intent) ?: return
                PackageEvent(pkg, Kind.UPDATED, replacing = true)
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                val pkg = packageFromData(intent) ?: return
                PackageEvent(
                    packageName = pkg,
                    kind = Kind.REMOVED,
                    replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                )
            }

            else -> return
        }

        // Never act on ourselves.
        if (event.packageName == context.packageName) return

        val app = context.applicationContext as? MorphXposedApp ?: return
        Log.i(TAG, "Package event ${event.kind} for ${event.packageName}")

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (event.kind) {
                    Kind.REMOVED -> {
                        if (!event.replacing) {
                            // Drop the row so a stale entry never triggers a run.
                            app.database.supportedAppDao().deleteByPackage(event.packageName)
                            Log.i(TAG, "Removed ${event.packageName} from the supported list")
                        }
                    }

                    Kind.INSTALLED, Kind.UPDATED, Kind.CHANGED -> {
                        handle(app, event.packageName, event.kind == Kind.UPDATED)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to handle ${event.kind} for ${event.packageName}", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun packageFromData(intent: Intent): String? =
        intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() }

    /** Reads the bridge intent the events module sends, or null when it is unusable. */
    private fun fromBridge(intent: Intent): PackageEvent? {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)?.takeIf { it.isNotBlank() } ?: return null
        val kind = when (intent.getStringExtra(EXTRA_EVENT)?.lowercase()) {
            "installed" -> Kind.INSTALLED
            "updated" -> Kind.UPDATED
            "removed" -> Kind.REMOVED
            "changed" -> Kind.CHANGED
            else -> return null
        }
        return PackageEvent(pkg, kind, replacing = kind == Kind.UPDATED)
    }

    private suspend fun handle(app: MorphXposedApp, packageName: String, isUpdate: Boolean) {
        // Re-scan so the row reflects the version just installed.
        val supported = app.sourceRepository.supportedPackages()
        if (packageName !in supported) {
            Log.i(TAG, "$packageName is not supported by any source; ignoring")
            return
        }

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

    /** What happened to a package, normalized across both event sources. */
    enum class Kind { INSTALLED, UPDATED, REMOVED, CHANGED }

    data class PackageEvent(
        val packageName: String,
        val kind: Kind,
        val replacing: Boolean
    )

    companion object {
        private const val TAG = "MorphXPackageReceiver"

        /**
         * Broadcast sent by the MorphXposed Events LSPosed module.
         *
         * The string values here are a contract with that module: `EventsModule.ACTION_EVENT`,
         * `EXTRA_PACKAGE` and `EXTRA_EVENT` must stay identical.
         */
        const val ACTION_LSPOSED_EVENT = "dev.morphxposed.LSPOSED_PACKAGE_EVENT"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_EVENT = "event"

        /**
         * Signature-level permission guarding the bridge. The events module is signed with the
         * same key as this app, so it holds it; no other app can.
         */
        const val PERMISSION_BRIDGE = "dev.morphxposed.permission.BRIDGE"
    }
}
