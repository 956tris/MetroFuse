package com.metrolist.desktop.utils

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposeTrayIconResourcesTest {
    @Test
    fun trayIconPathsResolveToCachedPngAndIcoFilesWithValidHeaders() {
        val (lightPngPath, windowsIcoPath) = ComposeTrayManager.resolveTrayIconPaths(isMacDarkMenu = true)
        val (darkPngPath, darkWindowsIcoPath) = ComposeTrayManager.resolveTrayIconPaths(isMacDarkMenu = false)

        val lightPng = File(lightPngPath)
        val darkPng = File(darkPngPath)
        val windowsIco = File(windowsIcoPath)

        assertTrue(lightPng.isFile && lightPng.length() > 8)
        assertTrue(darkPng.isFile && darkPng.length() > 8)
        assertTrue(windowsIco.isFile && windowsIco.length() > 4)
        assertEquals(windowsIcoPath, darkWindowsIcoPath)
        assertEquals(lightPngPath, ComposeTrayManager.resolveTrayIconPaths(isMacDarkMenu = true).first)

        val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertContentEquals(pngMagic, lightPng.inputStream().use { it.readNBytes(8) })
        assertContentEquals(pngMagic, darkPng.inputStream().use { it.readNBytes(8) })

        val icoMagic = byteArrayOf(0x00, 0x00, 0x01, 0x00)
        assertContentEquals(icoMagic, windowsIco.inputStream().use { it.readNBytes(4) })
    }
}
