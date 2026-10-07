package com.dpmusic.app.core.model

/**
 * 评论的一条回复（楼中楼）。
 *
 * ⚠️ **平台差异（实测结论，勿想当然）**：
 * - **网易云**：`beReplied` 字段可用，但实测每 100 条评论里只有约 3 条带该字段，
 *   且每条的 `beReplied` **最多 1 个元素**（即「被回复的那一条」，不是完整楼层列表）；
 *   该对象**没有 `time` 字段**，所以 [timeMs] 为 0（UI 应跳过时间展示）。
 * - **QQ 音乐**：接口未提供回复数据。评论项字段里**没有** `commentcount` / `replycount`；
 *   `cmd=9` 返回的是「热评流」（固定 total=4000，换 commentId 结果不变），**不是楼中楼**。
 *   因此 QQ 的 [CommentItem.replies] 恒为空。
 * - **酷狗**：评论接口整体已失效（需签名 + 未公开参数），不实现。
 */
data class CommentReply(
    val id: String,
    val nickname: String,
    val avatarUrl: String,
    val content: String,
    /** 发布时间戳（毫秒）；网易云 `beReplied` 无此字段时为 0，UI 应跳过时间展示 */
    val timeMs: Long = 0L,
    val likedCount: Int = 0,
)

/** 评论条目（网易云 / QQ 音乐通用展示模型） */
data class CommentItem(
    val id: String,
    val nickname: String,
    val avatarUrl: String,
    val content: String,
    val likedCount: Int,
    /** 发布时间戳（毫秒） */
    val timeMs: Long,
    /** 已获取到的回复（可能不完整，见 [CommentReply] 的平台差异说明） */
    val replies: List<CommentReply> = emptyList(),
    /** 服务端声明的回复总数；0 表示未知（此时 UI 用 [replies] 的条数） */
    val replyCount: Int = 0,
) {
    /** 实际可展示的回复条数（服务端总数缺失时回退到已获取条数） */
    val displayReplyCount: Int get() = if (replyCount > 0) replyCount else replies.size

    /** 是否有回复可展开 */
    val hasReplies: Boolean get() = replies.isNotEmpty()
}

/** 评论分页结果（hot = 热门评论；items = 最新/普通评论） */
data class CommentsPage(
    val hot: List<CommentItem> = emptyList(),
    val items: List<CommentItem> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
)