package com.dpmusic.app.core.net

import com.dpmusic.app.core.lyric.LrcParser
import com.dpmusic.app.core.lyric.YrcParser
import com.dpmusic.app.core.model.CommentItem
import com.dpmusic.app.core.model.CommentsPage
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.RankSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import kotlinx.serialization.json.JsonElement

/**
 * 网易云音乐 API（全部实测通过）：
 * - 搜索：/api/cloudsearch/pc（result.songs[]: id/name/ar[].name/al.picUrl/dt(ms)）
 * - 歌单搜索：/api/search/get/web?type=1000
 * - 歌单/榜单歌曲：/api/v6/playlist/detail + /api/v3/song/detail 批量（500/批）
 * - 歌词：/api/song/lyric/v1（yrc 逐字 + lrc / tlyric 行级回退）
 * - 榜单：/api/toplist/detail
 */
class WyApi : PlatformApi {

    override val platform = MusicPlatform.WY

    override suspend fun searchSongs(keyword: String, page: Int, limit: Int): List<Song> {
        val offset = (page - 1) * limit
        val raw = Http.get(
            "https://music.163.com/api/cloudsearch/pc?s=${urlEnc(keyword)}&type=1&offset=$offset&limit=$limit",
            referer = "https://music.163.com/",
        )
        val json = parseJsonPayload(raw)
        return json.objOrNull("result")?.arrOrNull("songs")?.mapNotNull { it.toSong() } ?: emptyList()
    }

    /**
     * 搜索联想：/api/search/suggest/web（实测可用，GET 即可）
     * 返回 result.songs / artists / albums 三组建议；歌曲建议拼成「歌名 歌手」便于直接搜索。
     */
    override suspend fun searchSuggest(keyword: String): List<String> {
        val raw = Http.get(
            "https://music.163.com/api/search/suggest/web?s=${urlEnc(keyword)}&limit=10",
            referer = "https://music.163.com/",
        )
        val result = parseJsonPayload(raw).objOrNull("result") ?: return emptyList()
        val out = mutableListOf<String>()
        result.arrOrNull("songs")?.forEach { s ->
            val name = s.str("name")?.takeIf { it.isNotBlank() } ?: return@forEach
            val artist = s.arrOrNull("artists")?.firstOrNull()?.str("name").orEmpty()
            out += if (artist.isNotBlank()) "$name $artist" else name
        }
        result.arrOrNull("artists")?.forEach { a ->
            a.str("name")?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        result.arrOrNull("albums")?.forEach { a ->
            a.str("name")?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out.distinct().take(10)
    }

    /** 热搜榜（/api/search/chart/detail 匿名可用）：itemList[].searchWord */
    override suspend fun hotSearch(): List<String> {
        val raw = Http.get(
            "https://music.163.com/api/search/chart/detail?id=HOT_SEARCH_SONG%23%40%23",
            referer = "https://music.163.com/",
        )
        val list = parseJsonPayload(raw).objOrNull("data")?.arrOrNull("itemList") ?: return emptyList()
        return list.mapNotNull { it.str("searchWord")?.takeIf { w -> w.isNotBlank() } }.take(20)
    }

    override suspend fun songDetail(id: String): Song? {
        val c = urlEnc("""[{"id":$id}]""")
        val json = parseJsonPayload(
            Http.get(
                "https://music.163.com/api/v3/song/detail?c=$c",
                referer = "https://music.163.com/",
            )
        )
        return json.arrOrNull("songs")?.firstOrNull()?.toSong()
    }

    /** 批量歌曲详情（一起听房间歌单用）：/api/v3/song/detail 分片请求（500/批） */
    override suspend fun songsDetail(ids: List<String>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<Song>()
        ids.distinct().chunked(500).forEach { chunk ->
            val c = chunk.joinToString(",", prefix = "[", postfix = "]") { "{\"id\":$it}" }
            val batch = parseJsonPayload(
                Http.get(
                    "https://music.163.com/api/v3/song/detail?c=${urlEnc(c)}",
                    referer = "https://music.163.com/",
                )
            )
            batch.arrOrNull("songs")?.forEach { el -> el.toSong()?.let(out::add) }
        }
        return out
    }

    override suspend fun searchPlaylists(keyword: String, page: Int, limit: Int): List<PlaylistSummary> {
        val offset = (page - 1) * limit
        val raw = Http.get(
            "https://music.163.com/api/search/get/web?s=${urlEnc(keyword)}&type=1000&offset=$offset&limit=$limit",
            referer = "https://music.163.com/",
        )
        val json = parseJsonPayload(raw)
        return json.objOrNull("result")?.arrOrNull("playlists")?.mapNotNull { p ->
            val id = p.long("id")?.toString() ?: return@mapNotNull null
            PlaylistSummary(
                id = id,
                platform = MusicPlatform.WY,
                name = p.str("name").orEmpty(),
                coverUrl = p.str("coverImgUrl").orEmpty().toHttps(),
                trackCount = p.int("trackCount") ?: 0,
                playCount = p.long("playCount") ?: 0L,
            )
        } ?: emptyList()
    }

    override suspend fun playlistSongs(playlistId: String): List<Song> {
        val detail = parseJsonPayload(
            Http.get(
                "https://music.163.com/api/v6/playlist/detail?id=$playlistId&n=100000&s=0",
                referer = "https://music.163.com/playlist?id=$playlistId",
            )
        )
        val playlist = detail.objOrNull("playlist")
        val ids = playlist?.arrOrNull("trackIds")?.mapNotNull { it.long("id") }
            ?: playlist?.arrOrNull("tracks")?.mapNotNull { it.long("id") }
            ?: emptyList()
        if (ids.isEmpty()) return emptyList()

        val songs = mutableListOf<Song>()
        ids.chunked(500).forEach { chunk ->
            val c = chunk.joinToString(",", prefix = "[", postfix = "]") { "{\"id\":$it}" }
            val batch = parseJsonPayload(
                Http.get(
                    "https://music.163.com/api/v3/song/detail?c=${urlEnc(c)}",
                    referer = "https://music.163.com/",
                )
            )
            batch.arrOrNull("songs")?.forEach { el -> el.toSong()?.let(songs::add) }
        }
        return songs
    }

    override suspend fun playlistMeta(playlistId: String): PlaylistSummary? {
        val detail = parseJsonPayload(
            Http.get(
                "https://music.163.com/api/v6/playlist/detail?id=$playlistId&n=1&s=0",
                referer = "https://music.163.com/playlist?id=$playlistId",
            )
        )
        val pl = detail.objOrNull("playlist") ?: return null
        val name = pl.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return PlaylistSummary(
            id = playlistId,
            platform = MusicPlatform.WY,
            name = name,
            coverUrl = pl.str("coverImgUrl").orEmpty().toHttps(),
            trackCount = pl.int("trackCount") ?: 0,
            playCount = pl.long("playCount") ?: 0L,
            creator = pl.objOrNull("creator")?.str("nickname").orEmpty(),
        )
    }

    override suspend fun toplists(): List<RankSummary> {
        val json = parseJsonPayload(
            Http.get("https://music.163.com/api/toplist/detail", referer = "https://music.163.com/discover/toplist")
        )
        return json.arrOrNull("list")?.mapNotNull { r ->
            val id = r.long("id")?.toString() ?: return@mapNotNull null
            RankSummary(
                id = id,
                platform = MusicPlatform.WY,
                name = r.str("name").orEmpty(),
                coverUrl = r.str("coverImgUrl").orEmpty().toHttps(),
                updateFrequency = r.str("updateFrequency").orEmpty(),
                description = r.str("description").orEmpty(),
            )
        } ?: emptyList()
    }

    /** 网易云榜单即歌单：复用 playlistSongs */
    override suspend fun rankSongs(rankId: String): List<Song> = playlistSongs(rankId)

    override suspend fun lyrics(song: Song): SongLyrics {
        // 同时请求行级（lrc / tlyric）与逐字（yrc）：优先逐字渲染，无逐字数据自动回退行级
        val json = parseJsonPayload(
            Http.get(
                "https://music.163.com/api/song/lyric/v1?id=${song.id}&cp=false&lv=0&kv=0&tv=0&rv=0&yv=1&ytv=1&yrv=1",
                referer = "https://music.163.com/",
            )
        )
        val lrc = json.objOrNull("lrc")?.str("lyric").orEmpty()
        val tlrc = json.objOrNull("tlyric")?.str("lyric").orEmpty()
        val yrc = json.objOrNull("yrc")?.str("lyric").orEmpty()
        if (yrc.isNotBlank()) {
            val parsed = YrcParser.parse(yrc, tlrc)
            if (parsed.lines.isNotEmpty()) return parsed
        }
        return LrcParser.parse(lrc, tlrc)
    }

    /** 歌曲评论：R_SO_4 资源接口（热门 + 最新，分页） */
    override suspend fun comments(songId: String, page: Int, limit: Int): CommentsPage? {
        val offset = (page - 1).coerceAtLeast(0) * limit
        val raw = Http.get(
            "https://music.163.com/api/v1/resource/comments/R_SO_4_$songId?limit=$limit&offset=$offset",
            referer = "https://music.163.com/song?id=$songId",
        )
        val json = parseJsonPayload(raw)
        if ((json.int("code") ?: 0) != 200) return null
        fun parseList(arr: List<JsonElement>?): List<CommentItem> = arr?.mapNotNull { c ->
            val content = c.str("content")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CommentItem(
                id = c.long("commentId")?.toString().orEmpty(),
                nickname = c.objOrNull("user")?.str("nickname").orEmpty(),
                avatarUrl = c.objOrNull("user")?.str("avatarUrl").orEmpty().toHttps(),
                content = content,
                likedCount = c.int("likedCount") ?: 0,
                timeMs = c.long("time") ?: 0L,
            )
        } ?: emptyList()
        val hot = parseList(json.arrOrNull("hotComments"))
        val items = parseList(json.arrOrNull("comments"))
        return CommentsPage(
            hot = hot,
            items = items,
            total = json.int("total") ?: 0,
            hasMore = json.bool("more") ?: (items.size >= limit),
        )
    }

    /* ---------- 字段映射 ---------- */

    private fun JsonElement.toSong(): Song? {
        val id = long("id")?.toString() ?: return null
        val title = str("name") ?: return null
        val artistArr = arrOrNull("ar")
        val artist = artistArr?.mapNotNull { it.str("name") }?.joinToString("/").orEmpty()
        val albumObj = objOrNull("al")
        return Song(
            id = id,
            platform = MusicPlatform.WY,
            title = title,
            artist = artist.ifBlank { "未知歌手" },
            album = albumObj?.str("name").orEmpty(),
            durationMs = long("dt") ?: 0L,
            coverUrl = albumObj?.str("picUrl").orEmpty().toHttps(),
            extra = buildMap {
                artistArr?.firstOrNull()?.long("id")?.toString()?.let { put("wy_artist_id", it) }
                albumObj?.long("id")?.toString()?.let { put("wy_album_id", it) }
            },
        )
    }
}