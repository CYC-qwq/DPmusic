package com.dpmusic.app.ui.screens.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.AlbumDetail
import com.dpmusic.app.core.model.ArtistDetail
import com.dpmusic.app.core.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 歌手页 Tab：歌曲 / 专辑 */
enum class ArtistTab { Songs, Albums }

/**
 * 歌手详情 ViewModel（网易云源）：
 * - 头部信息 + 歌曲（热门 / 时间排序，分页）+ 专辑（分页）；
 * - 切换歌手时重置全部状态。
 */
class ArtistDetailViewModel : ViewModel() {

    private val ncm = AppContainer.ncm
    private val ncmApi = AppContainer.ncmApi
    private val player = AppContainer.player

    private var artistId: String = ""
    private var inited = false

    private val _artist = MutableStateFlow<ArtistDetail?>(null)
    val artist = _artist.asStateFlow()

    private val _tab = MutableStateFlow(ArtistTab.Songs)
    val tab = _tab.asStateFlow()

    private val _sort = MutableStateFlow("hot")
    val sort = _sort.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs = _songs.asStateFlow()

    private val _songsLoading = MutableStateFlow(false)
    val songsLoading = _songsLoading.asStateFlow()

    private val _songsHasMore = MutableStateFlow(false)
    val songsHasMore = _songsHasMore.asStateFlow()

    private val _albums = MutableStateFlow<List<AlbumDetail>>(emptyList())
    val albums = _albums.asStateFlow()

    private val _albumsLoading = MutableStateFlow(false)
    val albumsLoading = _albumsLoading.asStateFlow()

    private val _albumsHasMore = MutableStateFlow(false)
    val albumsHasMore = _albumsHasMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val nowPlaying = player.nowPlaying

    private var songsPage = 1
    private var albumsPage = 1

    /** 页面进入时初始化（幂等；重复进入同一歌手不重复加载） */
    fun init(id: String) {
        if (inited && artistId == id) return
        inited = true
        artistId = id
        // 切换歌手：清空旧状态
        _artist.value = null
        _songs.value = emptyList()
        _albums.value = emptyList()
        _error.value = null
        _tab.value = ArtistTab.Songs
        _sort.value = "hot"
        loadDetail()
        loadSongs(reset = true)
    }

    fun retry() {
        loadDetail()
        if (_tab.value == ArtistTab.Songs) loadSongs(reset = true) else loadAlbums(reset = true)
    }

    private fun loadDetail() {
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) {
            _error.value = "请先在设置中登录网易云账号"
            return
        }
        viewModelScope.launch {
            runCatching { ncmApi.artistDetail(cookie, artistId) }
                .onSuccess { _artist.value = it }
                .onFailure { if (_artist.value == null) _error.value = "获取歌手信息失败：${it.message ?: "网络异常"}" }
        }
    }

    private fun loadSongs(reset: Boolean) {
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) return
        if (_songsLoading.value) return
        if (!reset && !_songsHasMore.value) return
        viewModelScope.launch {
            _songsLoading.value = true
            if (reset) {
                _error.value = null
                songsPage = 1
            }
            val page = songsPage
            runCatching { ncmApi.artistSongs(cookie, artistId, _sort.value, PAGE_SIZE, (page - 1) * PAGE_SIZE) }
                .onSuccess { (list, hasMore) ->
                    _songs.value = if (reset) list else _songs.value + list
                    _songsHasMore.value = hasMore
                    songsPage = page + 1
                }
                .onFailure { if (reset && _songs.value.isEmpty()) _error.value = "获取歌手歌曲失败：${it.message ?: "网络异常"}" }
            _songsLoading.value = false
        }
    }

    fun loadMoreSongs() = loadSongs(reset = false)

    fun switchTab(target: ArtistTab) {
        if (_tab.value == target) return
        _tab.value = target
        if (target == ArtistTab.Albums && _albums.value.isEmpty()) loadAlbums(reset = true)
    }

    fun switchSort(target: String) {
        if (_sort.value == target) return
        _sort.value = target
        _songs.value = emptyList()
        loadSongs(reset = true)
    }

    private fun loadAlbums(reset: Boolean) {
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) return
        if (_albumsLoading.value) return
        if (!reset && !_albumsHasMore.value) return
        viewModelScope.launch {
            _albumsLoading.value = true
            if (reset) albumsPage = 1
            val page = albumsPage
            runCatching { ncmApi.artistAlbums(cookie, artistId, PAGE_SIZE, (page - 1) * PAGE_SIZE) }
                .onSuccess { (list, hasMore) ->
                    _albums.value = if (reset) list else _albums.value + list
                    _albumsHasMore.value = hasMore
                    albumsPage = page + 1
                }
                .onFailure { if (reset && _albums.value.isEmpty()) _error.value = "获取歌手专辑失败：${it.message ?: "网络异常"}" }
            _albumsLoading.value = false
        }
    }

    fun loadMoreAlbums() = loadAlbums(reset = false)

    fun playSong(index: Int) {
        val list = _songs.value
        if (index in list.indices) player.playQueue(list, index)
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
