package com.dpmusic.app.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.NcmChatRepository
import com.dpmusic.app.core.model.ChatMessage
import com.dpmusic.app.core.model.ChatMessageKind
import com.dpmusic.app.core.model.ChatThreadState
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 会话详情 ViewModel（网易云私信）。
 *
 * 体验设计：
 * - **乐观发送**：点发送后气泡立刻出现（半透明 + 「发送中」），成功后与服务端记录合并；
 * - **失败可重试**：发送失败的气泡标红，点击即可重发，不会静默丢消息；
 * - **轮询**：进入会话后每 3 秒拉取一次新消息（官方 App 的回复也能及时出现）；
 * - **频率保护**：两次发送至少间隔 1 秒（官方风控建议）。
 */
class ChatThreadViewModel(
    private val chat: NcmChatRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    private val _state = MutableStateFlow<ChatThreadState>(ChatThreadState.Loading)
    val state: StateFlow<ChatThreadState> = _state.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notice: SharedFlow<String> = _notice.asSharedFlow()

    private var userId = 0L
    private var pollJob: Job? = null
    private var lastSendAt = 0L

    /** 本地乐观消息（服务端尚未返回 / 发送失败的重试项） */
    private val local = MutableStateFlow<List<ChatMessage>>(emptyList())

    /** 当前正在播放的歌曲（用于「分享当前歌曲」） */
    private val _nowPlaying = MutableStateFlow<Song?>(null)
    val nowPlaying: StateFlow<Song?> = _nowPlaying.asStateFlow()

    init {
        viewModelScope.launch {
            player.nowPlaying.collect { _nowPlaying.value = it?.song }
        }
    }

    /** 打开某个会话（幂等：同一会话重复调用不会重开轮询） */
    fun open(uid: Long) {
        if (uid <= 0L) return
        if (uid == userId && pollJob?.isActive == true) return
        userId = uid
        local.value = emptyList()
        _state.value = ChatThreadState.Loading
        load(initial = true)
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                load(initial = false)
            }
        }
    }

    /** 离开会话页：停止轮询（省流量与配额） */
    fun close() {
        pollJob?.cancel()
        pollJob = null
        userId = 0L
    }

    private fun load(initial: Boolean) {
        val uid = userId
        if (uid <= 0L) return
        viewModelScope.launch {
            val result = runCatching { chat.history(uid) }
            if (uid != userId) return@launch // 期间切换了会话：丢弃过期结果
            result.onSuccess { server ->
                _state.value = ChatThreadState.Ready(merge(server, local.value))
            }.onFailure { e ->
                if (initial && _state.value !is ChatThreadState.Ready) {
                    _state.value = ChatThreadState.Error(e.message ?: "加载失败")
                }
            }
        }
    }

    /** 服务端记录 + 本地乐观消息合并（乐观项一旦出现在服务端即移除） */
    private fun merge(server: List<ChatMessage>, pending: List<ChatMessage>): List<ChatMessage> {
        if (pending.isEmpty()) return server
        val confirmed = pending.filterNot { p ->
            server.any { s ->
                s.fromMe && s.kind == p.kind && s.text == p.text &&
                    kotlin.math.abs(s.time - p.time) < CONFIRM_WINDOW_MS
            }
        }
        return (server + confirmed).sortedBy { it.time }
    }

    /** 发送文本（乐观气泡 + 失败可重试） */
    fun sendText(text: String) {
        val content = text.trim()
        val uid = userId
        if (content.isEmpty() || uid <= 0L) return
        val now = System.currentTimeMillis()
        if (now - lastSendAt < MIN_SEND_GAP_MS) {
            _notice.tryEmit("发送太快了，稍等一下")
            return
        }
        lastSendAt = now
        pushLocal(
            ChatMessage(
                id = -now,
                time = now,
                fromMe = true,
                kind = ChatMessageKind.TEXT,
                text = content,
                pending = true,
            ),
        )
        dispatch(uid) { chat.sendText(uid, content) }
    }

    /** 分享当前正在播放的歌曲 */
    fun sendCurrentSong() {
        val song = player.nowPlaying.value?.song
        val uid = userId
        if (song == null) {
            _notice.tryEmit("当前没有正在播放的歌曲")
            return
        }
        if (uid <= 0L) return
        val now = System.currentTimeMillis()
        if (now - lastSendAt < MIN_SEND_GAP_MS) {
            _notice.tryEmit("发送太快了，稍等一下")
            return
        }
        lastSendAt = now
        pushLocal(
            ChatMessage(
                id = -now,
                time = now,
                fromMe = true,
                kind = ChatMessageKind.SONG,
                text = "分享一首歌给你~",
                songId = song.id,
                songName = song.title,
                songArtist = song.artist,
                coverUrl = song.coverUrl.ifBlank { null },
                pending = true,
            ),
        )
        dispatch(uid) { chat.sendSong(uid, song) }
    }

    /** 重发失败的消息 */
    fun retry(message: ChatMessage) {
        val uid = userId
        if (uid <= 0L || !message.failed) return
        local.value = local.value.filterNot { it.id == message.id }
        when (message.kind) {
            ChatMessageKind.SONG -> {
                val songId = message.songId ?: return
                val song = Song(
                    id = songId,
                    platform = MusicPlatform.WY,
                    title = message.songName.orEmpty(),
                    artist = message.songArtist.orEmpty(),
                    coverUrl = message.coverUrl.orEmpty(),
                )
                pushLocal(message.copy(pending = true, failed = false))
                dispatch(uid) { chat.sendSong(uid, song) }
            }

            else -> {
                pushLocal(message.copy(pending = true, failed = false))
                dispatch(uid) { chat.sendText(uid, message.text) }
            }
        }
    }

    /** 点击歌曲卡片：直接播放这首（网易云平台） */
    fun playSong(message: ChatMessage) {
        val songId = message.songId?.takeIf { it.isNotBlank() }
        if (songId == null) {
            _notice.tryEmit("这张歌曲卡片缺少可播放信息")
            AppLogger.w(TAG, "歌曲卡片无 songId，无法播放：${message.text}")
            return
        }
        val song = Song(
            id = songId,
            platform = MusicPlatform.WY,
            title = message.songName.orEmpty().ifBlank { "未知歌曲" },
            artist = message.songArtist.orEmpty(),
            coverUrl = message.coverUrl.orEmpty(),
        )
        AppLogger.d(TAG, "从聊天卡片播放：id=$songId name=${song.title}")
        player.playQueue(listOf(song), 0)
    }

    private fun pushLocal(message: ChatMessage) {
        local.value = (local.value + message)
        refreshFromCache()
    }

    private fun refreshFromCache() {
        val current = (_state.value as? ChatThreadState.Ready)?.messages ?: return
        val server = current.filterNot { it.pending || it.failed }
        _state.value = ChatThreadState.Ready(merge(server, local.value))
    }

    private fun dispatch(uid: Long, block: suspend () -> Boolean) {
        _sending.value = true
        viewModelScope.launch {
            val ok = runCatching { block() }.getOrDefault(false)
            _sending.value = false
            if (uid != userId) return@launch
            if (ok) {
                // 成功：移除对应的乐观气泡，稍后由服务端记录接管
                local.value = local.value.filterNot { it.pending }
                load(initial = false)
            } else {
                local.value = local.value.map { if (it.pending) it.copy(pending = false, failed = true) else it }
                refreshFromCache()
                _notice.tryEmit("发送失败，点击气泡可重试")
            }
        }
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    private companion object {
        /** 日志标签 */
        const val TAG = "ChatThread"

        /** 新消息轮询间隔（毫秒） */
        const val POLL_INTERVAL_MS = 3_000L

        /** 官方风控建议：两次发送至少间隔 1 秒 */
        const val MIN_SEND_GAP_MS = 1_000L

        /** 乐观气泡与服务端记录的匹配窗口（毫秒） */
        const val CONFIRM_WINDOW_MS = 120_000L
    }
}