package com.dpmusic.app.core.net

import com.dpmusic.app.core.model.CommentsPage
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.RankSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics

/**
 * 三平台统一能力接口。
 * 每个平台实现均基于本地实测通过的公开接口（详见 docs/ARCHITECTURE.md）。
 */
interface PlatformApi {

    val platform: com.dpmusic.app.core.model.MusicPlatform

    /** 歌曲搜索 */
    suspend fun searchSongs(keyword: String, page: Int = 1, limit: Int = 30): List<Song>

    /** 单曲详情（链接解析场景）：按平台内 ID 精确获取；无法获取时返回 null */
    suspend fun songDetail(id: String): Song? = null

    /** 批量歌曲详情（一起听房间歌单等场景） */
    suspend fun songsDetail(ids: List<String>): List<Song> = emptyList()

    /** 歌单搜索 / 浏览 */
    suspend fun searchPlaylists(keyword: String, page: Int = 1, limit: Int = 30): List<PlaylistSummary>

    /** 歌单全量歌曲解析 */
    suspend fun playlistSongs(playlistId: String): List<Song>
    /** 歌单元信息（链接导入场景）：名称 / 封面；无法获取时返回 null，由调用方兜底命名 */
    suspend fun playlistMeta(playlistId: String): PlaylistSummary? = null

    /** 在线榜单列表 */
    suspend fun toplists(): List<RankSummary>

    /** 榜单歌曲 */
    suspend fun rankSongs(rankId: String): List<Song>

    /** 逐行动态歌词（含翻译合并） */
    suspend fun lyrics(song: Song): SongLyrics

    /** 搜索联想（搜索框输入预测）：返回建议关键词列表；无建议 / 失败返回空列表 */
    suspend fun searchSuggest(keyword: String): List<String> = emptyList()

    /** 热搜词列表（搜索页空态展示）；平台不支持 / 失败返回空列表 */
    suspend fun hotSearch(): List<String> = emptyList()

    /** 歌曲评论（分页）；平台不支持时返回 null */
    suspend fun comments(songId: String, page: Int = 1, limit: Int = 20): CommentsPage? = null
}