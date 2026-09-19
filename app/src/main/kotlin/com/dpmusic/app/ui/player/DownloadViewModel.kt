package com.dpmusic.app.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.download.DownloadOutcome
import com.dpmusic.app.core.download.DownloadPaths
import com.dpmusic.app.core.download.DownloadTask
import com.dpmusic.app.core.download.DownloadTaskStatus
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.repo.MusicRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.io.File

/** 下载任务状态 */
sealed interface DownloadUiState {

    /** 空闲：可发起新下载 */
    data object Idle : DownloadUiState

    /** 解析音源中（含逐级降档） */
    data class Preparing(val quality: PlayQuality) : DownloadUiState

    /** 下载中 */
    data class Downloading(
        val quality: PlayQuality,
        /** 0..1；-1 = 未知总长 */
        val progress: Float,
        val receivedBytes: Long,
        val totalBytes: Long,
        /** 附加提示（如「已降级为 320K」），下载期间保持显示 */
        val note: String? = null,
    ) : DownloadUiState

    /** 完成 */
    data class Done(
        val outcome: DownloadOutcome,
        /** 降级 / 换源 / 歌词等提示（以醒目颜色展示） */
        val notes: List<String>,
    ) : DownloadUiState

    /** 失败 */
    data class Failed(val message: String) : DownloadUiState
}

/**
 * 下载 ViewModel（队列化）：
 * - 下载任务统一交给 DownloadTaskStore 串行执行
 *   （弹窗关闭 / 离开页面后继续下载，可在「设置 → 下载管理」查看）；
 * - 本 VM 只订阅当前任务的进度并映射为 UI 状态；
 * - 「取消」= 从下载列表移除任务。
 */
class DownloadViewModel(
    private val music: MusicRepository,
    private val settingsRepo: SettingsRepository,
    private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    /** 当前下载目录（实时跟随设置） */
    val dirPath: StateFlow<String> = settingsRepo.settings
        .map { DownloadPaths.resolve(context, it.downloadDir).absolutePath }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = DownloadPaths.resolve(context, settingsRepo.settings.value.downloadDir).absolutePath,
        )

    private var job: Job? = null
    private var currentTaskId: String? = null

    fun start(song: Song, quality: PlayQuality, withLyrics: Boolean) {
        if (job?.isActive == true) return
        val id = AppContainer.downloadTasks.enqueue(song, quality, withLyrics)
        if (id == null) {
            _state.value = DownloadUiState.Failed("该歌曲已加入过下载列表，可在「设置 → 下载管理」中查看")
            return
        }
        currentTaskId = id
        job = viewModelScope.launch {
            AppContainer.downloadTasks.tasks
                .takeWhile { list ->
                    val task = list.firstOrNull { it.id == id }
                    _state.value = mapTask(task)
                    // 终态（完成 / 失败 / 中断 / 被移除）：结束订阅；
                    // 否则 job 永不结束，reset() / 再次下载会被 isActive 拦下
                    task != null &&
                        task.status != DownloadTaskStatus.COMPLETED &&
                        task.status != DownloadTaskStatus.ERROR &&
                        task.status != DownloadTaskStatus.PAUSED
                }
                .collect {}
        }
    }

    /** 取消进行中的下载（从下载列表移除任务） */
    fun cancel() {
        currentTaskId?.let { AppContainer.downloadTasks.remove(it) }
        job?.cancel()
        job = null
        currentTaskId = null
        _state.value = DownloadUiState.Idle
    }

    /** 重置已完成 / 失败的状态（下次打开回到初始表单） */
    fun reset() {
        if (job?.isActive == true) return
        job = null
        currentTaskId = null
        _state.value = DownloadUiState.Idle
    }

    /** 任务状态 → UI 状态映射 */
    private fun mapTask(task: DownloadTask?): DownloadUiState {
        if (task == null) return DownloadUiState.Idle
        return when (task.status) {
            DownloadTaskStatus.WAITING -> DownloadUiState.Preparing(task.requestedQuality)
            DownloadTaskStatus.DOWNLOADING -> DownloadUiState.Downloading(
                quality = task.actualQuality,
                progress = if (task.progress > 0f) task.progress else -1f,
                receivedBytes = task.downloadedBytes,
                totalBytes = task.totalBytes,
                note = task.notes.lastOrNull(),
            )

            DownloadTaskStatus.COMPLETED -> {
                val file = task.filePath?.let(::File)
                if (file == null || !file.exists()) {
                    DownloadUiState.Failed("文件不存在或已被移动")
                } else {
                    val lyricsFile = if (task.lyricsSaved) {
                        File(file.parentFile, file.nameWithoutExtension + ".lrc").takeIf { it.exists() }
                    } else {
                        null
                    }
                    DownloadUiState.Done(
                        outcome = DownloadOutcome(
                            file = file,
                            quality = task.actualQuality,
                            requestedQuality = task.requestedQuality,
                            song = task.song,
                            platformSwitched = task.song.stableKey != task.requestedSongKey,
                            lyricsFile = lyricsFile,
                            lyricsNote = null,
                        ),
                        notes = task.notes,
                    )
                }
            }

            DownloadTaskStatus.ERROR -> DownloadUiState.Failed(task.errorMsg ?: "下载失败")
            DownloadTaskStatus.PAUSED -> DownloadUiState.Failed("下载已中断，可在「设置 → 下载管理」中恢复")
            else -> DownloadUiState.Idle
        }
    }
}
