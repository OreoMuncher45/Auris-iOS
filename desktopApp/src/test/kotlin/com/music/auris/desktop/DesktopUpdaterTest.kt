package com.music.auris.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopUpdaterTest {

    private val hash = "a".repeat(64)
    private val sums = """
        ${"b".repeat(64)}  Auris-1.8.3-macos-arm64.zip
        $hash  Auris-1.8.3-windows-x64.msi
        ${"C".repeat(64)} *Auris-1.8.3-linux-x86_64.AppImage
    """.trimIndent()

    @Test
    fun findsTheNamedFile() {
        assertEquals(hash, DesktopUpdater.expectedHash(sums, "Auris-1.8.3-windows-x64.msi"))
    }

    @Test
    fun readsBinaryModeAndLowercases() {
        assertEquals("c".repeat(64), DesktopUpdater.expectedHash(sums, "Auris-1.8.3-linux-x86_64.AppImage"))
    }

    @Test
    fun unlistedOrPartialNamesAreNotTrusted() {
        assertNull(DesktopUpdater.expectedHash(sums, "Auris-1.8.3-windows-x64-setup.exe"))
        assertNull(DesktopUpdater.expectedHash(sums, "Auris-1.8.3-windows-x64"))
    }

    @Test
    fun aMalformedHashIsNotTrusted() {
        assertNull(DesktopUpdater.expectedHash("abc123  Auris.msi", "Auris.msi"))
    }
}
