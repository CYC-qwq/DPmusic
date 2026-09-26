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

/**
     * 歌单全量歌曲解析（**大歌单专项优化**）。
     *
     * 旧实现有三个坑，表现就是「歌单歌曲数一多就获取不到」：
     * 1. `playlist/detail?n=100000` 让服务端把整个歌单的 tracks 正文都序列化回来，
     *    上千首时响应体可达数 MB，20s 读超时直接失败；
     * 2. 批量取详情走 **GET + URL 拼 500 个 id**，URL 长度上万字符，被网关截断 / 414；
     * 3. 任一子请求抛异常，整条链路即失败，没有任何降级。
     *
     * 现方案：
     * ① 主路径 `playlist/track/all` **分页**拉取（每页 500，天然支持超大歌单）；
     * ② 兜底路径：取 trackIds（`n=0`，响应体与歌单规模无关）→ **POST 表单**批量取详情
     *    （`c=[...]` 放在 body，彻底规避 URL 长度限制）；
     * ③ 分片 200/批，单批失败自动拆半重试（200 → 50 → 12），部分失败不拖垮整体；
     * ④ 全程带上限与防死循环保护。
     */
    override suspend fun playlistSongs(playlistId: String): List<Song> {
        val paged = fetchSongsByPaging(playlistId)
        if (paged.isNotEmpty()) return paged

        val ids = fetchTrackIds(playlistId)
        if (ids.isNotEmpty()) {
            val songs = fetchSongDetails(ids)
            if (songs.isNotEmpty()) return songs
        }
        return emptyList()
    }

    /** 主路径：`playlist/track/all` 分页拉全量（对超大歌单最稳，响应体线性可控） */
    private suspend fun fetchSongsByPaging(playlistId: String): List<Song> {
        val songs = ArrayList<Song>()
        var offset = 0
        var lastFirstId: String? = null
        while (songs.size < MAX_PLAYLIST_SONGS) {
            val raw = runCatching {
                Http.get(
                    "https://music.163.com/api/playlist/track/all?id=$playlistId" +
                        "&limit=$PAGE_SIZE&offset=$offset",
                    referer = "https://music.163.com/playlist?id=$playlistId",
                )
            }.getOrNull() ?: break
            val page = runCatching {
                parseJsonPayload(raw).arrOrNull("songs")?.mapNotNull { it.toSong() }
            }.getOrNull().orEmpty()
            if (page.isEmpty()) break
            // 服务端忽略 offset（重复返回同一页）时立即止损，避免死循环
            if (page.first().id == lastFirstId) break
            lastFirstId = page.first().id
            songs += page
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return songs
    }

    /** 兜底：只取 id 列表（`n=0` → 不返回 tracks 正文，响应体与歌单规模无关） */
    private suspend fun fetchTrackIds(playlistId: String): List<Long> {
        val detail = runCatching {
            parseJsonPayload(
                Http.get(
                    "https://music.163.com/api/v6/playlist/detail?id=$playlistId&n=0&s=0",
                    referer = "https://music.163.com/playlist?id=$playlistId",
                ),
            )
        }.getOrNull() ?: return emptyList()
        val playlist = detail.objOrNull("playlist") ?: return emptyList()
        return playlist.arrOrNull("trackIds")?.mapNotNull { it.long("id") }
            ?: playlist.arrOrNull("tracks")?.mapNotNull { it.long("id") }
            ?: emptyList()
    }

    /** 批量取详情：POST 表单（body 承载 id 列表）+ 失败自动缩片重试 */
    private suspend fun fetchSongDetails(ids: List<Long>): List<Song> {
        val songs = ArrayList<Song>(ids.size)
        var index = 0
        var chunk = CHUNK_SIZE
        while (index < ids.size) {
            val slice = ids.subList(index, minOf(index + chunk, ids.size))
            val batch = runCatching { fetchSongDetailBatch(slice) }.getOrNull()
            if (batch == null) {
                // 单批失败：缩小分片重试；已到最小分片仍失败则跳过该批，保住其余歌曲
                if (chunk > MIN_CHUNK_SIZE) {
                    chunk = maxOf(MIN_CHUNK_SIZE, chunk / 4)
                    continue
                }
                index += slice.size
                chunk = CHUNK_SIZE
                continue
            }
            songs += batch
            index += slice.size
            chunk = CHUNK_SIZE
        }
        return songs
    }

    private suspend fun fetchSongDetailBatch(ids: List<Long>): List<Song> {
        val c = ids.joinToString(",", prefix = "[", postfix = "]") { "{\"id\":$it}" }
        val raw = Http.postForm(
            "https://music.163.com/api/v3/song/detail",
            mapOf("c" to c),
            referer = "https://music.163.com/",
        )
        return parseJsonPayload(raw).arrOrNull("songs")?.mapNotNull { it.toSong() } ?: emptyList()
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

    companion object {
        /** 单歌单歌曲上限（防御异常歌单 / 服务端 offset 失效导致的死循环） */
        private const val MAX_PLAYLIST_SONGS = 10_000

        /** 分页接口每页条数 */
        private const val PAGE_SIZE = 500

        /** 批量取详情分片大小（走 POST body，无 URL 长度压力） */
        private const val CHUNK_SIZE = 200

        /** 单批失败后的最小分片（低于该值仍失败则跳过该批） */
        private const val MIN_CHUNK_SIZE = 12
    }
}