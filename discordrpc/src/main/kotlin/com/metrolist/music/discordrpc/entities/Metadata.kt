package com.metrolist.music.discordrpc.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Metadata(
    @SerialName("button_urls")
    val buttonUrls: List<String>? = null,
)
