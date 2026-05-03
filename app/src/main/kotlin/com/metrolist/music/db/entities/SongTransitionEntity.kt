/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Immutable
@Entity(
    tableName = "song_transition",
    primaryKeys = ["outgoingSongId", "incomingSongId"],
    indices = [
        Index(value = ["outgoingSongId"]),
        Index(value = ["incomingSongId"]),
    ],
)
data class SongTransitionEntity(
    val outgoingSongId: String,
    val incomingSongId: String,
    @ColumnInfo(defaultValue = "NULL")
    val mixInStartMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL")
    val mixOutStartMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL")
    val mixTransitionStyleOverride: String? = null,
    @ColumnInfo(defaultValue = "8")
    val overlapBars: Int = 8,
    @ColumnInfo(defaultValue = "NULL")
    val bpmA: Float? = null,
    @ColumnInfo(defaultValue = "NULL")
    val bpmB: Float? = null,
    @ColumnInfo(defaultValue = "NULL")
    val keySignature: String? = null,
    @ColumnInfo(defaultValue = "'CROSSFADE'")
    val volumeCurve: String = "CROSSFADE",
    @ColumnInfo(defaultValue = "'QUICK_BASS_CUT'")
    val eqTemplate: String = "QUICK_BASS_CUT",
    @ColumnInfo(defaultValue = "'LOW_PASS'")
    val effectType: String = "LOW_PASS",
)
