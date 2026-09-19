package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/**
 * 统一歌曲模型。
 * 三平台字段映射：
 * - 网易云：id / name / ar[].name / al.name / al.picUrl / dt(ms)
 * - QQ 音乐：mid / title / singer[].name / album.name / album.mid / interval(s)
 * - 酷狗：hash / songname / singername / album_name / trans_param.union_cover / duration(s)
 */
@Serializable
data class Song(
    val id: String,
    val platform: MusicPlatform,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String = "",
    val extra: Map<String, String> = emptyMap(),
) {
    /** 全局唯一键：平台 + 平台内 ID */
    val stableKey: String get() = "${platform.id}:$id"
}