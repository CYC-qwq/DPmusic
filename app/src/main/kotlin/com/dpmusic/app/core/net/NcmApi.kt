package com.dpmusic.app.core.net

import com.dpmusic.app.core.model.AlbumDetail
import com.dpmusic.app.core.model.ArtistDetail
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.NcmPlaylist
import com.dpmusic.app.core.model.NcmProfile
import com.dpmusic.app.core.model.Song
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 网易云音乐直连客户端（eapi 加密）：
 * - 账号接口与「一起听」全部接口均经本机实测可用；
 * - Cookie 由调用方传入（来自 [com.dpmusic.app.core.data.NcmRepository]，仅本地保存）。
 */
class NcmApi(private val deviceIdProvider: () -> String) {

    /* ---------- 账号 ---------- */

    /** 校验 Cookie 并获取账号资料；Cookie 无效时返回 null（响应 account 为空） */
    suspend fun loginStatus(cookie: String): NcmProfile? {
        val json = eapi(cookie, "/api/w/nuser/account/get", buildJsonObject {})
        val account = json.objOrNull("account") ?: return null
        val userId = account.long("id") ?: return null
        val profile = json.objOrNull("profile")
        return NcmProfile(
            userId = userId,
            nickname = profile?.str("nickname").orEmpty(),
            avatarUrl = profile?.str("avatarUrl").orEmpty(),
        )
    }

    /* ---------- 账号内容（需 Cookie 的 eapi 接口） ---------- */

    /** 每日推荐（约 30 首；需登录 Cookie） */
    suspend fun dailySongs(cookie: String): List<Song> {
        val json = eapi(cookie, "/api/v1/discovery/recommend/songs", buildJsonObject {})
        return json.objOrNull("data")?.arrOrNull("dailySongs")?.mapNotNull { it.toNcmSong() } ?: emptyList()
    }

    /** 私人 FM（一批，通常 3 首；需登录 Cookie） */
    suspend fun fmSongs(cookie: String): List<Song> {
        val json = eapi(cookie, "/api/v1/radio/get", buildJsonObject { put("mode", "DEFAULT") })
        return json.arrOrNull("data")?.mapNotNull { it.toNcmSong() } ?: emptyList()
    }

    /** 我的歌单（含收藏；「我喜欢的音乐」为 specialType=5；需登录 Cookie） */
    suspend fun userPlaylists(cookie: String, uid: Long, limit: Int = 100, offset: Int = 0): List<NcmPlaylist> {
        val json = eapi(cookie, "/api/user/playlist", buildJsonObject {
            put("uid", uid)
            put("limit", limit)
            put("offset", offset)
        })
        return json.arrOrNull("playlist")?.mapNotNull { it.toNcmPlaylist() } ?: emptyList()
    }

    /** 每日推荐歌单（含「私人雷达」；需登录 Cookie） */
    suspend fun recommendPlaylists(cookie: String): List<NcmPlaylist> {
        val json = eapi(cookie, "/api/v1/discovery/recommend/resource", buildJsonObject {})
        return json.arrOrNull("recommend")?.mapNotNull { it.toNcmPlaylist() } ?: emptyList()
    }

    /* ---------- 红心（我喜欢的音乐）同步 ---------- */

    /** 「我喜欢的音乐」全量：trackIds（权威 id 集合）+ 歌曲详情（新格式字段） */
    suspend fun likedSongs(cookie: String, playlistId: String): NcmLikedSongs {
        val json = eapi(cookie, "/api/v6/playlist/detail", buildJsonObject {
            put("id", playlistId.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(playlistId))
            put("n", 1000)
            put("s", 8)
        })
        val playlist = json.objOrNull("playlist") ?: return NcmLikedSongs(emptySet(), emptyList())
        val ids = playlist.arrOrNull("trackIds")?.mapNotNull { it.long("id") } ?: emptyList()
        val idSet = ids.toSet()
        val tracks = playlist.arrOrNull("tracks")?.mapNotNull { it.toNcmSong() } ?: emptyList()
        val known = tracks.mapNotNull { it.id.toLongOrNull() }.toSet()
        // 超过单页上限时（tracks 少于 trackIds）用 song/detail 分批补差
        val missing = idSet - known
        val extra = if (missing.isEmpty()) emptyList()
        else missing.chunked(100).flatMap { chunk ->
            runCatching { songDetails(cookie, chunk) }.getOrDefault(emptyList())
        }
        val songs = (tracks + extra).distinctBy { it.id }
        return NcmLikedSongs(ids = idSet, songs = songs)
    }

    /** 批量歌曲详情（v3/song/detail；c 为 JSON 数组字符串） */
    private suspend fun songDetails(cookie: String, ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val c = ids.joinToString(",", "[", "]") { "{\"id\":$it}" }
        val json = eapi(cookie, "/api/v3/song/detail", buildJsonObject { put("c", c) })
        return json.arrOrNull("songs")?.mapNotNull { it.toNcmSong() } ?: emptyList()
    }

    /**
     * 红心 / 取消红心（radio/like，单曲）。
     * 注意：连续调用会触发风控（code 405 操作频繁）；批量场景请用 [likeSongs]。
     */
    suspend fun likeSong(cookie: String, songId: String, like: Boolean): Boolean {
        val json = eapi(cookie, "/api/radio/like", buildJsonObject {
            put("alg", "itembased")
            put("trackId", songId.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(songId))
            put("like", if (like) "true" else "false")
            put("time", "3")
        })
        return (json.int("code") ?: 0) == 200
    }

    /**
     * 批量红心（manipulate/tracks：把歌曲加入「我喜欢的音乐」歌单）。
     * 已实测：不受 radio/like 的连续调用风控影响；单次建议 ≤100 首。
     */
    suspend fun likeSongs(cookie: String, playlistId: String, songIds: List<Long>): Boolean {
        if (songIds.isEmpty()) return true
        val ids = songIds.joinToString(",", "[", "]")
        val json = eapi(cookie, "/api/playlist/manipulate/tracks", buildJsonObject {
            put("op", "add")
            put("pid", playlistId.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(playlistId))
            put("trackIds", ids)
        })
        // 200 = 添加成功；502 =「歌单歌曲重复」（目标状态已达成，视为成功）
        val code = json.int("code") ?: 0
        return code == 200 || code == 502
    }

    /* ---------- 歌手 / 专辑（eapi 实测可用） ---------- */

    /** 歌手详情（head/info/get）：名称 / 别名 / 简介 / 头像 */
    suspend fun artistDetail(cookie: String, id: String): ArtistDetail? {
        val json = eapi(cookie, "/api/artist/head/info/get", buildJsonObject { put("id", id) })
        val artist = json.objOrNull("data")?.objOrNull("artist") ?: return null
        return ArtistDetail(
            id = artist.long("id")?.toString() ?: id,
            name = artist.str("name").orEmpty(),
            alias = artist.strList("alias").joinToString("/"),
            avatarUrl = (artist.str("avatar") ?: artist.str("cover")).orEmpty().toHttps(),
            coverUrl = (artist.str("cover") ?: artist.str("avatar")).orEmpty().toHttps(),
            briefDesc = artist.str("briefDesc").orEmpty(),
            identities = artist.strList("identities").joinToString("/"),
        )
    }

    /** 歌手歌曲（v1/artist/songs）：order = hot / time；返回（歌曲列表, 是否还有更多） */
    suspend fun artistSongs(cookie: String, id: String, order: String, limit: Int, offset: Int): Pair<List<Song>, Boolean> {
        val json = eapi(cookie, "/api/v1/artist/songs", buildJsonObject {
            put("id", id)
            put("private_cloud", "true")
            put("work_type", 1)
            put("order", order)
            put("offset", offset)
            put("limit", limit)
        })
        val songs = json.arrOrNull("songs")?.mapNotNull { it.toNcmSong() }.orEmpty()
        val more = json.bool("more") ?: (songs.size >= limit)
        return songs to more
    }

    /** 歌手专辑（artist/albums/{id}）；返回（专辑列表, 是否还有更多） */
    suspend fun artistAlbums(cookie: String, id: String, limit: Int, offset: Int): Pair<List<AlbumDetail>, Boolean> {
        val json = eapi(cookie, "/api/artist/albums/$id", buildJsonObject {
            put("limit", limit)
            put("offset", offset)
            put("total", true)
        })
        val albums = json.arrOrNull("hotAlbums")?.mapNotNull { it.toNcmAlbum() }.orEmpty()
        val more = json.bool("more") ?: (albums.size >= limit)
        return albums to more
    }

    /** 专辑详情（v1/album/{id}）：含歌曲列表；不存在返回 null */
    suspend fun albumDetail(cookie: String, id: String): AlbumDetail? {
        val json = eapi(cookie, "/api/v1/album/$id", buildJsonObject {})
        val album = json.objOrNull("album") ?: return null
        val songs = json.arrOrNull("songs")?.mapNotNull { it.toNcmSong() }.orEmpty()
        return album.toNcmAlbum().copy(songs = songs)
    }

    /** 相似歌曲（v1/discovery/simiSong；部分歌曲无推荐时返回空列表） */
    suspend fun similarSongs(cookie: String, songId: String, limit: Int, offset: Int): List<Song> {
        val json = eapi(cookie, "/api/v1/discovery/simiSong", buildJsonObject {
            put("songid", songId)
            put("limit", limit)
            put("offset", offset)
        })
        return json.arrOrNull("songs")?.mapNotNull { it.toNcmSong() }.orEmpty()
    }

    /* ---------- 一起听 ---------- */

    /** 接受邀请加入房间（refer 固定为 inbox_invite） */
    suspend fun togetherAccept(cookie: String, roomId: String, inviterId: Long): JsonElement =
        eapi(cookie, "/api/listen/together/play/invitation/accept", buildJsonObject {
            put("refer", "inbox_invite")
            put("roomId", roomId)
            put("inviterId", inviterId)
        })

    /** 读取房间状态与播放列表（信息量最大的接口） */
    suspend fun togetherSync(cookie: String, roomId: String): JsonElement =
        eapi(cookie, "/api/listen/together/sync/playlist/get", buildJsonObject {
            put("roomId", roomId)
        })

    /** 房间状态（唯一含完整 roomInfo / 成员列表 roomUsers 的接口；已实测 eapi 直连可用） */
    suspend fun togetherStatus(cookie: String): JsonElement =
        eapi(cookie, "/api/listen/together/status/get", buildJsonObject { })

    /** 心跳（服务端超时窗口约 30 秒；建议 5 秒一次保活，同时上报播放进度） */
    suspend fun togetherHeartbeat(
        cookie: String,
        roomId: String,
        songId: String?,
        playStatus: String?,
        progress: Long,
    ): JsonElement = eapi(cookie, "/api/listen/together/heartbeat", buildJsonObject {
        put("roomId", roomId)
        if (!songId.isNullOrBlank()) {
            put("songId", songId.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(songId))
        }
        if (!playStatus.isNullOrBlank()) put("playStatus", playStatus)
        put("progress", progress)
    })

    /** 播放控制（PLAY / PAUSE / GOTO / seek）；commandInfo 为 JSON 字符串 */
    suspend fun togetherPlayCommand(cookie: String, roomId: String, commandInfoJson: String): JsonElement =
        eapi(cookie, "/api/listen/together/play/command/report", buildJsonObject {
            put("roomId", roomId)
            put("commandInfo", commandInfoJson)
        })

    /** 结束房间（必须带 shareInfo:'{}'，否则「假成功」且锁定不解除） */
    suspend fun togetherEnd(cookie: String, roomId: String): JsonElement =
        eapi(cookie, "/api/listen/together/end/v2", buildJsonObject {
            put("roomId", roomId)
            put("shareInfo", "{}")
        })

    /** 创建房间（refer 固定为 songplay_more；房间 30 分钟内有效） */
    suspend fun togetherCreate(cookie: String): JsonElement =
        eapi(cookie, "/api/listen/together/room/create", buildJsonObject {
            put("refer", "songplay_more")
        })

    /** 同步房间播放列表（REPLACE / ADD / PLAYMODE_CHANGE）；playlistParam 为 JSON 字符串 */
    suspend fun togetherListCommand(cookie: String, roomId: String, playlistParamJson: String): JsonElement =
        eapi(cookie, "/api/listen/together/sync/list/command/report", buildJsonObject {
            put("roomId", roomId)
            put("playlistParam", playlistParamJson)
        })

    /** 发送官方邀请消息（好友将在网易云「消息」中收到一起听邀请，可直接加入） */
    suspend fun togetherInviteMessage(cookie: String, roomId: String, acceptorId: Long): JsonElement =
        eapi(cookie, "/api/listen/together/invite/message/send", buildJsonObject {
            put("roomId", roomId)
            put("acceptorId", acceptorId)
        })

    /* ---------- 私信（一起听邀请卡片识别） ---------- */

    /** 私信会话列表（含最近一条消息；既用于一起听邀请识别，也用于「消息」列表页） */
    suspend fun msgPrivateUsers(cookie: String, limit: Int = 20, offset: Int = 0): JsonElement =
        eapi(cookie, "/api/msg/private/users", buildJsonObject {
            put("offset", offset)
            put("limit", limit)
            put("total", "true")
        })
    /**
     * 与指定好友的私信历史（时间倒序）。
     *
     * @param before 只取该时间戳（毫秒）之前的消息；0 = 最新一页
     */
    suspend fun msgPrivateHistory(
        cookie: String,
        userId: Long,
        limit: Int = 20,
        before: Long = 0L,
    ): JsonElement =
        eapi(cookie, "/api/msg/private/history", buildJsonObject {
            put("userId", userId)
            put("limit", limit)
            put("time", before)
            put("total", "true")
        })

    /** 发送私信文本（聊天通道；type=text，userIds 为字符串数组） */
    suspend fun msgPrivateSendText(cookie: String, userId: Long, text: String): JsonElement =
        eapi(cookie, "/api/msg/private/send", buildJsonObject {
            put("type", "text")
            put("msg", text)
            put("userIds", "[$userId]")
        })

    /** 发送私信歌曲卡片（聊天通道；type=song） */
    suspend fun msgPrivateSendSong(cookie: String, userId: Long, songId: String, msg: String = ""): JsonElement =
        eapi(cookie, "/api/msg/private/send", buildJsonObject {
            put("id", songId)
            put("msg", msg)
            put("type", "song")
            put("userIds", "[$userId]")
        })

    /** 发送私信歌单卡片（聊天通道；type=playlist） */
    suspend fun msgPrivateSendPlaylist(
        cookie: String,
        userId: Long,
        playlistId: String,
        msg: String = "",
    ): JsonElement =
        eapi(cookie, "/api/msg/private/send", buildJsonObject {
            put("playlist", playlistId)
            put("msg", msg)
            put("type", "playlist")
            put("userIds", "[$userId]")
        })

    /** 发送私信专辑卡片（聊天通道；type=album） */
    suspend fun msgPrivateSendAlbum(cookie: String, userId: Long, albumId: String, msg: String = ""): JsonElement =
        eapi(cookie, "/api/msg/private/send", buildJsonObject {
            put("id", albumId)
            put("msg", msg)
            put("type", "album")
            put("userIds", "[$userId]")
        })

    /**
     * 用户资料（公开接口，无需登录）。
     *
     * 私信会话列表只返回 `fromUserId / toUserId`，**不含昵称与头像**，
     * 因此用该接口补全聊天列表的展示信息（`profile.nickname` / `profile.avatarUrl`）。
     */
    suspend fun userProfile(userId: Long): JsonElement {
        val raw = Http.get(
            url = "https://music.163.com/api/v1/user/detail/$userId",
            headers = mapOf("User-Agent" to Http.DEFAULT_UA),
        )
        return parseJsonPayload(raw)
    }

    /* ---------- 内部 ---------- */

    private suspend fun eapi(cookie: String, uri: String, payload: JsonObject): JsonElement {
        val cookies = NcmEapi.parseCookie(cookie)
        val header = NcmEapi.buildHeader(cookies, deviceIdProvider())
        val body = buildJsonObject {
            payload.forEach { (k, v) -> put(k, v) }
            put("e_r", false)
            put("header", header)
        }
        val params = NcmEapi.buildParams(uri, body)
        val raw = Http.postForm(
            url = "https://interfacepc.music.163.com/eapi${uri.removePrefix("/api")}",
            form = mapOf("params" to params),
            headers = mapOf(
                "User-Agent" to NcmEapi.EAPI_UA,
                "Cookie" to NcmEapi.cookieHeader(header),
            ),
        )
        return parseJsonPayload(raw)
    }

    /* ---------- NCM JSON → 模型映射 ---------- */

    /**
     * NCM 歌曲 JSON → Song（兼容两种字段风格）：
     * - weapi（搜索/歌单）：ar/al/dt；
     * - eapi 账号接口（每日推荐/私人FM）：artists/album/duration。
     */
    private fun JsonElement.toNcmSong(): Song? {
        val id = long("id")?.toString() ?: return null
        val title = str("name") ?: return null
        val artistArr = arrOrNull("ar") ?: arrOrNull("artists")
        val artist = artistArr
            ?.mapNotNull { it.str("name") }
            ?.joinToString("/")
            .orEmpty()
        val albumObj = objOrNull("al") ?: objOrNull("album")
        val cover = albumObj?.str("picUrl")?.takeIf { it.isNotBlank() }
            ?: albumObj?.str("blurPicUrl")
        return Song(
            id = id,
            platform = MusicPlatform.WY,
            title = title,
            artist = artist.ifBlank { "未知歌手" },
            album = albumObj?.str("name").orEmpty(),
            durationMs = long("dt") ?: long("duration") ?: 0L,
            coverUrl = cover.orEmpty().toHttps(),
            extra = buildMap {
                artistArr?.firstOrNull()?.long("id")?.toString()?.let { put("wy_artist_id", it) }
                albumObj?.long("id")?.toString()?.let { put("wy_album_id", it) }
            },
        )
    }

    /** NCM 专辑 JSON → AlbumDetail（歌手专辑列表 / 专辑详情共用） */
    private fun JsonElement.toNcmAlbum(): AlbumDetail {
        val artist = arrOrNull("artists")?.mapNotNull { it.str("name") }?.joinToString("/").orEmpty()
        return AlbumDetail(
            id = long("id")?.toString().orEmpty(),
            name = str("name").orEmpty(),
            coverUrl = (str("picUrl") ?: str("blurPicUrl")).orEmpty().toHttps(),
            artist = artist,
            publishTime = long("publishTime") ?: 0L,
            trackCount = int("size") ?: 0,
            company = str("company").orEmpty(),
            description = str("description").orEmpty(),
        )
    }

    /** NCM 歌单 JSON → NcmPlaylist（兼容「我的歌单」与「推荐歌单」两种字段命名） */
    private fun JsonElement.toNcmPlaylist(): NcmPlaylist? {
        val id = long("id")?.toString() ?: return null
        val name = str("name") ?: return null
        return NcmPlaylist(
            id = id,
            name = name,
            coverUrl = (str("coverImgUrl") ?: str("picUrl")).orEmpty().toHttps(),
            trackCount = int("trackCount") ?: 0,
            subscribed = bool("subscribed") ?: false,
            special = (int("specialType") ?: 0) == 5,
            playCount = long("playcount") ?: 0L,
        )
    }
}

/** 「我喜欢的音乐」同步数据：权威 id 集合 + 可播放的歌曲详情 */
data class NcmLikedSongs(
    val ids: Set<Long>,
    val songs: List<Song>,
)