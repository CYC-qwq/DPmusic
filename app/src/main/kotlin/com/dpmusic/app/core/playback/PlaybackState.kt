package com.dpmusic.app.core.playback

import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song

/** 播放器 UI 状态快照（由 PlayerConnection 单向流出） */
data class NowPlaying(
    val song: Song,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: Int = 0,
    val shuffleEnabled: Boolean = false,
    val quality: PlayQuality = PlayQuality.HIGH,
    /** 首选音质（设置值）；与 quality 不同表示当前已自动降级 */
    val desiredQuality: PlayQuality = PlayQuality.HIGH,
    val errorMessage: String? = null,
)

/** 播放队列快照 */
data class QueueSnapshot(
    val songs: List<Song> = emptyList(),
    val currentIndex: Int = -1,
)

/** 播放器向上层抛出的 UI 命令（打开播放页 / 队列页 / 提示） */
sealed interface PlayerCommand {
    data object OpenPlayerSheet : PlayerCommand
    data object ShowQueueSheet : PlayerCommand
    data class ShowMessage(val text: String) : PlayerCommand
}