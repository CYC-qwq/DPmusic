package com.dpmusic.app.ui.screens.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.HistoryRepository
import com.dpmusic.app.core.data.ListeningStatsStore
import com.dpmusic.app.core.model.RecentPlay
import com.dpmusic.app.core.playback.PlayerConnection
import kotlinx.coroutines.launch

/**
 * 最近播放 ViewModel：
 * - 历史列表（含播放进度）由 DataStore 单向流出；
 * - 支持从历史处续播 / 全部播放 / 清空。
 */
class RecentViewModel(
    private val historyRepo: HistoryRepository,
    private val player: PlayerConnection,
    private val listeningStats: ListeningStatsStore,
) : ViewModel() {

    val recent = historyRepo.recent

    val nowPlaying = player.nowPlaying

    // ---- 听歌统计（近 7 日 + 本次会话计时） ----
    val statsDays = listeningStats.days
    val todaySeconds = listeningStats.todaySeconds
    val sessionSeconds = listeningStats.sessionSeconds
    val sessionStartMs = listeningStats.sessionStartMs

    fun playAt(index: Int) {
        val list = recent.value
        if (index in list.indices) {
            player.playQueue(list.map { it.song }, index)
        }
    }

    fun playAll() {
        val list = recent.value.map { it.song }
        if (list.isNotEmpty()) player.playQueue(list, 0)
    }

    fun clear() {
        viewModelScope.launch { historyRepo.clear() }
    }

    fun togglePlay() = player.togglePlayPause()

    fun next() = player.next()

    fun previous() = player.previous()

    fun openPlayer() = player.openPlayerSheet()
}

/** 时间轴分组：按 今天/昨天/本周/更早 聚合 */
data class RecentGroup(
    val label: String,
    val items: List<IndexedValue<RecentPlay>>,
)