package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/** 统一榜单摘要模型 */
@Serializable
data class RankSummary(
    val id: String,
    val platform: MusicPlatform,
    val name: String,
    val coverUrl: String = "",
    val updateFrequency: String = "",
    val description: String = "",
)