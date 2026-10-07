package com.dpmusic.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.data.enabledPlatforms
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.NcmPlaylist
import com.dpmusic.app.core.model.QqPlaylist
import com.dpmusic.app.core.model.RankSummary
import com.dpmusic.app.core.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主页 ViewModel：
 * - 聚合本地数据（我喜欢 / 最近播放 / 我的歌单）实时联动；
 * - 平台热榜预览（每平台取前几个榜单，失败静默）。
 */
class HomeViewModel : ViewModel() {

    private val favorites = AppContainer.favorites
    private val history = AppContainer.history
    private val userPlaylists = AppContainer.userPlaylists
    private val repository = AppContainer.musicRepository
    private val player = AppContainer.player
    private val ncm = AppContainer.ncm
    private val ncmApi = AppContainer.ncmApi
    private val qq = AppContainer.qq
    private val qqApi = AppContainer.qqApi

    val favoriteCount = favorites.favorites
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val recent = history.recent
    val playlists = userPlaylists.playlists
    val nowPlaying = player.nowPlaying

    private val _toplists = MutableStateFlow<Map<MusicPlatform, List<RankSummary>>>(emptyMap())
    val toplists = _toplists.asStateFlow()

    private val _toplistsLoading = MutableStateFlow(false)
    val toplistsLoading = _toplistsLoading.asStateFlow()

    /** 网易云登录态（有 Cookie = 已登录） */
    val ncmLoggedIn = ncm.cookie
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _ncmPlaylists = MutableStateFlow<List<NcmPlaylist>>(emptyList())
    val ncmPlaylists = _ncmPlaylists.asStateFlow()

    private val _ncmLoading = MutableStateFlow(false)
    val ncmLoading = _ncmLoading.asStateFlow()

    private val _likedPlaylistId = MutableStateFlow<String?>(null)
    val likedPlaylistId = _likedPlaylistId.asStateFlow()
    /** QQ 音乐登录态（有 Cookie = 已登录） */
    val qqLoggedIn = qq.cookie
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _qqPlaylists = MutableStateFlow<List<QqPlaylist>>(emptyList())
    val qqPlaylists = _qqPlaylists.asStateFlow()
    private val _qqLoading = MutableStateFlow(false)
    val qqLoading = _qqLoading.asStateFlow()
    private val _qqLikedTid = MutableStateFlow<String?>(null)
    val qqLikedTid = _qqLikedTid.asStateFlow()
    private val _qqMessage = MutableStateFlow<String?>(null)
    val qqMessage = _qqMessage.asStateFlow()

    private val _ncmMessage = MutableStateFlow<String?>(null)
    val ncmMessage = _ncmMessage.asStateFlow()

    init {
        // 榜单只拉取**已启用**的平台；开关变化时自动重载（关掉的平台不再联网、也不出现在分段里）
        viewModelScope.launch {
            AppContainer.settings.settings
                .map { it.enabledPlatforms() }
                .distinctUntilChanged()
                .collect { loadToplists() }
        }
        viewModelScope.launch {
            ncm.cookie.collect { cookie ->
                if (cookie.isNotBlank()) {
                    loadNcmContent()
                } else {
                    _ncmPlaylists.value = emptyList()
                    _likedPlaylistId.value = null
                }
            }
        }
        viewModelScope.launch {
            qq.cookie.collect { cookie ->
                if (cookie.isNotBlank()) {
                    loadQqContent()
                } else {
                    _qqPlaylists.value = emptyList()
                    _qqLikedTid.value = null
                }
            }
        }
    }

    /** 拉取网易云账号内容（推荐歌单 + 我喜欢的音乐歌单 id） */
    fun loadNcmContent() {
        if (_ncmLoading.value) return
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) return
        viewModelScope.launch {
            _ncmLoading.value = true
            try {
                runCatching { ncmApi.recommendPlaylists(cookie) }
                    .onSuccess { _ncmPlaylists.value = it }
                    .onFailure { _ncmMessage.value = "网易云内容加载失败：${it.message ?: "网络异常"}" }
                val uid = ncm.profile.value?.userId
                if (uid != null && _likedPlaylistId.value == null) {
                    runCatching { ncmApi.userPlaylists(cookie, uid) }
                        .onSuccess { list -> _likedPlaylistId.value = list.firstOrNull { it.special }?.id }
                }
            } finally {
                _ncmLoading.value = false
            }
        }
    }

    /** 拉取 QQ 音乐账号内容（我的歌单 + 「我喜欢」tid） */
    fun loadQqContent() {
        if (_qqLoading.value) return
        val cookie = qq.cookie.value
        if (cookie.isBlank()) return
        viewModelScope.launch {
            _qqLoading.value = true
            try {
                runCatching { qqApi.userPlaylists(cookie) }
                    .onSuccess { list ->
                        _qqPlaylists.value = list
                        _qqLikedTid.value = list.firstOrNull { it.dirId == "201" }?.tid
                    }
                    .onFailure { _qqMessage.value = "QQ 音乐内容加载失败：${it.message ?: "网络异常"}" }
            } finally {
                _qqLoading.value = false
            }
        }
    }

    fun consumeQqMessage() {
        _qqMessage.value = null
    }

    /** 开启私人 FM（需登录） */
    fun startFm() = AppContainer.ncmFm.start()

    fun consumeNcmMessage() {
        _ncmMessage.value = null
    }

    fun loadToplists() {
        val platforms = AppContainer.settings.settings.value.enabledPlatforms()
        viewModelScope.launch {
            _toplistsLoading.value = true
            try {
                val result = mutableMapOf<MusicPlatform, List<RankSummary>>()
                platforms.forEach { platform ->
                    runCatching { repository.toplists(platform) }
                        .onSuccess { result[platform] = it.take(4) }
                }
                _toplists.value = result
            } finally {
                _toplistsLoading.value = false
            }
        }
    }

    /** 继续收听：优先恢复上一次的完整播放队列与进度；无会话时回退为最近第一首 */
    fun resumeRecent() {
        if (player.resumeSession()) return
        val first = recent.value.firstOrNull() ?: return
        player.playQueue(listOf(first.song), 0)
    }

    /**
     * 「继续收听」卡片的**统一点击入口**：
     *
     * - 若该曲**就是当前正在播放的那首** → 切换播放/暂停（与迷你播放条行为一致）；
     * - 否则 → 起播（恢复上次会话 / 从最近一首重开）。
     *
     * 之所以要分流：卡片展示的是「最近播放」，而它可能正插在播放队列里播着。
     * 此时再点「播放」若走 `resumeRecent()` 会**从头重开**，与用户预期相反
     * （他想要的是暂停）。
     */
    fun toggleOrResume(song: Song) {
        val current = player.nowPlaying.value
        if (current != null && current.song.stableKey == song.stableKey) {
            player.togglePlayPause()
        } else {
            resumeRecent()
        }
    }

    /** 播放/暂停（与迷你播放条同一动作） */
    fun togglePlay() = player.togglePlayPause()

    /** 单曲播放（热榜点击场景外部处理队列，这里用于继续收听） */
    fun playSong(song: Song) {
        player.playQueue(listOf(song), 0)
    }

    fun openPlayer() = player.openPlayerSheet()
}