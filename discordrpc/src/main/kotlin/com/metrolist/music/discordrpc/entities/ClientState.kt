package com.metrolist.music.discordrpc.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClientState(
    @SerialName("guild_versions")
    val guildVersions: Map<String, String> = emptyMap(),
    @SerialName("highest_last_message_id")
    val highestLastMessageId: String = "0",
    @SerialName("read_state_version")
    val readStateVersion: Int = 0,
    @SerialName("user_guild_settings_version")
    val userGuildSettingsVersion: Int = -1,
    @SerialName("user_settings_version")
    val userSettingsVersion: Int = -1,
    @SerialName("private_channels_version")
    val privateChannelsVersion: String = "0",
    @SerialName("api_code_version")
    val apiCodeVersion: Int = 0,
)
