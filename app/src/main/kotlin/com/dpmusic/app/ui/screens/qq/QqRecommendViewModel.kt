package com.dpmusic.app.ui.screens.qq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** QQ 音乐推荐（radio=猜你喜欢 / radar=雷达；需登录 Cookie） */
class QqRecommendViewModel : ViewModel() {
    private val qq = AppContainer.qq
    private val qqApi = AppContainer.qqApi
    private val player = AppContainer.player

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs = _songs.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    val nowPlaying = player.nowPlaying

    private var loadedSource: String? = null

    fun load(source: String) {
        if (loadedSource == source && _songs.value.isNotEmpty()) return
        loadedSource = source
        val cookie = qq.cookie.value
        if (cookie.isBlank()) {
            _error.value = "请先在设置中登录 QQ 音乐账号"
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                if (source == "radar") qqApi.radarRecommend(cookie) else qqApi.radioRecommend(cookie)
            }
                .onSuccess { list ->
                    _songs.value = list
                    if (list.isEmpty()) _error.value = "暂未获取到推荐，请稍后再试"
                }
                .onFailure { _error.value = "加载失败：${it.message ?: "网络异常"}" }
            _loading.value = false
        }
    }

    fun retry(source: String) {
        loadedSource = null
        load(source)
    }

    fun play(index: Int) {
        val list = _songs.value
        if (index !in list.indices) return
        player.playQueue(list, index)
    }

    fun playAll() = play(0)
}
