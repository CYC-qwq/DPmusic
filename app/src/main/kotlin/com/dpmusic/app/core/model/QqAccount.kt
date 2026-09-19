package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/** QQ 音乐账号资料（Cookie 校验成功后缓存） */
@Serializable
data class QqProfile(
    val userId: String,
    val nickname: String,
    val avatarUrl: String = "",
)

/** QQ 音乐歌单摘要（我的歌单；special = 「我喜欢」dirId=201） */
data class QqPlaylist(
    val dirId: String,
    val tid: String,
    val name: String,
    val trackCount: Int = 0,
    val special: Boolean = false,
    val coverUrl: String = "",
)
