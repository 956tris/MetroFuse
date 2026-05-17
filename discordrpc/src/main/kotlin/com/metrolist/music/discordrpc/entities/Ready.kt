package com.metrolist.music.discordrpc.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Ready(
    @SerialName("session_id")
    val sessionId: String? = null,
    @SerialName("resume_gateway_url")
    val resumeGatewayUrl: String? = null,
)
