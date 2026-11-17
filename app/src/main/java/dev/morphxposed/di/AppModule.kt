package dev.morphxposed.di

import androidx.room.Room
import dev.morphxposed.automation.Notifications
import dev.morphxposed.automation.PatchCoordinator
import dev.morphxposed.data.db.MorphDatabase
import dev.morphxposed.data.prefs.SettingsStore
import dev.morphxposed.domain.apps.AppScanner
import dev.morphxposed.domain.install.RootMountInstaller
import dev.morphxposed.domain.install.SessionInstaller
import dev.morphxposed.domain.patch.PatchEngine
import dev.morphxposed.domain.patch.PatchOrchestrator
import dev.morphxposed.domain.source.BundleInspector
import dev.morphxposed.domain.source.PatchSourceClient
import dev.morphxposed.domain.source.PatchSourceRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Single dependency graph. Every collaborator is a singleton because they either hold native
 * resources (database, HTTP client) or coordinate shared state (the patcher).
 */
val appModule: Module = module {

    single { SettingsStore(androidContext()) }

    single {
        Room.databaseBuilder(androidContext(), MorphDatabase::class.java, "morphxposed.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    single { get<MorphDatabase>().patchSourceDao() }
    single { get<MorphDatabase>().supportedAppDao() }
    single { get<MorphDatabase>().patchRunDao() }
    single { get<MorphDatabase>().apkBackupDao() }

    single { Notifications(androidContext()) }

    single { AppScanner(androidContext()) }
    single { BundleInspector() }
    single { PatchSourceClient(androidContext(), get()) }
    single { PatchSourceRepository(get(), get(), get()) }

    single { PatchEngine() }
    single { dev.morphxposed.domain.backup.RollbackService(androidContext(), get(), get(), get()) }
    single { SessionInstaller(androidContext()) }
    single { RootMountInstaller(androidContext()) }

    single { PatchOrchestrator(androidContext(), get(), get(), get(), get(), get(), get(), get()) }

    single {
        PatchCoordinator(
            context = androidContext(),
            db = get(),
            settings = get(),
            scanner = get(),
            orchestrator = get(),
            sourceRepository = get(),
            notifications = get()
        )
    }
}
