package dev.morphxposed.automation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/**
 * Reports what can actually be determined about the LSPosed events bridge.
 *
 * ## What is detectable, and what is not
 *
 * A module **cannot** read its own activation state. LSPosed offers no API for that, and any
 * attempt to infer it from a file's existence would be a guess presented as a fact. So this
 * reports only what the platform will answer:
 *
 *  * whether an LSPosed manager app is installed, which means LSPosed (or a fork such as
 *    LSPatch/Magisk's built-in) is present on the device;
 *  * whether the MorphXposed Events APK is installed;
 *  * whether the main app holds the bridge permission, which it declares itself, so this is
 *    always true and is not evidence of anything;
 *  * whether the bridge has ever delivered an event, which is the only trustworthy signal that
 *    the module is actually active — and it can only be learned by waiting.
 *
 * The last point is the important one: the honest answer to "is the module enabled" is
 * *"unknown until it reports something"*. The UI says exactly that rather than showing a
 * green tick that means nothing.
 */
class LsposedStatus(private val context: Context) {

    /** LSPosed's manager package, plus the forks that ship an equivalent UI. */
    private val managerPackages = listOf(
        "org.lsposed.manager",
        "org.lsposed.lspatch",
        "io.github.lsposed.manager"
    )

    /** The companion APK's package, with and without the debug suffix. */
    private val modulePackages = listOf(
        "dev.morphxposed.events",
        "dev.morphxposed.events.debug"
    )

    data class State(
        /** An LSPosed manager app is installed. */
        val managerInstalled: Boolean,
        /** The manager's version name, when it could be read. */
        val managerVersion: String?,
        /** The MorphXposed Events APK is installed. */
        val moduleInstalled: Boolean,
        /** The module's version name, when it could be read. */
        val moduleVersion: String?,
        /**
         * An event has been delivered over the bridge at least once since the app started.
         *
         * This is the only reliable proof the module is active: LSPosed cannot be asked.
         */
        val bridgeObserved: Boolean
    ) {
        /** A one-line summary suitable for a settings row. */
        fun describe(): String = when {
            bridgeObserved -> "Events module active - events are arriving"
            !managerInstalled -> "No LSPosed detected - the module cannot be used"
            !moduleInstalled -> "LSPosed found; install MorphXposed Events to enable real-time events"
            else -> "Module installed. Activation cannot be read; enable it in LSPosed Manager " +
                "and scope it to System Framework."
        }
    }

    /** Reads the current state. Never throws: package lookups fail on a locked-down device. */
    fun read(bridgeObserved: Boolean = BridgeObservations.observed): State {
        val manager = firstInstalled(managerPackages)
        val module = firstInstalled(modulePackages)
        return State(
            managerInstalled = manager != null,
            managerVersion = manager?.second,
            moduleInstalled = module != null,
            moduleVersion = module?.second,
            bridgeObserved = bridgeObserved
        )
    }

    /** Returns the package name and version of the first installed candidate. */
    private fun firstInstalled(candidates: List<String>): Pair<String, String?>? {
        for (pkg in candidates) {
            val version = runCatching {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(pkg, 0)
                info.versionName
            }.getOrNull() ?: continue
            return pkg to version
        }
        return null
    }

    /** Whether the device resolves the package at all, regardless of version visibility. */
    private fun isInstalled(packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    companion object {
        /** Minimum LSPosed version the module's `xposedminversion` targets. */
        const val MIN_XPOSED_API = 82

        /** True when the platform is new enough for LSPosed at all. */
        fun isPlatformSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}

/**
 * Remembers whether the events bridge has ever delivered anything.
 *
 * Set by [PackageChangeReceiver] when a bridge intent arrives. This is deliberately a simple
 * in-memory flag rather than persisted state: it answers "has it worked since launch", which is
 * what a settings row needs, and persisting it would let a stale `true` outlive an uninstall.
 */
object BridgeObservations {
    @Volatile
    var observed: Boolean = false
        private set

    /** The last event received, for troubleshooting. */
    @Volatile
    var lastEvent: String? = null
        private set

    fun record(event: String) {
        observed = true
        lastEvent = event
    }

    /** Clears the flags; used by tests and by a manual refresh. */
    fun reset() {
        observed = false
        lastEvent = null
    }
}
