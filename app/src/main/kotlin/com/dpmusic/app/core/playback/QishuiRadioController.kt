package com.dpmusic.app.core.playback

import com.dpmusic.app.core.data.DislikeRepository
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.QishuiPlatformApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 汽水**场景电台**控制器（对应官方 App「一进就播」的那条流）。
 *
 * ## 为什么不能只靠 `feed/radio/tracks`
 *
 * 实测该接口有两条硬限制：
 * 1. **`count` 被服务端忽略**，每次恒定只回 6 首；
 * 2. **响应无 cursor / next_cursor / session_id**，且**该场景池子有限**
 *    （「治愈」场景连续 40 次抽样只去重出 27 首）。
 *
 * 所以单靠它做不到"一直播下去"。
 *
 * ## 正解：场景种子 + 关联推荐链
 *
 * 实测 `POST /luna/media/related {id}` 返回 6 首**关联曲**，
 * 拿"上一批最后一首"继续做种子，**每步都能拿到新歌**
 * （12 步累计 72 首，无断链）。
 *
 * 策略：
 * - **启动**：场景电台取种子批次（保证风格对）→ 播放；
 * - **续杯**：从**当前正在播的歌**取关联曲（跟着听感走），去重后追加；
 * - 关联链断 → 回退场景批次；
 * - 队列被外部替换（用户点播别的歌）→ 自动停止。
 */
class QishuiRadioController(
    private val api: QishuiPlatformApi,
    private val player: PlayerConnection,
    private val dislike: DislikeRepository,
    /** 汽水音源开关：关闭时不启动电台（入口已隐藏，这是纵深防御，避免残留调用绕过 UI） */
    private val enabledProvider: () -> Boolean = { true },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _sceneName = MutableStateFlow("")
    val sceneName: StateFlow<String> = _sceneName.asStateFlow()

    private var watchJob: Job? = null
    private var fetchJob: Job? = null
    private var radioId: String = ""
    private var firstKey: String? = null

    /** 已入队曲目 id（去重 + 内存保护） */
    private val seen = LinkedHashSet<String>()

    /** 链式延伸游标：上一次拿到的新歌里最后一首（也是"当前播放歌"的镜像） */
    private var chainCursor: String = ""

    fun start(id: String, name: String) {
        if (id.isBlank()) return
        if (!enabledProvider()) return
        stopInternal()
        radioId = id
        _sceneName.value = name
        scope.launch {
            // 开局就把队列填到 INITIAL_TARGET 首：
            //   ① 先场景抽样若干次（保证风格对）
            //   ② 再用关联链延伸（保证量够）
            // 否则用户只会看到「播放队列 · 6 首」，以为坏了。
            val songs = mutableListOf<Song>()
            repeat(SEED_TRIES) {
                if (songs.size >= SEED_TARGET) return@repeat
                songs += takeFresh(api.radioSongs(id, 30))
            }
            if (songs.isNotEmpty()) chainCursor = songs.last().id
            repeat(CHAIN_TRIES) {
                if (songs.size >= INITIAL_TARGET) return@repeat
                val fresh = takeFresh(api.relatedSongs(chainCursor))
                if (fresh.isEmpty()) return@repeat
                songs += fresh
                chainCursor = fresh.last().id
            }
            val playable = filterDisliked(songs)
            if (playable.isEmpty()) {
                player.commands.tryEmit(PlayerCommand.ShowMessage("「$name」暂无曲目"))
                return@launch
            }
            firstKey = playable.first().stableKey
            chainCursor = playable.last().id
            _active.value = true
            player.playQueue(playable, 0)
            player.openPlayerSheet()
            watchQueue()
        }
    }

    fun stop() = stopInternal()

    private fun stopInternal() {
        _active.value = false
        watchJob?.cancel(); watchJob = null
        fetchJob?.cancel(); fetchJob = null
        firstKey = null
        chainCursor = ""
        seen.clear()
    }

    private fun watchQueue() {
        watchJob?.cancel()
        watchJob = scope.launch {
            player.queue.collect { snapshot ->
                if (!_active.value) return@collect
                val first = snapshot.songs.firstOrNull()?.stableKey
                if (first == null || first != firstKey) {
                    stopInternal()
                    return@collect
                }
                // 用「当前正在播的歌」当种子，让续杯跟着听感走
                snapshot.songs.getOrNull(snapshot.currentIndex)?.id?.let { chainCursor = it }
                val remaining = snapshot.songs.size - 1 - snapshot.currentIndex
                if (remaining <= 2) fetchMore()
            }
        }
    }

    private fun fetchMore() {
        if (fetchJob?.isActive == true) return
        fetchJob = scope.launch {
            // ① 优先关联链（可延伸）
            var fresh = takeFresh(api.relatedSongs(chainCursor))
            // ② 链断则回退场景批次（风格兜底）
            if (fresh.isEmpty()) fresh = takeFresh(api.radioSongs(radioId, 30))
            if (fresh.isEmpty()) {
                player.commands.tryEmit(PlayerCommand.ShowMessage("「${_sceneName.value}」已无可推荐曲目"))
                return@launch
            }
            fresh.lastOrNull()?.let { chainCursor = it.id }
            val songs = filterDisliked(fresh)
            if (songs.isEmpty() || !_active.value) return@launch
            player.appendToQueue(songs)
        }
    }

    /** 过滤出没入过队的曲目，并记入 [seen]（含内存保护） */
    private fun takeFresh(list: List<Song>): List<Song> {
        val out = list.filter { it.id.isNotBlank() && !seen.contains(it.id) }
        out.forEach { seen += it.id }
        if (seen.size > MAX_SEEN) {
            val keep = seen.toList().takeLast(MAX_SEEN)
            seen.clear(); seen.addAll(keep)
        }
        return out
    }

    private fun filterDisliked(songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return songs
        val filtered = songs.filterNot { dislike.matches(it) }
        return filtered.ifEmpty { songs }
    }

    private companion object {
        /** 去重表上限（防止长时间播放无限增长） */
        const val MAX_SEEN = 800
        /** 开局目标队列长度 */
        const val INITIAL_TARGET = 30
        /** 场景抽样次数上限 */
        const val SEED_TRIES = 4
        /** 场景抽样够这个数就不再多抽（避免小池场景空转） */
        const val SEED_TARGET = 12
        /** 关联链延伸次数上限 */
        const val CHAIN_TRIES = 6
    }
}