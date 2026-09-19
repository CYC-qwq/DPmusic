package com.dpmusic.app.ui.screens.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.data.UserPlaylistRepository
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.repo.MusicRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 歌单详情 ViewModel（竖屏独立导航页）。
 */
class PlaylistDetailViewModel(
    private val repository: MusicRepository,
    private val player: PlayerConnection,
    private val userPlaylists: UserPlaylistRepository,
) : ViewModel() {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs = _songs.asStateFlow()

    /** 操作反馈（保存到我的歌单等） */
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val nowPlaying = player.nowPlaying

    private var loadedKey: String? = null

    fun load(platform: MusicPlatform, playlistId: String) {
        val key = "${platform.id}:$playlistId"
        if (loadedKey == key && _songs.value.isNotEmpty()) return
        loadedKey = key
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _songs.value = repository.playlistSongs(platform, playlistId)
            } catch (e: Exception) {
                _error.value = e.message ?: "加载失败"
            } finally {
                _loading.value = false
            }
        }
    }

    fun retry(platform: MusicPlatform, playlistId: String) {
        loadedKey = null
        load(platform, playlistId)
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

    /** 收藏整个歌单到我的歌单 */
    fun saveToMyPlaylists(name: String) {
        val list = _songs.value
        if (list.isEmpty()) return
        viewModelScope.launch {
            runCatching { userPlaylists.createWithSongs(name = name.ifBlank { "未命名歌单" }, songs = list) }
                .onSuccess { _message.value = "已保存到我的歌单（${list.size}首）" }
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