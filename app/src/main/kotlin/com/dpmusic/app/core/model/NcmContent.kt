package com.dpmusic.app.core.model

/** 网易云歌单摘要（我的歌单 / 每日推荐歌单） */
data class NcmPlaylist(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val trackCount: Int = 0,
    /** 是否为收藏的歌单（false = 自建；接口字段 subscribed） */
    val subscribed: Boolean = false,
    /** 特殊歌单（我喜欢的音乐 specialType=5） */
    val special: Boolean = false,
    /** 播放量（推荐歌单展示用） */
    val playCount: Long = 0L,
)
