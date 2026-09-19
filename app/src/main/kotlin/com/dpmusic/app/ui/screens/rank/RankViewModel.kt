package com.dpmusic.app.ui.screens.rank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.data.UserPlaylistRepository
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.RankSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.repo.MusicRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 排行榜 ViewModel（横屏 Master-Detail）：
 * - 左栏：榜单列表（切换平台自动重载，默认选中第一个）；
 * - 右栏：选中榜单的歌曲详情，与左栏联动。
 */
class RankViewModel(
    private val repository: MusicRepository,
    settings: SettingsRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    private val _platform = MutableStateFlow(settings.settings.value.defaultPlatform)
    val platform = _platform.asStateFlow()

    private val _ranks = MutableStateFlow<List<RankSummary>>(emptyList())
    val ranks = _ranks.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _selectedRank = MutableStateFlow<RankSummary?>(null)
    val selectedRank = _selectedRank.asStateFlow()

    private val _detailSongs = MutableStateFlow<List<Song>>(emptyList())
    val detailSongs = _detailSongs.asStateFlow()

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading = _detailLoading.asStateFlow()

    private val _detailError = MutableStateFlow<String?>(null)
    val detailError = _detailError.asStateFlow()

    val nowPlaying = player.nowPlaying

    private var detailJob: Job? = null

    init {
        loadRanks()
    }

    fun loadRanks() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val list = repository.toplists(_platform.value)
                _ranks.value = list
                if (list.isNotEmpty()) selectRank(list.first())
            } catch (e: Exception) {
                _error.value = e.message ?: "榜单加载失败，请检查网络"
            } finally {
                _loading.value = false
            }
        }
    }

    fun onPlatformChange(target: MusicPlatform) {
        if (_platform.value == target) return
        _platform.value = target
        loadRanks()
    }

    fun selectRank(rank: RankSummary) {
        _selectedRank.value = rank
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _detailLoading.value = true
            _detailError.value = null
            _detailSongs.value = emptyList()
            try {
                _detailSongs.value = repository.rankSongs(_platform.value, rank.id)
            } catch (e: Exception) {
                _detailError.value = e.message ?: "榜单详情加载失败"
            } finally {
                _detailLoading.value = false
            }
        }
    }

    fun retryDetail() {
        _selectedRank.value?.let { selectRank(it) }
    }

    fun playDetailAt(index: Int) {
        val list = _detailSongs.value
        if (index in list.indices) player.playQueue(list, index)
    }

    /** 按 stableKey 定位播放（过滤列表用） */
    fun playSong(song: Song) {
        val list = _detailSongs.value
        val index = list.indexOfFirst { it.stableKey == song.stableKey }
        if (index >= 0) player.playQueue(list, index)
    }

    fun playAll() {
        val list = _detailSongs.value
        if (list.isNotEmpty()) player.playQueue(list, 0)
    }

    fun togglePlay() = player.togglePlayPause()

    fun next() = player.next()

    fun previous() = player.previous()

    fun openPlayer() = player.openPlayerSheet()
}

/**
 * 榜单详情 ViewModel（竖屏独立导航页）。
 */
class RankDetailViewModel(
    private val repository: MusicRepository,
    private val player: PlayerConnection,
    private val userPlaylists: UserPlaylistRepository,
) : ViewModel() {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs = _songs.asStateFlow()

    /** 操作反馈（保存歌单等） */
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val nowPlaying = player.nowPlaying

    private var loadedKey: String? = null

    fun load(platform: MusicPlatform, rankId: String) {
        val key = "${platform.id}:$rankId"
        if (loadedKey == key && _songs.value.isNotEmpty()) return
        loadedKey = key
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _songs.value = repository.rankSongs(platform, rankId)
            } catch (e: Exception) {
                _error.value = e.message ?: "加载失败"
            } finally {
                _loading.value = false
            }
        }
    }

    fun retry(platform: MusicPlatform, rankId: String) {
        loadedKey = null
        load(platform, rankId)
    }

    fun playAt(index: Int) {
        val list = _songs.value
        if (index in list.indices) player.playQueue(list, index)
    }

    fun playAll() {
        val list = _songs.value
        if (list.isNotEmpty()) player.playQueue(list, 0)
    }

    /** 按 stableKey 定位播放（过滤列表用） */
    fun playSong(song: Song) {
        val list = _songs.value
        val index = list.indexOfFirst { it.stableKey == song.stableKey }
        if (index >= 0) player.playQueue(list, index)
    }

    /** 整个榜单保存为我的歌单 */
    fun saveAsPlaylist(name: String) {
        val list = _songs.value
        if (list.isEmpty()) return
        viewModelScope.launch {
            runCatching { userPlaylists.createWithSongs(name = name.ifBlank { "榜单歌单" }, songs = list) }
                .onSuccess { _message.value = "已保存「$name」到我的歌单（${list.size}首）" }
                .onFailure { _message.value = "保存失败：${it.message ?: "未知错误"}" }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun togglePlay() = player.togglePlayPause()

    fun next() = player.next()

    fun previous() = player.previous()

    fun openPlayer() = player.openPlayerSheet()
}