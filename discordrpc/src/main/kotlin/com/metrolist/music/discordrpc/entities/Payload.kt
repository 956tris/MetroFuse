package com.metrolist.music.discordrpc.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Payload(
    @SerialName("op")
    val op: OpCode? = null,
    @SerialName("d")
    val d: JsonElement? = null,
    @SerialName("s")
    val s: Int? = null,
    @SerialName("t")
    val t: String? = null,
)
