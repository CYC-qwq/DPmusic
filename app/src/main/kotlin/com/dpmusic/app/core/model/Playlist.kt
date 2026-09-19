package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/** 统一歌单摘要模型 */
@Serializable
data class PlaylistSummary(
    val id: String,
    val platform: MusicPlatform,
    val name: String,
    val coverUrl: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    val creator: String = "",
)