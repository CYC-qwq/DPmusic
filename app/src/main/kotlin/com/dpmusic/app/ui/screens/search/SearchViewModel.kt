package com.dpmusic.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.SearchHistoryRepository
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.data.UserPlaylistRepository
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.ParsedSongLink
import com.dpmusic.app.core.net.SongLinkParser
import com.dpmusic.app.core.playback.PlayerConnection
import com.dpmusic.app.core.repo.MusicRepository
import com.dpmusic.app.ui.components.SearchMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 搜索页 ViewModel（歌曲 / 歌单双模式）：
 * - 300ms 输入防抖（取消前序任务）；
 * - 模式 / 平台切换即时重搜；
 * - 支持歌曲官方链接解析（自动识别平台 → 拉取单曲详情）；
 * - 分页加载更多（30/页）。
 */
class SearchViewModel(
    private val repository: MusicRepository,
    settings: SettingsRepository,
    private val player: PlayerConnection,
    private val userPlaylists: UserPlaylistRepository,
    private val searchHistory: SearchHistoryRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _platform = MutableStateFlow(settings.settings.value.defaultPlatform)
    val platform = _platform.asStateFlow()

    private val _mode = MutableStateFlow(SearchMode.Songs)
    val mode = _mode.asStateFlow()

    private val _results = MutableStateFlow<List<Song>>(emptyList())
    val results = _results.asStateFlow()

    /** 搜索联想（输入预测） */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions = _suggestions.asStateFlow()

    /** 搜索历史（最近关键词，去重置顶） */
    val history = searchHistory.history

    /** 热搜词（搜索页空态展示；进入页面时加载一次，切换平台时刷新） */
    private val _hotSearch = MutableStateFlow<List<String>>(emptyList())
    val hotSearch = _hotSearch.asStateFlow()

    init {
        refreshHotSearch()
    }

    private val _playlists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore = _loadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore = _hasMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** 链接识别：当前查询被识别为歌曲链接时记录其平台（null = 普通关键词搜索） */
    private val _linkPlatform = MutableStateFlow<MusicPlatform?>(null)
    val linkPlatform = _linkPlatform.asStateFlow()

    /** 操作反馈（保存歌单等） */
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** 正在保存的歌单（platform:id），用于按钮加载态 */
    private val _savingKey = MutableStateFlow<String?>(null)
    val savingKey = _savingKey.asStateFlow()

    val nowPlaying = player.nowPlaying

    private var page = 1
    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    fun onQueryChange(value: String) {
        _query.value = value
        searchJob?.cancel()
        suggestJob?.cancel()
        if (value.isBlank()) {
            clearResults()
            _suggestions.value = emptyList()
            return
        }
        // 粘贴 / 输入歌曲官方链接：自动切到歌曲模式，保证链接解析结果可见
        if (_mode.value != SearchMode.Songs && SongLinkParser.parse(value) != null) {
            _mode.value = SearchMode.Songs
        }
        fetchSuggestions(value)
        searchJob = viewModelScope.launch {
            delay(300) // 防抖
            search(reset = true)
        }
    }

    /** 点击联想词：填入搜索框并立即搜索（不再触发联想） */
    fun applySuggestion(text: String) {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        _query.value = text
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            recordKeywordIfNeeded(text)
            search(reset = true)
        }
    }

    /** 键盘「搜索」：立即执行搜索并收起联想 */
    fun executeSearch() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            recordKeywordIfNeeded(_query.value)
            search(reset = true)
        }
    }

    /** 收起联想（结果列表滚动等场景） */
    fun dismissSuggestions() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
    }

    /** 点击历史词：填入搜索框、置顶历史并立即搜索 */
    fun applyHistory(text: String) {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        _query.value = text
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searchHistory.record(text)
            search(reset = true)
        }
    }

    /** 点击热搜词：填入搜索框并立即搜索（与历史词同路径） */
    fun applyHotSearch(text: String) {
        applyHistory(text)
    }

    /** 刷新热搜榜（进入页面 / 切换平台时调用；失败静默为空列表） */
    private fun refreshHotSearch() {
        viewModelScope.launch {
            _hotSearch.value = repository.hotSearch(_platform.value)
        }
    }

    /** 删除单条历史 */
    fun removeHistory(text: String) {
        viewModelScope.launch { searchHistory.remove(text) }
    }

    /** 清空全部历史 */
    fun clearHistory() {
        viewModelScope.launch { searchHistory.clear() }
    }

    /** 记录搜索词：空词与歌曲链接不入历史 */
    private suspend fun recordKeywordIfNeeded(value: String) {
        val keyword = value.trim()
        if (keyword.isEmpty() || SongLinkParser.parse(keyword) != null) return
        searchHistory.record(keyword)
    }

    /** 拉取搜索联想（防抖 220ms；空关键词与链接输入不联想） */
    private fun fetchSuggestions(value: String) {
        suggestJob?.cancel()
        val q = value.trim()
        if (q.isEmpty() || SongLinkParser.parse(q) != null) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(220)
            val list = repository.searchSuggest(_platform.value, q)
            // 仅当查询未变时写入，避免过期结果覆盖
            if (_query.value.trim() == q) _suggestions.value = list
        }
    }

    fun onPlatformChange(target: MusicPlatform) {
        if (_platform.value == target) return
        _platform.value = target
        refreshHotSearch()
        if (_query.value.isBlank()) return
        fetchSuggestions(_query.value)
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(reset = true) }
    }

    fun onModeChange(target: SearchMode) {
        if (_mode.value == target) return
        _mode.value = target
        if (_query.value.isBlank()) {
            // 切换模式时清空旧结果，避免残留
            _results.value = emptyList()
            _playlists.value = emptyList()
            _error.value = null
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(reset = true) }
    }

    fun retry() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(reset = true) }
    }

    fun loadMore() {
        if (_loading.value || _loadingMore.value || !_hasMore.value) return
        if (_query.value.isBlank()) return
        if (_linkPlatform.value != null) return
        viewModelScope.launch { loadMoreInternal() }
    }

    fun playAt(index: Int) {
        val list = _results.value
        if (index in list.indices) {
            player.playQueue(list, index)
            // 播放搜索结果视为「使用过」该搜索：记入历史（链接除外）
            viewModelScope.launch { recordKeywordIfNeeded(_query.value) }
        }
    }

    fun playSongNext(song: Song) = player.playSongNext(song)
    fun togglePlay() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun openPlayer() = player.openPlayerSheet()

    /** 把搜索到的歌单保存到我的歌单（拉取全量歌曲） */
    fun savePlaylist(summary: PlaylistSummary) {
        val key = "${summary.platform.id}:${summary.id}"
        if (_savingKey.value != null) return
        _savingKey.value = key
        viewModelScope.launch {
            try {
                val songs = repository.playlistSongs(summary.platform, summary.id)
                if (songs.isEmpty()) {
                    _message.value = "「${summary.name}」暂无歌曲"
                } else {
                    userPlaylists.createWithSongs(name = summary.name, songs = songs)
                    _message.value = "已保存「${summary.name}」到我的歌单（${songs.size}首）"
                }
            } catch (e: Exception) {
                _message.value = "保存失败：${e.message ?: "未知错误"}"
            } finally {
                _savingKey.value = null
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun clearResults() {
        _results.value = emptyList()
        _playlists.value = emptyList()
        _error.value = null
        _linkPlatform.value = null
        _loading.value = false
        _hasMore.value = true
        page = 1
    }

    /** 链接解析：按平台 + 歌曲 ID 拉取详情，并作为唯一结果展示 */
    private suspend fun resolveLink(link: ParsedSongLink) {
        _linkPlatform.value = link.platform
        _loading.value = true
        _error.value = null
        _results.value = emptyList()
        _playlists.value = emptyList()
        _hasMore.value = false
        page = 1
        try {
            val song = repository.songDetail(link.platform, link.songId)
            if (song != null) {
                _results.value = listOf(song)
            } else {
                _error.value = "未找到链接对应的歌曲，请检查链接是否完整"
            }
        } catch (e: Exception) {
            _error.value = "链接解析失败：${e.message ?: "请检查网络"}"
        } finally {
            _loading.value = false
        }
    }

    private suspend fun search(reset: Boolean) {
        val keyword = _query.value.trim()
        if (keyword.isBlank()) return
        // 歌曲链接：走「链接解析」通道（仅歌曲模式；歌单模式下按普通关键词处理）
        val link = SongLinkParser.parse(keyword)
        if (link != null && _mode.value == SearchMode.Songs) {
            resolveLink(link)
            return
        }
        _linkPlatform.value = null
        if (reset) {
            page = 1
            _loading.value = true
        }
        _error.value = null
        try {
            when (_mode.value) {
                SearchMode.Songs -> {
                    val list = repository.searchSongs(_platform.value, keyword, page, PAGE_SIZE)
                    _results.value = if (reset) list else _results.value + list
                    _hasMore.value = list.size >= PAGE_SIZE
                    if (list.isNotEmpty()) page++
                }

                SearchMode.Playlists -> {
                    val list = repository.searchPlaylists(_platform.value, keyword, page, PAGE_SIZE)
                    _playlists.value = if (reset) list else _playlists.value + list
                    _hasMore.value = list.size >= PAGE_SIZE
                    if (list.isNotEmpty()) page++
                }
            }
        } catch (e: Exception) {
            if (reset) {
                _results.value = emptyList()
                _playlists.value = emptyList()
            }
            _error.value = e.message ?: "搜索失败，请检查网络"
        } finally {
            _loading.value = false
        }
    }

    private suspend fun loadMoreInternal() {
        _loadingMore.value = true
        try {
            when (_mode.value) {
                SearchMode.Songs -> {
                    val list = repository.searchSongs(_platform.value, _query.value.trim(), page, PAGE_SIZE)
                    _results.value = _results.value + list
                    _hasMore.value = list.size >= PAGE_SIZE
                    if (list.isNotEmpty()) page++
                }

                SearchMode.Playlists -> {
                    val list = repository.searchPlaylists(_platform.value, _query.value.trim(), page, PAGE_SIZE)
                    _playlists.value = _playlists.value + list
                    _hasMore.value = list.size >= PAGE_SIZE
                    if (list.isNotEmpty()) page++
                }
            }
        } catch (_: Exception) {
            _hasMore.value = false
        } finally {
            _loadingMore.value = false
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}