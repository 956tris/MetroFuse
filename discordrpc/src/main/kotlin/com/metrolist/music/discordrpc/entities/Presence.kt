package com.metrolist.music.discordrpc.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Presence(
    @SerialName("activities")
    val activities: List<Activity> = emptyList(),
    @SerialName("since")
    val since: Long? = null,
    @SerialName("status")
    val status: String = "online",
    @SerialName("afk")
    val afk: Boolean = false,
)
