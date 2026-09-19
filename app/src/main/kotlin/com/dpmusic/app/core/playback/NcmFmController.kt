package com.dpmusic.app.core.playback

import com.dpmusic.app.core.data.DislikeRepository
import com.dpmusic.app.core.data.NcmRepository
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.NcmApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 私人 FM 控制器（需登录 Cookie）：
 * - 开启：拉取一批 FM 歌曲 → 替换播放队列 → 打开播放页；
 * - 续杯：监听播放队列，接近队尾时自动追加下一批（队列被外部替换则自动停止）；
 * - 歌曲为网易云平台，播放地址走 LX 音源解析（与搜索 / 榜单一致）。
 */
class NcmFmController(
    private val api: NcmApi,
    private val ncm: NcmRepository,
    private val player: PlayerConnection,
    private val dislike: DislikeRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var watchJob: Job? = null
    private var fetchJob: Job? = null
    private var firstKey: String? = null

    /** 开启私人 FM（重新拉取一批并播放） */
    fun start() {
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) {
            player.commands.tryEmit(PlayerCommand.ShowMessage("请先在设置中登录网易云账号"))
            return
        }
        stopInternal()
        scope.launch {
            val songs = filterDisliked(runCatching { api.fmSongs(cookie) }.getOrNull().orEmpty())
            if (songs.isEmpty()) {
                player.commands.tryEmit(PlayerCommand.ShowMessage("私人 FM 加载失败，请稍后重试"))
                return@launch
            }
            firstKey = songs.first().stableKey
            _active.value = true
            player.playQueue(songs, 0)
            player.openPlayerSheet()
            watchQueue()
        }
    }

    /** 停止 FM 续杯（不影响当前正在播放的歌曲） */
    fun stop() = stopInternal()

    /** 过滤「不喜欢」的歌曲；整批全被屏蔽时保留原始批次（避免 FM 无歌可播） */
    private fun filterDisliked(songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return songs
        val filtered = songs.filterNot { dislike.matches(it) }
        return filtered.ifEmpty { songs }
    }

    private fun stopInternal() {
        _active.value = false
        watchJob?.cancel()
        watchJob = null
        fetchJob?.cancel()
        fetchJob = null
        firstKey = null
    }

    private fun watchQueue() {
        watchJob?.cancel()
        watchJob = scope.launch {
            player.queue.collect { snapshot ->
                if (!_active.value) return@collect
                val first = snapshot.songs.firstOrNull()?.stableKey
                if (first == null || first != firstKey) {
                    // 队列已被外部替换（用户点播了别的歌）：自动退出 FM
                    stopInternal()
                    return@collect
                }
                val remaining = snapshot.songs.size - 1 - snapshot.currentIndex
                if (remaining <= 1) fetchMore()
            }
        }
    }

    private fun fetchMore() {
        if (fetchJob?.isActive == true) return
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) return
        fetchJob = scope.launch {
            val songs = filterDisliked(runCatching { api.fmSongs(cookie) }.getOrNull().orEmpty())
            if (songs.isEmpty() || !_active.value) return@launch
            player.appendToQueue(songs)
        }
    }
}
