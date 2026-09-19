package com.dpmusic.app.core.together

import com.dpmusic.app.core.data.NcmRepository
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.TogetherChatKind
import com.dpmusic.app.core.model.TogetherChatMessage
import com.dpmusic.app.core.model.TogetherInvite
import com.dpmusic.app.core.model.TogetherPlayCommand
import com.dpmusic.app.core.model.TogetherPlaylist
import com.dpmusic.app.core.model.TogetherRoomInfo
import com.dpmusic.app.core.model.TogetherUser
import com.dpmusic.app.core.net.AppJson
import com.dpmusic.app.core.net.NcmApi
import com.dpmusic.app.core.net.arrOrNull
import com.dpmusic.app.core.net.bool
import com.dpmusic.app.core.net.int
import com.dpmusic.app.core.net.long
import com.dpmusic.app.core.net.objOrNull
import com.dpmusic.app.core.net.str
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.repo.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/** 一起听页面状态 */
sealed interface TogetherUiState {

    /** 未加入（展示加入入口） */
    data object Idle : TogetherUiState

    /** 加入 / 恢复中 */
    data class Joining(val step: String) : TogetherUiState

    /** 在房间中 */
    data class InRoom(
        val room: TogetherRoomInfo,
        val command: TogetherPlayCommand? = null,
        val playlistIds: List<String> = emptyList(),
        val songs: List<Song> = emptyList(),
        /** 列表版本数组（发 REPLACE/ADD 命令时按自己那条自增） */
        val versions: List<TogetherPlaylist.VersionEntry> = emptyList(),
        /** 当前命令的本地锚点（收到该 serverSeq 时的本地时间；用于实时进度换算） */
        val anchorMs: Long = 0L,
        val lastSyncAt: Long = 0L,
    ) : TogetherUiState {
        val currentIndex: Int
            get() {
                val id = command?.targetSongId ?: return -1
                return songs.indexOfFirst { it.id == id }
            }

        val currentSong: Song? get() = songs.getOrNull(currentIndex)

        /** 实时进度：PLAY 时在锚点上外推；PAUSE 时取命令进度；按曲长夹紧 */
        fun positionNow(now: Long): Long {
            val cmd = command ?: return 0L
            val base = if (cmd.playStatus == "PLAY" && anchorMs > 0L) {
                cmd.progress + (now - anchorMs).coerceAtLeast(0L)
            } else {
                cmd.progress
            }
            val duration = currentSong?.durationMs ?: 0L
            return if (duration > 0L) base.coerceIn(0L, duration) else base.coerceAtLeast(0L)
        }
    }

    /** 房间已结束 */
    data class Ended(val summary: String?) : TogetherUiState

    /** 错误态（加入失败 / 登录失效等） */
    data class Error(
        val message: String,
        /** 检测到「已在旧房间」时的旧房间 id（可一键清理后重试） */
        val staleRoomId: String? = null,
        /** 是否可重试上一次加入 */
        val canRetry: Boolean = false,
        /** 是否因登录失效（引导去设置） */
        val needLogin: Boolean = false,
        /** 是否因「创建房间」触发（清理旧房后重试创建而非加入） */
        val pendingCreate: Boolean = false,
    ) : TogetherUiState
}

/**
 * 一起听会话（进程级单例，挂载于 AppContainer）：
 * - 创建 / 加入 / 恢复 / 结束 / 退出房间；1 秒轮询同步 + 5 秒心跳保活；
 * - 播放跟随：房间播放命令变化时自动驱动本地播放器对齐歌曲与进度；
 * - 播放控制：播放/暂停、上一首/下一首、seek、点歌（GOTO），带乐观更新；
 * - 自动切歌（可选开关）：歌曲结束由本端发起切换，并接管对方侧的自然切歌（用于完整播放 VIP 歌曲）。
 *
 * 实测要点（见项目技术文档）：
 * - accept 失败 ALREADY_IN_ROOM 时携带旧房间信息，需先 end/v2 清理再重试；
 * - end/v2 任何成员均可调用（会结束双方房间）；
 * - 关房后 sync 返回 data:{}（空数据即视为已结束）；
 * - clientSeq 必须以读回值自增；commandInfo 必须为 JSON 字符串。
 */
class TogetherSession(
    private val api: NcmApi,
    private val ncm: NcmRepository,
    private val player: PlayerConnection,
    private val musicRepository: MusicRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<TogetherUiState>(TogetherUiState.Idle)
    val state: StateFlow<TogetherUiState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null
    private var autoJob: Job? = null

    private var currentRoomId: String? = null
    private var lastJoinInput: String? = null

    /** 聊天：轮询任务 */
    private var chatJob: Job? = null

    /** 聊天：上次发送时间（风控节流） */
    private var lastChatSendAt = 0L

    private val _chatMessages = MutableStateFlow<List<TogetherChatMessage>>(emptyList())

    /** 聊天消息列表（时间正序） */
    val chatMessages: StateFlow<List<TogetherChatMessage>> = _chatMessages.asStateFlow()

    /** play/command 序列号（以轮询读回值为基线自增） */
    private var commandSeq = 0L

    /** 本地命令时间：4 秒内不执行跟随纠偏，避免与乐观更新打架 */
    private var localCommandAt = 0L

    /** 播放跟随节流（key -> 发起时间），避免解析失败时高频重试 */
    private var followAttempt: Pair<String, Long>? = null

    /** 歌单详情缓存（按 id 列表变化刷新） */
    private var songsCache: Pair<List<String>, List<Song>>? = null

    /** 本次房间会话是否见过有效内容（区分「新房空数据」与「已结束空数据」） */
    private var seenContent = false

    /** 自动切歌：本端刚推进过的歌（等待房间回执，防重复发送） */
    private var autoGuardSongId: String? = null
    private var autoGuardAt = 0L

    /** 自动切歌：发送失败后的重试时间戳（毫秒） */
    private var autoRetryAfter = 0L

    /** 自动接管：刚重新以本端确认过的歌（防重复接管） */
    private var reassertGuardSongId: String? = null
    private var reassertGuardAt = 0L

    /* ---------------- 页面联动 ---------------- */

    fun onPageOpen() {
        if (_state.value is TogetherUiState.InRoom) refreshNow()
    }

    /* ---------------- 加入 / 清理 / 恢复 / 结束 ---------------- */

    fun join(input: String) {
        val invite = TogetherInviteParser.parse(input)
        if (invite == null) {
            _state.value = TogetherUiState.Error("无法识别邀请信息：请粘贴好友分享的一起听链接（需包含 roomId 与邀请人 uid）")
            return
        }
        lastJoinInput = input
        launchJoin(invite)
    }

    /** 重试上一次加入（网络类失败后） */
    fun retryJoin() {
        val input = lastJoinInput ?: return
        val invite = TogetherInviteParser.parse(input) ?: return
        launchJoin(invite)
    }

    /** 创建一起听房间（房主视角：建房后与「加入」共用轮询 / 心跳 / 播放跟随） */
    fun createRoom() {
        if (_state.value is TogetherUiState.InRoom || _state.value is TogetherUiState.Joining) return
        launchCreate()
    }

    private fun launchCreate() {
        _state.value = TogetherUiState.Joining("正在创建一起听房间…")
        scope.launch {
            val cookie = ncm.cookie.value
            if (cookie.isBlank()) {
                _state.value = TogetherUiState.Error("请先在设置中登录网易云音乐", needLogin = true)
                return@launch
            }
            val json = runCatching { api.togetherCreate(cookie) }.getOrElse {
                _state.value = TogetherUiState.Error("网络异常：${it.message ?: "请稍后重试"}", canRetry = true)
                return@launch
            }
            val code = json.int("code") ?: -1
            when (code) {
                200 -> {
                    val data = json.objOrNull("data")
                    val type = data?.str("type").orEmpty()
                    if (type == "ALREADY_IN_ROOM") {
                        val stale = data?.objOrNull("roomInfo")?.str("roomId")
                        _state.value = TogetherUiState.Error(
                            message = "你的账号还在另一个一起听房间中，需要先结束旧房间才能创建",
                            staleRoomId = stale,
                            canRetry = stale != null,
                            pendingCreate = true,
                        )
                    } else {
                        val room = parseRoomInfo(data?.objOrNull("roomInfo"))
                        if (room == null) {
                            _state.value = TogetherUiState.Error("创建失败：响应缺少房间信息", canRetry = true)
                        } else {
                            enterRoom(room)
                        }
                    }
                }
                301 -> _state.value = TogetherUiState.Error("网易云登录已失效，请到设置中重新登录", needLogin = true)
                else -> _state.value = TogetherUiState.Error("创建失败（code=$code），请稍后重试", canRetry = true)
            }
        }
    }

    /** 结束旧房间并重试（ALREADY_IN_ROOM 场景：按上次意图重试「加入」或「创建」） */
    fun retryCleanupAndJoin() {
        val err = _state.value as? TogetherUiState.Error ?: return
        val stale = err.staleRoomId ?: return
        scope.launch {
            _state.value = TogetherUiState.Joining("正在结束旧房间…")
            val cookie = ncm.cookie.value
            val ok = runCatching { api.togetherEnd(cookie, stale) }.getOrNull()?.int("code") == 200
            if (!ok) {
                _state.value = TogetherUiState.Error("旧房间结束失败，请稍后重试", staleRoomId = stale, canRetry = true, pendingCreate = err.pendingCreate)
                return@launch
            }
            ncm.setLastRoomId(null)
            if (err.pendingCreate) {
                launchCreate()
                return@launch
            }
            val input = lastJoinInput
            val invite = if (input != null) TogetherInviteParser.parse(input) else null
            if (invite != null) launchJoin(invite) else _state.value = TogetherUiState.Idle
        }
    }

    /** 恢复上次未结束的房间（冷启动后） */
    fun resumeLastRoom() {
        val roomId = ncm.lastRoomId.value.takeIf { it.isNotBlank() } ?: return
        _state.value = TogetherUiState.Joining("正在恢复上次的一起听…")
        scope.launch {
            val cookie = ncm.cookie.value
            if (cookie.isBlank()) {
                _state.value = TogetherUiState.Error("请先在设置中登录网易云音乐", needLogin = true)
                return@launch
            }
            val json = runCatching { api.togetherSync(cookie, roomId) }.getOrNull()
            val data = json?.objOrNull("data")
            val alive = json != null && json.int("code") == 200 && data != null &&
                (data.objOrNull("playCommand") != null || data.objOrNull("playlist") != null)
            if (!alive) {
                // 空数据：无法区分「新房尚未加歌」与「房间已结束」。
                // 心跳实测不可用于探活（关房后仍返回 result:true），改用「本地过期时间」兜底：
                // 1) 本地持久化过期时间戳；2) roomId 尾部时间戳（秒）+ 30 分钟推算。
                val nowMs = System.currentTimeMillis()
                val localExpire = ncm.lastRoomExpireAt.value.takeIf { it > 0L }
                val roomIdExpire = roomId.substringAfterLast('_', "")
                    .toLongOrNull()?.let { it * 1000L + 30 * 60 * 1000L }
                val expireAt = localExpire ?: roomIdExpire ?: 0L
                if (expireAt <= 0L || nowMs > expireAt + 30_000L) {
                    ncm.setLastRoomId(null)
                    _state.value = TogetherUiState.Idle
                    _notice.value = "上次的一起听已结束"
                    return@launch
                }
                // 房间可能仍存活（空房）：恢复进房间，交由轮询按过期时间继续判断
                ncm.setLastRoomExpireAt(expireAt)
                currentRoomId = roomId
                seenContent = false
                _state.value = TogetherUiState.InRoom(room = TogetherRoomInfo(roomId = roomId, creatorId = 0L))
                startLoops(roomId)
                return@launch
            }
            currentRoomId = roomId
            seenContent = true
            val room = parseRoomInfo(data.objOrNull("roomInfo"))
                ?: TogetherRoomInfo(roomId = roomId, creatorId = 0L)
            if (room.roomCreateTime > 0L && room.effectiveDurationMs > 0L) {
                ncm.setLastRoomExpireAt(room.roomCreateTime + room.effectiveDurationMs)
            }
            _state.value = TogetherUiState.InRoom(room = room)
            applySnapshot(roomId, data)
            startLoops(roomId)
        }
    }

    /** 结束当前房间（双方退出；同时解除账号锁定） */
    fun endRoom() {
        val roomId = currentRoomId ?: return
        scope.launch {
            val cookie = ncm.cookie.value
            val json = runCatching { api.togetherEnd(cookie, roomId) }.getOrNull()
            if (json == null) {
                _notice.value = "结束请求失败：网络异常"
                return@launch
            }
            if (json.int("code") != 200) {
                _notice.value = "结束失败（code=${json.int("code")}）"
                return@launch
            }
            val share = json.objOrNull("data")?.objOrNull("shareInfo")
            val count = share?.int("totalListenCount")
            val seconds = share?.long("totalDuration")
            val summary = buildString {
                if (count != null && count > 0) append("共聆听 $count 首")
                if (seconds != null && seconds > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("时长约 ${(seconds / 60).coerceAtLeast(1)} 分钟")
                }
            }.takeIf { it.isNotBlank() }
            stopLoops()
            currentRoomId = null
            ncm.setLastRoomId(null)
            _state.value = TogetherUiState.Ended(summary)
        }
    }

    /** 退出房间（官方无 leave 接口：仅本地停止轮询/心跳，不结束房间、不影响对方；之后可「恢复连接」） */
    fun exitRoom() {
        if (_state.value !is TogetherUiState.InRoom) return
        stopLoops()
        currentRoomId = null
        _state.value = TogetherUiState.Idle
        _notice.value = "已退出房间（未结束，对方不受影响）"
    }

    /** 合成当前房间的分享链接（复制给好友，DPmusic 粘贴 / 剪贴板识别即可加入） */
    fun shareLink(): String? {
        val room = (_state.value as? TogetherUiState.InRoom)?.room ?: return null
        val uid = ncm.profile.value?.userId ?: return null
        return "https://st.music.163.com/listen-together/share/?roomId=${room.roomId}&inviterId=$uid"
    }

    /** 发送官方私信邀请（对方在网易云「消息」中收到邀请卡片；DPmusic 端会自动识别提示） */
    fun sendInviteMessage(acceptorId: Long) {
        val roomId = currentRoomId ?: return
        if (acceptorId <= 0L) {
            _notice.value = "请输入有效的网易云 UID"
            return
        }
        scope.launch {
            val cookie = ncm.cookie.value
            if (cookie.isBlank()) {
                _notice.value = "请先在设置中登录网易云音乐"
                return@launch
            }
            val json = runCatching { api.togetherInviteMessage(cookie, roomId, acceptorId) }.getOrNull()
            val ok = json?.objOrNull("data")?.bool("result") == true
            _notice.value = if (ok) "邀请已发送，对方将在网易云消息中收到邀请卡片" else "邀请发送失败，可改用「复制邀请链接」"
        }
    }

    /* ---------------- 歌单同步 ---------------- */

    /** 把「当前播放队列」的歌曲同步到房间歌单 */
    fun syncQueueToRoom() {
        syncPlaylistToRoom(player.queue.value.songs)
    }

    /** 从「我的网易云歌单」导入到房间：拉取歌单歌曲后同步 */
    fun importPlaylistToRoom(playlistId: String) {
        scope.launch {
            _notice.value = "正在读取歌单…"
            val songs = runCatching { musicRepository.playlistSongs(MusicPlatform.WY, playlistId) }.getOrNull().orEmpty()
            if (songs.isEmpty()) {
                _notice.value = "歌单为空或读取失败"
                return@launch
            }
            syncPlaylistToRoom(songs)
        }
    }

    /**
     * 把歌曲列表同步到房间歌单（REPLACE）并切到第一首开始播放；
     * 自动过滤非网易云歌曲、去重、限 [MAX_SYNC_SONGS] 首；房主 / 成员均可使用。
     */
    fun syncPlaylistToRoom(songs: List<Song>) {
        val roomId = currentRoomId ?: return
        val wySongs = songs.filter { it.platform == MusicPlatform.WY }.distinctBy { it.id }
        if (wySongs.isEmpty()) {
            _notice.value = "没有可同步的网易云歌曲"
            return
        }
        val limited = wySongs.size > MAX_SYNC_SONGS
        val finalSongs = wySongs.take(MAX_SYNC_SONGS)
        val ids = finalSongs.map { it.id }
        scope.launch {
            val cookie = ncm.cookie.value
            if (cookie.isBlank()) {
                _notice.value = "请先在设置中登录网易云音乐"
                return@launch
            }
            val uid = ncm.profile.value?.userId ?: return@launch
            val st = _state.value as? TogetherUiState.InRoom ?: return@launch
            // version：只递增自己账号那条（基于服务端最新值 +1；首次参与从 1 开始）
            val myVersion = st.versions.firstOrNull { it.userId == uid }?.version ?: 0
            val param = buildJsonObject {
                put("commandType", "REPLACE")
                put("version", buildJsonArray {
                    add(buildJsonObject {
                        put("userId", uid)
                        put("version", myVersion + 1)
                    })
                })
                put("anchorSongId", "")
                put("anchorPosition", -1)
                put("randomList", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                put("displayList", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
            }.toString()
            val ok = runCatching { api.togetherListCommand(cookie, roomId, param) }.getOrNull()?.int("code") == 200
            if (!ok) {
                _notice.value = "歌单同步失败，请稍后重试"
                return@launch
            }
            // 本地立即开始播放第一首
            player.playQueue(finalSongs, 0, 0L)
            // 等另一端完成列表同步后再发 GOTO（实测建议约 2 秒）
            delay(2000L)
            val cmd = (_state.value as? TogetherUiState.InRoom)?.command ?: TogetherPlayCommand()
            sendGotoCommand(cmd, ids.first(), 0L)
            _notice.value = if (limited) "已同步前 $MAX_SYNC_SONGS 首到房间（列表较大）" else "已同步 ${ids.size} 首到房间，一起听开始"
        }
    }

    /* ---------------- 聊天（私信通道） ---------------- */

    /** 聊天对象（房间内另一位成员）；无则 null */
    fun chatPartner(): Pair<Long, String>? {
        val st = _state.value as? TogetherUiState.InRoom ?: return null
        val myUid = ncm.profile.value?.userId ?: return null
        val other = st.room.roomUsers.firstOrNull { it.userId != myUid } ?: return null
        return other.userId to other.nickname.ifBlank { "好友" }
    }

    /** 打开聊天：启动消息轮询（返回 false 表示房间内暂无聊天对象） */
    fun openChat(): Boolean {
        if (chatPartner() == null) {
            _notice.value = "对方加入房间后即可聊天"
            return false
        }
        if (chatJob?.isActive == true) return true
        chatJob = scope.launch {
            while (isActive) {
                runCatching { loadChatMessages() }
                delay(CHAT_POLL_MS)
            }
        }
        return true
    }

    /** 关闭聊天：停止轮询（消息列表保留，下次打开即见） */
    fun closeChat() {
        chatJob?.cancel()
        chatJob = null
    }

    /** 发送聊天文本（私信通道，对方官方 App 可直接收到并回复） */
    fun sendChatText(text: String) {
        val other = chatPartner()?.first ?: return
        val content = text.trim()
        if (content.isBlank()) return
        if (content.length > MAX_CHAT_LEN) {
            _notice.value = "消息过长（限 $MAX_CHAT_LEN 字）"
            return
        }
        if (!chatSendAllowed()) return
        scope.launch {
            val cookie = ncm.cookie.value
            if (cookie.isBlank()) {
                _notice.value = "请先在设置中登录网易云音乐"
                return@launch
            }
            val ok = runCatching { api.msgPrivateSendText(cookie, other, content) }.getOrNull()?.int("code") == 200
            if (!ok) {
                _notice.value = "发送失败，请稍后重试"
                return@launch
            }
            loadChatMessages()
        }
    }

    /** 发送「当前歌曲」卡片给聊天对象（一起听场景：把这首歌分享给你） */
    fun sendChatSong() {
        val other = chatPartner()?.first ?: return
        val song = (_state.value as? TogetherUiState.InRoom)?.currentSong
        if (song == null) {
            _notice.value = "当前没有播放中的歌曲"
            return
        }
        if (!chatSendAllowed()) return
        scope.launch {
            val cookie = ncm.cookie.value
            if (cookie.isBlank()) {
                _notice.value = "请先在设置中登录网易云音乐"
                return@launch
            }
            val ok = runCatching { api.msgPrivateSendSong(cookie, other, song.id, "给你分享一首歌~") }.getOrNull()?.int("code") == 200
            if (!ok) {
                _notice.value = "发送失败，请稍后重试"
                return@launch
            }
            loadChatMessages()
        }
    }

    /** 发送间隔节流（风控：≥1.2 秒） */
    private fun chatSendAllowed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastChatSendAt < CHAT_SEND_GAP_MS) {
            _notice.value = "发送太快，稍等一下"
            return false
        }
        lastChatSendAt = now
        return true
    }

    /** 拉取并合并聊天消息（最新 30 条；按 id 去重、时间正序、上限保留） */
    private suspend fun loadChatMessages() {
        val other = chatPartner()?.first ?: return
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) return
        val myUid = ncm.profile.value?.userId ?: 0L
        val json = runCatching { api.msgPrivateHistory(cookie, other, 30) }.getOrNull() ?: return
        val arr = json.arrOrNull("msgs") ?: return
        val parsed = arr.mapNotNull { parseChatMessage(it, myUid) }
        _chatMessages.value = (parsed + _chatMessages.value)
            .distinctBy { it.id }
            .sortedBy { it.time }
            .takeLast(MAX_CHAT_KEEP)
    }

    private fun parseChatMessage(el: JsonElement, myUid: Long): TogetherChatMessage? {
        val id = el.long("id") ?: return null
        val time = el.long("time") ?: 0L
        val fromUid = el.objOrNull("fromUser")?.long("userId") ?: 0L
        val raw = el.str("msg").orEmpty()
        val inner = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        val innerMsg = inner?.str("msg").orEmpty()
        val song = inner?.objOrNull("song")
        return when {
            song != null -> TogetherChatMessage(
                id = id,
                time = time,
                fromMe = fromUid == myUid,
                kind = TogetherChatKind.SONG,
                text = innerMsg.ifBlank { "分享了一首歌" },
                songId = song.str("id"),
                songName = song.str("name"),
                songArtist = song.arrOrNull("artists")?.mapNotNull { it.str("name") }?.joinToString("/").orEmpty(),
            )
            (inner?.int("type") ?: 0) == 23 -> TogetherChatMessage(
                id = id,
                time = time,
                fromMe = fromUid == myUid,
                kind = TogetherChatKind.INVITE,
                text = innerMsg.ifBlank { "一起听邀请" },
            )
            innerMsg.isNotBlank() -> TogetherChatMessage(
                id = id,
                time = time,
                fromMe = fromUid == myUid,
                kind = TogetherChatKind.TEXT,
                text = innerMsg,
            )
            else -> TogetherChatMessage(
                id = id,
                time = time,
                fromMe = fromUid == myUid,
                kind = TogetherChatKind.OTHER,
                text = "[消息]",
            )
        }
    }

    /* ---------------- 播放控制 ---------------- */

    fun playPause() {
        val st = _state.value as? TogetherUiState.InRoom ?: return
        val cmd = st.command ?: return
        val target = cmd.targetSongId ?: return
        val cookie = ncm.cookie.value
        val nextStatus = if (cmd.playStatus == "PLAY") "PAUSE" else "PLAY"
        val seq = nextSeq()
        val now = System.currentTimeMillis()
        val info = buildJsonObject {
            put("commandType", nextStatus)
            put("progress", st.positionNow(now))
            put("playStatus", nextStatus)
            put("formerSongId", target)
            put("targetSongId", target)
            put("clientSeq", seq)
        }.toString()
        scope.launch {
            if (!sendCommand(cookie, info)) return@launch
            localCommandAt = System.currentTimeMillis()
            val updated = cmd.copy(
                userId = ncm.profile.value?.userId ?: cmd.userId,
                commandType = nextStatus,
                playStatus = nextStatus,
                progress = st.positionNow(now),
                clientSeq = seq,
                serverSeq = System.currentTimeMillis(),
            )
            _state.value = st.copy(command = updated, anchorMs = System.currentTimeMillis())
            followLocal(_state.value as? TogetherUiState.InRoom ?: return@launch, force = true)
        }
    }

    fun nextSong() = gotoByOffset(1)

    fun previousSong() = gotoByOffset(-1)

    private fun gotoByOffset(offset: Int) {
        val st = _state.value as? TogetherUiState.InRoom ?: return
        val cmd = st.command ?: return
        if (st.songs.isEmpty()) return
        val idx = st.currentIndex.takeIf { it >= 0 } ?: 0
        val nextIdx = (idx + offset).mod(st.songs.size)
        sendGoto(st, cmd, st.songs[nextIdx], 0L)
    }

    /** 点歌（房间歌单点击 → GOTO 切换） */
    fun jumpTo(index: Int) {
        val st = _state.value as? TogetherUiState.InRoom ?: return
        val cmd = st.command ?: return
        val target = st.songs.getOrNull(index) ?: return
        if (target.id == cmd.targetSongId) return
        sendGoto(st, cmd, target, 0L)
    }

    private fun sendGoto(st: TogetherUiState.InRoom, cmd: TogetherPlayCommand, target: Song, progress: Long) {
        val cookie = ncm.cookie.value
        val seq = nextSeq()
        val info = buildJsonObject {
            put("commandType", "GOTO")
            put("progress", progress)
            put("playStatus", "PLAY")
            put("formerSongId", cmd.targetSongId ?: "-1")
            put("targetSongId", target.id)
            put("clientSeq", seq)
        }.toString()
        scope.launch {
            if (!sendCommand(cookie, info)) return@launch
            localCommandAt = System.currentTimeMillis()
            val latest = _state.value as? TogetherUiState.InRoom ?: return@launch
            val updated = cmd.copy(
                userId = ncm.profile.value?.userId ?: cmd.userId,
                commandType = "GOTO",
                targetSongId = target.id,
                progress = progress,
                playStatus = "PLAY",
                clientSeq = seq,
                serverSeq = System.currentTimeMillis(),
            )
            _state.value = latest.copy(command = updated, anchorMs = System.currentTimeMillis())
            followLocal(_state.value as? TogetherUiState.InRoom ?: return@launch, force = true)
        }
    }

    fun seekTo(positionMs: Long) {
        val st = _state.value as? TogetherUiState.InRoom ?: return
        val cmd = st.command ?: return
        val target = cmd.targetSongId ?: return
        val cookie = ncm.cookie.value
        val seq = nextSeq()
        val pos = positionMs.coerceAtLeast(0L)
        val info = buildJsonObject {
            put("commandType", "seek")
            put("progress", pos)
            put("playStatus", cmd.playStatus.ifBlank { "PLAY" })
            put("formerSongId", target)
            put("targetSongId", target)
            put("clientSeq", seq)
        }.toString()
        scope.launch {
            if (!sendCommand(cookie, info)) return@launch
            localCommandAt = System.currentTimeMillis()
            val latest = _state.value as? TogetherUiState.InRoom ?: return@launch
            _state.value = latest.copy(
                command = cmd.copy(progress = pos, clientSeq = seq, serverSeq = System.currentTimeMillis()),
                anchorMs = System.currentTimeMillis(),
            )
            player.seekTo(pos)
        }
    }

    fun refreshNow() {
        val roomId = currentRoomId ?: return
        scope.launch { pollOnce(roomId) }
    }

    /* ---------------- 状态清理 ---------------- */

    fun dismissError() {
        if (_state.value is TogetherUiState.Error) _state.value = TogetherUiState.Idle
    }

    fun acknowledgeEnd() {
        if (_state.value is TogetherUiState.Ended) _state.value = TogetherUiState.Idle
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /* ---------------- 内部：加入 / 快照 ---------------- */

    private fun launchJoin(invite: TogetherInvite) {
        _state.value = TogetherUiState.Joining("正在加入一起听…")
        scope.launch {
            val cookie = ncm.cookie.value
            if (cookie.isBlank()) {
                _state.value = TogetherUiState.Error("请先在设置中登录网易云音乐", needLogin = true)
                return@launch
            }
            val json = runCatching { api.togetherAccept(cookie, invite.roomId, invite.inviterId) }.getOrElse {
                _state.value = TogetherUiState.Error("网络异常：${it.message ?: "请稍后重试"}", canRetry = true)
                return@launch
            }
            val code = json.int("code") ?: -1
            when (code) {
                200 -> {
                    val data = json.objOrNull("data")
                    val type = data?.str("type").orEmpty()
                    if (type == "ALREADY_IN_ROOM") {
                        val stale = data?.objOrNull("roomInfo")?.str("roomId")
                        _state.value = TogetherUiState.Error(
                            message = "你的账号还在另一个一起听房间中，需要先结束旧房间才能加入",
                            staleRoomId = stale,
                            canRetry = stale != null,
                        )
                    } else {
                        val room = parseRoomInfo(data?.objOrNull("roomInfo"))
                        if (room == null) {
                            _state.value = TogetherUiState.Error("加入失败：响应缺少房间信息", canRetry = true)
                        } else {
                            enterRoom(room)
                        }
                    }
                }

                488 -> _state.value = TogetherUiState.Error("一起听已失效：房间已过期或不存在，请让好友重新创建并邀请")
                301 -> _state.value = TogetherUiState.Error("网易云登录已失效，请到设置中重新登录", needLogin = true)
                else -> _state.value = TogetherUiState.Error("加入失败（code=$code），请稍后重试", canRetry = true)
            }
        }
    }

    private suspend fun enterRoom(room: TogetherRoomInfo) {
        currentRoomId = room.roomId
        commandSeq = 0L
        songsCache = null
        followAttempt = null
        seenContent = false
        ncm.setLastRoomId(room.roomId)
        if (room.roomCreateTime > 0L && room.effectiveDurationMs > 0L) {
            ncm.setLastRoomExpireAt(room.roomCreateTime + room.effectiveDurationMs)
        }
        _state.value = TogetherUiState.InRoom(room = room)
        startLoops(room.roomId)
        pollOnce(room.roomId)
    }

    private suspend fun applySnapshot(roomId: String, data: JsonElement) {
        val roomInfo = parseRoomInfo(data.objOrNull("roomInfo"))
        val command = parseCommand(data.objOrNull("playCommand"))
        val playlist = parsePlaylist(data.objOrNull("playlist"))
        val ids = playlist?.songIds.orEmpty()
        val songs = if (ids.isEmpty()) emptyList() else loadSongs(ids)
        val prev = _state.value as? TogetherUiState.InRoom
        val room = roomInfo ?: prev?.room ?: TogetherRoomInfo(roomId = roomId, creatorId = 0L)
        val anchor = if (prev?.command?.serverSeq != command?.serverSeq) {
            System.currentTimeMillis()
        } else {
            prev?.anchorMs ?: System.currentTimeMillis()
        }
        command?.let { commandSeq = maxOf(commandSeq, it.clientSeq) }
        val newState = TogetherUiState.InRoom(
            room = room,
            command = command,
            playlistIds = ids,
            songs = songs,
            versions = playlist?.versions.orEmpty(),
            anchorMs = anchor,
            lastSyncAt = System.currentTimeMillis(),
        )
        _state.value = newState
        followLocal(newState)
        // 自动切歌：检测对方侧发起的自然切歌并接管确认（强制播放）
        maybeReassert(prev, command, newState)
    }

    /* ---------------- 内部：轮询 / 心跳 ---------------- */

    private fun startLoops(roomId: String) {
        pollJob?.cancel()
        heartbeatJob?.cancel()
        autoJob?.cancel()
        // 自动切歌：进入（或恢复）房间时重置防重状态
        autoGuardSongId = null
        autoRetryAfter = 0L
        reassertGuardSongId = null
        pollJob = scope.launch {
            while (isActive && currentRoomId == roomId) {
                runCatching { pollOnce(roomId) }
                delay(POLL_INTERVAL_MS)
            }
        }
        heartbeatJob = scope.launch {
            while (isActive && currentRoomId == roomId) {
                runCatching { heartbeatOnce(roomId) }
                // 房间信息（成员列表）刷新：roomUsers 仅在 status/get 返回（sync 不含），与心跳同频
                runCatching { refreshRoomInfo(roomId) }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
        autoJob = scope.launch {
            while (isActive && currentRoomId == roomId) {
                runCatching { autoAdvanceTick() }
                delay(AUTO_ADVANCE_TICK_MS)
            }
        }
    }

    private fun stopLoops() {
        pollJob?.cancel()
        pollJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        autoJob?.cancel()
        autoJob = null
    }

    private suspend fun pollOnce(roomId: String) {
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) {
            stopWithLoginLost()
            return
        }
        val json = runCatching { api.togetherSync(cookie, roomId) }.getOrElse { return }
        when (json.int("code")) {
            301 -> stopWithLoginLost()
            200 -> {
                val data = json.objOrNull("data")
                val hasContent = data != null &&
                    (data.objOrNull("playCommand") != null || data.objOrNull("playlist") != null)
                if (hasContent) {
                    seenContent = true
                    applySnapshot(roomId, data)
                    return
                }
                // 空数据：可能是「新房尚未加歌」或「房间已结束」。
                // 见过内容后再变空 → 已结束；从未见过内容 → 以本地有效期兜底判断。
                // 有效期来源：本地持久化过期时间 > roomInfo 时间 > roomId 尾缀时间戳（+30 分钟）。
                val st = _state.value as? TogetherUiState.InRoom
                val nowMs = System.currentTimeMillis()
                val localExpire = ncm.lastRoomExpireAt.value.takeIf { it > 0L }
                val roomInfoExpire = st?.room?.let { r ->
                    if (r.roomCreateTime > 0L && r.effectiveDurationMs > 0L) r.roomCreateTime + r.effectiveDurationMs else 0L
                }?.takeIf { it > 0L }
                val roomIdExpire = roomId.substringAfterLast('_', "")
                    .toLongOrNull()?.let { it * 1000L + 30 * 60 * 1000L }
                val expireAt = localExpire ?: roomInfoExpire ?: roomIdExpire ?: 0L
                val expired = expireAt > 0L && nowMs > expireAt + 30_000L
                if (seenContent || expired) {
                    stopLoops()
                    currentRoomId = null
                    ncm.setLastRoomId(null)
                    _state.value = TogetherUiState.Ended(null)
                }
            }
        }
    }

    private fun stopWithLoginLost() {
        stopLoops()
        currentRoomId = null
        _state.value = TogetherUiState.Error("网易云登录已失效，请到设置中重新登录", needLogin = true)
    }

    private suspend fun heartbeatOnce(roomId: String) {
        val st = _state.value as? TogetherUiState.InRoom ?: return
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) return
        // 空房（尚无播放命令）也发送心跳：songId=0 / PAUSE / 0（实测可用），保持等待窗口内房间活跃
        val cmd = st.command
        runCatching {
            api.togetherHeartbeat(
                cookie = cookie,
                roomId = roomId,
                songId = cmd?.targetSongId ?: "0",
                playStatus = cmd?.playStatus ?: "PAUSE",
                progress = st.positionNow(System.currentTimeMillis()),
            )
        }
    }

    /**
     * 刷新房间信息（成员列表）：roomUsers 仅由 status/get 返回（sync/playlist/get 不含），
     * 与心跳同频 5 秒刷新；对方（含官方 App 用户）加入 / 退出后成员列表随之更新。
     */
    private suspend fun refreshRoomInfo(roomId: String) {
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) return
        val json = runCatching { api.togetherStatus(cookie) }.getOrNull() ?: return
        val roomInfo = parseRoomInfo(json.objOrNull("data")?.objOrNull("roomInfo")) ?: return
        val prev = _state.value as? TogetherUiState.InRoom ?: return
        if (prev.room.roomId != roomId || roomInfo.roomId != roomId) return
        _state.value = prev.copy(room = roomInfo)
    }

    /* ---------------- 内部：播放跟随 ---------------- */

    private suspend fun followLocal(st: TogetherUiState.InRoom, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - localCommandAt < 4000) return
        val cmd = st.command ?: return
        val targetId = cmd.targetSongId ?: return
        val wantPlaying = cmd.playStatus == "PLAY"

        val np = player.nowPlaying.value
        val target = st.songs.firstOrNull { it.id == targetId }
        val sameSong = np != null && (
            np.song.stableKey == target?.stableKey ||
                (target != null && np.song.title.equals(target.title, ignoreCase = true) &&
                    np.song.artist.equals(target.artist, ignoreCase = true))
            )

        if (!sameSong) {
            val throttleKey = target?.stableKey ?: "wy:$targetId"
            val attempt = followAttempt
            if (!force && attempt != null && attempt.first == throttleKey && now - attempt.second < 25000) return
            followAttempt = throttleKey to now
            val pos = st.positionNow(now)
            if (target != null && st.songs.isNotEmpty()) {
                player.playQueue(st.songs, st.songs.indexOf(target).coerceAtLeast(0), pos)
            } else {
                val single = runCatching { musicRepository.songDetail(MusicPlatform.WY, targetId) }.getOrNull() ?: return
                player.playQueue(listOf(single), 0, pos)
            }
            if (!wantPlaying) pauseWhenLoaded(throttleKey)
            return
        }

        // 同一首歌：状态与进度对齐
        val roomPos = st.positionNow(now)
        if (abs(np.positionMs - roomPos) > 4000) player.seekTo(roomPos)
        if (wantPlaying && !np.isPlaying) player.togglePlayPause()
        if (!wantPlaying && np.isPlaying) player.togglePlayPause()
    }

    /** 暂停房间场景：等目标歌曲装载完成后本地暂停（避免加载瞬间短暂播放） */
    private fun pauseWhenLoaded(songKey: String) {
        scope.launch {
            repeat(8) {
                delay(600)
                val np = player.nowPlaying.value
                if (np?.song?.stableKey == songKey) {
                    if (np.isPlaying) player.togglePlayPause()
                    return@launch
                }
            }
        }
    }

    /* ---------------- 内部：命令发送 ---------------- */

    private suspend fun sendCommand(cookie: String, commandInfoJson: String): Boolean {
        val roomId = currentRoomId ?: return false
        val json = runCatching { api.togetherPlayCommand(cookie, roomId, commandInfoJson) }.getOrNull()
        if (json == null) {
            _notice.value = "操作失败：网络异常"
            return false
        }
        if (json.int("code") != 200) {
            _notice.value = "操作失败（code=${json.int("code")}）"
            return false
        }
        return true
    }

    private fun nextSeq(): Long {
        commandSeq += 1
        return commandSeq
    }

    /* ---------------- 内部：解析 ---------------- */

    /** 批量加载房间歌单歌曲详情（网易云；WyApi 500/批；ids 未变时用缓存，避免轮询重复请求） */
    private suspend fun loadSongs(ids: List<String>): List<Song> {
        val cached = songsCache
        if (cached != null && cached.first == ids) return cached.second
        val songs = runCatching { musicRepository.songsDetail(MusicPlatform.WY, ids) }
            .getOrElse { emptyList() }
        if (songs.isNotEmpty()) songsCache = ids to songs
        return songs
    }

    private fun parseRoomInfo(el: JsonElement?): TogetherRoomInfo? {
        val roomId = el?.str("roomId") ?: return null
        val users = el.arrOrNull("roomUsers")?.mapNotNull { u ->
            val id = u.long("userId") ?: return@mapNotNull null
            TogetherUser(
                userId = id,
                nickname = u.str("nickname").orEmpty(),
                avatarUrl = u.str("avatarUrl").orEmpty(),
            )
        }.orEmpty()
        return TogetherRoomInfo(
            roomId = roomId,
            creatorId = el.long("creatorId") ?: 0L,
            roomUsers = users,
            roomType = el.str("roomType").orEmpty(),
            roomCreateTime = el.long("roomCreateTime") ?: 0L,
            effectiveDurationMs = el.long("effectiveDurationMs") ?: 0L,
        )
    }

    private fun parseCommand(el: JsonElement?): TogetherPlayCommand? {
        if (el !is JsonObject) return null
        return TogetherPlayCommand(
            userId = el.long("userId") ?: 0L,
            commandType = el.str("commandType").orEmpty(),
            targetSongId = el.str("targetSongId"),
            progress = el.long("progress") ?: 0L,
            playStatus = el.str("playStatus").orEmpty(),
            clientSeq = el.long("clientSeq") ?: 0L,
            serverSeq = el.long("serverSeq") ?: 0L,
        )
    }

    private fun parsePlaylist(el: JsonElement?): TogetherPlaylist? {
        if (el !is JsonObject) return null
        val ids = el.objOrNull("displayList")?.arrOrNull("result")
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val versions = el.arrOrNull("version")?.mapNotNull { v ->
            val uid = v.long("userId") ?: return@mapNotNull null
            TogetherPlaylist.VersionEntry(uid, v.int("version") ?: 0)
        }.orEmpty()
        return TogetherPlaylist(
            songIds = ids,
            playMode = el.str("playMode") ?: "ORDER_LOOP",
            versions = versions,
        )
    }

    /* ---------------- 内部：自动切歌（本端发起） ---------------- */

    /**
     * 自动切歌节拍：开启后，当前歌曲接近自然结束时由本端发起切到队列下一首。
     * 目的：让每次切歌（含 VIP 歌曲）由本端（音源代理侧）发起，避免对方客户端切歌时
     * 因 VIP 受限导致无法完整收听；对方手动切歌 / 拖动进度不受影响（仅在自然结束时触发）。
     */
    private suspend fun autoAdvanceTick() {
        if (!ncm.togetherAutoAdvance.value) return
        val st = _state.value as? TogetherUiState.InRoom ?: return
        val cmd = st.command ?: return
        if (cmd.playStatus != "PLAY") return
        val targetId = cmd.targetSongId ?: return
        if (st.songs.size <= 1) return
        val now = System.currentTimeMillis()
        // 防重：刚由本端推进过的同一首歌，等待房间回执（含陈旧快照回跳场景）
        if (cmd.targetSongId == autoGuardSongId && now - autoGuardAt < AUTO_ADVANCE_GUARD_MS) return
        if (now < autoRetryAfter) return
        val idx = st.currentIndex
        if (idx < 0) return
        val duration = st.songs[idx].durationMs
        if (duration <= 0L) return
        val pos = st.positionNow(now)
        // 尚未接近结尾：继续等待
        if (pos < duration - AUTO_ADVANCE_LEAD_MS) return
        // 接近（或已到）结尾：由本端发起切歌
        val next = st.songs[(idx + 1) % st.songs.size]
        autoGuardSongId = targetId
        autoGuardAt = now
        val ok = sendGotoCommand(cmd, next.id, 0L)
        if (!ok) {
            // 失败：解除防重并退避重试
            autoGuardSongId = null
            autoRetryAfter = now + AUTO_ADVANCE_RETRY_MS
        }
    }

    /**
     * 接管确认：检测到「对方侧发起的自然切歌」时，本端重新以 GOTO 确认当前歌曲，
     * 使房间最后一条切歌命令由本端发起（强制播放，含 VIP 歌曲）。
     * 仅在旧曲接近结束时触发；对方手动切歌 / 拖动进度不干预。
     */
    private suspend fun maybeReassert(
        prev: TogetherUiState.InRoom?,
        command: TogetherPlayCommand?,
        nowState: TogetherUiState.InRoom,
    ) {
        if (!ncm.togetherAutoAdvance.value) return
        if (prev == null || command == null) return
        val prevCmd = prev.command ?: return
        val prevSongId = prevCmd.targetSongId ?: return
        val newSongId = command.targetSongId ?: return
        val myUid = ncm.profile.value?.userId ?: 0L
        if (myUid <= 0L) return
        if (command.userId == myUid) return
        if (command.playStatus != "PLAY") return
        val now = System.currentTimeMillis()
        val songChanged = newSongId != prevSongId
        if (songChanged) {
            // 场景1：切到了新歌——仅当旧曲已进入结尾窗口时接管（自然结束）；手动切歌不干预
            val prevSong = prev.songs.firstOrNull { it.id == prevSongId }
            val prevDuration = prevSong?.durationMs ?: 0L
            if (prevDuration <= 0L) return
            if (prev.positionNow(now) < prevDuration - REASSERT_END_WINDOW_MS) return
        } else {
            // 场景2：仍是同一首歌——本端此前的发起被对方命令覆盖（切歌竞速落败）
            if (prevCmd.userId != myUid) return
            if (now - localCommandAt > REASSERT_STAMP_WINDOW_MS) return
            if (command.commandType != "GOTO") return
            if (command.progress > 5_000L) return
        }
        // 防重：同一首歌短时间内只接管一次
        if (newSongId == reassertGuardSongId && now - reassertGuardAt < REASSERT_GUARD_MS) return
        val newSong = nowState.songs.firstOrNull { it.id == newSongId }
        val duration = newSong?.durationMs ?: 0L
        // 进度估计：接管发生在两次快照之间，取区间中点作为当前进度（尽量不产生跳变）
        val est = if (songChanged) {
            ((now - prev.lastSyncAt) / 2).coerceAtLeast(0L)
        } else {
            (now - localCommandAt).coerceAtLeast(0L)
        }
        val progress = if (duration > 0L) est.coerceIn(0L, duration) else est
        val ok = sendGotoCommand(command, newSongId, progress)
        if (ok) {
            reassertGuardSongId = newSongId
            reassertGuardAt = now
        }
    }

    /**
     * 由本端发送切歌命令（GOTO）并做乐观更新（自动切歌 / 接管确认共用）。
     * @return 是否发送成功（失败不发乐观更新，交由调用方决定重试）
     */
    private suspend fun sendGotoCommand(
        cmd: TogetherPlayCommand,
        targetSongId: String,
        progress: Long,
    ): Boolean {
        val roomId = currentRoomId ?: return false
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) return false
        val seq = nextSeq()
        val info = buildJsonObject {
            put("commandType", "GOTO")
            put("progress", progress)
            put("playStatus", "PLAY")
            put("formerSongId", cmd.targetSongId ?: "-1")
            put("targetSongId", targetSongId)
            put("clientSeq", seq)
        }.toString()
        val json = runCatching { api.togetherPlayCommand(cookie, roomId, info) }.getOrNull() ?: return false
        if (json.int("code") != 200) return false
        localCommandAt = System.currentTimeMillis()
        val latest = _state.value as? TogetherUiState.InRoom ?: return true
        val updated = cmd.copy(
            userId = ncm.profile.value?.userId ?: cmd.userId,
            commandType = "GOTO",
            targetSongId = targetSongId,
            progress = progress,
            playStatus = "PLAY",
            clientSeq = seq,
            serverSeq = System.currentTimeMillis(),
        )
        _state.value = latest.copy(command = updated, anchorMs = System.currentTimeMillis())
        followLocal(_state.value as? TogetherUiState.InRoom ?: return true, force = true)
        return true
    }

    private companion object {
        /** 房间轮询间隔（毫秒）——1 秒轮询实时跟随（MeloX 配方实测值） */
        const val POLL_INTERVAL_MS = 1_000L

        /** 心跳间隔（毫秒）——服务端超时窗口约 30 秒，5 秒一次保活更稳 */
        const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** 自动切歌：提前量（毫秒）——略早于自然结束发起，避免对方客户端先切导致 VIP 受限 */
        const val AUTO_ADVANCE_LEAD_MS = 1000L

        /** 自动切歌：检查节拍（毫秒） */
        const val AUTO_ADVANCE_TICK_MS = 500L

        /** 自动切歌：防重窗口（毫秒；同曲等待房间回执） */
        const val AUTO_ADVANCE_GUARD_MS = 20_000L

        /** 自动切歌：发送失败后的重试间隔（毫秒） */
        const val AUTO_ADVANCE_RETRY_MS = 5_000L

        /** 接管确认：防重窗口（毫秒；同一首歌） */
        const val REASSERT_GUARD_MS = 60_000L

        /** 接管确认：旧曲进入结尾 N 毫秒内视为「自然结束」（手动切歌不干预） */
        const val REASSERT_END_WINDOW_MS = 10_000L

        /** 接管确认：同一首歌被覆盖的判定窗口（毫秒；相对于本端上次发起） */
        const val REASSERT_STAMP_WINDOW_MS = 15_000L

        /** 单次同步歌曲数上限（避免超大列表 REPLACE 失败） */
        const val MAX_SYNC_SONGS = 500

        /** 聊天：消息轮询间隔（毫秒） */
        const val CHAT_POLL_MS = 3_000L

        /** 聊天：发送节流（毫秒；风控建议 ≥1 秒） */
        const val CHAT_SEND_GAP_MS = 1_200L

        /** 聊天：单条消息长度上限（官方限制 1000 字） */
        const val MAX_CHAT_LEN = 1_000

        /** 聊天：本地消息保留条数上限 */
        const val MAX_CHAT_KEEP = 200
    }
}