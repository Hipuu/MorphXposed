package dev.morphxposed.domain.sign

import android.util.Log
import app.morphe.patcher.apk.ApkSigner as MorpheApkSigner
import app.morphe.patcher.apk.ApkUtils
import java.io.File
import java.util.Date

/**
 * Signs a patched APK using **Morphe's own** signing utilities.
 *
 * The patcher writes an aligned but unsigned APK; installation requires a signature. Rather
 * than reimplementing keystore handling, this delegates to
 * [app.morphe.patcher.apk.ApkUtils] and [MorpheApkSigner], which are the same code paths
 * Morphe Manager and Morphe Desktop use.
 *
 * The signing key is generated once into app-private storage and reused, because Android only
 * accepts an update signed with the same key as the installed build.
 */
class PatchSigner(private val keystoreFile: File, private val alias: String) {

    /**
     * Signs [unsignedApk] into [signedApk].
     *
     * @return true when signing succeeded and a non-empty output exists.
     */
    fun sign(unsignedApk: File, signedApk: File): Boolean = runCatching {
        ensureKeyStore()
        signedApk.parentFile?.mkdirs()
        if (signedApk.exists()) signedApk.delete()

        val details = ApkUtils.KeyStoreDetails(
            keystoreFile,
            KEY_STORE_PASSWORD,
            alias,
            KEY_PASSWORD
        )
        ApkUtils.signApk(unsignedApk, signedApk, alias, details)

        signedApk.isFile && signedApk.length() > 0
    }.getOrElse { t ->
        Log.e(TAG, "Signing failed", t)
        false
    }

    /** Generates the keystore on first use, using Morphe's certificate generator. */
    private fun ensureKeyStore() {
        if (keystoreFile.isFile && keystoreFile.length() > 0) return

        keystoreFile.parentFile?.mkdirs()

        // 4096-bit RSA with a 30 year validity, matching what Morphe generates.
        val validUntil = Date(System.currentTimeMillis() + THIRTY_YEARS_MS)
        val keyPair = MorpheApkSigner.newPrivateKeyCertificatePair(COMMON_NAME, validUntil)

        MorpheApkSigner.writeKeyStore(
            keystoreFile.outputStream().buffered(),
            KEY_STORE_PASSWORD,
            mapOf(alias to keyPair)
        )
        Log.i(TAG, "Generated signing keystore at ${keystoreFile.name}")
    }

    fun hasKey(): Boolean = keystoreFile.isFile && keystoreFile.length() > 0

    companion object {
        private const val TAG = "MorphXSigner"
        private const val COMMON_NAME = "MorphXposed"
        private val KEY_STORE_PASSWORD = "morphxposed"
        private val KEY_PASSWORD = "morphxposed"
        private const val THIRTY_YEARS_MS = 30L * 365 * 24 * 60 * 60 * 1000

        /** The alias every generated key uses; kept stable so signatures stay consistent. */
        const val DEFAULT_ALIAS = "MorphXposed"
    }
}
