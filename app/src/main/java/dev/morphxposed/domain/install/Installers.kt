package dev.morphxposed.domain.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Outcome of an install attempt. */
sealed interface InstallResult {
    /** Every result names the strategy that produced it. */
    val strategy: InstallStrategyName

    data class Success(override val strategy: InstallStrategyName) : InstallResult
    /** The platform accepted the request and will report the outcome asynchronously. */
    data class PendingUserAction(
        override val strategy: InstallStrategyName,
        val sessionId: Int?
    ) : InstallResult

    data class Failure(
        override val strategy: InstallStrategyName,
        val message: String
    ) : InstallResult

    /** The strategy cannot run here (no root, no permission, Shizuku absent). */
    data class NotPermitted(override val strategy: InstallStrategyName) : InstallResult
}

enum class InstallStrategyName { SESSION, ROOT_MOUNT, SHIZUKU }

/**
 * Installs a patched APK over the stock app.
 *
 * Three strategies exist because the platform and the patch itself constrain the choice:
 *
 *  * [SESSION] — the only strategy that works without privilege. Because the patched APK is
 *    signed with a different key than the stock app, Android refuses to replace it, so the
 *    stock app must be uninstalled first, which loses its data.
 *
 *  * [ROOT_MOUNT] — the recommended path on a rooted device. The patched APK is bind-mounted
 *    over the stock one at boot by a generated root-manager module, so the stock signature and
 *    the app's data both survive. This is what Morphe calls "Root mount mode".
 *
 *  * [SHIZUKU] — installation through Shizuku where the user has it running.
 *
 * None of these silently modifies any package: each is invoked only for a package the user
 * explicitly chose.
 */
interface ApkInstaller {
    val strategy: InstallStrategyName

    /** Whether this strategy can run right now on this device. */
    suspend fun isAvailable(): Boolean

    /** Whether switching to this strategy requires uninstalling the stock app. */
    val requiresUninstallOfStock: Boolean

    /**
     * Installs [apk] for [packageName].
     *
     * @param expectedStockVersionName version the patch was built against; a mismatch is
     *   refused rather than mounting a patched APK over a different version.
     */
    suspend fun install(
        apk: File,
        packageName: String,
        expectedStockVersionName: String?
    ): InstallResult
}

/**
 * Standard Android package-installer session. This is the unrooted path.
 *
 * The patched APK is signed with this app's own key, so it cannot replace an app signed by
 * Google. A signature-conflicting install therefore has to uninstall the stock app, which
 * clears its data. That trade-off is surfaced in the UI rather than hidden.
 */
class SessionInstaller(private val context: Context) : ApkInstaller {

    override val strategy = InstallStrategyName.SESSION

    /** Session installs cannot preserve an app that is signed by someone else. */
    override val requiresUninstallOfStock = true

    override suspend fun isAvailable(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    override suspend fun install(
        apk: File,
        packageName: String,
        expectedStockVersionName: String?
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return@withContext InstallResult.NotPermitted(strategy)
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(packageName)
        }

        try {
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(dev.morphxposed.installer.InstallResultReceiver.ACTION_INSTALL_RESULT)
                    .setPackage(context.packageName)
                val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_MUTABLE
                val pending = android.app.PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    flags
                )
                session.commit(pending.intentSender)
            }
            // The platform now shows its own confirmation UI and reports back to the receiver.
            InstallResult.PendingUserAction(strategy, sessionId)
        } catch (t: Throwable) {
            InstallResult.Failure(strategy, t.message ?: t.javaClass.simpleName)
        }
    }
}

/**
 * Root-mount installer: writes a Magisk/KernelSU module that bind-mounts the patched APK over
 * the stock one at boot.
 *
 * The module layout follows Morphe Manager's own generator, so the result behaves exactly like
 * a module Morphe would create:
 *
 * ```
 * /data/adb/modules/morphxposed_<pkg>/
 *   module.prop          metadata shown by the root manager
 *   post-fs-data.sh      mount executed early in boot
 *   service.sh           late mount + version guard
 *   <package>.apk        the patched APK
 * ```
 */
class RootMountInstaller(private val context: Context) : ApkInstaller {

    override val strategy = InstallStrategyName.ROOT_MOUNT

    /** Mounting preserves the stock app, so nothing has to be uninstalled. */
    override val requiresUninstallOfStock = false

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        RootShell.hasRoot()
    }

    override suspend fun install(
        apk: File,
        packageName: String,
        expectedStockVersionName: String?
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!RootShell.hasRoot()) return@withContext InstallResult.NotPermitted(strategy)

        val moduleId = "morphxposed_${packageName.replace('.', '_')}"
        val moduleDir = "/data/adb/modules/$moduleId"
        val stagedApk = "$moduleDir/$packageName.apk"

        try {
            // 1. Create the module directory and push the patched APK.
            RootShell.run("mkdir -p $moduleDir") { it.isSuccess }
                .takeIf { it } ?: return@withContext InstallResult.Failure(
                strategy, "Could not create module directory (is /data/adb writable?)"
            )

            val push = RootShell.pushFile(apk, stagedApk)
            if (!push) {
                return@withContext InstallResult.Failure(strategy, "Could not copy patched APK into the module")
            }

            // 2. Write module.prop, post-fs-data.sh and service.sh.
            val version = expectedStockVersionName ?: "unknown"
            val moduleProp = buildModuleProp(moduleId, packageName, version)
            if (!RootShell.writeText("$moduleDir/module.prop", moduleProp)) {
                return@withContext InstallResult.Failure(strategy, "Could not write module.prop")
            }
            if (!RootShell.writeText("$moduleDir/post-fs-data.sh", POST_FS_DATA)) {
                return@withContext InstallResult.Failure(strategy, "Could not write post-fs-data.sh")
            }
            if (!RootShell.writeText("$moduleDir/service.sh", serviceScript(packageName, version))) {
                return@withContext InstallResult.Failure(strategy, "Could not write service.sh")
            }

            // 3. Permissions the root manager expects.
            RootShell.run(
                "chmod 0755 $moduleDir/post-fs-data.sh $moduleDir/service.sh",
                "chown 0:0 $moduleDir/*",
                "chmod 0644 $moduleDir/module.prop $moduleDir/$packageName.apk",
                "chcon u:object_r:apk_data_file:s0 $stagedApk 2>/dev/null || true"
            ) { true }

            // 4. Apply immediately so the user does not have to reboot.
            val mount = RootShell.run(buildMountCommand(moduleDir, packageName)) { it.isSuccess }
            if (!mount) {
                // The module is in place; a reboot will mount it. Report as pending rather than failed.
                return@withContext InstallResult.PendingUserAction(strategy, null)
            }

            RootShell.run("am force-stop $packageName") { true }
            InstallResult.Success(strategy)
        } catch (t: Throwable) {
            InstallResult.Failure(strategy, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun buildModuleProp(moduleId: String, packageName: String, version: String) = buildString {
        appendLine("id=$moduleId")
        appendLine("name=MorphXposed mount for $packageName")
        appendLine("version=$version")
        appendLine("versionCode=1")
        appendLine("author=MorphXposed")
        appendLine("description=Mounts the Morphe-patched APK over $packageName at boot")
    }

    private fun serviceScript(packageName: String, version: String): String = """
        #!/system/bin/sh
        # Written by MorphXposed. Mounts the patched APK over the stock one once boot completes.
        MODDIR=${'$'}{0%/*}
        PKG=$packageName
        EXPECTED_VERSION=$version

        until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do sleep 3; done

        STOCK=${'$'}(pm path "${'$'}PKG" 2>/dev/null | grep base | head -n 1 | sed 's/package://g')
        [ -z "${'$'}STOCK" ] && exit 0

        ACTUAL=${'$'}(dumpsys package "${'$'}PKG" 2>/dev/null | grep -m1 versionName= | sed 's/.*versionName=//' | tr -d '\r')
        if [ -n "${'$'}EXPECTED_VERSION" ] && [ "${'$'}EXPECTED_VERSION" != "unknown" ] && [ "${'$'}ACTUAL" != "${'$'}EXPECTED_VERSION" ]; then
            # The stock app changed; mounting a mismatched APK would crash it.
            exit 0
        fi

        umount -l "${'$'}STOCK" 2>/dev/null
        mount -o bind "${'$'}MODDIR/${'$'}PKG.apk" "${'$'}STOCK" 2>/dev/null
        am force-stop "${'$'}PKG" 2>/dev/null
    """.trimIndent() + "\n"

    private fun buildMountCommand(moduleDir: String, packageName: String) = """
        STOCK=${'$'}(pm path $packageName 2>/dev/null | grep base | head -n 1 | sed 's/package://g'); \
        if [ -n "${'$'}STOCK" ]; then \
          umount -l "${'$'}STOCK" 2>/dev/null; \
          mount -o bind $moduleDir/$packageName.apk "${'$'}STOCK"; \
        else exit 1; fi
    """.trimIndent()

    private companion object {
        /**
         * Mounted before the package manager scans apps, so the very first launch after boot
         * already sees the patched APK.
         */
        val POST_FS_DATA = """
            #!/system/bin/sh
            # Written by MorphXposed.
            MODDIR=${'$'}{0%/*}
            for APK in "${'$'}MODDIR"/*.apk; do
                [ -f "${'$'}APK" ] || continue
                PKG=${'$'}(basename "${'$'}APK" .apk)
                STOCK=${'$'}(pm path "${'$'}PKG" 2>/dev/null | grep base | head -n 1 | sed 's/package://g')
                [ -z "${'$'}STOCK" ] && continue
                mount -o bind "${'$'}APK" "${'$'}STOCK" 2>/dev/null
            done
        """.trimIndent() + "\n"
    }
}

/**
 * Minimal root shell helper built on `su -c`. Deliberately narrow: it can run a fixed set of
 * commands and push/write files, and nothing it executes comes from a downloaded artifact.
 */
object RootShell {

    /** Cached result of the one-time root probe. */
    @Volatile
    private var cachedRoot: Boolean? = null

    fun hasRoot(): Boolean {
        cachedRoot?.let { return it }
        val ok = runCatching {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        }.getOrDefault(false)
        cachedRoot = ok
        return ok
    }

    /** Forgets the cached probe so the next call re-tests (used after the user grants root). */
    fun invalidate() {
        cachedRoot = null
    }

    fun run(vararg commands: String, verdict: (ExitStatus) -> Boolean): Boolean {
        val script = commands.joinToString(" ; ")
        return runCatching {
            val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            verdict(ExitStatus(code, output))
        }.getOrDefault(false)
    }

    /**
     * Copies [source] to [destinationPath] as root by streaming it into `su -c "cat > path"`.
     *
     * Streams rather than reading the file into memory: a patched APK is routinely over 100 MB,
     * and buffering it whole (twice, as an earlier version did) is enough to OOM a low-end phone.
     */
    fun pushFile(source: File, destinationPath: String): Boolean {
        if (!source.isFile) return false
        return runCatching {
            val process = ProcessBuilder("su", "-c", "cat > $destinationPath")
                .redirectErrorStream(true)
                .start()
            // Drain stderr/stdout so the child cannot block on a full pipe.
            val drain = Thread {
                runCatching { process.inputStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) } }
            }.apply { isDaemon = true; start() }

            process.outputStream.use { out ->
                source.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            }
            val code = process.waitFor()
            drain.join(2_000)
            code == 0
        }.getOrDefault(false)
    }

    /** Writes [content] to [path] as root. */
    fun writeText(path: String, content: String): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "cat > $path").redirectErrorStream(true).start()
        process.outputStream.use { it.write(content.toByteArray()) }
        process.waitFor() == 0
    }.getOrDefault(false)

    data class ExitStatus(val code: Int, val output: String) {
        val isSuccess: Boolean get() = code == 0
    }
}
