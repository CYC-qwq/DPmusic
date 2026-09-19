package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/** 网易云账号资料（Cookie 校验成功后缓存） */
@Serializable
data class NcmProfile(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String = "",
)

/** 一起听房间成员 */
data class TogetherUser(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String = "",
)

/** 一起听房间信息 */
data class TogetherRoomInfo(
    val roomId: String,
    val creatorId: Long,
    val roomUsers: List<TogetherUser> = emptyList(),
    val roomType: String = "",
    /** 房间创建时间戳（毫秒；用于本地有效期判断） */
    val roomCreateTime: Long = 0L,
    /** 房间总有效期（毫秒，官方为 30 分钟） */
    val effectiveDurationMs: Long = 0L,
)

/** 当前播放命令（房间状态的播放侧） */
data class TogetherPlayCommand(
    val userId: Long = 0L,
    val commandType: String = "",
    val targetSongId: String? = null,
    val progress: Long = 0L,
    val playStatus: String = "",
    val clientSeq: Long = 0L,
    val serverSeq: Long = 0L,
)

/** 房间播放列表 */
data class TogetherPlaylist(
    val songIds: List<String> = emptyList(),
    val playMode: String = "ORDER_LOOP",
    val versions: List<VersionEntry> = emptyList(),
) {
    data class VersionEntry(val userId: Long, val version: Int)
}

/** 邀请信息（解析自分享链接） */
data class TogetherInvite(
    val roomId: String,
    val inviterId: Long,
)

/** 聊天消息类别 */
enum class TogetherChatKind { TEXT, SONG, INVITE, OTHER }

/** 一起听·聊天消息（私信通道） */
data class TogetherChatMessage(
    val id: Long,
    val time: Long,
    val fromMe: Boolean,
    val kind: TogetherChatKind,
    val text: String,
    val songId: String? = null,
    val songName: String? = null,
    val songArtist: String? = null,
)