package dev.morphexposed.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.java.KoinJavaComponent.getKoin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.KoinApplicationAlreadyStartedException
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Resolves the whole dependency graph.
 *
 * A DI graph is the one thing that can be completely broken while every unit test passes,
 * because each collaborator is constructed by hand in tests. A missing or mis-typed binding
 * only surfaces at runtime, on the device, when that screen is first opened. This boots the
 * real graph and asks for every type the app asks for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DependencyGraphTest {

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        try {
            startKoin {
                androidContext(context)
                // The database is built in-memory here so the test does not touch disk, but
                // every other binding is the real one.
                modules(
                    module {
                        single {
                            androidx.room.Room.inMemoryDatabaseBuilder(
                                context, MorphDatabase::class.java
                            ).allowMainThreadQueries().build()
                        }
                    },
                    appModule
                )
            }
        } catch (alreadyStarted: KoinApplicationAlreadyStartedException) {
            // Another test in the same JVM already started the graph; reuse it.
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    /** Resolves exactly as production code does: through the running Koin instance. */
    private inline fun <reified T : Any> resolve(): T = getKoin().get()

    @Test
    fun `every collaborator the app resolves is resolvable`() {
        // These are exactly the types the app asks Koin for.
        assertNotNull(resolve<MorphDatabase>())
        assertNotNull(resolve<SettingsStore>())
        assertNotNull(resolve<PatchCoordinator>())
        assertNotNull(resolve<PatchSourceRepository>())
        assertNotNull(resolve<Notifications>())
    }

    @Test
    fun `the full dependency chain constructs without throwing`() {
        // PatchOrchestrator needs eight collaborators; if any one is missing or ambiguous this
        // throws here rather than on the patching screen.
        val orchestrator = resolve<PatchOrchestrator>()
        assertNotNull(orchestrator)

        val coordinator = resolve<PatchCoordinator>()
        assertNotNull(coordinator)
    }

    @Test
    fun `every declared binding resolves`() {
        assertNotNull(resolve<AppScanner>())
        assertNotNull(resolve<BundleInspector>())
        assertNotNull(resolve<PatchSourceClient>())
        assertNotNull(resolve<PatchEngine>())
        assertNotNull(resolve<SessionInstaller>())
        assertNotNull(resolve<RootMountInstaller>())
    }

    @Test
    fun `daos resolve independently of the database binding`() {
        assertNotNull(resolve<dev.morphexposed.data.db.PatchSourceDao>())
        assertNotNull(resolve<dev.morphexposed.data.db.SupportedAppDao>())
        assertNotNull(resolve<dev.morphexposed.data.db.PatchRunDao>())
        assertNotNull(resolve<dev.morphexposed.data.db.ApkBackupDao>())
    }

    @Test
    fun `collaborators are singletons so state is shared`() {
        // The coordinator, repository and orchestrator must see the same database and settings,
        // otherwise a run recorded by one would be invisible to the other.
        assertSame(resolve<MorphDatabase>(), resolve<MorphDatabase>())
        assertSame(resolve<SettingsStore>(), resolve<SettingsStore>())
        assertSame(resolve<PatchCoordinator>(), resolve<PatchCoordinator>())
        assertSame(resolve<PatchEngine>(), resolve<PatchEngine>())
    }

    @Test
    fun `the engine is not accidentally shared into a running state`() {
        val engine = resolve<PatchEngine>()
        assertTrue("a freshly resolved engine must be idle", !engine.isRunning)
    }
}
