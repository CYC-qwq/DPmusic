package com.dpmusic.app.ui.navigation

import kotlinx.serialization.Serializable

/* 类型安全路由（Navigation Compose 2.8+ 序列化路由体系） */

@Serializable
data object HomeRoute

@Serializable
data object SearchRoute

@Serializable
data object RankRoute

@Serializable
data class RankDetailRoute(
    val platform: String,
    val rankId: String,
    val title: String,
)

@Serializable
data object PlaylistRoute

@Serializable
data class PlaylistDetailRoute(
    val platform: String,
    val playlistId: String,
    val title: String,
)

/** 本地用户歌单详情 */
@Serializable
data class UserPlaylistDetailRoute(
    val playlistId: String,
)

@Serializable
data object MineRoute

@Serializable
data object SettingsRoute

@Serializable
data object TogetherRoute
/** 网易云私信会话列表（消息） */
@Serializable
data object ChatListRoute
/** 与某个好友的私信会话详情 */
@Serializable
data class ChatThreadRoute(
    val userId: Long,
    val nickname: String,
    val avatar: String = "",
)

/** 网易云「每日推荐」列表 */
@Serializable
data object DailySongsRoute

/** 网易云「我的歌单」列表 */
@Serializable
data object NcmPlaylistsRoute

/** QQ 音乐「我的歌单」列表 */
@Serializable
data object QqPlaylistsRoute

/** QQ 音乐推荐列表（source：radio=猜你喜欢 / radar=雷达） */
@Serializable
data class QqRecommendRoute(
    val source: String,
)

/** 运行日志 */
@Serializable
data object LogsRoute

/** 音源管理（LX Music 自定义音源脚本） */
@Serializable
data object SourceManagerRoute

/** 网易云歌手详情（name 用于头部即时显示） */
@Serializable
data class ArtistDetailRoute(
    val artistId: String,
    val name: String,
)

/** 网易云专辑详情 */
@Serializable
data class AlbumDetailRoute(
    val albumId: String,
)

/** 下载管理（任务队列 / 历史记录） */
@Serializable
data object DownloadManagerRoute

/** 数据同步（WebDAV 备份 / 恢复） */
@Serializable
data object SyncRoute
