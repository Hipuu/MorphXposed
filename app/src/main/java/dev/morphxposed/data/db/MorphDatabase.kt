package dev.morphxposed.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PatchSourceEntity::class,
        SupportedAppEntity::class,
        PatchRunEntity::class,
        ApkBackupEntity::class
    ],
    // v2 adds patch_sources.latestTag, so the Patches screen can show an update is available.
    version = 2,
    exportSchema = false
)
abstract class MorphDatabase : RoomDatabase() {

    abstract fun patchSourceDao(): PatchSourceDao
    abstract fun supportedAppDao(): SupportedAppDao
    abstract fun patchRunDao(): PatchRunDao
    abstract fun apkBackupDao(): ApkBackupDao

    companion object {
        fun build(context: Context): MorphDatabase =
            Room.databaseBuilder(context, MorphDatabase::class.java, "morphxposed.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
