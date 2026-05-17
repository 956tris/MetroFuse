package com.metrolist.music.discordrpc.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Resume(
    @SerialName("token")
    val token: String,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("seq")
    val seq: Int,
)
