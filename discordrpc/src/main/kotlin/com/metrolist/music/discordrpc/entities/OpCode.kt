package com.metrolist.music.discordrpc.entities

import kotlinx.serialization.Serializable

@Serializable(OpCodeSerializer::class)
enum class OpCode(val value: Int) {
    DISPATCH(0),
    HEARTBEAT(1),
    IDENTIFY(2),
    PRESENCE_UPDATE(3),
    VOICE_STATE(4),
    RESUME(6),
    RECONNECT(7),
    REQUEST_GUILD_MEMBERS(8),
    INVALID_SESSION(9),
    HELLO(10),
    HEARTBEAT_ACK(11),
    UNKNOWN(-1);
}
