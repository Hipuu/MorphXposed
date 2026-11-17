package dev.morphexposed.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

/**
 * Covers the "outdated" state of a patch source.
 *
 * The brief asks the Patches screen to show bundles that are "available, installed, and
 * outdated". Only the installed tag was tracked, so an available update was invisible until the
 * user downloaded it. These tests pin the state that makes "outdated" observable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatchSourceUpdateStateTest {

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

    private suspend fun source(): Long = db.patchSourceDao().insert(
        PatchSourceEntity(
            repoOwner = "MorpheApp",
            repoName = "morphe-patches",
            displayName = "official",
            isOfficial = true
        )
    )

    @Test
    fun `a new source has neither an installed nor an available tag`() = runBlocking {
        val id = source()
        val row = db.patchSourceDao().byId(id)!!
        assertNull(row.installedTag)
        assertNull(row.latestTag)
    }

    @Test
    fun `checking records the advertised tag before anything is downloaded`() = runBlocking {
        // This is what makes "update available" possible without downloading.
        val id = source()
        db.patchSourceDao().markChecked(id, checkedAt = 100L, latestTag = "v2.4.0")

        val row = db.patchSourceDao().byId(id)!!
        assertEquals("v2.4.0", row.latestTag)
        assertNull("nothing is installed yet", row.installedTag)
        assertEquals(100L, row.lastCheckedAt)
    }

    @Test
    fun `downloading makes the installed and advertised tags agree`() = runBlocking {
        val id = source()
        db.patchSourceDao().markDownloaded(
            id = id,
            checkedAt = 200L,
            tag = "v2.3.0",
            fileName = "b.mpp",
            sha256 = "abc",
            size = 10L,
            requiredPatcherVersion = "2.4.0"
        )

        val row = db.patchSourceDao().byId(id)!!
        assertEquals("v2.3.0", row.installedTag)
        assertEquals("a fresh download is not outdated", row.installedTag, row.latestTag)
    }

    @Test
    fun `a later check reveals an update is available`() = runBlocking {
        val id = source()
        db.patchSourceDao().markDownloaded(id, 200L, "v2.3.0", "b.mpp", "abc", 10L, "2.4.0")

        // The source publishes something newer, but nothing is downloaded.
        db.patchSourceDao().markChecked(id, checkedAt = 300L, latestTag = "v2.4.0")

        val row = db.patchSourceDao().byId(id)!!
        assertEquals("the installed bundle must not be silently replaced", "v2.3.0", row.installedTag)
        assertEquals("the newer release must be recorded", "v2.4.0", row.latestTag)
        assertTrue(
            "the UI derives 'outdated' from these two differing",
            row.latestTag != row.installedTag
        )
    }

    @Test
    fun `re-checking an up-to-date source keeps the state consistent`() = runBlocking {
        val id = source()
        db.patchSourceDao().markDownloaded(id, 200L, "v2.3.0", "b.mpp", "abc", 10L, "2.4.0")
        db.patchSourceDao().markChecked(id, checkedAt = 300L, latestTag = "v2.3.0")

        val row = db.patchSourceDao().byId(id)!!
        assertEquals("up to date must not look outdated", row.installedTag, row.latestTag)
    }

    @Test
    fun `a failed check preserves the working bundle and its advertised tag`() = runBlocking {
        val id = source()
        db.patchSourceDao().markDownloaded(id, 200L, "v2.3.0", "b.mpp", "abc", 10L, "2.4.0")
        db.patchSourceDao().markChecked(id, 300L, "v2.4.0")

        db.patchSourceDao().markFailed(id, 400L, "rate limited")

        val row = db.patchSourceDao().byId(id)!!
        assertEquals("the working bundle must survive", "v2.3.0", row.installedTag)
        assertEquals("the advertised tag must survive", "v2.4.0", row.latestTag)
        assertEquals("rate limited", row.lastError)
        assertNotNull(row.lastCheckedAt)
    }

    @Test
    fun `an outdated source is distinguishable from one never checked`() = runBlocking {
        val checked = source()
        db.patchSourceDao().markChecked(checked, 100L, "v2.4.0")

        val never = db.patchSourceDao().insert(
            PatchSourceEntity(repoOwner = "a", repoName = "b", displayName = "other")
        )

        val checkedRow = db.patchSourceDao().byId(checked)!!
        val neverRow = db.patchSourceDao().byId(never)!!

        assertNotNull("a checked source knows what is available", checkedRow.latestTag)
        assertNull("an unchecked source does not", neverRow.latestTag)
        assertEquals("an unchecked source has no check time", 0L, neverRow.lastCheckedAt)
    }
}
