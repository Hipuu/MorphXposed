package dev.morphexposed.domain.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import dev.morphexposed.data.db.SupportedAppEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enumerates installed packages and reports those a patch bundle can patch.
 *
 * Only packages that appear in a bundle's declared compatibility list are reported,
 * so unrelated apps are never surfaced or touched.
 */
class AppScanner(private val context: Context) {

    private val packageManager: PackageManager get() = context.packageManager

    /** Result of scanning: apps that at least one supported package name matched. */
    data class ScanResult(
        val apps: List<SupportedAppEntity>,
        /** Total packages visible to us, for diagnostics. */
        val totalPackages: Int
    )

    /**
     * Scans installed apps.
     *
     * @param supportedPackages package names declared by the currently enabled bundles,
     *   mapped to the source id that can patch them.
     */
    suspend fun scan(supportedPackages: Map<String, Long>): ScanResult = withContext(Dispatchers.IO) {
        val installed = queryInstalled()
        val apps = ArrayList<SupportedAppEntity>()
        val now = System.currentTimeMillis()

        for (pkg in installed) {
            val sourceId = supportedPackages[pkg.packageName] ?: continue
            val label = runCatching {
                pkg.applicationInfo?.loadLabel(packageManager)?.toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg.packageName

            apps.add(
                SupportedAppEntity(
                    packageName = pkg.packageName,
                    label = label,
                    versionName = pkg.versionName.orEmpty(),
                    versionCode = PackageInfoCompat.getLongVersionCode(pkg),
                    apkPath = pkg.applicationInfo?.sourceDir.orEmpty(),
                    splitApkPaths = pkg.applicationInfo?.splitSourceDirs?.joinToString("\n").orEmpty(),
                    sourceId = sourceId,
                    discoveredAt = now
                )
            )
        }

        ScanResult(apps = apps.sortedBy { it.label.lowercase() }, totalPackages = installed.size)
    }

    private fun queryInstalled(): List<PackageInfo> {
        @Suppress("DEPRECATION")
        val all = runCatching {
            packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
        // Only packages with a real code location can ever be patched.
        return all.filter { it.applicationInfo?.sourceDir?.isNotBlank() == true }
    }

    /** Metadata for one package, or null when it is not installed. */
    suspend fun packageInfo(packageName: String): SupportedAppEntity? = withContext(Dispatchers.IO) {
        val info = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
        }.getOrNull() ?: return@withContext null
        val app = info.applicationInfo ?: return@withContext null
        SupportedAppEntity(
            packageName = info.packageName,
            label = runCatching { app.loadLabel(packageManager)?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: info.packageName,
            versionName = info.versionName.orEmpty(),
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            apkPath = app.sourceDir.orEmpty(),
            splitApkPaths = app.splitSourceDirs?.joinToString("\n").orEmpty(),
            discoveredAt = System.currentTimeMillis()
        )
    }

    /** True when [packageName] is currently installed. */
    suspend fun isInstalled(packageName: String): Boolean = withContext(Dispatchers.IO) {
        isInstalledSync(packageName)
    }

    @Suppress("DEPRECATION")
    private fun isInstalledSync(packageName: String): Boolean = runCatching {
        packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** Whether the app is debuggable, which is not relevant to patching but is shown in details. */
    fun isSystemApp(info: ApplicationInfo): Boolean =
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
}
