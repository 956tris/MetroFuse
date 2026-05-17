package com.metrolist.music.discordrpc.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IdentifyProperties(
    @SerialName("os")
    val os: String,
    @SerialName("browser")
    val browser: String,
    @SerialName("device")
    val device: String,
    @SerialName("system_locale")
    val systemLocale: String? = null,
    @SerialName("client_version")
    val clientVersion: String? = null,
    @SerialName("release_channel")
    val releaseChannel: String? = null,
    @SerialName("os_version")
    val osVersion: String? = null,
    @SerialName("os_sdk_version")
    val osSdkVersion: String? = null,
    @SerialName("client_build_number")
    val clientBuildNumber: Int? = null,
)

@Serializable
data class Identify(
    @SerialName("token")
    val token: String,
    @SerialName("properties")
    val properties: IdentifyProperties,
    @SerialName("capabilities")
    val capabilities: Int = 16381,
    @SerialName("compress")
    val compress: Boolean = false,
    @SerialName("presence")
    val presence: Presence? = null,
    @SerialName("client_state")
    val clientState: ClientState = ClientState(),
)
