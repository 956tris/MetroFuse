package com.metrolist.shared.playback

data class PlaybackAudioFormat(
    val codec: String,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val bitrateBps: Long?,
    val bitrateIsAverage: Boolean = false,
)
