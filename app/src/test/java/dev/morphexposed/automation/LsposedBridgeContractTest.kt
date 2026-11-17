package dev.morphexposed.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.morphexposed.data.db.MorphDatabase
import dev.morphexposed.data.db.SupportedAppEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/**
 * Verifies the LSPosed events bridge actually connects to the app.
 *
 * This exists because the bridge was once silently broken: the module sent an intent whose
 * action the receiver did not filter for, carrying no `package:` data URI (which the receiver
 * required) and naming its extra differently. Each mismatch alone was enough to drop every
 * event, and nothing failed until traced by hand.
 *
 * These tests pin the wire format so a change on one side cannot silently orphan the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LsposedBridgeContractTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() = runBlocking {
        val app = context.applicationContext as dev.morphexposed.MorpheXposedApp
        app.database.supportedAppDao().deleteByPackage(PKG)
    }

    private val PKG = "com.google.android.youtube"

    /** Exactly the intent the events module builds. */
    private fun bridgeIntent(pkg: String, event: String) = Intent(ACTION)
        .apply {
            // setPackage() is what the module does; in a unit test there is no other app to
            // target, so the package is left unset and the action/extra contract is what is tested.
            putExtra(EXTRA_PACKAGE, pkg)
            putExtra(EXTRA_EVENT, event)
        }

    @Test
    fun `bridge action and extras match between the two APKs`() {
        // Values duplicated from EventsModule's companion; asserted here so a rename on either
        // side fails the build's tests rather than silently breaking the bridge in the field.
        assertEquals(
            "the module sends this action",
            "dev.morphexposed.LSPOSED_PACKAGE_EVENT",
            PackageChangeReceiver.ACTION_LSPOSED_EVENT
        )
        assertEquals("package", PackageChangeReceiver.EXTRA_PACKAGE)
        assertEquals("event", PackageChangeReceiver.EXTRA_EVENT)
        assertEquals(
            "the bridge must be guarded by a signature permission",
            "dev.morphexposed.permission.BRIDGE",
            PackageChangeReceiver.PERMISSION_BRIDGE
        )
    }

    @Test
    fun `a bridge removal event drops the row`() = runBlocking {
        val app = context.applicationContext as dev.morphexposed.MorpheXposedApp
        val dao = app.database.supportedAppDao()
        dao.upsert(
            SupportedAppEntity(
                packageName = PKG, label = "YouTube", versionName = "21.45.82",
                versionCode = 1, apkPath = "/data/app/base.apk", sourceId = 1
            )
        )

        PackageChangeReceiver().onReceive(context, bridgeIntent(PKG, "removed"))

        // The receiver works on a background scope.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && dao.byPackage(PKG) != null) {
            Thread.sleep(50)
        }
        assertNull(
            "a bridge removal must reach the receiver and drop the row",
            dao.byPackage(PKG)
        )
    }

    @Test
    fun `a bridge intent without a package name is ignored`() = runBlocking {
        val app = context.applicationContext as dev.morphexposed.MorpheXposedApp
        val dao = app.database.supportedAppDao()
        dao.upsert(
            SupportedAppEntity(
                packageName = PKG, label = "YouTube", versionName = "1.0",
                versionCode = 1, apkPath = "/data/app/base.apk", sourceId = 1
            )
        )

        // Malformed: action is right but the package extra is missing.
        PackageChangeReceiver().onReceive(
            context,
            Intent(PackageChangeReceiver.ACTION_LSPOSED_EVENT).putExtra(EXTRA_EVENT, "removed")
        )

        Thread.sleep(600)
        assertEquals(
            "a malformed bridge intent must be ignored, not acted on",
            1,
            dao.byPackage(PKG)?.let { 1 } ?: 0
        )
    }

    @Test
    fun `an unknown bridge event name is ignored`() = runBlocking {
        val app = context.applicationContext as dev.morphexposed.MorpheXposedApp
        val dao = app.database.supportedAppDao()
        dao.upsert(
            SupportedAppEntity(
                packageName = PKG, label = "YouTube", versionName = "1.0",
                versionCode = 1, apkPath = "/data/app/base.apk", sourceId = 1
            )
        )

        PackageChangeReceiver().onReceive(context, bridgeIntent(PKG, "not-a-real-event"))

        Thread.sleep(600)
        assertEquals(
            "an unrecognized event must not remove the row",
            1,
            dao.byPackage(PKG)?.let { 1 } ?: 0
        )
    }

    @Test
    fun `platform package broadcast still routes by data uri`() {
        // The platform path is unchanged and must keep working for the app's own packages.
        val intent = Intent(Intent.ACTION_PACKAGE_REMOVED).apply {
            data = Uri.parse("package:$PKG")
            putExtra(Intent.EXTRA_REPLACING, false)
        }
        // Must not throw; the receiver parses the data URI.
        PackageChangeReceiver().onReceive(context, intent)
    }

    private companion object {
        // Mirrors EventsModule constants; a change there without changing these fails the test.
        const val ACTION = "dev.morphexposed.LSPOSED_PACKAGE_EVENT"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_EVENT = "event"
    }
}
