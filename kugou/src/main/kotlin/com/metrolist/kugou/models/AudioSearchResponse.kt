package com.metrolist.kugou.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudioSearchResponse(
    val status: Int = 0,
    val errcode: Int = -1,
    val data: Data? = null,
) {
    @Serializable
    data class Data(
        val info: List<Song> = emptyList(),
        val total: Int = 0,
    ) {
        @Serializable
        data class Song(
            val hash: String = "",
            @SerialName("320hash")
            val hash320: String = "",
            @SerialName("sqhash")
            val hashFlac: String = "",
            val songname: String = "",
            val singername: String = "",
            @SerialName("album_name")
            val albumName: String = "",
            // Duration in seconds.
            val duration: Int = 0,
            // Music video hash; empty when the track has no MV.
            val mvhash: String = "",
            // Entitlement flags: 0 = free for the tier, non-zero = paid/region-gated.
            val privilege: Int = 0,
            @SerialName("320privilege")
            val privilege320: Int = 0,
            @SerialName("sqprivilege")
            val privilegeFlac: Int = 0,
        )
    }
}
