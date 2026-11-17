package dev.morphexposed

import android.app.Application
import android.util.Log
import dev.morphexposed.automation.PatchCoordinator
import dev.morphexposed.automation.UpdateCheckWorker
import dev.morphexposed.data.db.MorphDatabase
import dev.morphexposed.data.prefs.SettingsStore
import dev.morphexposed.di.appModule
import dev.morphexposed.domain.source.PatchSourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin

class MorpheXposedApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: MorphDatabase get() = getKoin().get()
    val settings: SettingsStore get() = getKoin().get()
    val coordinator: PatchCoordinator get() = getKoin().get()
    val sourceRepository: PatchSourceRepository get() = getKoin().get()

    override fun onCreate() {
        super.onCreate()

        // Starting Koin twice throws. The Application can be recreated (and is, per test, under
        // instrumentation), so the start is guarded rather than assumed to be the first.
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidLogger()
                androidContext(this@MorpheXposedApp)
                modules(appModule)
            }
        }

        appScope.launch {
            // Each step is isolated. They were wrapped in a single runCatching, so a failure in
            // notification-channel setup would have skipped seeding the source *and* scheduling
            // the periodic check — silently disabling the automation path that is meant to be the
            // guaranteed one. Independent failures stay independent.
            step("notification channels") {
                getKoin().get<dev.morphexposed.automation.Notifications>().ensureChannels()
            }

            // Seed the official Morphe source on first run.
            step("seed sources") { sourceRepository.seedDefaults() }

            // Schedule or cancel the periodic check according to the user's settings.
            step("schedule update check") {
                val snap = settings.snapshot()
                if (snap.autoCheckForPatchUpdates) {
                    UpdateCheckWorker.schedule(this@MorpheXposedApp, snap.updateCheckIntervalHours)
                } else {
                    UpdateCheckWorker.cancel(this@MorpheXposedApp)
                }
            }
        }
    }

    /** Runs one startup step, logging rather than propagating a failure. */
    private inline fun step(name: String, block: () -> Unit) {
        runCatching { block() }.onFailure { Log.e(TAG, "Startup step '$name' failed", it) }
    }

    private companion object {
        const val TAG = "MorphXApp"
    }
}
