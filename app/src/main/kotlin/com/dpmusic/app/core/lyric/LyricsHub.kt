package com.dpmusic.app.core.lyric

import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.repo.MusicRepository
import java.util.concurrent.ConcurrentHashMap
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

    /** 原始歌词缓存（key = Song.stableKey） */
    private val cache = ConcurrentHashMap<String, SongLyrics>()

    private val _lyrics = MutableStateFlow(SongLyrics.EMPTY)

    /** 当前曲目的歌词（无 = [SongLyrics.EMPTY]） */
    val lyrics: StateFlow<SongLyrics> = _lyrics

    private val _currentKey = MutableStateFlow("")

    /** 当前歌词对应的曲目 key（空 = 无曲目） */
    val currentKey: StateFlow<String> = _currentKey

    private var loadJob: Job? = null
    private var attached = false

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
        cache[song.stableKey] = lyrics
        if (_currentKey.value == song.stableKey) _lyrics.value = lyrics
    }

    private fun clear() {
        loadJob?.cancel()
        _currentKey.value = ""
        _lyrics.value = SongLyrics.EMPTY
    }

    private fun load(song: Song, repository: MusicRepository) {
        loadJob?.cancel()
        _currentKey.value = song.stableKey
        cache[song.stableKey]?.let { cached ->
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
            cache[song.stableKey] = lyrics
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
