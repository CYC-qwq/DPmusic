package com.dpmusic.app.core.lyric

import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.repo.MusicRepository
import com.dpmusic.app.core.util.BoundedCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 全局歌词中心（进程级单例）。
 *
 * 桌面歌词悬浮窗是独立前台服务，无法依赖播放页 ViewModel 的歌词状态，
 * 因此这里把「当前曲目歌词」抽成进程级共享数据源：
 * - 接入播放器后自动跟随当前曲目加载 / 缓存歌词（空结果不入缓存，切回可重试）；
 * - 播放页（PlayerViewModel）加载完成的歌词可经 [publish] 回填，避免重复联网；
 * - 订阅方：桌面歌词服务、未来的其他后台组件。
 *
 * 注意：这里保存的是**原始歌词**（未做繁简转换），显示层按设置自行派生。
 */
object LyricsHub {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 原始歌词缓存（key = Song.stableKey）。
     *
     * 为什么必须是**有界**的：改动前它是无上限的 `ConcurrentHashMap`，只增不减，
     * 且没有任何清理入口。实测每首逐字歌词约 20KB（YRC 逐字单元平均 261 个）
     * —— 听 1000 首就是 20MB 的纯累积，在低内存设备上会直接把进程推向 OOM。
     *
     * 并发：由 IO 协程写入、通知构建线程 / 主线程读取，必须 `synchronized = true`。
     * 上限 300 ≈ 6MB，覆盖「连续听一下午」的回头查看需求；
     * 极端内存压力时由 [MemoryPressureCenter] 调 [trimTo] 进一步收缩。
     */
    private val cache = BoundedCache<String, SongLyrics>(
        maxEntries = CACHE_MAX,
        synchronized = true,
    )

    /**
     * 常规缓存上限（首）。
     *
     * 取 300：约 6MB，覆盖「连续听一下午」的回头查看需求；
     * 内存压力时由 [trimTo] 进一步收缩，不靠这个常量独自兜住极端场景。
     */
    private const val CACHE_MAX = 300

    private val _lyrics = MutableStateFlow(SongLyrics.EMPTY)

    /** 当前曲目的歌词（无 = [SongLyrics.EMPTY]） */
    val lyrics: StateFlow<SongLyrics> = _lyrics

    private val _currentKey = MutableStateFlow("")

    /** 当前歌词对应的曲目 key（空 = 无曲目） */
    val currentKey: StateFlow<String> = _currentKey

    private var loadJob: Job? = null
    private var attached = false

    /** 当前缓存条数（供诊断） */
    val cachedCount: Int get() = cache.size

    /** 指定曲目是否在缓存中（供诊断 / 测试） */
    fun isCached(stableKey: String): Boolean = cache.containsKey(stableKey)

    /**
     * 接入播放器（幂等；应用启动后调用一次即可）。
     * 之后歌词会随切歌自动加载，无需播放页参与。
     */
    fun attach(player: PlayerConnection, repository: MusicRepository) {
        if (attached) return
        attached = true
        scope.launch {
            player.nowPlaying
                .map { it?.song }
                .distinctUntilChanged()
                .collect { song ->
                    if (song == null) clear() else load(song, repository)
                }
        }
    }

    /** 播放页已加载完成的歌词回填（命中即复用，不再重复请求） */
    fun publish(song: Song, lyrics: SongLyrics) {
        if (lyrics.isEmpty) return
        cache.put(song.stableKey, lyrics)
        if (_currentKey.value == song.stableKey) _lyrics.value = lyrics
    }

    /**
     * 收缩缓存到 [keep] 条（内存压力 / 设置页手动清理时调用）。
     *
     * 当前曲永远保留 —— 否则正在显示的歌词会被清掉，切回播放页要重新联网拉一次。
     */
    fun trimTo(keep: Int) {
        cache.trimTo(keep, protect = _currentKey.value.ifBlank { null })
    }

    /** 清空全部缓存（设置页「清理缓存」入口） */
    fun clearCache() = cache.clear()

    /**
     * 仅测试用：设置「当前曲」key。
     *
     * 生产路径只由 [load] 写入（跟随播放器切歌）。测试需要验证
     * 「[trimTo] 不会把正在看的那首逐出」，而没有真实播放器可驱动，
     * 故开这个钩子 —— 与其写一个验证不了核心逻辑的测试，不如把可控点暴露出来。
     */
    internal fun setCurrentKeyForTest(key: String) {
        _currentKey.value = key
    }

    private fun clear() {
        loadJob?.cancel()
        _currentKey.value = ""
        _lyrics.value = SongLyrics.EMPTY
    }

    private fun load(song: Song, repository: MusicRepository) {
        loadJob?.cancel()
        _currentKey.value = song.stableKey
        cache.get(song.stableKey)?.let { cached ->
            _lyrics.value = cached
            return
        }
        _lyrics.value = SongLyrics.EMPTY
        loadJob = scope.launch {
            val lyrics = try {
                fetch(repository, song)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (lyrics.isEmpty) return@launch
            cache.put(song.stableKey, lyrics)
            // 慢任务返回时若已切歌：只写缓存，不覆盖当前歌词
            if (_currentKey.value == song.stableKey) _lyrics.value = lyrics
        }
    }

    /** 拉取歌词（空结果自动重试一次，与播放页策略保持一致） */
    private suspend fun fetch(repository: MusicRepository, song: Song): SongLyrics {
        var lyrics = repository.lyrics(song)
        if (lyrics.isEmpty) {
            delay(RETRY_DELAY_MS)
            lyrics = repository.lyrics(song)
        }
        return lyrics
    }

    /** 空结果重试等待（ms） */
    private const val RETRY_DELAY_MS = 900L
}
