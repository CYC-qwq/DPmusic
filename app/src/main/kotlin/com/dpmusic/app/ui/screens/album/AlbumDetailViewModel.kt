package com.dpmusic.app.ui.screens.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.AlbumDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 专辑详情 ViewModel（网易云源） */
class AlbumDetailViewModel : ViewModel() {

    private val ncm = AppContainer.ncm
    private val ncmApi = AppContainer.ncmApi
    private val player = AppContainer.player

    private var albumId: String = ""
    private var inited = false

    private val _album = MutableStateFlow<AlbumDetail?>(null)
    val album = _album.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val nowPlaying = player.nowPlaying

    fun init(id: String) {
        if (inited && albumId == id) return
        inited = true
        albumId = id
        _album.value = null
        _error.value = null
        load()
    }

    fun retry() = load()

    private fun load() {
        if (_loading.value) return
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) {
            _error.value = "请先在设置中登录网易云账号"
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { ncmApi.albumDetail(cookie, albumId) }
                .onSuccess { detail ->
                    if (detail == null) _error.value = "专辑不存在或已下架" else _album.value = detail
                }
                .onFailure { _error.value = "获取专辑失败：${it.message ?: "网络异常"}" }
            _loading.value = false
        }
    }

    fun playSong(index: Int) {
        val list = _album.value?.songs.orEmpty()
        if (index in list.indices) player.playQueue(list, index)
    }
}
