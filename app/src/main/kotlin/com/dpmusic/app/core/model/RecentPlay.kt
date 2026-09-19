package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/** 最近播放记录（含进度胶囊数据） */
@Serializable
data class RecentPlay(
    val song: Song,
    val playedAt: Long,
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    val progressFraction: Float
        get() = if (durationMs <= 0L) 0f else (progressMs.toFloat() / durationMs).coerceIn(0f, 1f)
}