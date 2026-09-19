package com.dpmusic.app.ui.screens.ncm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 每日推荐 ViewModel（需登录 Cookie） */
class DailySongsViewModel : ViewModel() {

    private val ncm = AppContainer.ncm
    private val ncmApi = AppContainer.ncmApi
    private val player = AppContainer.player

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs = _songs.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val nowPlaying = player.nowPlaying

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
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { ncmApi.dailySongs(cookie) }
                .onSuccess { list ->
                    // 过滤「不喜欢」的歌曲（屏蔽规则可在设置中管理）
                    val visible = list.filterNot { AppContainer.dislike.matches(it) }
                    _songs.value = visible
                    _error.value = when {
                        visible.isNotEmpty() -> null
                        list.isEmpty() -> "今日推荐暂未生成，请稍后再试"
                        else -> "今日推荐的歌曲均已加入屏蔽，可在设置中管理"
                    }
                }
                .onFailure { _error.value = "加载失败：${it.message ?: "网络异常"}" }
            _loading.value = false
        }
    }

    fun play(index: Int) {
        val list = _songs.value
        if (index !in list.indices) return
        player.playQueue(list, index)
    }

    fun playAll() = play(0)
}
