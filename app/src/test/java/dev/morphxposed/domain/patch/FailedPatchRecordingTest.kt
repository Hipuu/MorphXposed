package dev.morphxposed.domain.patch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.morphxposed.data.db.MorphDatabase
import dev.morphxposed.data.db.PatchRunEntity
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
 * Covers per-patch failure recording.
 *
 * The orchestrator collapsed failures into a single `patchFailure` string and then wrote
 * `failedPatches = ""` regardless, so the column was **always empty**. The Logs screen showed
 * "Applied 2 patches" for a run where a third had failed, with no way to learn which one or why —
 * even though `PatchEvent.PatchFailed` carried both the name and the message.
 *
 * These tests pin the storage contract: every failure is kept, with its patch name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FailedPatchRecordingTest {

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

    private suspend fun run(): Long = db.patchRunDao().insert(
        PatchRunEntity(
            packageName = "com.example.target",
            appLabel = "Target",
            startedAt = 1_000L,
            status = "PATCHING",
            sourceVersionName = "1.0.0",
            sourceVersionCode = 1,
            bundleTag = "v2.3.0"
        )
    )

    @Test
    fun `several failures are all kept, not just the last`() = runBlocking {
        // The old code kept only the final message, so a multi-patch failure reported one cause.
        val id = run()
        val failed = linkedMapOf(
            "Hide ads" to "fingerprint not found",
            "Remove tracking" to "method signature changed",
            "Unlock premium" to "class missing"
        )

        db.patchRunDao().complete(
            id = id,
            status = "PATCHED",
            finishedAt = 2_000L,
            applied = "Disable telemetry",
            failed = failed.entries.joinToString("\n") { (name, message) -> "$name: $message" },
            installed = false,
            strategy = "ROOT_MOUNT",
            log = "",
            outputPath = null,
            error = null
        )

        val stored = db.patchRunDao().latestFor("com.example.target")!!
        failed.keys.forEach { name ->
            assertTrue(
                "every failed patch must be listed, missing $name in: ${stored.failedPatches}",
                stored.failedPatches.contains(name)
            )
        }
        assertTrue(
            "the reasons must be kept too, was: ${stored.failedPatches}",
            stored.failedPatches.contains("fingerprint not found")
        )
    }

    @Test
    fun `a fully successful run records no failures`() = runBlocking {
        val id = run()
        db.patchRunDao().complete(
            id = id,
            status = "SUCCEEDED",
            finishedAt = 2_000L,
            applied = "Hide ads",
            failed = "",
            installed = true,
            strategy = "ROOT_MOUNT",
            log = "",
            outputPath = "/tmp/x.apk",
            error = null
        )

        val stored = db.patchRunDao().latestFor("com.example.target")!!
        assertEquals("", stored.failedPatches)
        assertEquals("Hide ads", stored.appliedPatches)
    }

    @Test
    fun `the failed column survives the install-result update`() = runBlocking {
        // InstallResultReceiver rewrites the row on an install failure and must carry the
        // per-patch detail through rather than clearing it.
        val id = run()
        db.patchRunDao().complete(
            id = id,
            status = "INSTALLING",
            finishedAt = 2_000L,
            applied = "Hide ads",
            failed = "Remove tracking: method signature changed",
            installed = false,
            strategy = "SESSION",
            log = "",
            outputPath = "/tmp/x.apk",
            error = null
        )
        val before = db.patchRunDao().latestFor("com.example.target")!!

        db.patchRunDao().complete(
            id = id,
            status = "FAILED",
            finishedAt = 3_000L,
            applied = before.appliedPatches,
            failed = before.failedPatches,
            installed = false,
            strategy = before.installStrategy,
            log = "",
            outputPath = before.outputApkPath,
            error = "signature mismatch"
        )

        val after = db.patchRunDao().latestFor("com.example.target")!!
        assertEquals("FAILED", after.status)
        assertTrue(
            "the per-patch detail must survive an install failure, was: ${after.failedPatches}",
            after.failedPatches.contains("Remove tracking")
        )
    }

    @Test
    fun `the log screen can read a blank failed column without breaking`() = runBlocking {
        // Older rows predate this field; the UI must treat blank as "nothing to show".
        val id = run()
        db.patchRunDao().complete(
            id = id,
            status = "SUCCEEDED",
            finishedAt = 2_000L,
            applied = "",
            failed = "",
            installed = true,
            strategy = null,
            log = "",
            outputPath = null,
            error = null
        )
        val rows = db.patchRunDao().observeRecent(10).first()
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.failedPatches.isBlank() })
    }
}
