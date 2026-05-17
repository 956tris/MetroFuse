package com.metrolist.music.discordrpc.entities

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class OpCodeSerializer : KSerializer<OpCode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OpCode", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): OpCode {
        val code = decoder.decodeInt()
        return OpCode.values().firstOrNull { it.value == code } ?: OpCode.UNKNOWN
    }

    override fun serialize(encoder: Encoder, value: OpCode) {
        encoder.encodeInt(value.value)
    }
}
