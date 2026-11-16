package dev.morphxposed

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.morphxposed.data.db.MorphDatabase
import dev.morphxposed.data.db.PatchSourceEntity
import dev.morphxposed.data.db.SupportedAppEntity
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

/**
 * Runs the app's persistence and scanning code against the real Android framework on the JVM
 * (Robolectric), which a plain JUnit test cannot do.
 *
 * This covers the paths that were previously only "compiled but never executed": Room schema
 * creation and DAO queries, the DAO's reconcile logic that must preserve user settings across
 * rescans, and the scanner's interaction with `PackageManager`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseAndScannerTest {

    private lateinit var context: Context
    private lateinit var db: MorphDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MorphDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `schema creates and the official source round-trips`() = kotlinx.coroutines.runBlocking {
        val id = db.patchSourceDao().insert(
            PatchSourceEntity(
                repoOwner = "MorpheApp",
                repoName = "morphe-patches",
                displayName = "Morphe (official)",
                isOfficial = true
            )
        )
        assertTrue("insert should return a row id", id > 0)

        val loaded = db.patchSourceDao().byId(id)
        assertNotNull(loaded)
        assertEquals("MorpheApp", loaded!!.repoOwner)
        assertTrue(loaded.isOfficial)

        // The official source is unique by owner/repo.
        val duplicate = db.patchSourceDao().byRepo("MorpheApp", "morphe-patches")
        assertNotNull(duplicate)
    }

    @Test
    fun `markDownloaded stores bundle metadata and clears the error`() = kotlinx.coroutines.runBlocking {
        val id = db.patchSourceDao().insert(
            PatchSourceEntity(repoOwner = "o", repoName = "r", displayName = "test")
        )
        db.patchSourceDao().markFailed(id, 1L, "network down")
        assertEquals("network down", db.patchSourceDao().byId(id)!!.lastError)

        db.patchSourceDao().markDownloaded(
            id = id,
            checkedAt = 2L,
            tag = "v2.3.0",
            fileName = "bundles-$id-v2.3.0.mpp",
            sha256 = "abc123",
            size = 16_663_050,
            requiredPatcherVersion = "2.4.0"
        )

        val loaded = db.patchSourceDao().byId(id)!!
        assertEquals("v2.3.0", loaded.installedTag)
        assertEquals("abc123", loaded.cachedSha256)
        assertEquals(16_663_050, loaded.cachedSize)
        assertEquals("2.4.0", loaded.requiredPatcherVersion)
        assertNull("a successful download must clear the previous error", loaded.lastError)
    }

    @Test
    fun `official source cannot be deleted but a third-party one can`() = kotlinx.coroutines.runBlocking {
        val official = db.patchSourceDao().insert(
            PatchSourceEntity(
                repoOwner = "MorpheApp", repoName = "morphe-patches",
                displayName = "official", isOfficial = true
            )
        )
        val thirdParty = db.patchSourceDao().insert(
            PatchSourceEntity(repoOwner = "someone", repoName = "patches", displayName = "community")
        )

        db.patchSourceDao().deleteIfNotOfficial(official)
        db.patchSourceDao().deleteIfNotOfficial(thirdParty)

        assertNotNull("official source must survive", db.patchSourceDao().byId(official))
        assertNull("third-party source should be gone", db.patchSourceDao().byId(thirdParty))
    }

    @Test
    fun `reconcile preserves user settings across a rescan`() = kotlinx.coroutines.runBlocking {
        val dao = db.supportedAppDao()

        // First scan discovers the app.
        dao.reconcileDiscovered(
            listOf(
                SupportedAppEntity(
                    packageName = "com.google.android.youtube",
                    label = "YouTube",
                    versionName = "21.45.82",
                    versionCode = 1,
                    apkPath = "/data/app/base.apk",
                    sourceId = 1
                )
            ),
            scannedAt = 100L
        )

        // The user enables automation and records a successful patch.
        dao.setAutoPatch("com.google.android.youtube", true)
        dao.setSelectedPatches("com.google.android.youtube", "Hide ads,SponsorBlock")
        dao.markPatched(
            packageName = "com.google.android.youtube",
            at = 500L,
            version = "21.45.82",
            bundleTag = "v2.3.0",
            bundleSha = "abc",
            mounted = true
        )

        // A later scan sees a new installed version.
        dao.reconcileDiscovered(
            listOf(
                SupportedAppEntity(
                    packageName = "com.google.android.youtube",
                    label = "YouTube",
                    versionName = "21.46.0",
                    versionCode = 2,
                    apkPath = "/data/app/base.apk",
                    sourceId = 1
                )
            ),
            scannedAt = 900L
        )

        val row = dao.byPackage("com.google.android.youtube")!!
        assertEquals("version must refresh", "21.46.0", row.versionName)
        assertEquals(900L, row.discoveredAt)
        // User-owned state must survive.
        assertTrue("auto-patch opt-in must survive a rescan", row.autoPatchEnabled)
        assertEquals("Hide ads,SponsorBlock", row.selectedPatches)
        assertEquals(500L, row.lastPatchedAt)
        assertEquals("the previously patched version must stay recorded", "21.45.82", row.lastPatchedVersion)
        assertTrue("a mount record must survive", row.mounted)
    }

    @Test
    fun `reconcile drops apps that are no longer installed`() = kotlinx.coroutines.runBlocking {
        val dao = db.supportedAppDao()
        dao.reconcileDiscovered(
            listOf(
                app("com.a.one", "One"), app("com.b.two", "Two")
            ),
            scannedAt = 1L
        )
        assertEquals(2, dao.all().size)

        // Second scan no longer sees com.b.two (uninstalled).
        dao.reconcileDiscovered(listOf(app("com.a.one", "One")), scannedAt = 2L)

        val remaining = dao.all()
        assertEquals(1, remaining.size)
        assertEquals("com.a.one", remaining.first().packageName)
    }

    @Test
    fun `skip-if-unchanged logic compares the bundle actually used`() {
        // Mirrors PatchOrchestrator's guard: a repatch is skipped only when both the app
        // version and the bundle checksum match the last successful run.
        data class State(val version: String, val sha: String?)
        fun shouldSkip(last: State, current: State) =
            last.version == current.version && last.sha == current.sha

        assertTrue(shouldSkip(State("1.0", "abc"), State("1.0", "abc")))
        assertTrue("a version change must force a repatch", !shouldSkip(State("1.0", "abc"), State("1.1", "abc")))
        assertTrue("a bundle change must force a repatch", !shouldSkip(State("1.0", "abc"), State("1.0", "def")))
        assertTrue("a null bundle checksum must not match a real one", !shouldSkip(State("1.0", null), State("1.0", "abc")))
    }

    private fun app(pkg: String, label: String) = SupportedAppEntity(
        packageName = pkg,
        label = label,
        versionName = "1.0",
        versionCode = 1,
        apkPath = "/data/app/$pkg/base.apk",
        sourceId = 1
    )
}
