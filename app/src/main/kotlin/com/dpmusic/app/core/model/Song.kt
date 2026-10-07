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
    /**
     * 该曲在该平台的**最高可用档位** id（来自列表接口元数据，见 `qualityFromMaxLevel`）。
     * 空串表示列表接口未提供可靠元数据 → UI 不展示音质徽标。
     */
    val maxQuality: String = "",
    /**
     * **逐档真实体积**（字节，键为 [PlayQuality.id]）：仅酷狗列表接口直接给出。
     * 值为 `"0"` = 该档确证无资源；键缺失 = 该档未知（不可据此判不可用）。
     * 用于音质菜单精确标注/灰化，避免「猜错了反而误导用户」。
     */
    val qualitySizes: Map<String, String> = emptyMap(),
    val extra: Map<String, String> = emptyMap(),
) {
    /** 全局唯一键：平台 + 平台内 ID */
    val stableKey: String get() = "${platform.id}:$id"

    /** 列表接口标注的最高可用档位（解析失败为 null） */
    val ceilingQuality: PlayQuality?
        get() = PlayQuality.entries.firstOrNull { it.id == maxQuality }

    /**
     * 该档在本曲是否有资源。
     * 返回 `null` 表示**未知**（接口未提供该档字段）—— 调用方不应据此判不可用。
     */
    fun hasQuality(quality: PlayQuality): Boolean? =
        qualitySizes[quality.id]?.let { (it.toLongOrNull() ?: 0L) > 0L }
}