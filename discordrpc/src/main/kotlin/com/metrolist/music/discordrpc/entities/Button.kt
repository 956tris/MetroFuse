package com.metrolist.music.discordrpc.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Button(
    @SerialName("label")
    val label: String,
    @SerialName("url")
    val url: String,
)
