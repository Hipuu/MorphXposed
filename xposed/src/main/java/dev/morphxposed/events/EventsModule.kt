package dev.morphxposed.events

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed module: forwards package install/update/removal events to MorphXposed in real time.
 *
 * ## Why this exists
 *
 * `PACKAGE_ADDED` and `PACKAGE_REPLACED` are not delivered to manifest-declared receivers of
 * ordinary apps since Android 8.0 (the implicit-broadcast restrictions). Without a privileged
 * observer, an app manager can only *poll*.
 *
 * This module hooks `PackageManagerService` inside `system_server` and rebroadcasts the events to
 * the MorphXposed app, which then decides whether the changed package is supported and whether
 * automation is enabled for it.
 *
 * ## What it deliberately does not do
 *
 * It does **not** patch anything, does **not** touch other apps' code, and does **not** carry the
 * patcher. Morphe patches are static APK rewrites; they cannot be applied from a hook, so this
 * module only reports events. If it is not installed, MorphXposed still works — its scheduled
 * check is the guaranteed path.
 */
class EventsModule : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only system_server hosts PackageManagerService.
        if (lpparam.packageName != "android") return

        runCatching { hookPackageManagerService(lpparam) }
            .onFailure { Log.w(TAG, "Could not hook PackageManagerService: ${it.message}") }
    }

    private fun hookPackageManagerService(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClass(
            "com.android.server.pm.PackageManagerService",
            lpparam.classLoader
        )

        // Each platform entry point is hooked exactly once. An earlier version hooked
        // `sendPackageAddedForNewUsers` twice with different event labels, so a single install
        // produced two bridged events, one claiming "installed" and one "updated". The host
        // treated both as facts, which doubled the work and could patch the same app twice.
        //
        // These are the internal entry points the platform itself uses after an install or
        // update completes. Signatures vary between Android releases, so each is hooked
        // independently and a missing one is not fatal.
        hookOnce(clazz, "sendPackageAddedForNewUsers", EVENT_INSTALLED)
        hookOnce(clazz, "sendPackageChangedBroadcast", EVENT_CHANGED)
        hookOnce(clazz, "sendPackageRemovedBroadcast", EVENT_REMOVED)

        Log.i(TAG, "MorphXposed events module active in system_server")
    }

    /**
     * Hooks every overload of [methodName] once, reporting [event].
     *
     * A method that does not exist on this Android version is skipped rather than treated as an
     * error, because the internal API differs across releases.
     */
    private fun hookOnce(clazz: Class<*>, methodName: String, event: String) {
        val methods = clazz.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) {
            Log.d(TAG, "No $methodName on this Android version; skipping")
            return
        }
        methods.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pkg = packageNameArgument(param.args) ?: return
                        notifyHost(pkg, event)
                    }
                })
            }.onFailure { Log.d(TAG, "Could not hook ${method.name}: ${it.message}") }
        }
    }

    /**
     * Broadcasts to the main app. Runs inside `system_server`, so the work must be offloaded to a
     * handler and must never block; a failure here must not affect the platform.
     */
    private fun notifyHost(packageName: String, event: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val ctx = systemContext() ?: return@runCatching

                // The intent is explicit (setPackage) and carries the app's signature permission,
                // so only the real MorphXposed can receive it.
                val intent = Intent(ACTION_EVENT).apply {
                    setPackage(HOST_PACKAGE)
                    putExtra(EXTRA_PACKAGE, packageName)
                    putExtra(EXTRA_EVENT, event)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                ctx.sendBroadcast(intent, PERMISSION_BRIDGE)
                Log.d(TAG, "Forwarded $event for $packageName")
            }.onFailure { Log.d(TAG, "Forward failed: ${it.message}") }
        }
    }

    /** Resolves the `android` package context, which is always available inside system_server. */
    private fun systemContext(): Context? = runCatching {
        val activityThread = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null),
            "currentActivityThread"
        )
        XposedHelpers.callMethod(activityThread, "getSystemContext") as? Context
    }.getOrNull()

    /** Delegates to [PackageNames], which has no Xposed dependency and is unit-tested. */
    private fun packageNameArgument(args: Array<Any?>?): String? = PackageNames.fromArguments(args)
    private companion object {
        const val TAG = "MorphXEvents"

        /** The main app's package; the debug variant appends `.debug`. */
        const val HOST_PACKAGE = "dev.morphxposed"

        /**
         * Contract with [dev.morphxposed.automation.PackageChangeReceiver]: the action, both
         * extra names, and the signature permission must stay identical on both sides.
         */
        const val ACTION_EVENT = "dev.morphxposed.LSPOSED_PACKAGE_EVENT"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_EVENT = "event"
        const val PERMISSION_BRIDGE = "dev.morphxposed.permission.BRIDGE"

        const val EVENT_INSTALLED = "installed"
        // "updated" is still accepted by the host; the module no longer emits it because
        // sendPackageAddedForNewUsers cannot distinguish an install from a replacement.
        const val EVENT_UPDATED = "updated"
        const val EVENT_REMOVED = "removed"
        const val EVENT_CHANGED = "changed"
    }
}
