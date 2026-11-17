package dev.morphexposed.domain.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Minimal projection of the GitHub releases API; only the fields we consume. */
@Serializable
data class GhRelease(
    val tag_name: String = "",
    val name: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val published_at: String? = null,
    val assets: List<GhAsset> = emptyList()
)

@Serializable
data class GhAsset(
    val name: String = "",
    val size: Long = 0,
    val content_type: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    /** GitHub returns digests as `sha256:<hex>` on supported endpoints. */
    val digest: String? = null
)

/** A patch bundle release that this app can install. */
data class PatchBundleRelease(
    val tag: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** Lowercase hex SHA-256, when the release publishes one. */
    val sha256: String?,
    val publishedAt: Long?,
    val isPrerelease: Boolean
)

sealed interface SourceError {
    data class Network(val message: String) : SourceError
    data class RateLimited(val resetAtEpochSeconds: Long?) : SourceError
    data class NotFound(val message: String) : SourceError
    data class NoBundleAsset(val tag: String) : SourceError
    data class ChecksumMismatch(val expected: String, val actual: String) : SourceError
    data class InvalidBundle(val message: String) : SourceError
    data class Other(val message: String) : SourceError

    fun describe(): String = when (this) {
        is Network -> "Network error: $message"
        is RateLimited -> "GitHub rate limit reached" + (resetAtEpochSeconds?.let { " (resets at $it)" } ?: "")
        is NotFound -> "Not found: $message"
        is NoBundleAsset -> "Release $tag has no patch bundle asset"
        is ChecksumMismatch -> "Checksum mismatch: expected $expected, got $actual"
        is InvalidBundle -> "Invalid patch bundle: $message"
        is Other -> message
    }
}

class SourceException(val error: SourceError) : Exception(error.describe())
