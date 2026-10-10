package com.metrolist.kugou.models

import kotlinx.serialization.Serializable

/**
 * Response of the signed trackermv video URL endpoint. `data` is keyed by
 * MV hash; only [VideoEntry.downurl] is consumed, everything else is
 * ignored for forward-compatibility.
 */
@Serializable
data class VideoUrlResponse(
    val status: Int = 0,
    val data: Map<String, VideoEntry>? = null,
) {
    @Serializable
    data class VideoEntry(
        val downurl: String? = null,
    )

    fun firstUrl(): String? =
        data?.values?.firstOrNull()?.downurl?.takeIf { it.isNotBlank() }
}
