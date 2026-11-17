package dev.morphexposed.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.morphexposed.MorpheXposedApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Turns package install/update/removal events into patch runs.
 *
 * There are two sources, and they are not interchangeable:
 *
 *  * **Platform broadcasts** (`PACKAGE_ADDED`/`REPLACED`/`REMOVED`). Android 8+ does not deliver
 *    these to a manifest-declared receiver of an ordinary app, so on a modern device this source
 *    is usually silent. It is still implemented because it costs nothing and works where it does.
 *  * **The LSPosed events module**, which forwards the same events from `system_server`. This is
 *    the source that actually fires on current Android. It is optional: the scheduled check is
 *    the guaranteed path, so the user can switch it off without losing automation.
 *
 * The receiver only decides *what* happened. Whether to patch is `AutoPatchPolicy`'s job, in the
 * coordinator, so the user's switches are evaluated in one tested place.
 */
class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = eventOf(context, intent) ?: return

        val app = context.applicationContext as? MorpheXposedApp ?: return
        Log.i(TAG, "Package event ${event.kind} for ${event.packageName}")

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Honour the user switch for events that came from the module. Without this the
                // setting was stored, displayed and never acted on, which made it a setting that
                // did nothing. Platform broadcasts are deliberately unaffected: they still work
                // when the bridge is off, which is why the bridge is optional.
                if (event.fromBridge && !app.settings.snapshot().lsposedBridgeEnabled) {
                    Log.i(TAG, "Bridge event ignored: the events bridge is switched off")
                    return@launch
                }
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

    /**
     * Parses an intent into an event, recording bridge reception as a side effect.
     *
     * Extracted from [onReceive] so the routing can be tested without a live database and
     * without waiting on a coroutine. The bridge is identified by its action rather than a data
     * URI, because its intent is not a package broadcast and carries no scheme.
     */
    internal fun eventOf(context: Context, intent: Intent): PackageEvent? {
        val action = intent.action ?: return null

        val event = when (action) {
            ACTION_LSPOSED_EVENT -> {
                val bridged = fromBridge(intent) ?: return null
                // Recorded even when the bridge is switched off: it is the only trustworthy
                // evidence the module is active, and the settings screen must be able to say so.
                BridgeObservations.record(bridged.kind.name)
                bridged.copy(fromBridge = true)
            }

            Intent.ACTION_PACKAGE_ADDED -> {
                val pkg = packageFromData(intent) ?: return null
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                PackageEvent(
                    packageName = pkg,
                    kind = if (replacing) Kind.UPDATED else Kind.INSTALLED,
                    replacing = replacing
                )
            }

            Intent.ACTION_PACKAGE_REPLACED -> {
                val pkg = packageFromData(intent) ?: return null
                PackageEvent(pkg, Kind.UPDATED, replacing = true)
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                val pkg = packageFromData(intent) ?: return null
                PackageEvent(
                    packageName = pkg,
                    kind = Kind.REMOVED,
                    replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                )
            }

            else -> return null
        }

        // Never act on ourselves.
        if (event.packageName == context.packageName) return null
        return event
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

    private suspend fun handle(app: MorpheXposedApp, packageName: String, isUpdate: Boolean) {
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
        val replacing: Boolean,
        /** True when this arrived from the LSPosed module rather than the platform. */
        val fromBridge: Boolean = false
    )

    companion object {
        private const val TAG = "MorphXPackageReceiver"

        /**
         * Broadcast sent by the MorpheXposed Events LSPosed module.
         *
         * The string values here are a contract with that module: `EventsModule.ACTION_EVENT`,
         * `EXTRA_PACKAGE` and `EXTRA_EVENT` must stay identical.
         */
        const val ACTION_LSPOSED_EVENT = "dev.morphexposed.LSPOSED_PACKAGE_EVENT"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_EVENT = "event"

        /**
         * Signature-level permission guarding the bridge. The events module is signed with the
         * same key as this app, so it holds it; no other app can.
         */
        const val PERMISSION_BRIDGE = "dev.morphexposed.permission.BRIDGE"
    }
}
