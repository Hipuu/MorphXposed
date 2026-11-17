package dev.morphexposed.domain.install

/**
 * Generates the root-manager module that bind-mounts a patched APK over the stock app.
 *
 * Kept separate from [RootMountInstaller] so the generated shell can be inspected and tested
 * without a device or root. The scripts are the riskiest output this app produces: a broken
 * one either fails to mount (patched app silently not applied) or mounts a mismatch and
 * crashes the app. Both are hard to debug from a bug report.
 *
 * Two invariants the scripts must keep:
 *
 *  1. **Never mount a mismatched version.** If the stock app changed after patching, the mount
 *     is skipped rather than applied, because the patched DEX would not match resources.
 *  2. **Quote every interpolated value.** Paths under `/data/app/...` are usually safe, but a
 *     package name or path with a shell metacharacter must not become a command.
 */
object RootModuleScripts {

    /**
     * Module id, derived so it is a legal directory name for any package.
     *
     * Package names are attacker-influenced in the sense that this app reads them from the
     * device, and a value containing `/` or `..` would escape the modules directory when used
     * to build a path. Everything outside `[A-Za-z0-9_]` is therefore replaced, so the result
     * cannot contain a separator or a traversal segment.
     */
    fun moduleId(packageName: String): String {
        val safe = packageName.map { c ->
            if (c.isLetterOrDigit() && c.code < 128 || c == '_') c else '_'
        }.joinToString("")
        return "morphexposed_" + safe
    }

    /** Directory the module is installed to. */
    fun moduleDir(packageName: String): String = "/data/adb/modules/${moduleId(packageName)}"

    /**
     * `module.prop`, shown by the root manager.
     *
     * The description carries the mount outcome per boot, so it must stay a single line.
     */
    fun moduleProp(packageName: String, version: String): String = buildString {
        appendLine("id=${moduleId(packageName)}")
        appendLine("name=MorpheXposed mount for $packageName")
        appendLine("version=${oneLine(version)}")
        appendLine("versionCode=1")
        appendLine("author=MorpheXposed")
        appendLine("description=${oneLine("Mounts the Morphe-patched APK over $packageName at boot")}")
    }

    /**
     * `post-fs-data.sh`: mounts early, before the package manager scans apps, so the first
     * launch after boot already sees the patched APK.
     */
    fun postFsData(): String = """
        #!/system/bin/sh
        # Written by MorpheXposed. Mounts patched APKs before the package manager scans them.
        MODDIR=${'$'}{0%/*}
        [ -d "${'$'}MODDIR" ] || MODDIR=/data/adb/modules/${'$'}(basename "${'$'}0" .sh)

        for APK in "${'$'}MODDIR"/*.apk; do
            [ -f "${'$'}APK" ] || continue
            PKG=${'$'}(basename "${'$'}APK" .apk)
            STOCK=${'$'}(pm path "${'$'}PKG" 2>/dev/null | grep '^package:' | head -n 1 | sed 's/^package://')
            [ -n "${'$'}STOCK" ] || continue
            mount -o bind "${'$'}APK" "${'$'}STOCK" 2>/dev/null
        done
    """.trimIndent() + "\n"

    /**
     * `service.sh`: mounts once boot completes, and refuses when the stock app changed.
     *
     * The version guard is the important half. Android resolves resources by ID, so a patched
     * DEX over a different resource table crashes at launch; skipping is the safe failure.
     */
    fun service(packageName: String, expectedVersion: String): String = """
        #!/system/bin/sh
        # Written by MorpheXposed. Mounts the patched APK over the stock one after boot.
        MODDIR=${'$'}{0%/*}
        PKG=${quote(packageName)}
        EXPECTED_VERSION=${quote(expectedVersion)}

        until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do sleep 3; done

        STOCK=${'$'}(pm path "${'$'}PKG" 2>/dev/null | grep '^package:' | head -n 1 | sed 's/^package://')
        [ -z "${'$'}STOCK" ] && exit 0

        ACTUAL=${'$'}(dumpsys package "${'$'}PKG" 2>/dev/null | grep -m1 versionName= | sed 's/.*versionName=//' | tr -d '\r')

        # Refuse to mount a patched APK over a different version: the resource IDs would not line up.
        if [ -n "${'$'}EXPECTED_VERSION" ] && [ "${'$'}EXPECTED_VERSION" != "unknown" ] && [ "${'$'}ACTUAL" != "${'$'}EXPECTED_VERSION" ]; then
            exit 0
        fi

        APK="${'$'}MODDIR/${'$'}PKG.apk"
        [ -f "${'$'}APK" ] || exit 0

        umount -l "${'$'}STOCK" 2>/dev/null
        mount -o bind "${'$'}APK" "${'$'}STOCK" 2>/dev/null
        am force-stop "${'$'}PKG" 2>/dev/null
    """.trimIndent() + "\n"

    /**
     * One-shot mount applied immediately after install, so the user does not have to reboot.
     * Runs through `su -c`; exits non-zero when the stock path cannot be found.
     */
    fun applyNowCommand(packageName: String): String = """
        STOCK=${'$'}(pm path ${quote(packageName)} 2>/dev/null | grep '^package:' | head -n 1 | sed 's/^package://'); \
        [ -n "${'$'}STOCK" ] || exit 1; \
        APK="${quote(moduleDir(packageName))}/${quote(packageName)}.apk"; \
        [ -f "${'$'}APK" ] || exit 1; \
        umount -l "${'$'}STOCK" 2>/dev/null; \
        mount -o bind "${'$'}APK" "${'$'}STOCK"
    """.trimIndent()

    /** Single-quotes a value for POSIX sh, escaping embedded quotes. */
    internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Collapses newlines so a value cannot break out of a `key=value` line. */
    private fun oneLine(value: String): String = value.replace("\r", " ").replace("\n", " ").trim()
}
