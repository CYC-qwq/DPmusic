package com.dpmusic.app.core.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 网易云歌手详情（歌手页头部展示） */
data class ArtistDetail(
    val id: String,
    val name: String,
    /** 别名（多个以「/」连接，如 "Jay Chou/周董"） */
    val alias: String = "",
    val avatarUrl: String = "",
    val coverUrl: String = "",
    /** 简介（可能为空） */
    val briefDesc: String = "",
    /** 身份标签（多个以「/」连接，如 "作曲"） */
    val identities: String = "",
)

/** 网易云专辑（歌手页专辑列表 / 专辑页头部；专辑页填充 songs） */
data class AlbumDetail(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val artist: String = "",
    /** 发行时间（毫秒时间戳；0 = 未知） */
    val publishTime: Long = 0L,
    /** 曲目数 */
    val trackCount: Int = 0,
    /** 发行公司 */
    val company: String = "",
    /** 简介 */
    val description: String = "",
    /** 专辑歌曲（仅专辑详情页填充） */
    val songs: List<Song> = emptyList(),
)

/** 毫秒时间戳 → 年份字符串（0 / 非法值返回空串） */
fun formatPublishYear(ms: Long): String {
    if (ms <= 0L) return ""
    return SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(ms))
}
