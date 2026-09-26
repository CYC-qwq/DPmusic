package com.dpmusic.app.core.net

import com.dpmusic.app.core.lyric.LrcParser
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.dpmusic.app.core.lyric.QrcDecoder
import com.dpmusic.app.core.lyric.QrcParser
import com.dpmusic.app.core.model.CommentItem
import com.dpmusic.app.core.model.CommentsPage
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.QqPlaylist
import com.dpmusic.app.core.model.QqProfile
import com.dpmusic.app.core.model.RankSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

/**
 * QQ 音乐 API（全部实测通过，new_json 字段体系）：
 * - 搜索：client_search_cp?new_json=1（data.song.list[]: mid/title/singer[].name/album.mid/interval(s)）
 * - 歌单：关键词搜索接口匿名态已失效 → 自动降级为分类浏览（fcg_get_diss_by_tag）
 * - 歌单歌曲：musicu music.srfDissInfo.aiDissInfo / uniform_get_Dissinfo（2022+ 新版网页接口）
 * - 榜单歌曲：fcg_v8_toplist_cp.fcg
 * - 榜单列表：musicu musicToplist.ToplistInfoServer / GetAll
 * - 歌词：musicu QRC 逐字（自定义 DES 解密）+ fcg_query_lyric_new.fcg 行级回退
 */
class QqApi(private val cookieProvider: () -> String = { "" }) : PlatformApi {

    override val platform = MusicPlatform.QQ

    override suspend fun searchSongs(keyword: String, page: Int, limit: Int): List<Song> {
        val raw = Http.get(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&new_json=1&w=${urlEnc(keyword)}" +
                "&p=$page&n=$limit&aggr=1&lossless=1&cr=1",
            referer = "https://y.qq.com/",
        )
        val json = parseJsonPayload(raw)
        return json.objOrNull("data")?.objOrNull("song")?.arrOrNull("list")
            ?.mapNotNull { it.toSong() } ?: emptyList()
    }

    /**
     * 搜索联想：smartbox_new.fcg（实测可用）
     * 返回 data.song / singer / album.itemlist；歌曲建议拼成「歌名 歌手」便于直接搜索。
     */
    override suspend fun searchSuggest(keyword: String): List<String> {
        val raw = Http.get(
            "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=${urlEnc(keyword)}&format=json",
            referer = "https://y.qq.com/",
        )
        val data = parseJsonPayload(raw).objOrNull("data") ?: return emptyList()
        val out = mutableListOf<String>()
        data.objOrNull("song")?.arrOrNull("itemlist")?.forEach { s ->
            val name = s.str("name")?.takeIf { it.isNotBlank() } ?: return@forEach
            val singer = s.str("singer").orEmpty()
            out += if (singer.isNotBlank() && singer != name) "$name $singer" else name
        }
        data.objOrNull("singer")?.arrOrNull("itemlist")?.forEach { a ->
            a.str("name")?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        data.objOrNull("album")?.arrOrNull("itemlist")?.forEach { a ->
            a.str("name")?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out.distinct().take(10)
    }

    /** 热搜榜（musicu hotkey 模块）：vec_hotkey[].query */
    override suspend fun hotSearch(): List<String> {
        val body = buildJsonObject {
            putJsonObject("comm") {
                put("ct", "19")
                put("cv", "1803")
                put("guid", "0")
                put("patch", "118")
                put("psrf_access_token_expiresAt", 0)
                put("psrf_qqaccess_token", "")
                put("psrf_qqopenid", "")
                put("psrf_qqunionid", "")
                put("tmeAppID", "qqmusic")
                put("tmeLoginType", 0)
                put("uin", "0")
                put("wid", "0")
            }
            putJsonObject("hotkey") {
                put("method", "GetHotkeyForQQMusicPC")
                put("module", "tencent_musicsoso_hotkey.HotkeyService")
                putJsonObject("param") {
                    put("search_id", "")
                    put("uin", 0)
                }
            }
        }.toString()
        val json = parseJsonPayload(
            Http.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body, referer = "https://y.qq.com/portal/player.html")
        )
        val list = json.objOrNull("hotkey")?.objOrNull("data")?.arrOrNull("vec_hotkey") ?: return emptyList()
        return list.mapNotNull { it.str("query")?.takeIf { q -> q.isNotBlank() } }.take(20)
    }

    override suspend fun songDetail(id: String): Song? {
        val raw = Http.get(
            "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?songmid=$id&platform=yqq&format=json",
            referer = "https://y.qq.com/n/ryqq/songDetail/$id",
        )
        return parseJsonPayload(raw).arrOrNull("data")?.firstOrNull()?.toSong()
    }

    /**
     * 歌单搜索：先尝试桌面端关键词搜索；
     * 匿名态失效（返回空）时降级为「歌单分类浏览」，保证页面永远有内容。
     */
    override suspend fun searchPlaylists(keyword: String, page: Int, limit: Int): List<PlaylistSummary> {
        val bySearch = runCatching { searchPlaylistsByKeyword(keyword, page, limit) }.getOrDefault(emptyList())
        if (bySearch.isNotEmpty()) return bySearch
        return browsePlaylists(page, limit)
    }

    private suspend fun searchPlaylistsByKeyword(keyword: String, page: Int, limit: Int): List<PlaylistSummary> {
        val safeKeyword = keyword.replace("\"", "").replace("\\", "")
        val body = buildString {
            append("{\"comm\":{\"ct\":24,\"cv\":0},\"req\":{\"module\":\"music.search.SearchCgiService\",")
            append("\"method\":\"DoSearchForQQMusicDesktop\",\"param\":{\"remoteplace\":\"txt.yqq.playlist\",")
            append("\"search_type\":3,\"query\":\"").append(safeKeyword).append("\",")
            append("\"page_num\":").append(page).append(",\"num_per_page\":").append(limit).append("}}}")
        }
        val json = parseJsonPayload(
            Http.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body, referer = "https://y.qq.com/")
        )
        val list = json.objOrNull("req")?.objOrNull("data")?.objOrNull("body")
            ?.objOrNull("songlist")?.arrOrNull("list") ?: return emptyList()
        return list.mapNotNull { it.toPlaylist() }
    }

    private suspend fun browsePlaylists(page: Int, limit: Int): List<PlaylistSummary> {
        val sin = (page - 1) * limit
        val raw = Http.get(
            "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?picmid=1&rnd=0.1&g_tk=5381" +
                "&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0" +
                "&platform=yqq.json&needNewCode=0&categoryId=10000000&sortId=5&sin=$sin&ein=${sin + limit}",
            referer = "https://y.qq.com/",
        )
        val json = parseJsonPayload(raw)
        return json.objOrNull("data")?.arrOrNull("list")?.mapNotNull { it.toPlaylist() } ?: emptyList()
    }

    /**
     * 歌单全量歌曲（**大歌单专项**）。
     *
     * 旧实现把 `song_num` 写死 500 且不翻页——超过 500 首的歌单会被静默截断。
     * 现实现按 `song_begin`/`song_num` 翻页，直到取满、服务端不再返回新页或触及上限。
     * 已登录时优先带 Cookie（可读「我喜欢」等私密歌单），失败或为空回退匿名。
     */
    override suspend fun playlistSongs(playlistId: String): List<Song> {
        val cookie = cookieProvider().trim()
        if (cookie.isNotBlank()) {
            val viaCookie = runCatching { playlistSongsWithCookie(cookie, playlistId) }.getOrNull()
            if (!viaCookie.isNullOrEmpty()) return viaCookie
        }
        return playlistSongsAnonymous(playlistId)
    }

    /** 匿名分页拉取（逐页累加，单页失败即止损并返回已取到的部分） */
    private suspend fun playlistSongsAnonymous(playlistId: String): List<Song> {
        val songs = ArrayList<Song>()
        var begin = 0
        while (songs.size < MAX_PLAYLIST_SONGS) {
            val body = dissInfoBody(auth = null, playlistId = playlistId, begin = begin, num = PAGE_SIZE)
            val json = runCatching {
                parseJsonPayload(
                    Http.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body, referer = "https://y.qq.com/")
                )
            }.getOrNull() ?: break
            val data = json.objOrNull("req")?.objOrNull("data") ?: break
            val page = data.arrOrNull("songlist")?.mapNotNull { it.toSong() }.orEmpty()
            if (page.isEmpty()) break
            songs += page
            if (isLastPage(data, page.size, songs.size)) break
            begin += PAGE_SIZE
        }
        return songs
    }

    /** 带 Cookie 的歌单歌曲读取（musicu；「我喜欢」等私密歌单必需），同样支持分页 */
    private suspend fun playlistSongsWithCookie(cookie: String, playlistId: String): List<Song> {
        val songs = ArrayList<Song>()
        var begin = 0
        while (songs.size < MAX_PLAYLIST_SONGS) {
            val body = dissInfoBody(auth = cookie, playlistId = playlistId, begin = begin, num = PAGE_SIZE)
            val json = runCatching { parseJsonPayload(postMusicu(cookie, body)) }.getOrNull() ?: break
            val data = json.objOrNull("req")?.objOrNull("data") ?: break
            val page = data.arrOrNull("songlist")?.mapNotNull { it.toSong() }.orEmpty()
            if (page.isEmpty()) break
            songs += page
            if (isLastPage(data, page.size, songs.size)) break
            begin += PAGE_SIZE
        }
        return songs
    }

    /** 是否已取完最后一页（返回页不满 / 已达服务端声明的总数） */
    private fun isLastPage(data: JsonElement, pageSize: Int, fetched: Int): Boolean {
        if (pageSize < PAGE_SIZE) return true
        val total = data.int("total_song_num") ?: data.int("total") ?: 0
        return total > 0 && fetched >= total
    }

    /** 构造歌单详情请求体（`song_begin`/`song_num` 支持分页；auth 为 null 时匿名） */
    private fun dissInfoBody(auth: String?, playlistId: String, begin: Int, num: Int): String =
        buildString {
            append("{\"comm\":{\"ct\":24,\"cv\":0")
            auth?.let { append(authFragment(it)) }
            append("},\"req\":{\"module\":\"music.srfDissInfo.aiDissInfo\",\"method\":\"uniform_get_Dissinfo\",\"param\":{\"disstid\":")
            append(playlistId)
            append(",\"enc_host_uin\":\"\",\"tag\":1,\"userinfo\":1,\"song_begin\":")
            append(begin)
            append(",\"song_num\":")
            append(num)
            append("}}}")
        }

    override suspend fun playlistMeta(playlistId: String): PlaylistSummary? {
        val raw = Http.get(
            "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0" +
                "&disstid=$playlistId&format=json&g_tk=5381",
            referer = "https://y.qq.com/n/ryqq/playlist/$playlistId",
        )
        val cd = parseJsonPayload(raw).arrOrNull("cdlist")?.firstOrNull() ?: return null
        val name = cd.str("dissname")?.takeIf { it.isNotBlank() } ?: return null
        return PlaylistSummary(
            id = playlistId,
            platform = MusicPlatform.QQ,
            name = name,
            coverUrl = cd.str("logo").orEmpty().toHttps(),
            trackCount = cd.int("songnum") ?: 0,
            playCount = cd.long("listennum") ?: 0L,
            creator = cd.objOrNull("creator")?.str("name").orEmpty(),
        )
    }

    override suspend fun toplists(): List<RankSummary> {
        val body = "{\"comm\":{\"ct\":24,\"cv\":0},\"toplist\":{\"module\":\"musicToplist.ToplistInfoServer\"," +
            "\"method\":\"GetAll\",\"param\":{}}}"
        val json = parseJsonPayload(
            Http.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body, referer = "https://y.qq.com/")
        )
        val groups = json.objOrNull("toplist")?.objOrNull("data")?.arrOrNull("group") ?: return emptyList()
        val result = mutableListOf<RankSummary>()
        groups.forEach { group ->
            group.arrOrNull("toplist")?.forEach { t ->
                val topId = t.long("topId")?.toString() ?: return@forEach
                val firstSong = t.arrOrNull("song")?.firstOrNull()
                val coverMid = firstSong?.objOrNull("album")?.str("mid") ?: firstSong?.str("albumMid")
                result += RankSummary(
                    id = topId,
                    platform = MusicPlatform.QQ,
                    name = t.str("title").orEmpty(),
                    coverUrl = qqCover(coverMid),
                    updateFrequency = t.str("updateTime").orEmpty(),
                    description = t.str("intro").orEmpty().replace("<br>", "\n"),
                )
            }
        }
        return result
    }

    override suspend fun rankSongs(rankId: String): List<Song> {
        val raw = Http.get(
            "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid=$rankId&format=json&page=detail" +
                "&tpl=3&type=top&song_begin=0&song_num=100",
            referer = "https://y.qq.com/n/ryqq/toplist/$rankId",
        )
        val json = parseJsonPayload(raw)
        return json.arrOrNull("songlist")?.mapNotNull { el ->
            // songlist 条目可能直接是歌曲对象，也可能嵌套在 data 字段内
            val s = el.objOrNull("data") ?: el
            s.toSong()
        } ?: emptyList()
    }

    override suspend fun lyrics(song: Song): SongLyrics {
        // 优先 QRC 逐字（musicu 接口）；失败或无逐字时回退行级 LRC（fcg 接口）
        val qrc = runCatching { fetchQrcLyrics(song) }.getOrNull()
        if (qrc != null && !qrc.isEmpty) return qrc
        val raw = Http.get(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${song.id}&format=json&nobase64=1&g_tk=5381",
            referer = "https://y.qq.com/n/ryqq/songDetail/${song.id}",
        )
        val json = parseJsonPayload(raw)
        val lrc = json.str("lyric").orEmpty().decodeHtmlEntities()
        val trans = json.str("trans").orEmpty().decodeHtmlEntities()
        return LrcParser.parse(lrc, trans)
    }

    /** QRC 拉取节点：u 为主节点，u6 为备用节点（实测等价；部分网络对个别节点不可达时兜底） */
    private val qrcHosts = listOf("u.y.qq.com", "u6.y.qq.com")

    /**
     * QRC 逐字歌词：musicu 获取 hex 密文 → 自定义 DES 解密 → XML 提取 → QRC 解析（含翻译合并）。
     * 多节点兜底：部分网络环境对个别 musicu 节点不可达时自动切换备用节点；
     * 单节点最多等待 10 秒，避免拖慢行级回退。
     */
    private suspend fun fetchQrcLyrics(song: Song): SongLyrics? {
        val body = """{"comm":{"ct":11,"cv":"1003006","v":"1003006","uin":"0"},"req":{"module":"music.musichallSong.PlayLyricInfo","method":"GetPlayLyricInfo","param":{"songMID":"${song.id}","songID":0,"format":"json","qrc":1,"trans":1,"roma":1,"crypt":1,"lrc_t":0,"type":0}}}"""
        for (host in qrcHosts) {
            val result = try {
                withTimeoutOrNull(10_000) { fetchQrcFromHost(host, body) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (result != null) return result
        }
        return null
    }

    /** 从单个 musicu 节点拉取并解析 QRC；失败返回 null（由调用方切换节点） */
    private suspend fun fetchQrcFromHost(host: String, body: String): SongLyrics? {
        val raw = Http.postJson("https://$host/cgi-bin/musicu.fcg", body, referer = "https://y.qq.com/")
        val data = parseJsonPayload(raw).objOrNull("req")?.objOrNull("data") ?: return null
        val qrcText = QrcDecoder.decrypt(data.str("lyric").orEmpty()) ?: return null
        val transText = QrcDecoder.decrypt(data.str("trans").orEmpty()).orEmpty()
        val parsed = QrcParser.parse(qrcText, QrcParser.extractContent(transText))
        return parsed.takeIf { !it.isEmpty }
    }

    /** 歌曲评论：global_comment_h5（热门 + 最新，分页） */
    override suspend fun comments(songId: String, page: Int, limit: Int): CommentsPage? {
        val pagenum = (page - 1).coerceAtLeast(0)
        val raw = Http.get(
            "https://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg" +
                "?biztype=1&topid=${urlEnc(songId)}&cmd=8&pagenum=$pagenum&pagesize=$limit&format=json",
            referer = "https://y.qq.com/n/ryqq/songDetail/$songId",
        )
        val json = parseJsonPayload(raw)
        if ((json.int("code") ?: 0) != 0) return null
        fun parseList(arr: List<JsonElement>?): List<CommentItem> = arr?.mapNotNull { c ->
            val content = c.str("rootcommentcontent")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CommentItem(
                id = c.str("commentid").orEmpty(),
                nickname = c.str("nick").orEmpty(),
                avatarUrl = c.str("avatarurl").orEmpty(),
                content = content,
                likedCount = c.int("praisenum") ?: 0,
                timeMs = (c.long("time") ?: 0L) * 1000L,
            )
        } ?: emptyList()
        val hot = parseList(json.objOrNull("hot_comment")?.arrOrNull("commentlist"))
        val items = parseList(json.objOrNull("comment")?.arrOrNull("commentlist"))
        return CommentsPage(
            hot = hot,
            items = items,
            total = json.objOrNull("comment")?.int("commenttotal") ?: 0,
            hasMore = (json.int("morecomment") ?: 0) != 0,
        )
    }


    /* ---------- 账号能力（Cookie 直连：设置页登录 / 红心同步） ---------- */

    /**
     * uin 取值。
     *
     * 网页登录拿到的 `uin` 可能是 `o1234567` 形式（跨域 cookie 的 o 前缀），
     * 而 QQ 音乐接口只认纯数字，这里统一清洗。
     */
    private fun uinOf(cookie: String): String? {
        val raw = cookieValue(cookie, "uin") ?: cookieValue(cookie, "luin") ?: return null
        val cleaned = raw.trim().removePrefix("o").removePrefix("O")
        return cleaned.takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
    }

    /** 登录校验：Cookie 含 uin + qqmusic_key（qm_keyst）且资料接口可用时返回资料 */
    suspend fun loginStatus(cookie: String): QqProfile? {
        val uin = uinOf(cookie) ?: return null
        cookieValue(cookie, "qqmusic_key") ?: cookieValue(cookie, "qm_keyst") ?: return null
        val raw = Http.get(
            "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?cid=205360838&reqfrom=1&userid=${urlEnc(uin)}",
            referer = "https://y.qq.com/portal/profile.html",
            headers = mapOf("Cookie" to cookie.trim()),
        )
        val creator = parseJsonPayload(raw).objOrNull("data")?.objOrNull("creator") ?: return null
        val nick = creator.str("nick")?.takeIf { it.isNotBlank() } ?: return null
        return QqProfile(userId = uin, nickname = nick, avatarUrl = creator.str("headpic").orEmpty())
    }

    /** 我的歌单（含「我喜欢」dirId=201；需登录 Cookie） */
    suspend fun userPlaylists(cookie: String): List<QqPlaylist> {
        val uin = uinOf(cookie) ?: return emptyList()
        val body = buildString {
            append("{\"comm\":{\"ct\":24,\"cv\":0")
            append(authFragment(cookie))
            append("},\"req\":{\"module\":\"music.musicasset.PlaylistBaseRead\",\"method\":\"GetPlaylistByUin\",")
            append("\"param\":{\"uin\":\"").append(uin).append("\"}}}")
        }
        val json = parseJsonPayload(postMusicu(cookie, body))
        val list = json.objOrNull("req")?.objOrNull("data")?.arrOrNull("v_playlist") ?: return emptyList()
        return list.mapNotNull { item ->
            val dirId = item.str("dirId") ?: return@mapNotNull null
            val tid = item.str("tid") ?: return@mapNotNull null
            QqPlaylist(
                dirId = dirId,
                tid = tid,
                name = item.str("dirName").orEmpty(),
                trackCount = item.int("songNum") ?: 0,
                special = dirId == "201",
                coverUrl = item.str("picUrl").orEmpty().toHttps(),
            )
        }
    }

    /** 「我喜欢」全量：mid 集合 + 歌曲详情（分页拉全；需登录 Cookie） */
    suspend fun likedSongs(cookie: String, tid: String): QqLikedSongs {
        val mids = mutableSetOf<String>()
        val songs = mutableListOf<Song>()
        var begin = 0
        val pageSize = 300
        var total = -1
        while (true) {
            val body = buildString {
                append("{\"comm\":{\"ct\":24,\"cv\":0")
                append(authFragment(cookie))
                append("},\"req\":{\"module\":\"music.srfDissInfo.aiDissInfo\",\"method\":\"uniform_get_Dissinfo\",")
                append("\"param\":{\"disstid\":").append(tid).append(",\"enc_host_uin\":\"\",\"tag\":1,\"userinfo\":1,")
                append("\"song_begin\":").append(begin).append(",\"song_num\":").append(pageSize).append("}}}")
            }
            val json = parseJsonPayload(postMusicu(cookie, body))
            val data = json.objOrNull("req")?.objOrNull("data") ?: break
            if (total < 0) total = data.objOrNull("dirinfo")?.int("songnum") ?: 0
            val list = data.arrOrNull("songlist") ?: break
            for (item in list) {
                val mid = item.str("mid") ?: item.str("songmid") ?: continue
                mids.add(mid)
                item.toSong()?.let { songs.add(it) }
            }
            begin += list.size
            if (list.isEmpty() || list.size < pageSize || (total in 0..begin)) break
        }
        return QqLikedSongs(mids = mids, songs = songs.distinctBy { it.id })
    }

    /** 批量解析 mid → 数字 songId（music.trackInfo.UniformRuleCtrl） */
    suspend fun songIdsByMids(cookie: String, mids: List<String>): Map<String, Long> {
        if (mids.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, Long>()
        for (chunk in mids.distinct().chunked(50)) {
            val midsJson = chunk.joinToString(",", "[", "]") { "\"$it\"" }
            val typesJson = chunk.joinToString(",", "[", "]") { "0" }
            val stampsJson = chunk.joinToString(",", "[", "]") { "0" }
            val body = buildString {
                append("{\"comm\":{\"ct\":24,\"cv\":0")
                append(authFragment(cookie))
                append("},\"req\":{\"module\":\"music.trackInfo.UniformRuleCtrl\",\"method\":\"CgiGetTrackInfo\",")
                append("\"param\":{\"ctx\":0,\"client\":1,\"types\":").append(typesJson)
                append(",\"modify_stamp\":").append(stampsJson)
                append(",\"mids\":").append(midsJson).append("}}}")
            }
            val json = runCatching { parseJsonPayload(postMusicu(cookie, body)) }.getOrNull() ?: continue
            val tracks = json.objOrNull("req")?.objOrNull("data")?.arrOrNull("tracks") ?: continue
            for (track in tracks) {
                val mid = track.str("mid") ?: continue
                val id = track.long("id") ?: continue
                if (id > 0L) out[mid] = id
            }
        }
        return out
    }

    /** 批量加红心（签名版 AddSonglist；单次 ≤100 首） */
    suspend fun likeSongs(cookie: String, tid: String, songIds: List<Long>): Boolean {
        if (songIds.isEmpty()) return true
        val items = songIds.joinToString(",") { "{\"songId\":$it,\"songType\":0}" }
        val req = "{\"module\":\"music.musicasset.PlaylistDetailWrite\",\"method\":\"AddSonglist\"," +
            "\"param\":{\"dirId\":201,\"tid\":$tid,\"bFmtUtf8\":true,\"v_songInfo\":[$items]}}"
        return signedRequest(cookie, req)
    }

    /** 批量取消红心（签名版 DelSonglist） */
    suspend fun unlikeSongs(cookie: String, tid: String, songIds: List<Long>): Boolean {
        if (songIds.isEmpty()) return true
        val items = songIds.joinToString(",") { "{\"songId\":$it,\"songType\":0}" }
        val req = "{\"module\":\"music.musicasset.PlaylistDetailWrite\",\"method\":\"DelSonglist\"," +
            "\"param\":{\"dirId\":201,\"tid\":$tid,\"bFmtUtf8\":true,\"v_songInfo\":[$items]}}"
        return signedRequest(cookie, req)
    }
    /** 猜你喜欢（radio；需登录 Cookie） */
    suspend fun radioRecommend(cookie: String): List<Song> {
        val body = buildString {
            append("{\"comm\":{\"ct\":24,\"cv\":0")
            append(authFragment(cookie))
            append("},\"req\":{\"module\":\"music.radioProxy.MbTrackRadioSvr\",\"method\":\"get_radio_track\",")
            append("\"param\":{\"id\":99,\"num\":30,\"from\":0,\"scene\":0,\"song_ids\":[]}}}")
        }
        val json = parseJsonPayload(postMusicu(cookie, body))
        return json.objOrNull("req")?.objOrNull("data")?.arrOrNull("tracks")?.mapNotNull { it.toSong() } ?: emptyList()
    }

    /** 雷达推荐（需登录 Cookie） */
    suspend fun radarRecommend(cookie: String): List<Song> {
        val body = buildString {
            append("{\"comm\":{\"ct\":24,\"cv\":0")
            append(authFragment(cookie))
            append("},\"req\":{\"module\":\"music.recommend.TrackRelationServer\",\"method\":\"GetRadarSong\",")
            append("\"param\":{\"Page\":1,\"ReqType\":0,\"FavSongs\":[],\"EntranceSongs\":[]}}}")
        }
        val json = parseJsonPayload(postMusicu(cookie, body))
        val vec = json.objOrNull("req")?.objOrNull("data")?.arrOrNull("VecSongs") ?: return emptyList()
        return vec.mapNotNull { it.objOrNull("Track")?.toSong() }
    }

    /** musicu 直连（携带 Cookie 的 POST JSON） */
    private suspend fun postMusicu(cookie: String, body: String): String =
        Http.postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body,
            referer = "https://y.qq.com/",
            headers = mapOf("Cookie" to cookie.trim()),
        )

    /** 签名请求（musics.fcg + zzc_sign）；成功条件：req_0.code == 0 */
    private suspend fun signedRequest(cookie: String, reqJson: String): Boolean {
        val payload = "{\"comm\":{\"ct\":24,\"cv\":0" + authFragment(cookie) + "},\"req_0\":" + reqJson + "}"
        val sign = zzcSign(payload)
        val url = "https://u.y.qq.com/cgi-bin/musics.fcg?sign=" + sign + "&_=" + System.currentTimeMillis()
        val raw = Http.postJson(url, payload, referer = "https://y.qq.com/", headers = mapOf("Cookie" to cookie.trim()))
        val json = parseJsonPayload(raw)
        return (json.objOrNull("req_0")?.int("code") ?: -1) == 0
    }

    /** 登录态 comm 片段（authst + uin） */
    private fun authFragment(cookie: String): String {
        val uin = uinOf(cookie).orEmpty()
        val key = cookieValue(cookie, "qqmusic_key") ?: cookieValue(cookie, "qm_keyst").orEmpty()
        val sb = StringBuilder()
        if (key.isNotBlank()) sb.append(",\"authst\":\"").append(key).append('"')
        if (uin.isNotBlank()) sb.append(",\"uin\":\"").append(uin).append('"')
        return sb.toString()
    }

    /** 从 Cookie 字符串提取字段（分号分隔） */
    private fun cookieValue(cookie: String, name: String): String? {
        for (part in cookie.split(';')) {
            val p = part.trim()
            if (p.startsWith(name + "=")) return p.substring(name.length + 1)
        }
        return null
    }

    /** zzc 签名（QQ 音乐客户端请求签名；SHA-1 + 索引重排 + 异或 + Base64 清洗） */
    private fun zzcSign(payload: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = "0123456789ABCDEF"
        val hashHex = buildString {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(hex[v ushr 4])
                append(hex[v and 0x0F])
            }
        }
        val part1 = intArrayOf(23, 14, 6, 36, 16, 7, 19).joinToString("") { hashHex[it].toString() }
        val part2 = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5).joinToString("") { hashHex[it].toString() }
        val scramble = intArrayOf(89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179)
        val scrambled = ByteArray(20) { i ->
            (scramble[i] xor hashHex.substring(i * 2, i * 2 + 2).toInt(16)).toByte()
        }
        val b64 = android.util.Base64.encodeToString(scrambled, android.util.Base64.NO_WRAP)
            .replace("\\", "").replace("/", "").replace("+", "").replace("=", "")
        return ("zzc" + part1 + b64 + part2).lowercase()
    }

    /* ---------- 字段映射 ---------- */

    private fun JsonElement.toSong(): Song? {
        val mid = str("mid") ?: str("songmid") ?: return null
        val title = str("title") ?: str("songname") ?: str("name") ?: return null
        val artist = arrOrNull("singer")?.mapNotNull { it.str("name") }?.joinToString("/").orEmpty()
        val albumObj = objOrNull("album")
        val albumMid = albumObj?.str("mid") ?: str("albummid")
        return Song(
            id = mid,
            platform = MusicPlatform.QQ,
            title = title,
            artist = artist.ifBlank { "未知歌手" },
            album = albumObj?.str("name") ?: str("albumname").orEmpty(),
            durationMs = (int("interval") ?: 0).toLong() * 1000L,
            coverUrl = qqCover(albumMid),
        )
    }

    private fun JsonElement.toPlaylist(): PlaylistSummary? {
        val id = str("dissid") ?: return null
        val name = str("dissname") ?: return null
        return PlaylistSummary(
            id = id,
            platform = MusicPlatform.QQ,
            name = name,
            coverUrl = str("imgurl").orEmpty().toHttps(),
            trackCount = int("song_count") ?: 0,
            playCount = long("listennum") ?: 0L,
            creator = objOrNull("creator")?.str("name").orEmpty(),
        )
    }

    /** QQ 专辑封面 CDN（实测可用） */
    private fun qqCover(albumMid: String?): String =
        if (albumMid.isNullOrBlank()) "" else "https://y.qq.com/music/photo_new/T002R500x500M000$albumMid.jpg"

    companion object {
        /** 歌单分页每页条数（musicu 单次返回上限的稳定取值） */
        private const val PAGE_SIZE = 500

        /** 单歌单歌曲上限（防御异常歌单 / 服务端忽略 song_begin 导致的死循环） */
        private const val MAX_PLAYLIST_SONGS = 10_000
    }
}

/** 「我喜欢」同步数据：权威 mid 集合 + 可播放的歌曲详情 */
data class QqLikedSongs(
    val mids: Set<String>,
    val songs: List<Song>,
)

/** 解码 QQ 歌词接口返回的 HTML 实体（&#58; / &#10; / &amp; 等） */
internal fun String.decodeHtmlEntities(): String {
    if (isEmpty()) return this
    var s = this
    s = s.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("\u0026quot;", "\"")
        .replace("\u0026apos;", "'")
        .replace("&nbsp;", " ")
    s = Regex("&#(\\d+);").replace(s) { m ->
        m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
    }
    s = Regex("&#[xX]([0-9a-fA-F]+);").replace(s) { m ->
        m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
    }
    return s
}