package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    @Test
    fun comparesSemanticVersionNumbers() {
        assertEquals(1, Updater.compareVersions("7.0.5", "7.0.4"))
        assertEquals(0, Updater.compareVersions("v7.0.5", "7.0.5"))
        assertEquals(-1, Updater.compareVersions("7.0.4", "7.0.5"))
    }

    @Test
    fun recognizesMetroFuseReleaseAssets() {
        assertEquals("universal" to "foss", Updater.inferAssetTarget("MetroFuse.apk"))
        assertEquals(
            "universal" to "gms",
            Updater.inferAssetTarget("MetroFuse-with-Google-Cast.apk"),
        )
        assertNull(Updater.inferAssetTarget("MetroFuse-izzy.apk"))
    }

    @Test
    fun selectsOnlyCompatibleReleaseAsset() {
        val arm64Foss = ReleaseAsset("arm64.apk", "arm64", 1L, "arm64-v8a", "foss")
        val universalFoss = ReleaseAsset("universal.apk", "universal", 1L, "universal", "foss")
        val arm64Gms = ReleaseAsset("gms.apk", "gms", 1L, "arm64-v8a", "gms")

        assertEquals(
            arm64Foss,
            Updater.selectCompatibleAsset(listOf(universalFoss, arm64Gms, arm64Foss), "arm64-v8a", "foss"),
        )
        assertEquals(
            universalFoss,
            Updater.selectCompatibleAsset(listOf(universalFoss, arm64Gms), "x86_64", "foss"),
        )
        assertNull(Updater.selectCompatibleAsset(listOf(arm64Gms), "arm64-v8a", "foss"))
    }
}
