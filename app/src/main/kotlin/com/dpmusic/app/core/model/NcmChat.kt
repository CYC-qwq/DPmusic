package com.dpmusic.app.core.model

/**
 * 网易云「私信」聊天系统数据模型。
 *
 * 依据实测调研（`music api大全/一起听/聊天功能调研报告.md`）：
 * 一起听**房间内**的聊天走网易云信私有协议，纯 API 不可接入；
 * 但**私信系统**完全可编程，且官方 App 用户能直接收到并回复 → 双向互通。
 * 因此 DPmusic 的聊天系统以私信为后端。
 */

/** 私信会话（会话列表项） */
data class ChatConversation(
    /** 对方用户 id */
    val userId: Long,
    val nickname: String,
    val avatarUrl: String = "",
    /** 最近一条消息的预览文本 */
    val lastMessage: String = "",
    /** 最近一条消息时间（毫秒） */
    val lastTime: Long = 0L,
    /** 未读数 */
    val unreadCount: Int = 0,
    /** 最近一条消息是否为「我」发出（用于「我：xxx」预览） */
    val lastFromMe: Boolean = false,
)

/** 聊天消息类型（对应私信 `type` 与内层卡片字段） */
enum class ChatMessageKind {
    /** 纯文本 */
    TEXT,

    /** 歌曲卡片 */
    SONG,

    /** 歌单卡片 */
    PLAYLIST,

    /** 专辑卡片 */
    ALBUM,

    /** 一起听邀请卡片 */
    INVITE,

    /** 图片 / 表情 */
    IMAGE,

    /** 其他暂不识别的内容 */
    UNKNOWN,
}

/** 一条聊天消息 */
data class ChatMessage(
    val id: Long,
    val time: Long,
    /** 是否由「我」发出 */
    val fromMe: Boolean,
    val senderId: Long = 0L,
    val kind: ChatMessageKind = ChatMessageKind.TEXT,
    val text: String = "",
    /** 歌曲卡片 */
    val songId: String? = null,
    val songName: String? = null,
    val songArtist: String? = null,
    val coverUrl: String? = null,
    /** 歌单 / 专辑卡片 */
    val cardId: String? = null,
    val cardTitle: String? = null,
    val cardSubtitle: String? = null,
    val cardCoverUrl: String? = null,
    /** 本地乐观发送中（尚未确认成功） */
    val pending: Boolean = false,
    /** 本地发送失败 */
    val failed: Boolean = false,
)

/** 聊天页数据状态 */
sealed interface ChatListState {
    /** 未登录网易云 */
    data object NotLoggedIn : ChatListState

    data object Loading : ChatListState

    data class Ready(val conversations: List<ChatConversation>) : ChatListState

    data class Error(val message: String) : ChatListState
}

/** 会话详情页数据状态 */
sealed interface ChatThreadState {
    data object Loading : ChatThreadState

    data class Ready(val messages: List<ChatMessage>) : ChatThreadState

    data class Error(val message: String) : ChatThreadState
}
