package com.metrolist.music.discordrpc.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HeartbeatResponse(
    @SerialName("heartbeat_interval")
    val heartbeatInterval: Long,
)
