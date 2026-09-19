package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/**
 * 本地用户歌单：
 * - 支持创建 / 重命名 / 删除 / 导入 / 导出；
 * - 歌曲来源：搜索页 / 收藏页长按「添加到歌单」，或导入备份文件。
 */
@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val songs: List<Song> = emptyList(),
    val createdAt: Long = 0L,
    /** 链接导入来源（本地创建 / 文件导入的歌单为 null） */
    val sourceLink: String? = null,
    val sourcePlatformId: String? = null,
    val sourcePlaylistId: String? = null,
    /** 定时更新（打开歌单页时自动检查） */
    val autoUpdate: AutoUpdateMode = AutoUpdateMode.OFF,
    /** 最近一次从链接更新的时间（0 = 从未） */
    val lastUpdatedAt: Long = 0L,
) {
    /** 封面：取第一首歌的封面（无歌时为空 → UI 显示占位） */
    val coverUrl: String get() = songs.firstOrNull()?.coverUrl.orEmpty()

    /** 是否为链接导入的歌单（可更新 / 可分享链接） */
    val isLinked: Boolean get() = !sourceLink.isNullOrBlank()
}

/** 定时更新频率（打开歌单页时自动检查并刷新） */
@Serializable
enum class AutoUpdateMode(val label: String, val intervalMs: Long) {
    OFF("关闭", 0L),
    DAILY("每天", 24L * 60 * 60 * 1000),
    EVERY_3_DAYS("每3天", 3 * 24L * 60 * 60 * 1000),
    WEEKLY("每周", 7 * 24L * 60 * 60 * 1000),
}