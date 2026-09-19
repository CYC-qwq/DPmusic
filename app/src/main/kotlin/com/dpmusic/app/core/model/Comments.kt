package com.dpmusic.app.core.model

/** 评论条目（网易云 / QQ 音乐通用展示模型） */
data class CommentItem(
    val id: String,
    val nickname: String,
    val avatarUrl: String,
    val content: String,
    val likedCount: Int,
    /** 发布时间戳（毫秒） */
    val timeMs: Long,
)

/** 评论分页结果（hot = 热门评论；items = 最新/普通评论） */
data class CommentsPage(
    val hot: List<CommentItem> = emptyList(),
    val items: List<CommentItem> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
)
