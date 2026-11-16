package dev.morphxposed

import android.app.Application
import android.util.Log
import dev.morphxposed.automation.PatchCoordinator
import dev.morphxposed.automation.UpdateCheckWorker
import dev.morphxposed.data.db.MorphDatabase
import dev.morphxposed.data.prefs.SettingsStore
import dev.morphxposed.di.appModule
import dev.morphxposed.domain.source.PatchSourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin

class MorphXposedApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: MorphDatabase get() = getKoin().get()
    val settings: SettingsStore get() = getKoin().get()
    val coordinator: PatchCoordinator get() = getKoin().get()
    val sourceRepository: PatchSourceRepository get() = getKoin().get()

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger()
            androidContext(this@MorphXposedApp)
            modules(appModule)
        }

        appScope.launch {
            runCatching {
                val notifications = getKoin().get<dev.morphxposed.automation.Notifications>()
                notifications.ensureChannels()

                // Seed the official Morphe source on first run.
                sourceRepository.seedDefaults()

                // Schedule or cancel the periodic check according to the user's settings.
                val snap = settings.snapshot()
                if (snap.autoCheckForPatchUpdates) {
                    UpdateCheckWorker.schedule(this@MorphXposedApp, snap.updateCheckIntervalHours)
                } else {
                    UpdateCheckWorker.cancel(this@MorphXposedApp)
                }
            }.onFailure { Log.e(TAG, "Startup failed", it) }
        }
    }

    private companion object {
        const val TAG = "MorphXApp"
    }
}
