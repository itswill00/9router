package com.ninerouter.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManagerAssetTest {
    @Test
    fun prefersReleaseVariantOverDebug() {
        val names = listOf("9Router-0.1.16-debug.apk", "9Router-0.1.16-release.apk")

        assertEquals("9Router-0.1.16-release.apk", selectApkAsset(names))
        assertEquals("9Router-0.1.16-release.apk", selectApkAsset(names.reversed()))
    }

    @Test
    fun neverFallsBackToDebugWhenNoReleaseAsset() {
        val names = listOf("notes.txt", "9Router-0.1.16-debug.apk")

        assertNull(selectApkAsset(names))
    }

    @Test
    fun rejectsUnsignedApks() {
        assertNull(selectApkAsset(listOf("app-release-unsigned.apk")))
    }

    @Test
    fun returnsNullWhenNoApkIsPresent() {
        assertNull(selectApkAsset(listOf("notes.txt", "SHA256SUMS")))
    }
}
