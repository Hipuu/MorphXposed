package dev.morphexposed.di

import androidx.room.Room
import dev.morphexposed.automation.Notifications
import dev.morphexposed.automation.PatchCoordinator
import dev.morphexposed.data.db.MorphDatabase
import dev.morphexposed.data.prefs.SettingsStore
import dev.morphexposed.domain.apps.AppScanner
import dev.morphexposed.domain.install.RootMountInstaller
import dev.morphexposed.domain.install.SessionInstaller
import dev.morphexposed.domain.patch.PatchEngine
import dev.morphexposed.domain.patch.PatchOrchestrator
import dev.morphexposed.domain.source.BundleInspector
import dev.morphexposed.domain.source.PatchSourceClient
import dev.morphexposed.domain.source.PatchSourceRepository
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
        Room.databaseBuilder(androidContext(), MorphDatabase::class.java, "morphexposed.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    single { get<MorphDatabase>().patchSourceDao() }
    single { get<MorphDatabase>().supportedAppDao() }
    single { get<MorphDatabase>().patchRunDao() }
    single { get<MorphDatabase>().apkBackupDao() }

    single { Notifications(androidContext()) }
    single { dev.morphexposed.automation.PermissionStatus(androidContext()) }

    single { AppScanner(androidContext()) }
    single { BundleInspector() }
    single { PatchSourceClient(androidContext(), get()) }
    single { PatchSourceRepository(get(), get(), get()) }

    single { PatchEngine() }
    single { dev.morphexposed.domain.backup.RollbackService(androidContext(), get(), get(), get()) }
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
