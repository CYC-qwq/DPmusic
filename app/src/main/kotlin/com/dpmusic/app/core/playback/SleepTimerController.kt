package com.dpmusic.app.core.playback

import android.os.SystemClock
import com.dpmusic.app.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 定时退出状态快照 */
data class SleepTimerState(
    /** 倒计时进行中 */
    val active: Boolean = false,
    /** 倒计时已到、正在等待当前歌曲播放结束（「播完当前歌曲后停止」模式） */
    val pendingSongEnd: Boolean = false,
    /** 剩余毫秒（active 时有效） */
    val remainingMs: Long = 0L,
    /** 本次定时总分钟数（用于 UI 标记选中档位） */
    val totalMinutes: Int = 0,
)

/**
 * 定时退出控制器：
 * - 倒计时到点后自动暂停播放；
 * - 「播完当前歌曲后停止」模式：到点后等当前曲目自然结束（或用户切歌）再暂停；
 * - 状态经 StateFlow 单向流出，供播放页菜单 / 面板实时展示。
 */
class SleepTimerController(
    private val player: PlayerConnection,
    private val settings: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state

    /** 开始（或重置）倒计时；minutes 超出 1..1440 会被夹紧 */
    fun start(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        cancel()
        val totalMs = clamped * 60_000L
        val endAt = SystemClock.elapsedRealtime() + totalMs
        _state.value = SleepTimerState(active = true, remainingMs = totalMs, totalMinutes = clamped)
        job = scope.launch {
            while (isActive) {
                val remain = endAt - SystemClock.elapsedRealtime()
                if (remain <= 0L) break
                _state.value = _state.value.copy(remainingMs = remain)
                delay(TICK_MS)
            }
            onCountdownFinished()
            _state.value = SleepTimerState()
        }
    }

    /** 取消定时（含「等待本曲结束」挂起态） */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SleepTimerState()
    }

    /** 倒计时结束：按模式决定立即暂停或等待本曲结束 */
    private suspend fun onCountdownFinished() {
        val waitSongEnd = settings.settings.value.sleepTimerWaitSongEnd && player.isPlayingNow()
        if (!waitSongEnd) {
            player.pause()
            return
        }
        _state.value = _state.value.copy(active = false, pendingSongEnd = true, remainingMs = 0L)
        awaitSongEnd()
    }

    /** 等待当前曲目结束（自然播完 / 用户切歌 / 单曲循环回绕），随后暂停 */
    private suspend fun awaitSongEnd() {
        val stopKey = player.nowPlaying.value?.song?.stableKey
        val deadline = SystemClock.elapsedRealtime() + MAX_WAIT_MS
        var lastPosition = player.nowPlaying.value?.positionMs ?: 0L
        // 取消依赖循环内的 delay 抛出 CancellationException（主线程单线程序，无竞态）
        while (true) {
            if (SystemClock.elapsedRealtime() > deadline) break
            delay(TICK_MS)
            val np = player.nowPlaying.value ?: break
            if (stopKey == null || np.song.stableKey != stopKey) {
                // 已切到下一首：立即暂停
                player.pause()
                break
            }
            if (np.isBuffering) {
                lastPosition = np.positionMs
                continue
            }
            if (!np.isPlaying) break // 已暂停 / 队列播完：无需再暂停
            if (
                np.durationMs > 0L &&
                lastPosition >= np.durationMs - LOOP_END_WINDOW_MS &&
                np.positionMs <= LOOP_START_WINDOW_MS
            ) {
                // 单曲循环回绕：视为本曲结束
                player.pause()
                break
            }
            lastPosition = np.positionMs
        }
    }

    private companion object {
        const val TICK_MS = 1000L
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 1440

        /** 等待本曲结束的最长兜底时长（防异常状态下无限等待） */
        const val MAX_WAIT_MS = 2 * 60 * 60 * 1000L

        /** 单曲循环回绕判定：结束侧 / 起始侧窗口（ms） */
        const val LOOP_END_WINDOW_MS = 3000L
        const val LOOP_START_WINDOW_MS = 3000L
    }
}
