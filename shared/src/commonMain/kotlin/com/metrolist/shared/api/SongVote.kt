package com.metrolist.shared.api

import kotlinx.serialization.Serializable

@Serializable
enum class SongVote { LIKE, DISLIKE, INDIFFERENT }
