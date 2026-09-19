package com.dpmusic.app.ui.screens.qq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.QqPlaylist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 我的歌单（QQ 音乐账号创建，需登录 Cookie） */
class QqPlaylistsViewModel : ViewModel() {
    private val qq = AppContainer.qq
    private val qqApi = AppContainer.qqApi

    private val _playlists = MutableStateFlow<List<QqPlaylist>>(emptyList())
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
        val cookie = qq.cookie.value
        if (cookie.isBlank()) {
            _error.value = "请先在设置中登录 QQ 音乐账号"
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { qqApi.userPlaylists(cookie) }
                .onSuccess { _playlists.value = it }
                .onFailure { _error.value = "加载失败：${it.message ?: "网络异常"}" }
            _loading.value = false
        }
    }
}
