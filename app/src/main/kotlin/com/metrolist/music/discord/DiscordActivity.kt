package com.metrolist.music.discord

data class DiscordActivity(
    val name: String?,
    val type: String = "listening",
    val state: String,
    val details: String?,
    val startTimestamp: Long,
    val endTimestamp: Long?,
    val largeImage: String?,
    val largeText: String?,
    val smallImage: String?,
    val smallText: String?,
    val button1Label: String?,
    val button1Url: String?,
    val button2Label: String?,
    val button2Url: String?,
)
