package dev.morphexposed.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers how the events module picks a package name out of a hooked method's arguments.
 *
 * The internal `PackageManagerService` methods take different parameters across Android
 * releases, so the package is found by shape rather than position. A wrong guess is not
 * harmless: the host acts on whatever package is reported, so mis-reading an argument could
 * point automation at the wrong app.
 */
class PackageNamesTest {

    @Test
    fun `finds a package among mixed arguments`() {
        val args = arrayOf<Any?>(1234, true, "/data/app/base.apk", "com.google.android.youtube", 0L)
        assertEquals("com.google.android.youtube", PackageNames.fromArguments(args))
    }

    @Test
    fun `skips a path that contains dots`() {
        // A file path can contain dots; picking it would report a nonsense package.
        val args = arrayOf<Any?>("com.android.internal/foo.Bar", "com.example.app")
        assertEquals("com.example.app", PackageNames.fromArguments(args))
    }

    @Test
    fun `returns null when nothing looks like a package`() {
        assertNull(PackageNames.fromArguments(arrayOf<Any?>(1, 2, 3)))
        assertNull(PackageNames.fromArguments(arrayOf<Any?>("just-a-word")))
        assertNull(PackageNames.fromArguments(null))
        assertNull(PackageNames.fromArguments(emptyArray()))
    }

    @Test
    fun `a dex descriptor is not a package`() {
        assertNull(PackageNames.fromArguments(arrayOf<Any?>("Lcom/example/Thing;")))
    }

    @Test
    fun `accepts real package shapes`() {
        listOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.reddit.frontpage",
            "org.example.app_2"
        ).forEach {
            assertTrue("$it should be accepted", PackageNames.looksLikePackage(it))
        }
    }

    @Test
    fun `rejects things that are not packages`() {
        listOf(
            "",
            "nodots",
            "com.example.slash/inside",
            "com.example.with space",
            "com.example.label=value",
            "com.example:port",
            "com.2example.app",
            "com..app",
            ".com.app",
            "com.app."
        ).forEach {
            assertFalse("\"$it\" should be rejected", PackageNames.looksLikePackage(it))
        }
    }

    @Test
    fun `rejects an oversized value`() {
        assertFalse(PackageNames.looksLikePackage("com." + "a".repeat(300)))
    }
}
