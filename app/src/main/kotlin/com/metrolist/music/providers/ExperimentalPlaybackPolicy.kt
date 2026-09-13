/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.music.constants.AudioProviderOrderItem

internal object ExperimentalPlaybackPolicy {
    fun prioritizeDeezer(
        providers: List<AudioProviderOrderItem>,
        enabled: Boolean,
    ): List<AudioProviderOrderItem> =
        if (enabled) {
            listOf(AudioProviderOrderItem.DEEZER) + providers.filterNot { it == AudioProviderOrderItem.DEEZER }
        } else {
            providers
        }
}
