package com.metrolist.desktop.state.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAnimatedArtworkTest {
    @Test
    fun fullscreenCanvasLoadsWithoutEnablingAnimatedAlbumCovers() {
        assertTrue(desktopAnimatedArtworkEnabled(animatedCoversEnabled = false, expanded = true, canvasEnabled = true))
        assertFalse(desktopAnimatedArtworkEnabled(animatedCoversEnabled = false, expanded = false, canvasEnabled = true))
        assertFalse(desktopAnimatedArtworkEnabled(animatedCoversEnabled = false, expanded = true, canvasEnabled = false))
    }

    @Test
    fun albumCoversRemainAvailableWhenFullscreenCanvasIsOff() {
        assertTrue(desktopAnimatedArtworkEnabled(animatedCoversEnabled = true, expanded = true, canvasEnabled = false))
        assertTrue(desktopAnimatedArtworkEnabled(animatedCoversEnabled = true, expanded = false, canvasEnabled = false))
    }
}
