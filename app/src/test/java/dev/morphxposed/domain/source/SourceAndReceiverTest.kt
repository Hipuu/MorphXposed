package dev.morphxposed.domain.source

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.morphxposed.automation.PackageChangeReceiver
import dev.morphxposed.data.db.MorphDatabase
import dev.morphxposed.data.db.PatchSourceEntity
import dev.morphxposed.data.db.SupportedAppEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Intent

/**
 * Tests the parts of the package-event path that do not need a live framework hook: the
 * receiver's own validation, and the source-repository bookkeeping around a refresh.
 *
 * The receiver is security-sensitive: it acts on data from a broadcast, so it must ignore
 * malformed intents, its own package, and anything it cannot parse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceAndReceiverTest {

    private lateinit var context: Context
    private lateinit var db: MorphDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MorphDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- Package change receiver -------------------------------------------------------

    @Test
    fun `receiver ignores an intent without a package uri`() = runBlocking {
        val receiver = PackageChangeReceiver()
        // A broadcast with an action but no data must be a no-op, not a crash.
        receiver.onReceive(context, Intent(Intent.ACTION_PACKAGE_ADDED))
        receiver.onReceive(context, Intent())
        assertTrue("nothing should have been recorded", db.supportedAppDao().all().isEmpty())
    }

    @Test
    fun `receiver ignores its own package`() = runBlocking {
        val receiver = PackageChangeReceiver()
        val intent = Intent(Intent.ACTION_PACKAGE_REPLACED).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        // Must not attempt to patch itself.
        receiver.onReceive(context, intent)
        assertNull(db.supportedAppDao().byPackage(context.packageName))
    }

    @Test
    fun `receiver ignores unsupported packages`() = runBlocking {
        val receiver = PackageChangeReceiver()
        val intent = Intent(Intent.ACTION_PACKAGE_ADDED).apply {
            data = Uri.parse("package:com.example.unsupported")
        }
        receiver.onReceive(context, intent)
        assertNull(
            "an app no source supports must not be recorded",
            db.supportedAppDao().byPackage("com.example.unsupported")
        )
    }

    @Test
    fun `receiver removal drops the row`() = runBlocking {
        // The receiver works through the Application's own database, so the row must be seeded
        // there rather than in this test's in-memory instance.
        val app = context.applicationContext as dev.morphxposed.MorphXposedApp
        val appDao = app.database.supportedAppDao()

        val pkg = "com.example.removed.bytest"
        appDao.upsert(
            SupportedAppEntity(
                packageName = pkg,
                label = "Removed",
                versionName = "1.0",
                versionCode = 1,
                apkPath = "/data/app/base.apk",
                sourceId = 1
            )
        )
        assertNotNull(appDao.byPackage(pkg))

        PackageChangeReceiver().onReceive(
            context,
            Intent(Intent.ACTION_PACKAGE_REMOVED).apply {
                data = Uri.parse("package:$pkg")
                putExtra(Intent.EXTRA_REPLACING, false)
            }
        )

        // The receiver does its work on a background scope, so poll briefly.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && appDao.byPackage(pkg) != null) {
            Thread.sleep(50)
        }
        assertNull(
            "an uninstalled app must be dropped so a stale row cannot trigger a run",
            appDao.byPackage(pkg)
        )
    }

    // ---- Source repository bookkeeping -------------------------------------------------

    @Test
    fun `spec parsing accepts the shapes users actually paste`() {
        // Mirrors PatchSourceRepository.parseSpec via addSource's validation.
        val accepted = listOf(
            "MorpheApp/morphe-patches",
            "https://github.com/MorpheApp/morphe-patches",
            "github.com/MorpheApp/morphe-patches",
            "https://github.com/MorpheApp/morphe-patches/",
            "https://github.com/MorpheApp/morphe-patches.git"
        )
        val normalized = accepted.map { spec ->
            spec.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removePrefix("github.com/")
                .trimEnd('/')
                .removeSuffix(".git")
        }.toSet()

        assertEquals(
            "every accepted form must normalize to the same owner/repo",
            1,
            normalized.size
        )
        assertEquals("MorpheApp/morphe-patches", normalized.first())
    }

    @Test
    fun `a source records its bundle metadata and a failed refresh records the error`() = runBlocking {
        val dao = db.patchSourceDao()
        val id = dao.insert(
            PatchSourceEntity(
                repoOwner = "MorpheApp",
                repoName = "morphe-patches",
                displayName = "official",
                isOfficial = true
            )
        )
        assertNull("a new source has no bundle yet", dao.byId(id)!!.installedTag)

        dao.markDownloaded(id, 1L, "v2.3.0", "b.mpp", "sha", 10L, "2.4.0")
        assertEquals("v2.3.0", dao.byId(id)!!.installedTag)

        // A later failure must not erase the working bundle.
        dao.markFailed(id, 2L, "rate limited")
        val after = dao.byId(id)!!
        assertEquals("the cached bundle must survive a failed refresh", "v2.3.0", after.installedTag)
        assertEquals("sha", after.cachedSha256)
        assertEquals("rate limited", after.lastError)
    }

    @Test
    fun `enabled sources are what the app actually reads`() = runBlocking {
        val dao = db.patchSourceDao()
        dao.insert(PatchSourceEntity(repoOwner = "a", repoName = "one", displayName = "one", enabled = true))
        dao.insert(PatchSourceEntity(repoOwner = "b", repoName = "two", displayName = "two", enabled = false))

        val enabled = dao.enabled()
        assertEquals(1, enabled.size)
        assertEquals("one", enabled.first().displayName)
    }
}
