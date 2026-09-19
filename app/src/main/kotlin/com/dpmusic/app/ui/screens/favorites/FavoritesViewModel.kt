package com.dpmusic.app.ui.screens.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.FavoritesRepository
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.playback.PlayerConnection
import kotlinx.coroutines.launch

/**
 * 收藏页 ViewModel：
 * - 收藏列表由 DataStore 单向流出；
 * - 支持全部播放 / 单曲移除 / 下一首播放。
 */
class FavoritesViewModel(
    private val favoritesRepo: FavoritesRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    val favorites = favoritesRepo.favorites

    val nowPlaying = player.nowPlaying

    fun playAll() {
        val list = favorites.value
        if (list.isNotEmpty()) player.playQueue(list, 0)
    }

    fun playAt(index: Int) {
        val list = favorites.value
        if (index in list.indices) player.playQueue(list, index)
    }

    fun remove(song: Song) {
        viewModelScope.launch { favoritesRepo.remove(song.stableKey) }
    }

    fun playNext(song: Song) = player.playSongNext(song)

    fun togglePlay() = player.togglePlayPause()

    fun next() = player.next()

    fun previous() = player.previous()

    fun openPlayer() = player.openPlayerSheet()
}