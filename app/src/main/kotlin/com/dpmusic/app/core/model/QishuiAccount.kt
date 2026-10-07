package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/**
 * 汽水音乐账号资料（仅本地保存）。
 *
 * 登录方式：扫码（本机展示二维码，用已登录的「抖音 APP」确认后取 `sessionid`）。
 * 未登录（`sessionid` 为空）= 匿名模式，仍可解析「免费歌全曲」，但 VIP 曲目仅 30s 试听。
 */
@Serializable
data class QishuiProfile(
    val userId: String = "",
    val nickname: String = "",
    val avatarUrl: String = "",
)

/** 单个音质档位（汽水 `h5/seo_track` 的 video_list 项） */
data class QishuiQuality(
    val quality: String,
    val bitrate: Long,
    val size: Long,
    val url: String,
)

/** 汽水取播放地址结果（免登录即可获取；VIP 曲目仅为试听片段） */
data class QishuiPlayUrl(
    val title: String = "",
    val url: String = "",
    val backupUrl: String = "",
    val expireAt: Long = 0L,
    val qualities: List<QishuiQuality> = emptyList(),
)
