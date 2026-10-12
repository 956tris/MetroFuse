package com.metrolist.kugou.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * Response of the tiered trackermv endpoint (`cmd=100`). Unlike the
 * single-file endpoint it always answers JSON: one CDN downurl per
 * quality tier (`sd`/`hd`/`sq`/`rq`).
 */
@Serializable
data class VideoTierResponse(
    val status: Int = 0,
    val mvdata: Map<String, TierEntry>? = null,
) {
    @Serializable
    data class TierEntry(
        val downurl: String? = null,
        val backupdownurl: List<String>? = null,
        // KuGou encodes this as a number or a string depending on tier.
        val filesize: JsonElement? = null,
    ) {
        fun fileSizeLong(): Long? =
            runCatching {
                filesize?.jsonPrimitive?.content?.toLongOrNull()
            }.getOrNull()
    }
}
