package com.dpmusic.app.core.data

import com.dpmusic.app.core.model.ChatConversation
import com.dpmusic.app.core.model.ChatMessage
import com.dpmusic.app.core.model.ChatMessageKind
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.AppJson
import com.dpmusic.app.core.net.NcmApi
import com.dpmusic.app.core.net.arrOrNull
import com.dpmusic.app.core.net.bool
import com.dpmusic.app.core.net.int
import com.dpmusic.app.core.net.long
import com.dpmusic.app.core.net.objOrNull
import com.dpmusic.app.core.net.str
import com.dpmusic.app.core.util.AppLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 网易云私信（聊天）仓储。
 *
 * 后端依据实测调研报告：
 * - 会话列表 `POST /api/msg/private/users`（含未读数与最近一条消息）
 * - 会话记录 `POST /api/msg/private/history`（时间倒序，可用 `time` 向前翻页）
 * - 发送 `POST /api/msg/private/send`（type = text / song / playlist / album）
 *
 * 与官方 App 双向互通：官方 App 用户可直接收到并回复。
 */
class NcmChatRepository(
    private val api: NcmApi,
    private val ncm: NcmRepository,
) {

    /** 是否已登录网易云（聊天功能依赖 Cookie） */
    val loggedIn: Boolean
        get() = ncm.cookie.value.isNotBlank() && (ncm.profile.value?.userId ?: 0L) > 0L

    private val myUid: Long
        get() = ncm.profile.value?.userId ?: 0L

    /** 好友资料缓存（uid → 昵称 / 头像）；会话列表本身不含昵称，需补齐 */
    private val profileCache = mutableMapOf<Long, Pair<String, String>>()

    /** 会话列表（按最近消息时间倒序；昵称/头像缺失时自动补全） */
    suspend fun conversations(limit: Int = 30, offset: Int = 0): List<ChatConversation> {
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) return emptyList()
        val json = api.msgPrivateUsers(cookie, limit, offset)
        dumpProbe("users", json)
        val items = json.arrOrNull("msgs") ?: return emptyList()
        val me = myUid
        val parsed = items.mapNotNull { parseConversation(it, me) }
            .filter { it.userId > 0L }
            .sortedByDescending { it.lastTime }
        return fillProfiles(parsed)
    }

    /** 未读总数（用于入口角标） */
    suspend fun unreadTotal(): Int =
        runCatching { conversations(limit = 50).sumOf { it.unreadCount } }.getOrDefault(0)

    /** 与指定好友的聊天记录（时间正序；[before] 为向前翻页游标，0 = 最新一页） */
    suspend fun history(userId: Long, limit: Int = 30, before: Long = 0L): List<ChatMessage> {
        val cookie = ncm.cookie.value
        if (cookie.isBlank() || userId <= 0L) return emptyList()
        val json = api.msgPrivateHistory(cookie, userId, limit, before)
        dumpProbe("history", json)
        val items = json.arrOrNull("msgs") ?: return emptyList()
        val me = myUid
        return items.mapNotNull { parseMessage(it, me) }
            .distinctBy { it.id }
            .sortedBy { it.time }
    }

    /** 发送文本（官方限制 1000 字，这里统一截断） */
    suspend fun sendText(userId: Long, text: String): Boolean {
        val cookie = ncm.cookie.value
        val content = text.trim().take(MAX_TEXT_LENGTH)
        if (cookie.isBlank() || userId <= 0L || content.isEmpty()) return false
        return runCatching { api.msgPrivateSendText(cookie, userId, content) }
            .getOrNull()?.int("code") == 200
    }

    /** 分享歌曲卡片 */
    suspend fun sendSong(userId: Long, song: Song, message: String = "分享一首歌给你~"): Boolean {
        val cookie = ncm.cookie.value
        if (cookie.isBlank() || userId <= 0L) return false
        return runCatching { api.msgPrivateSendSong(cookie, userId, song.id, message) }
            .getOrNull()?.int("code") == 200
    }

    /** 分享歌单卡片 */
    suspend fun sendPlaylist(userId: Long, playlistId: String, message: String = "分享一个歌单给你~"): Boolean {
        val cookie = ncm.cookie.value
        if (cookie.isBlank() || userId <= 0L) return false
        return runCatching { api.msgPrivateSendPlaylist(cookie, userId, playlistId, message) }
            .getOrNull()?.int("code") == 200
    }
    /* ---------------- 解析 ---------------- */

    /**
     * 歌曲卡片里的歌曲 id。
     *
     * 实测官方卡片形如：
     * `{"msg":"给你分享一首歌~","song":{"no":2,…,"mMusic":{"id":723812,…},…}}`
     * 其中 `song.id` 可能以**数字**下发（`JsonPrimitive.content` 会转成字符串），
     * 也可能只出现在 `mMusic` / `hMusic` / `lMusic` 里（网易云这三个字段的 id
     * 与歌曲 id 恒等），因此逐级兜底。
     */
    private fun songIdOf(song: JsonObject, inner: JsonObject?, el: JsonElement): String? =
        song.str("id")
            ?: song.str("songId")
            ?: song.str("songid")
            ?: song.objOrNull("mMusic")?.str("id")
            ?: song.objOrNull("hMusic")?.str("id")
            ?: song.objOrNull("lMusic")?.str("id")
            ?: inner?.str("songId")
            ?: inner?.str("id")
            ?: el.str("songId")

    /** 歌手：`artists[].name`（老结构）/ `ar[].name`（新结构）/ 纯字符串 `artists` */
    private fun artistOf(song: JsonObject): String {
        val fromArray = (song.arrOrNull("artists") ?: song.arrOrNull("ar"))
            ?.mapNotNull { it.str("name") }
            ?.filter { it.isNotBlank() }
            ?.joinToString("/")
            .orEmpty()
        return fromArray.ifBlank { song.str("artists").orEmpty() }
    }

    /** 封面：`album.picUrl`（老）/ `al.picUrl`（新）/ 卡片自带 `picUrl` */
    private fun coverOf(song: JsonObject): String? =
        song.objOrNull("album")?.str("picUrl")
            ?: song.objOrNull("al")?.str("picUrl")
            ?: song.str("picUrl")
            ?: song.str("coverUrl")


    private fun parseConversation(el: JsonElement, me: Long): ChatConversation? {
        // 实测结构：{"user": {"id","toUserId","fromUserId","msgCount","newMsgCount","lastMsgTime","lastMsg"}}
        // 注意 newMsgCount / lastMsgTime / lastMsg 都嵌在 user 里，不在外层。
        val user = el.objOrNull("user") ?: el
        val fromUid = user.long("fromUserId") ?: el.long("fromUserId") ?: 0L
        val toUid = user.long("toUserId") ?: el.long("toUserId") ?: 0L
        val other = when {
            fromUid > 0L && fromUid != me -> fromUid
            toUid > 0L && toUid != me -> toUid
            else -> return null
        }
        val nickname = user.str("nickname")
            ?: user.str("nickName")
            ?: el.str("nickname")
            .orEmpty()
        val avatar = user.str("avatarUrl") ?: el.str("avatarUrl").orEmpty()
        val unread = user.int("newMsgCount") ?: user.int("unreadCount") ?: el.int("newMsgCount") ?: 0
        val lastTime = user.long("lastMsgTime") ?: el.long("lastMsgTime") ?: el.long("time") ?: 0L
        val preview = previewFromLastMsg(user.str("lastMsg") ?: el.str("lastMsg"))
        return ChatConversation(
            userId = other,
            nickname = nickname,
            avatarUrl = avatar,
            lastMessage = preview.orEmpty(),
            lastTime = lastTime,
            unreadCount = unread,
            lastFromMe = false,
        )
    }

    /**
     * 逐层解开嵌套的 JSON 字符串。
     *
     * 实测网易云私信存在**两层包装**：
     * `msg = {"msgId":…,"msg":"{…真正的卡片 JSON…}"}`；
     * 而聊天记录里通常只有一层。这里统一解到「最内层的对象」，
     * 解析失败（例如纯文本）返回 null，由调用方按纯文本处理。
     */
    private fun unwrapJsonChain(raw: String, maxDepth: Int = 3): JsonObject? {
        var obj = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        repeat(maxDepth - 1) {
            // `msg` 既可能是「再包一层的 JSON 字符串」，也可能直接是对象
            val next = when (val value = obj["msg"]) {
                is JsonObject -> value
                is JsonPrimitive -> runCatching { AppJson.parseToJsonElement(value.content) }
                    .getOrNull() as? JsonObject
                else -> null
            } ?: return obj
            obj = next
        }
        return obj
    }

    /**
     * 会话预览文本（`lastMsg` 是两层嵌套的 JSON 字符串）。
     */
    private fun previewFromLastMsg(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val inner = unwrapJsonChain(raw) ?: return raw.take(PREVIEW_MAX)
        val text = inner.str("msg").orEmpty()
        val song = inner.objOrNull("song")
        val playlist = inner.objOrNull("playlist")
        val album = inner.objOrNull("album")
        return when {
            song != null -> "[歌曲] ${song.str("name").orEmpty()}"
            playlist != null -> "[歌单] ${playlist.str("name").orEmpty()}"
            album != null -> "[专辑] ${album.str("name").orEmpty()}"
            (inner.int("type") ?: 0) == TYPE_INVITE -> text.ifBlank { "[一起听邀请]" }
            text.isNotBlank() -> text
            else -> "[消息]"
        }
    }

    /**
     * 补全会话的昵称与头像。
     *
     * 私信会话列表只给 uid，不含昵称；这里用公开的用户详情接口补齐，
     * 并做**进程内缓存**（同一好友只请求一次），最多补 [MAX_PROFILE_FETCH] 个，
     * 并发限 [PROFILE_CONCURRENCY]，避免对接口造成压力。
     */
    private suspend fun fillProfiles(list: List<ChatConversation>): List<ChatConversation> {
        val missing = list.filter { it.nickname.isBlank() && profileCache[it.userId] == null }
            .take(MAX_PROFILE_FETCH)
        if (missing.isEmpty()) {
            return list.map { conv -> mergeProfile(conv) }
        }
        val semaphore = Semaphore(PROFILE_CONCURRENCY)
        coroutineScope {
            missing.map { conv ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            val json = api.userProfile(conv.userId)
                            val profile = json.objOrNull("profile") ?: json
                            val name = profile.str("nickname").orEmpty()
                            val avatar = profile.str("avatarUrl").orEmpty()
                            if (name.isNotBlank() || avatar.isNotBlank()) {
                                synchronized(profileCache) { profileCache[conv.userId] = name to avatar }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        return list.map { conv -> mergeProfile(conv) }
    }

    private fun mergeProfile(conv: ChatConversation): ChatConversation {
        val cached = synchronized(profileCache) { profileCache[conv.userId] } ?: return conv
        return conv.copy(
            nickname = conv.nickname.ifBlank { cached.first },
            avatarUrl = conv.avatarUrl.ifBlank { cached.second },
        )
    }

    private fun parseMessage(el: JsonElement, me: Long): ChatMessage? {
        val id = el.long("id") ?: return null
        val time = el.long("time") ?: 0L
        val fromUid = el.objOrNull("fromUser")?.long("userId") ?: el.long("fromUserId") ?: 0L
        val type = el.int("type") ?: 0
        val raw = el.str("msg").orEmpty()
        val inner = unwrapJsonChain(raw)
        val innerMsg = inner?.str("msg").orEmpty()
        val song = inner?.objOrNull("song") ?: el.objOrNull("song")
        val playlist = inner?.objOrNull("playlist") ?: el.objOrNull("playlist")
        val album = inner?.objOrNull("album") ?: el.objOrNull("album")
        val innerType = inner?.int("type") ?: type
        val fromMe = fromUid > 0L && fromUid == me
        val fallbackText = if (inner == null) raw else ""

        return when {
            song != null -> ChatMessage(
                id = id,
                time = time,
                fromMe = fromMe,
                senderId = fromUid,
                kind = ChatMessageKind.SONG,
                text = innerMsg,
                songId = songIdOf(song, inner, el),
                songName = song.str("name") ?: song.str("songName") ?: song.str("title"),
                songArtist = artistOf(song),
                coverUrl = coverOf(song),
            ).also {
                // 诊断：卡片解析出的关键字段（若 id 缺失，把原始内容写进日志便于定位结构差异）
                if (it.songId.isNullOrBlank()) {
                    AppLogger.w(TAG, "歌曲卡片缺少 id，原始内容：${raw.take(CARD_LOG_MAX)}")
                } else {
                    AppLogger.d(TAG, "歌曲卡片解析：id=${it.songId} name=${it.songName}")
                }
            }

            playlist != null -> ChatMessage(
                id = id,
                time = time,
                fromMe = fromMe,
                senderId = fromUid,
                kind = ChatMessageKind.PLAYLIST,
                text = innerMsg,
                cardId = playlist.str("id"),
                cardTitle = playlist.str("name").orEmpty(),
                cardSubtitle = playlist.long("trackCount")?.let { "$it 首" }.orEmpty(),
                cardCoverUrl = playlist.str("coverImgUrl") ?: playlist.str("picUrl"),
            )

            album != null -> ChatMessage(
                id = id,
                time = time,
                fromMe = fromMe,
                senderId = fromUid,
                kind = ChatMessageKind.ALBUM,
                text = innerMsg,
                cardId = album.str("id"),
                cardTitle = album.str("name").orEmpty(),
                cardSubtitle = album.str("artist").orEmpty().ifBlank {
                    album.objOrNull("artist")?.str("name").orEmpty()
                },
                cardCoverUrl = album.str("picUrl") ?: album.str("blurPicUrl"),
            )

            innerType == TYPE_INVITE -> ChatMessage(
                id = id,
                time = time,
                fromMe = fromMe,
                senderId = fromUid,
                kind = ChatMessageKind.INVITE,
                text = innerMsg.ifBlank { "一起听邀请" },
            )

            innerMsg.isNotBlank() -> ChatMessage(
                id = id,
                time = time,
                fromMe = fromMe,
                senderId = fromUid,
                kind = ChatMessageKind.TEXT,
                text = innerMsg,
            )

            fallbackText.isNotBlank() -> ChatMessage(
                id = id,
                time = time,
                fromMe = fromMe,
                senderId = fromUid,
                kind = ChatMessageKind.TEXT,
                text = fallbackText,
            )

            else -> ChatMessage(
                id = id,
                time = time,
                fromMe = fromMe,
                senderId = fromUid,
                kind = ChatMessageKind.UNKNOWN,
                text = "[暂不支持的消息类型]",
            )
        }
    }

    /* ---------------- 临时探针（用于核对真实响应字段名，验证后会移除） ---------------- */

    private val probedNames = mutableSetOf<String>()

    private fun dumpProbe(name: String, json: JsonElement) {
        if (!probedNames.add(name)) return
        runCatching {
            val dir = com.dpmusic.app.AppContainer.appContext.cacheDir
            File(dir, "ncm_chat_probe_$name.json").writeText(json.toString())
        }
    }

    private companion object {
        /** 官方限制：私信文本最长 1000 字符 */
        const val MAX_TEXT_LENGTH = 1000

        /** 一起听邀请卡片的内层 type */
        const val TYPE_INVITE = 23

        /** 会话预览文本最大长度（防止卡片 JSON 泄漏到列表） */
        const val PREVIEW_MAX = 60

        /** 单次最多补全多少个好友资料（避免请求风暴） */
        const val MAX_PROFILE_FETCH = 12

        /** 好友资料补全的并发上限 */
        const val PROFILE_CONCURRENCY = 4

        /** 诊断日志里保留的卡片原始内容长度 */
        const val CARD_LOG_MAX = 400

        const val TAG = "NcmChat"
    }
}