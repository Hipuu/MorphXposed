package dev.morphexposed.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers the backup and rollback records that make a failed patch recoverable.
 *
 * Rollback is a stated requirement, and the backup write path in the orchestrator is wrapped in
 * `runCatching` — a silent failure there would leave the user with no way back and no warning.
 * These tests pin the behaviour the recovery path depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRollbackTest {

    private lateinit var context: Context
    private lateinit var db: MorphDatabase
    private lateinit var work: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MorphDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        work = File(context.cacheDir, "backup-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        db.close()
        work.deleteRecursively()
    }

    private fun backup(pkg: String, code: Long, version: String = "1.$code.0") =
        ApkBackupEntity(
            packageName = pkg,
            versionName = version,
            versionCode = code,
            filePath = File(work, "b-$pkg-$code.apk").apply { writeBytes(ByteArray(64)) }.absolutePath,
            sizeBytes = 64,
            createdAt = code * 1000
        )

    @Test
    fun `one backup per package and version`() = kotlinx.coroutines.runBlocking {
        val dao = db.apkBackupDao()
        dao.upsert(backup("com.example.app", 1))
        // A second backup of the same version must replace, not duplicate.
        dao.upsert(backup("com.example.app", 1))

        assertEquals("one row per version", 1, dao.forPackage("com.example.app").size)
    }

    @Test
    fun `rollback finds the newest backup`() = kotlinx.coroutines.runBlocking {
        val dao = db.apkBackupDao()
        listOf(1L, 2L, 3L).forEach { dao.upsert(backup("com.example.app", it)) }

        val latest = dao.latestFor("com.example.app")
        assertNotNull(latest)
        assertEquals("rollback must target the highest version code", 3L, latest!!.versionCode)
    }

    @Test
    fun `backups are scoped per package`() = kotlinx.coroutines.runBlocking {
        val dao = db.apkBackupDao()
        dao.upsert(backup("com.a.one", 1))
        dao.upsert(backup("com.b.two", 5))

        assertEquals(1, dao.forPackage("com.a.one").size)
        assertEquals(1, dao.forPackage("com.b.two").size)
        assertEquals("com.a.one", dao.latestFor("com.a.one")!!.packageName)
        assertEquals(5L, dao.latestFor("com.b.two")!!.versionCode)
    }

    @Test
    fun `an exact version lookup is available for repatching`() = kotlinx.coroutines.runBlocking {
        val dao = db.apkBackupDao()
        dao.upsert(backup("com.example.app", 7))

        assertNotNull(
            "the orchestrator reuses a backup for the same version",
            dao.forVersion("com.example.app", 7)
        )
        assertNull(dao.forVersion("com.example.app", 8))
    }

    @Test
    fun `total size accounting covers every backup`() = kotlinx.coroutines.runBlocking {
        val dao = db.apkBackupDao()
        dao.upsert(backup("com.a.one", 1))   // 64 bytes
        dao.upsert(backup("com.b.two", 2))   // 64 bytes

        assertEquals("the storage total must count every backup", 128L, dao.totalBytes())
    }

    @Test
    fun `deleting a backup removes only that row`() = kotlinx.coroutines.runBlocking {
        val dao = db.apkBackupDao()
        val first = backup("com.example.app", 1)
        dao.upsert(first)
        dao.upsert(backup("com.example.app", 2))

        val stored = dao.forVersion("com.example.app", 1)!!
        dao.delete(stored.id)

        assertNull("the deleted backup must be gone", dao.forVersion("com.example.app", 1))
        assertNotNull("the other version must survive", dao.forVersion("com.example.app", 2))
    }

    @Test
    fun `clearing a package drops all of its backups`() = kotlinx.coroutines.runBlocking {
        val dao = db.apkBackupDao()
        dao.upsert(backup("com.example.app", 1))
        dao.upsert(backup("com.example.app", 2))
        dao.upsert(backup("com.other.app", 1))

        dao.deleteForPackage("com.example.app")

        assertEquals(0, dao.forPackage("com.example.app").size)
        assertEquals("another package must be untouched", 1, dao.forPackage("com.other.app").size)
    }

    @Test
    fun `the recorded path points at a readable file`() = kotlinx.coroutines.runBlocking {
        val dao = db.apkBackupDao()
        dao.upsert(backup("com.example.app", 1))

        val stored = dao.latestFor("com.example.app")!!
        val file = File(stored.filePath)
        assertEquals(
            "the recorded size must match the file on disk",
            stored.sizeBytes,
            file.length()
        )
    }
}
