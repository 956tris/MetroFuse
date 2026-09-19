/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Learned rhythmic grid + structure for one track, observed passively by the
 * Automix analyzer while the track plays (or sniffed from its head during
 * prefetch). Lets future transitions lock to true downbeats and musical
 * boundaries instead of assuming them. Only derived boundaries are stored -
 * never raw audio or curves - and every consumer must honor [confidence].
 */
@Entity(
    tableName = "track_structure",
    indices = [Index(value = ["learnedAt"])],
)
data class TrackStructureEntity(
    @PrimaryKey val mediaId: String,
    val bpm: Float? = null,
    val beatPeriodMs: Float? = null,
    /** Offset of the first observed downbeat from track start, ms. */
    val downbeatOffsetMs: Long? = null,
    /** End of the intro (first sustained full-energy point), ms. */
    val introEndMs: Long? = null,
    /** Start of the outro (last point before the permanent drop), ms. */
    val outroStartMs: Long? = null,
    val isFadeOut: Boolean = false,
    /** 0..1 blend of beat-hit rate, vote margin, and observation length. */
    val confidence: Float = 0f,
    val learnedAt: Long = 0L,
)
