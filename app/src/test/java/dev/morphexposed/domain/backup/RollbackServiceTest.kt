package dev.morphexposed.domain.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.morphexposed.data.db.ApkBackupEntity
import dev.morphexposed.data.db.MorphDatabase
import dev.morphexposed.data.db.SupportedAppEntity
import dev.morphexposed.domain.install.RootMountInstaller
import dev.morphexposed.domain.install.SessionInstaller
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers rollback, which the app previously could not perform.
 *
 * Backups were written but never restorable, so "rollback and recovery" was a claim. The two
 * cases genuinely differ: a root-mounted app was never replaced, so removing the mount is
 * lossless; a session-installed app was replaced, so restoring it changes the signature back and
 * Android clears its data. The service must say which one applies rather than pretending
 * rollback is free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RollbackServiceTest {

    private lateinit var context: Context
    private lateinit var db: MorphDatabase
    private lateinit var service: RollbackService
    private lateinit var work: File

    private val pkg = "com.example.target"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MorphDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = RollbackService(
            context = context,
            db = db,
            sessionInstaller = SessionInstaller(context),
            rootInstaller = RootMountInstaller(context)
        )
        work = File(context.cacheDir, "rollback-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        db.close()
        work.deleteRecursively()
    }

    private suspend fun seedBackup(versionName: String = "1.0.0", withFile: Boolean = true): ApkBackupEntity {
        val file = File(work, "$pkg-$versionName.apk")
        if (withFile) {
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
                zip.closeEntry()
            }
        }
        val entity = ApkBackupEntity(
            packageName = pkg,
            versionName = versionName,
            versionCode = 1,
            filePath = file.absolutePath,
            sizeBytes = file.length(),
            createdAt = System.currentTimeMillis()
        )
        db.apkBackupDao().upsert(entity)
        db.supportedAppDao().upsert(
            SupportedAppEntity(
                packageName = pkg,
                label = "Target",
                versionName = versionName,
                versionCode = 1,
                apkPath = file.absolutePath,
                sourceId = 1
            )
        )
        return entity
    }

    @Test
    fun `with no backup rollback is refused and says why`() = runBlocking {
        val state = service.availability(pkg, wasMounted = false)

        assertFalse(state.canRollback)
        assertTrue(
            "the reason must explain the absence, was: ${state.reason}",
            state.reason.contains("No original APK", ignoreCase = true)
        )
    }

    @Test
    fun `with a backup rollback is offered`() = runBlocking {
        seedBackup()

        val state = service.availability(pkg, wasMounted = false)
        assertTrue(state.canRollback)
        assertNotNull(state.backup)
        assertEquals("1.0.0", state.backup!!.versionName)
    }

    @Test
    fun `a missing backup file makes rollback unavailable`() = runBlocking {
        // The row exists but the file was cleared from storage; offering a rollback that cannot
        // work would be worse than refusing it.
        seedBackup(withFile = false)

        val state = service.availability(pkg, wasMounted = false)
        assertFalse(state.canRollback)
        assertTrue(
            "the reason must name the missing file, was: ${state.reason}",
            state.reason.contains("missing", ignoreCase = true)
        )
    }

    @Test
    fun `a mounted app rolls back without losing data`() = runBlocking {
        seedBackup()

        val state = service.availability(pkg, wasMounted = true)
        assertTrue(state.canRollback)
        assertFalse(
            "a mount was never installed over the stock app, so nothing is lost",
            state.losesAppData
        )
        assertTrue(
            "the message must say data is kept, was: ${state.reason}",
            state.reason.contains("kept", ignoreCase = true)
        )
    }

    @Test
    fun `a session-installed app rolls back at the cost of its data`() = runBlocking {
        seedBackup()

        val state = service.availability(pkg, wasMounted = false)
        assertTrue(state.canRollback)
        assertTrue(
            "re-installing the original changes the signature, which clears data",
            state.losesAppData
        )
        assertTrue(
            "the user must be told, was: ${state.reason}",
            state.reason.contains("data", ignoreCase = true)
        )
    }

    @Test
    fun `rolling back an unmounted app without install permission fails clearly`() = runBlocking {
        seedBackup()

        val result = service.rollback(pkg, wasMounted = false)

        // Either the platform accepted the restore, or it refused for a stated reason; it must
        // never silently do nothing.
        assertTrue(
            "the rollback must return a definite outcome, got $result",
            result is RollbackService.Result.Restored || result is RollbackService.Result.Failed
        )
    }

    @Test
    fun `rolling back with no backup reports failure rather than throwing`() = runBlocking {
        val result = service.rollback("com.example.never.patched", wasMounted = false)

        assertTrue(result is RollbackService.Result.Failed)
        assertTrue(
            "the failure must explain itself, was: ${(result as RollbackService.Result.Failed).message}",
            result.message.isNotBlank()
        )
    }

    @Test
    fun `a mounted rollback clears the mount record`() = runBlocking {
        seedBackup()

        // Simulate an app previously applied by mount.
        db.supportedAppDao().markPatched(
            packageName = pkg,
            at = 1L,
            version = "1.0.0",
            bundleTag = "v2.3.0",
            bundleSha = "abc",
            mounted = true
        )
        assertTrue(db.supportedAppDao().byPackage(pkg)!!.mounted)

        service.rollback(pkg, wasMounted = true)

        val after = db.supportedAppDao().byPackage(pkg)!!
        assertFalse(
            "after removing the mount the app must no longer be recorded as mounted",
            after.mounted
        )
    }
}
