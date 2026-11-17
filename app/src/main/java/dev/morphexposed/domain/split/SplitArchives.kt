package dev.morphexposed.domain.split

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Helpers for split-APK installs, which are the normal shape for Play-distributed apps.
 *
 * A patched app must be a single APK, so a split install's modules are merged first. Backups
 * keep the original split set as an `.apks` archive so a rollback restores exactly what was
 * installed.
 *
 * Merging is a **file-level** operation: every entry from every module is copied into one
 * archive, with the base module winning on duplicate names. That is sufficient for the patcher,
 * which reads DEX, resources and native libraries by path. It deliberately does not attempt
 * manifest or resource-table merging, which is the platform's job at install time.
 */
object SplitArchives {

    private const val TAG = "MorphXSplit"

    /**
     * Packs [baseApk] and [splitApks] into an `.apks` archive at [output], storing each APK
     * uncompressed (APKs are already compressed, and `.apks` readers expect stored entries).
     */
    fun pack(baseApk: File, splitApks: List<File>, output: File): File {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            for (file in listOf(baseApk) + splitApks) {
                if (!file.isFile) continue
                val crc = CRC32()
                val buffer = ByteArray(64 * 1024)
                FileInputStream(file).use { input ->
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        crc.update(buffer, 0, read)
                    }
                }
                zip.putNextEntry(
                    ZipEntry(file.name).apply {
                        method = ZipEntry.STORED
                        size = file.length()
                        compressedSize = file.length()
                        this.crc = crc.value
                    }
                )
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        Log.i(TAG, "Packed ${1 + splitApks.size} modules into ${output.name}")
        return output
    }

    /**
     * Merges a split install into one APK at [output].
     *
     * Entries from the base module take precedence; a split entry with the same name is skipped
     * rather than duplicated (a ZIP with duplicate names is invalid).
     */
    fun mergeToApk(baseApk: File, splitApks: List<File>, output: File): File {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val written = HashSet<String>(4096)

        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            // Base first so its entries win.
            addModule(baseApk, zip, written)

            // Then config splits. Language/density/ABI splits only add files the base lacks.
            for (split in splitApks) {
                if (!split.isFile) continue
                addModule(split, zip, written)
            }
        }

        Log.i(TAG, "Merged ${1 + splitApks.size} modules into ${output.name} (${output.length()} bytes)")
        return output
    }

    private fun addModule(module: File, zip: ZipOutputStream, written: MutableSet<String>) {
        ZipFile(module).use { source ->
            val entries = source.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (!written.add(entry.name)) continue

                val outEntry = ZipEntry(entry.name)
                // Preserve stored-vs-deflated only for entries that must stay stored.
                if (entry.method == ZipEntry.STORED) {
                    outEntry.method = ZipEntry.STORED
                    outEntry.size = entry.size
                    outEntry.compressedSize = entry.size
                    outEntry.crc = entry.crc
                }
                runCatching {
                    zip.putNextEntry(outEntry)
                    source.getInputStream(entry).use { it.copyTo(zip) }
                    zip.closeEntry()
                }.onFailure {
                    Log.w(TAG, "Skipping ${entry.name} from ${module.name}: ${it.message}")
                    runCatching { zip.closeEntry() }
                }
            }
        }
    }

    /**
     * True when [file] looks like a split-APK container (`.apks`, `.apkm`, `.xapk`, or a ZIP
     * holding several APKs).
     */
    fun isSplitArchive(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in setOf("apks", "apkm", "xapk")) return true
        if (ext != "zip" && ext != "apk") return false
        return runCatching {
            ZipFile(file).use { zip ->
                zip.entries().toList().count { it.name.endsWith(".apk", true) } > 1
            }
        }.getOrDefault(false)
    }
}
