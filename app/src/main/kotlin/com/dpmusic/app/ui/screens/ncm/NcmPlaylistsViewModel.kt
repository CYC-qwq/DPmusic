package com.dpmusic.app.ui.screens.ncm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.NcmPlaylist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 我的歌单（网易云账号创建 + 收藏，需登录 Cookie） */
class NcmPlaylistsViewModel : ViewModel() {

    private val ncm = AppContainer.ncm
    private val ncmApi = AppContainer.ncmApi

    private val _playlists = MutableStateFlow<List<NcmPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (_loading.value) return
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) {
            _error.value = "请先在设置中登录网易云账号"
            return
        }
        val uid = ncm.profile.value?.userId
        if (uid == null) {
            _error.value = "账号资料缺失，请在设置中重新登录"
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { ncmApi.userPlaylists(cookie, uid) }
                .onSuccess { _playlists.value = it }
                .onFailure { _error.value = "加载失败：${it.message ?: "网络异常"}" }
            _loading.value = false
        }
    }
}
