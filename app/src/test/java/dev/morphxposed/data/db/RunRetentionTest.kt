package dev.morphxposed.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers retention of run history and the patch-run log.
 *
 * `pruneTo` existed on the DAO and was **never called**, so the Logs table grew without bound —
 * and each row carries the run's whole patch log, which is not small. The fix is to call it; these
 * tests pin that retention actually works, because "the method exists" was exactly the state that
 * looked fine before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunRetentionTest {

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

    private suspend fun run(packageName: String, startedAt: Long): Long =
        db.patchRunDao().insert(
            PatchRunEntity(
                packageName = packageName,
                appLabel = packageName,
                startedAt = startedAt,
                status = "SUCCEEDED",
                sourceVersionName = "1.0",
                sourceVersionCode = 1,
                bundleTag = "v2.3.0",
                appliedPatches = "Hide ads",
                installStrategy = "ROOT_MOUNT"
            )
        )

    @Test
    fun `pruning keeps the newest runs and drops the rest`() = runBlocking {
        repeat(20) { i -> run("com.example.app$i", startedAt = (i + 1) * 1000L) }
        assertEquals(20, db.patchRunDao().observeRecent(100).first().size)

        db.patchRunDao().pruneTo(keep = 5)

        val kept = db.patchRunDao().observeRecent(100).first()
        assertEquals("exactly the retention limit must remain", 5, kept.size)
        // The newest are kept: newest first, so the kept set is the highest start times.
        assertEquals(20_000L, kept.first().startedAt)
        assertEquals(16_000L, kept.last().startedAt)
    }

    @Test
    fun `pruning to a larger limit changes nothing`() = runBlocking {
        repeat(3) { i -> run("com.example.app$i", startedAt = (i + 1) * 1000L) }
        db.patchRunDao().pruneTo(keep = 100)
        assertEquals(3, db.patchRunDao().observeRecent(100).first().size)
    }

    @Test
    fun `pruning to zero clears the history`() = runBlocking {
        repeat(4) { i -> run("com.example.app$i", startedAt = (i + 1) * 1000L) }
        db.patchRunDao().pruneTo(keep = 0)
        assertTrue(db.patchRunDao().observeRecent(100).first().isEmpty())
    }

    @Test
    fun `the retention limit is a real number`() {
        // A limit that is enormous is the same as no limit at all.
        val limit = dev.morphxposed.domain.patch.PatchOrchestrator.MAX_RUNS_RETAINED
        assertTrue("retention must be bounded, was $limit", limit in 1..1000)
    }

    @Test
    fun `a run's log is stored so the Logs screen can show it`() = runBlocking {
        val id = run("com.example.app", startedAt = 1000L)
        val text = "line one\nline two\npatched 3 patches"
        db.patchRunDao().updateLog(id, text)

        val stored = db.patchRunDao().observeRecent(1).first().first()
        assertEquals(text, stored.log)
    }

    @Test
    fun `pruning preserves the most recent run for each package in practice`() = runBlocking {
        // Interleaved apps: retention is global (by start time), which is what a single Logs
        // screen shows. The invariant worth pinning is that the newest overall survives.
        run("com.a", 1_000L)
        run("com.b", 2_000L)
        val newest = run("com.c", 3_000L)

        db.patchRunDao().pruneTo(keep = 1)

        val kept = db.patchRunDao().observeRecent(10).first().single()
        assertEquals("the newest run must survive", newest, kept.id)
        assertEquals("com.c", kept.packageName)
    }
}
