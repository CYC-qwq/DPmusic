package com.dpmusic.app.ui.player

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.FavoritesRepository
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.repo.MusicRepository
import com.dpmusic.app.core.util.BoundedCache
import com.dpmusic.app.core.util.ChineseS2T
import com.dpmusic.app.core.util.CoverPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 歌词加载状态机 */
sealed interface PlayerLyricsState {
    data object Loading : PlayerLyricsState
    data object Empty : PlayerLyricsState
    data class Content(val lyrics: SongLyrics) : PlayerLyricsState
    data class Error(val message: String) : PlayerLyricsState
}

/**
 * 播放页 ViewModel：
 * - 监听当前曲目 → 加载歌词（内存缓存）与封面主色（Palette 流光）；
 * - 暴露收藏态供心形按钮使用。
 */
class PlayerViewModel(
    private val repository: MusicRepository,
    private val player: PlayerConnection,
    favorites: FavoritesRepository,
    private val settings: SettingsRepository,
    private val context: Context,
) : ViewModel() {

    val nowPlaying = player.nowPlaying
    val queue = player.queue

    val favoriteKeys: StateFlow<Set<String>> = favorites.favorites
        .map { list -> list.map { it.stableKey }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _lyrics = MutableStateFlow<PlayerLyricsState>(PlayerLyricsState.Loading)
    val lyrics: StateFlow<PlayerLyricsState> = _lyrics

    private val _palette = MutableStateFlow<List<Color>>(emptyList())
    val palette: StateFlow<List<Color>> = _palette

    /**
     * 封面种子色（MCU）：供播放页「封面动态取色」生成整套 MD3 配色。
     *
     * [Color.Unspecified] 表示「不启用 / 提取失败 / 封面近无彩色」，此时播放页沿用全局主题色。
     * 仅在设置开关打开时才做提取（默认关闭，避免为不使用该功能的用户白跑量化）。
     */
    private val _coverSeed = MutableStateFlow(Color.Unspecified)
    val coverSeed: StateFlow<Color> = _coverSeed

    /**
     * 播放页歌词缓存（key = Song.stableKey）。
     *
     * 有界 LRU：改动前是**无上限**的 `mutableMapOf`，只在「重试当前曲」时删一条，
     * 从不批量清理 —— 播放页开着一路听下去，它会独立累积（与 `LyricsHub.cache`
     * 是两份副本，同一首歌在内存里存两遍）。
     *
     * 上限取 60：播放页只需覆盖「本次前后翻几首」的快速回看，
     * 再多也不会被看到；比 `LyricsHub` 的 300 更小，因为它是纯 UI 用途，
     * 缓存未命中时重新拉取的代价可以接受。
     *
     * 仅在 ViewModel 主线程访问（loadLyrics / retryLyrics / settings collect 都在
     * viewModelScope 内），故用普通 LinkedHashMap 即可。
     */
    private val lyricsCache = BoundedCache<String, SongLyrics>(maxEntries = LYRICS_CACHE_MAX)
    private var lyricsJob: Job? = null
    private var paletteJob: Job? = null
    private var coverSeedJob: Job? = null

    init {
        viewModelScope.launch {
            player.nowPlaying
                .map { it?.song }
                .distinctUntilChanged()
                .collect { song ->
                    if (song == null) {
                        _lyrics.value = PlayerLyricsState.Empty
                        _palette.value = emptyList()
                        coverSeedJob?.cancel()
                        _coverSeed.value = Color.Unspecified
                    } else {
                        loadLyrics(song)
                        loadPalette(song)
                        loadCoverSeed(song)
                    }
                }
        }
        // 「封面动态取色」开关变化：开启即提取当前封面种子色，关闭立即回退全局主题
        viewModelScope.launch {
            settings.settings
                .map { it.coverDynamicColor }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (!enabled) {
                        coverSeedJob?.cancel()
                        _coverSeed.value = Color.Unspecified
                    } else {
                        player.nowPlaying.value?.song?.let(::loadCoverSeed)
                    }
                }
        }
        // 繁体歌词开关变化：基于缓存的原始歌词重新派生（无需重新联网拉取）
        viewModelScope.launch {
            settings.settings
                .map { it.lyricS2T }
                .distinctUntilChanged()
                .collect {
                    val song = player.nowPlaying.value?.song ?: return@collect
                    val raw = lyricsCache.get(song.stableKey) ?: return@collect
                    _lyrics.value = PlayerLyricsState.Content(applyS2T(raw))
                }
        }
    }

    fun retryLyrics() {
        val song = player.nowPlaying.value?.song ?: return
        lyricsCache.remove(song.stableKey)
        loadLyrics(song)
    }

    private fun loadLyrics(song: Song) {
        lyricsJob?.cancel()
        // 空结果不入缓存：每次切回都重新尝试拉取
        lyricsCache.get(song.stableKey)?.takeIf { !it.isEmpty }?.let { cached ->
            _lyrics.value = PlayerLyricsState.Content(applyS2T(cached))
            return
        }
        _lyrics.value = PlayerLyricsState.Loading
        lyricsJob = viewModelScope.launch {
            val lyrics = try {
                fetchLyrics(song)
            } catch (e: CancellationException) {
                // 任务已被「切歌」取消：直接结束。绝不能把取消当成加载失败写状态，
                // 否则旧任务的失败结果会覆盖新歌刚加载出的歌词，造成歌词与歌曲错位。
                throw e
            } catch (e: Exception) {
                // 失败也仅当仍是当前曲目时才写错误态，避免污染新歌的歌词状态
                if (player.nowPlaying.value?.song?.stableKey == song.stableKey) {
                    _lyrics.value = PlayerLyricsState.Error(e.message ?: "歌词加载失败")
                }
                return@launch
            }
            if (!lyrics.isEmpty) lyricsCache.put(song.stableKey, lyrics)
            // 慢任务返回时若已切到别的歌：只写缓存，不写界面状态
            if (player.nowPlaying.value?.song?.stableKey != song.stableKey) return@launch
            _lyrics.value = if (lyrics.isEmpty) PlayerLyricsState.Empty
            else PlayerLyricsState.Content(applyS2T(lyrics))
        }
    }

    /**
     * 拉取歌词（自动重试一次）：平台接口偶发返回空结果（网络抖动 / 限速），
     * 首次为空时短暂等待后自动再拉一次；仍为空则交给 UI 显示「暂无歌词 + 重试按钮」。
     */
    private suspend fun fetchLyrics(song: Song): SongLyrics {
        var lyrics = repository.lyrics(song)
        if (lyrics.isEmpty) {
            delay(LYRICS_AUTO_RETRY_DELAY_MS)
            lyrics = repository.lyrics(song)
        }
        return lyrics
    }

    /** 按「繁体歌词」设置转换（关闭时原样返回；缓存中始终保存原始歌词） */
    private fun applyS2T(lyrics: SongLyrics): SongLyrics {
        if (!settings.settings.value.lyricS2T) return lyrics
        return lyrics.copy(
            lines = lyrics.lines.map { line ->
                line.copy(
                    text = ChineseS2T.convert(line.text),
                    translation = line.translation?.let(ChineseS2T::convert),
                    words = line.words.map { word -> word.copy(text = ChineseS2T.convert(word.text)) },
                )
            },
        )
    }

    private fun loadPalette(song: Song) {
        paletteJob?.cancel()
        paletteJob = viewModelScope.launch {
            val colors = CoverPalette.colors(context, song.coverUrl)
            // 慢任务返回时若已切歌：丢弃旧歌颜色，避免流光背景与当前曲目错位
            if (player.nowPlaying.value?.song?.stableKey == song.stableKey) {
                _palette.value = colors
            }
        }
    }

    /**
     * 提取封面种子色（MCU 量化 + 打分）。
     *
     * 开关关闭时直接清空 —— 切歌路径也会走到这里，若不在入口判断，「关闭状态下听歌」
     * 会白白为每首歌跑一次量化。
     */
    private fun loadCoverSeed(song: Song) {
        coverSeedJob?.cancel()
        if (!settings.settings.value.coverDynamicColor) {
            _coverSeed.value = Color.Unspecified
            return
        }
        coverSeedJob = viewModelScope.launch {
            val seed = CoverPalette.seed(context, song.coverUrl)
            // 与 palette 同款防错位：慢任务返回时若已切歌，丢弃旧歌种子色
            if (player.nowPlaying.value?.song?.stableKey == song.stableKey) {
                _coverSeed.value = seed ?: Color.Unspecified
            }
        }
    }

    private companion object {
        /** 歌词为空时自动重试的等待时长（ms） */
        const val LYRICS_AUTO_RETRY_DELAY_MS = 900L

        /**
         * 播放页歌词缓存上限（首）。见 `lyricsCache` 注释。
         *
         * 取 60 而非与 `LyricsHub` 相同的 300：这里是纯 UI 侧缓存，
         * 未命中时重新拉取的代价可接受，没必要为「翻 300 首前看过的词」常驻内存。
         */
        const val LYRICS_CACHE_MAX = 60
    }
}