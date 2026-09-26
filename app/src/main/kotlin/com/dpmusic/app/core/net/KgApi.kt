package com.dpmusic.app.core.net

import android.util.Base64
import com.dpmusic.app.core.lyric.KrcParser
import com.dpmusic.app.core.lyric.LrcParser
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.RankSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import kotlinx.serialization.json.JsonElement

/**
 * 酷狗音乐 API（全部实测通过）：
 * - 搜索：mobilecdn /api/v3/search/song（data.info[]: hash/songname/singername/trans_param.union_cover/duration(s)）
 * - 歌单搜索：msearch.kugou.com /api/v3/search/special
 * - 歌单歌曲：mobilecdn /api/v3/special/song（分页 100/页，含 filename "歌手 - 歌名" 兜底解析）
 * - 榜单：m.kugou.com /rank/list + mobilecdn /api/v3/rank/song
 * - 歌词：krcs.kugou.com 搜索 + lyrics.kugou.com 下载（KRC 逐字 / LRC 行级回退）
 * - 封面：trans_param.union_cover（{size} 占位替换）
 */
class KgApi : PlatformApi {

    override val platform = MusicPlatform.KG

    override suspend fun searchSongs(keyword: String, page: Int, limit: Int): List<Song> {
        val raw = Http.get(
            "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=${urlEnc(keyword)}" +
                "&page=$page&pagesize=$limit&showtype=1",
            referer = "https://www.kugou.com/",
        )
        return parseJsonPayload(raw).objOrNull("data")?.arrOrNull("info")
            ?.mapNotNull { it.toSong() } ?: emptyList()
    }

    /**
     * 搜索联想：searchtip.kugou.com/getSearchTip（实测可用）
     * 响应 data[] 为「歌曲 / MV / 专辑」等多组建议，逐组取 RecordDatas[].HintInfo；
     * 过滤 MV 组（长句噪声）与超长文本，保持建议可读、可搜索。
     */
    override suspend fun searchSuggest(keyword: String): List<String> {
        val raw = Http.get(
            "https://searchtip.kugou.com/getSearchTip?keyword=${urlEnc(keyword)}&MusicTipCount=10&CloudTipCount=0",
            referer = "https://www.kugou.com/",
        )
        val json = parseJsonPayload(raw)
        val out = mutableListOf<String>()
        json.arrOrNull("data")?.forEach { group ->
            group.arrOrNull("RecordDatas")?.forEach { item ->
                val hint = item.str("HintInfo")?.takeIf { it.isNotBlank() } ?: return@forEach
                val use = item.str("Use").orEmpty()
                if (use.contains("mv") || hint.length > 40) return@forEach
                out += hint
            }
        }
        return out.distinct().take(10)
    }

    /** 热搜榜（gateway.kugou.com hot_tab）：data.list[].keywords[].keyword */
    override suspend fun hotSearch(): List<String> {
        val raw = Http.get(
            "http://gateway.kugou.com/api/v3/search/hot_tab?signature=ee44edb9d7155821412d220bcaf509dd&appid=1005&clientver=10026&plat=0",
            referer = "https://www.kugou.com/",
            headers = mapOf(
                "dfid" to "1ssiv93oVqMp27cirf2CvoF1",
                "mid" to "156798703528610303473757548878786007104",
                "clienttime" to "1584257267",
                "x-router" to "msearch.kugou.com",
                "user-agent" to "Android9-AndroidPhone-10020-130-0-searchrecommendprotocol-wifi",
                "kg-rc" to "1",
            ),
        )
        val groups = parseJsonPayload(raw).objOrNull("data")?.arrOrNull("list") ?: return emptyList()
        val out = mutableListOf<String>()
        groups.forEach { group ->
            group.arrOrNull("keywords")?.forEach { item ->
                item.str("keyword")?.takeIf { k -> k.isNotBlank() }?.let { out += it }
            }
        }
        return out.distinct().take(20)
    }

    override suspend fun songDetail(id: String): Song? {
        val json = parseJsonPayload(
            Http.get(
                "http://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$id",
                referer = "https://www.kugou.com/",
            )
        )
        val hash = json.str("hash")?.lowercase()?.takeIf { it.isNotBlank() } ?: id
        val title = json.str("songName")?.takeIf { it.isNotBlank() } ?: return null
        val artist = json.str("singerName").orEmpty().ifBlank { json.str("author_name").orEmpty() }
        return Song(
            id = hash,
            platform = MusicPlatform.KG,
            title = title,
            artist = artist.ifBlank { "未知歌手" },
            durationMs = (json.int("timeLength") ?: 0).toLong() * 1000L,
            coverUrl = json.str("album_img").orEmpty().replace("{size}", "480").toHttps(),
        )
    }

    override suspend fun searchPlaylists(keyword: String, page: Int, limit: Int): List<PlaylistSummary> {
        val raw = Http.get(
            "https://msearch.kugou.com/api/v3/search/special?format=json&keyword=${urlEnc(keyword)}" +
                "&page=$page&pagesize=$limit&showtype=1",
            referer = "https://www.kugou.com/",
        )
        return parseJsonPayload(raw).objOrNull("data")?.arrOrNull("info")
            ?.mapNotNull { it.toPlaylist() } ?: emptyList()
    }

    /**
     * 歌单全量歌曲（**大歌单专项**）。
     *
     * 旧实现：10 页 × 100 = 最多 1000 首，超出部分静默丢失；且任一页请求抛异常
     * 会让整个歌单加载失败（表现就是「歌曲数一多就获取不到」）。
     * 现实现：页数上限提升到 [MAX_PAGES]（6000 首），单页失败自动重试一次，
     * 仍失败则返回已取到的部分（部分可用优于全盘失败）。
     */
    override suspend fun playlistSongs(playlistId: String): List<Song> {
        val result = mutableListOf<Song>()
        var page = 1
        while (page <= MAX_PAGES) {
            val data = fetchSongPage(playlistId, page) ?: break
            val list = data.arrOrNull("info").orEmpty()
            if (list.isEmpty()) break
            list.forEach { el -> el.toSong()?.let(result::add) }
            val total = data.int("total") ?: 0
            if (result.size >= total || list.size < PAGE_SIZE) break
            page++
        }
        return result
    }

    /** 取单页歌曲；失败重试一次后仍失败返回 null（调用方据此结束分页，保留已取结果） */
    private suspend fun fetchSongPage(playlistId: String, page: Int): JsonElement? {
        repeat(2) { attempt ->
            val raw = runCatching {
                Http.get(
                    "http://mobilecdn.kugou.com/api/v3/special/song?specialid=$playlistId&page=$page" +
                        "&pagesize=$PAGE_SIZE&version=9108&area_code=1&with_res_tag=0",
                    referer = "https://www.kugou.com/",
                )
            }.getOrNull() ?: return@repeat
            val data = runCatching { parseJsonPayload(raw).objOrNull("data") }.getOrNull()
            if (data != null) return data
            if (attempt == 0) kotlinx.coroutines.delay(300L * (attempt + 1))
        }
        return null
    }

    override suspend fun playlistMeta(playlistId: String): PlaylistSummary? {
        // 实测专用接口（JSON）：specialname / imgurl({size}占位) / songcount / nickname
        val raw = Http.get(
            "http://mobilecdn.kugou.com/api/v3/special/info?specialid=$playlistId&version=9108&area_code=1&with_res_tag=0",
            referer = "https://www.kugou.com/",
        )
        val data = parseJsonPayload(raw).objOrNull("data") ?: return null
        val name = data.str("specialname")?.takeIf { it.isNotBlank() } ?: return null
        return PlaylistSummary(
            id = playlistId,
            platform = MusicPlatform.KG,
            name = name,
            coverUrl = data.str("imgurl").orEmpty().replace("{size}", "480").toHttps(),
            trackCount = data.int("songcount") ?: 0,
            playCount = data.long("playcount") ?: 0L,
            creator = data.str("nickname").orEmpty(),
        )
    }

    override suspend fun toplists(): List<RankSummary> {
        val raw = Http.get("https://m.kugou.com/rank/list?json=true", referer = "https://m.kugou.com/rank/list")
        val json = parseJsonPayload(raw)
        return json.objOrNull("rank")?.arrOrNull("list")?.mapNotNull { r ->
            val id = r.long("rankid")?.toString() ?: return@mapNotNull null
            RankSummary(
                id = id,
                platform = MusicPlatform.KG,
                name = r.str("rankname").orEmpty(),
                coverUrl = r.str("img_9").orEmpty().replace("{size}", "240").toHttps(),
                updateFrequency = r.str("update_frequency").orEmpty(),
                description = r.str("intro").orEmpty(),
            )
        } ?: emptyList()
    }

    override suspend fun rankSongs(rankId: String): List<Song> {
        val raw = Http.get(
            "http://mobilecdn.kugou.com/api/v3/rank/song?rankid=$rankId&page=1&pagesize=100" +
                "&version=9108&area_code=1&with_res_tag=0",
            referer = "https://www.kugou.com/",
        )
        return parseJsonPayload(raw).objOrNull("data")?.arrOrNull("info")
            ?.mapNotNull { it.toSong() } ?: emptyList()
    }

    override suspend fun lyrics(song: Song): SongLyrics {
        // ① 候选搜索（krcs / mobi；空格会破坏匹配 → 用「歌名-歌手」，为空再退回「歌名」）
        val candidate = searchLyricCandidate(song) ?: return SongLyrics.EMPTY
        val lyricId = candidate.long("id") ?: return SongLyrics.EMPTY
        val accessKey = candidate.str("accesskey") ?: return SongLyrics.EMPTY

        // ② 优先 KRC 逐字
        val krcContent = runCatching { downloadLyricContent(lyricId, accessKey, "krc") }.getOrNull()
        if (krcContent != null) {
            val text = KrcParser.decrypt(Base64.decode(krcContent, Base64.DEFAULT))
            if (!text.isNullOrBlank()) {
                val parsed = KrcParser.parse(text)
                if (parsed.lines.isNotEmpty()) return parsed
            }
        }

        // ③ 回退行级 LRC
        val lrcContent = runCatching { downloadLyricContent(lyricId, accessKey, "lrc") }.getOrNull()
            ?: return SongLyrics.EMPTY
        val lrc = String(Base64.decode(lrcContent, Base64.DEFAULT), Charsets.UTF_8)
        return LrcParser.parse(lrc, "")
    }

    /** 歌词候选搜索：优先「歌名-歌手」关键词（空格会导致匹配为空），无结果时退回「歌名」 */
    private suspend fun searchLyricCandidate(song: Song): JsonElement? {
        val keywords = listOf("${song.title}-${song.artist}", song.title)
        for (keyword in keywords) {
            if (keyword.isBlank()) continue
            val raw = runCatching {
                Http.get(
                    "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=${urlEnc(keyword)}" +
                        "&duration=${song.durationMs}&hash=${song.id}",
                    referer = "https://www.kugou.com/",
                )
            }.getOrNull() ?: continue
            val first = runCatching { parseJsonPayload(raw).arrOrNull("candidates")?.firstOrNull() }.getOrNull()
            if (first != null) return first
        }
        return null
    }

    /** 下载歌词内容（base64 字符串）；fmt = krc / lrc */
    private suspend fun downloadLyricContent(id: Long, accessKey: String, fmt: String): String? {
        val raw = Http.get(
            "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=$fmt&charset=utf8",
            referer = "https://www.kugou.com/",
        )
        return parseJsonPayload(raw).str("content")?.takeIf { it.isNotBlank() }
    }

    /* ---------- 字段映射 ---------- */

    private fun JsonElement.toSong(): Song? {
        val hash = str("hash") ?: return null
        var title = str("songname")?.takeIf { it.isNotBlank() } ?: str("filename") ?: return null
        var artist = str("singername").orEmpty()

        // 部分接口（榜单/歌单）无 singername，filename 形如 "歌手 - 歌名"
        if (artist.isBlank()) {
            val fn = str("filename").orEmpty()
            if (fn.contains(" - ")) {
                val parts = fn.split(" - ", limit = 2)
                artist = parts[0].trim()
                if (str("songname").isNullOrBlank() && parts.size > 1) title = parts[1].trim()
            }
        }

        val cover = objOrNull("trans_param")?.str("union_cover").orEmpty()
            .replace("{size}", "480")
            .toHttps()

        return Song(
            id = hash,
            platform = MusicPlatform.KG,
            title = title,
            artist = artist.ifBlank { "未知歌手" },
            album = str("album_name").orEmpty(),
            durationMs = (int("duration") ?: 0).toLong() * 1000L,
            coverUrl = cover,
        )
    }

    private fun JsonElement.toPlaylist(): PlaylistSummary? {
        val id = long("specialid")?.toString() ?: return null
        return PlaylistSummary(
            id = id,
            platform = MusicPlatform.KG,
            name = str("specialname").orEmpty(),
            coverUrl = str("imgurl").orEmpty().replace("{size}", "480").toHttps(),
            trackCount = int("songcount") ?: 0,
            playCount = long("playcount") ?: 0L,
            creator = str("nickname").orEmpty(),
        )
    }

    companion object {
        /** 每页条数（酷狗 mobilecdn 接口的稳定值） */
        private const val PAGE_SIZE = 100

        /** 分页上限：60 页 = 最多 6000 首（远超常规歌单，同时防御异常歌单死循环） */
        private const val MAX_PAGES = 60
    }
}