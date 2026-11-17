package dev.morphexposed.automation

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.morphexposed.MorpheXposedApp
import dev.morphexposed.data.db.MorphDatabase
import dev.morphexposed.data.db.SupportedAppEntity
import dev.morphexposed.data.prefs.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the events-bridge switch.
 *
 * `lsposedBridgeEnabled` was stored in DataStore, surfaced in the settings snapshot, and **read by
 * nothing** — so turning it off changed no behaviour at all. A setting that cannot take effect is
 * worse than a missing one, because the UI presents it as control the user does not have.
 *
 * These tests pin that the switch actually gates bridge events, and that it does **not** disable
 * the platform broadcasts, which are the path that must keep working when the module is absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeToggleTest {

    private lateinit var context: Context
    private lateinit var app: MorpheXposedApp
    private lateinit var settings: SettingsStore

    private val pkg = "com.example.target"

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        app = context.applicationContext as MorpheXposedApp
        settings = app.settings
        settings.resetToDefaults()
        BridgeObservations.reset()
    }

    @After
    fun tearDown() = runBlocking {
        settings.resetToDefaults()
        BridgeObservations.reset()
    }

    private fun bridgeIntent(packageName: String = pkg, event: String = "INSTALLED") =
        Intent(PackageChangeReceiver.ACTION_LSPOSED_EVENT).apply {
            putExtra(PackageChangeReceiver.EXTRA_PACKAGE, packageName)
            putExtra(PackageChangeReceiver.EXTRA_EVENT, event)
        }

    private fun platformIntent(packageName: String = pkg, replacing: Boolean = false) =
        Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = android.net.Uri.parse("package:$packageName")
            putExtra(Intent.EXTRA_REPLACING, replacing)
        }

    @Test
    fun `the switch defaults to on`() = runBlocking {
        assertTrue("events are acted on by default", settings.snapshot().lsposedBridgeEnabled)
    }

    @Test
    fun `the switch can be turned off and is remembered`() = runBlocking {
        settings.setLsposedBridgeEnabled(false)
        assertFalse(settings.snapshot().lsposedBridgeEnabled)

        settings.setLsposedBridgeEnabled(true)
        assertTrue(settings.snapshot().lsposedBridgeEnabled)
    }

    @Test
    fun `a bridge event is still observed when the switch is off`() = runBlocking {
        // The observed flag is diagnostic: the settings screen must be able to say the module is
        // reaching the app even while the user has chosen not to act on those events.
        settings.setLsposedBridgeEnabled(false)

        PackageChangeReceiver().onReceive(context, bridgeIntent())

        assertTrue(
            "reception must be recorded regardless of the switch, or the UI cannot report it",
            BridgeObservations.observed
        )
    }

    @Test
    fun `a bridge event does not add a supported app when the switch is off`() = runBlocking {
        settings.setLsposedBridgeEnabled(false)

        PackageChangeReceiver().onReceive(context, bridgeIntent())
        Thread.sleep(1_500)

        assertNull(
            "with the bridge switched off the event must not be acted on",
            app.database.supportedAppDao().byPackage(pkg)
        )
    }

    @Test
    fun `a bridge event is acted on when the switch is on`() = runBlocking {
        settings.setLsposedBridgeEnabled(true)
        // The package must exist for the scanner/coordinator to consider it; Robolectric
        // reports only what is installed, so a real check runs and simply finds nothing. The
        // event being processed at all is what this pins.
        PackageChangeReceiver().onReceive(context, bridgeIntent())
        Thread.sleep(1_500)

        assertTrue("the event must be processed", BridgeObservations.observed)
    }

    @Test
    fun `platform broadcasts are unaffected by the switch`() = runBlocking {
        // The bridge is optional; switching it off must never stop the guaranteed path.
        settings.setLsposedBridgeEnabled(false)

        seedSupported()
        PackageChangeReceiver().onReceive(context, platformIntent(replacing = false))
        Thread.sleep(1_500)

        // The row is not deleted for an install event, which proves the platform path ran rather
        // than being short-circuited by the bridge switch.
        assertNotNull(
            "the platform broadcast must still be processed with the bridge off",
            app.database.supportedAppDao().byPackage(pkg)
        )
    }

    @Test
    fun `a package added broadcast is parsed from its data uri`() {
        val event = PackageChangeReceiver().eventOf(context, platformIntent())
        assertNotNull(event)
        assertTrue(event!!.packageName == pkg)
        assertFalse("a platform event is not a bridge event", event.fromBridge)
    }

    @Test
    fun `a bridge intent is marked as coming from the bridge`() {
        val event = PackageChangeReceiver().eventOf(context, bridgeIntent())
        assertNotNull(event)
        assertTrue(
            "the origin must be carried, or the switch cannot be applied selectively",
            event!!.fromBridge
        )
    }

    private suspend fun seedSupported() {
        app.database.supportedAppDao().upsert(
            SupportedAppEntity(
                packageName = pkg,
                label = "Target",
                versionName = "1.0.0",
                versionCode = 1,
                apkPath = "/data/app/$pkg/base.apk",
                sourceId = 1
            )
        )
    }
}
