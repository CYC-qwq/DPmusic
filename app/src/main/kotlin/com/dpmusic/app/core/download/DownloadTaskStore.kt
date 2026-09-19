package com.dpmusic.app.core.download

import android.content.Context
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.AppJson
import com.dpmusic.app.core.net.Http
import com.dpmusic.app.core.net.NcmEapi
import com.dpmusic.app.core.repo.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Request
import java.io.File

/**
 * 下载任务队列（进程级单例，由 AppContainer 装配）：
 * - 串行执行（一次一个任务），进度 / 速度实时刷新到 [tasks]；
 * - 任务列表持久化到 filesDir/download_tasks.json（节流保存）；
 * - App 重启后把中断的任务标记为「已暂停」，可手动恢复；
 * - 下载完成后按设置执行元数据增强（标签 / 封面 / 歌词嵌入）。
 */
class DownloadTaskStore(
    private val context: Context,
    private val music: MusicRepository,
    private val settings: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private var worker: Job? = null
    private var runningId: String? = null
    private var runningJob: Job? = null
    private var saveJob: Job? = null

    init {
        scope.launch { load() }
    }

    /* ---------------- 对外操作 ---------------- */

    /** 加入下载队列；同一歌曲 + 音质已存在（非失败态）时返回 null */
    fun enqueue(song: Song, quality: PlayQuality, withLyrics: Boolean): String? {
        val id = NcmEapi.md5Hex("${song.stableKey}#${quality.id}")
        val existing = _tasks.value.firstOrNull { it.id == id }
        if (existing != null && existing.status != DownloadTaskStatus.ERROR) return null
        if (existing != null) {
            resetToWaiting(existing)
        } else {
            val task = DownloadTask(
                id = id,
                song = song,
                requestedQualityId = quality.id,
                withLyrics = withLyrics,
                status = DownloadTaskStatus.WAITING,
                createdAt = System.currentTimeMillis(),
            )
            _tasks.update { listOf(task) + it }
            persist()
        }
        ensureWorker()
        return id
    }

    /** 重试：失败 → 重新下载；已完成但元数据失败 → 只重试元数据 */
    fun retry(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        when {
            task.status == DownloadTaskStatus.ERROR -> {
                resetToWaiting(task)
                ensureWorker()
            }
            task.status == DownloadTaskStatus.COMPLETED && hasMetaFail(task) -> {
                scope.launch {
                    val current = _tasks.value.firstOrNull { it.id == id } ?: return@launch
                    applyMetadata(current)
                }
            }
        }
    }

    /** 恢复已中断（App 重启后）的任务 */
    fun resume(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status != DownloadTaskStatus.PAUSED) return
        resetToWaiting(task)
        ensureWorker()
    }

    /** 移除任务：进行中先取消；未完成的删除残留文件（已完成保留文件） */
    fun remove(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (runningId == id) runningJob?.cancel()
        if (task.status != DownloadTaskStatus.COMPLETED) {
            task.filePath?.let { runCatching { File(it).delete() } }
            task.filePath?.let { runCatching { File("$it.part").delete() } }
        }
        _tasks.update { list -> list.filterNot { it.id == id } }
        persist()
        ensureWorker()
    }

    /** 清除已完成 / 失败记录（不删除已下载文件） */
    fun clearFinished() {
        _tasks.update { list ->
            list.filterNot {
                it.status == DownloadTaskStatus.COMPLETED || it.status == DownloadTaskStatus.ERROR
            }
        }
        persist()
    }

    /* ---------------- 数据同步 ---------------- */

    /** 导出任务快照（供 WebDAV 同步） */
    fun snapshotForSync(): List<DownloadTask> = _tasks.value

    /** 从云端导入任务（按 id 合并：本地优先；未完成任务转为已暂停） */
    fun importFromSync(remote: List<DownloadTask>) {
        if (remote.isEmpty()) return
        val local = _tasks.value
        val localIds = local.mapTo(mutableSetOf()) { it.id }
        val merged = local.toMutableList()
        remote.forEach { task ->
            if (task.id in localIds) return@forEach
            merged += task.copy(
                status = when (task.status) {
                    DownloadTaskStatus.WAITING, DownloadTaskStatus.DOWNLOADING -> DownloadTaskStatus.PAUSED
                    else -> task.status
                },
                speedBytesPerSec = 0L,
            )
        }
        _tasks.value = merged.sortedByDescending { it.createdAt }
        persist()
    }

    /* ---------------- 队列执行 ---------------- */

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch { processLoop() }
    }

    private suspend fun processLoop() {
        while (true) {
            // 挂起等待「等待中」任务出现（worker 常驻，避免退出瞬间与 enqueue 竞态导致任务丢失调度）
            val next = _tasks
                .first { list -> list.any { it.status == DownloadTaskStatus.WAITING } }
                .first { it.status == DownloadTaskStatus.WAITING }
            val job = scope.launch { runTask(next) }
            runningJob = job
            runningId = next.id
            job.join()
            runningJob = null
            runningId = null
        }
    }

    private suspend fun runTask(task: DownloadTask) {
        // 任务可能在启动前已被移除（与 remove 竞态）：直接跳过
        if (_tasks.value.none { it.id == task.id }) return
        updateTask(task.id) { it.copy(status = DownloadTaskStatus.DOWNLOADING, errorMsg = null) }
        persist()

        val downloader = SongDownloader(context, music)
        val notes = mutableListOf<String>()
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        try {
            val outcome = downloader.download(
                song = task.song,
                requested = task.requestedQuality,
                withLyrics = task.withLyrics,
                dirRaw = settings.settings.value.downloadDir,
            ) { progress, received, total, note ->
                if (note != null && notes.lastOrNull() != note) notes += note
                val now = System.currentTimeMillis()
                val dt = now - lastTime
                val speed = if (dt > 0) (received - lastBytes) * 1000 / dt else 0L
                lastBytes = received
                lastTime = now
                updateTask(task.id) {
                    it.copy(
                        progress = if (progress >= 0f) progress else it.progress,
                        downloadedBytes = received,
                        totalBytes = if (total > 0) total else it.totalBytes,
                        speedBytesPerSec = speed,
                        notes = notes.toList(),
                    )
                }
            }

            updateTask(task.id) {
                it.copy(
                    status = DownloadTaskStatus.COMPLETED,
                    song = outcome.song,
                    qualityId = outcome.quality.id,
                    filePath = outcome.file.path,
                    fileName = outcome.file.name,
                    progress = 1f,
                    downloadedBytes = outcome.file.length(),
                    totalBytes = outcome.file.length(),
                    speedBytesPerSec = 0L,
                    lyricsSaved = outcome.lyricsFile != null,
                    notes = notes.toList(),
                    finishedAt = System.currentTimeMillis(),
                )
            }
            persist()

            val done = _tasks.value.firstOrNull { it.id == task.id }
            if (done != null) applyMetadata(done)
        } catch (e: CancellationException) {
            // 任务被移除（取消）时列表里已无此任务；其余情况标记暂停
            updateTask(task.id) {
                if (it.status == DownloadTaskStatus.DOWNLOADING) {
                    it.copy(status = DownloadTaskStatus.PAUSED, speedBytesPerSec = 0L)
                } else {
                    it
                }
            }
            persist()
            throw e
        } catch (e: Exception) {
            updateTask(task.id) {
                it.copy(
                    status = DownloadTaskStatus.ERROR,
                    errorMsg = e.message ?: "下载失败",
                    speedBytesPerSec = 0L,
                    finishedAt = System.currentTimeMillis(),
                )
            }
            persist()
        }
    }

    /* ---------------- 元数据增强 ---------------- */

    private fun hasMetaFail(task: DownloadTask): Boolean =
        task.metaTags == MetaStatus.FAIL || task.metaCover == MetaStatus.FAIL || task.metaLyric == MetaStatus.FAIL

    private suspend fun applyMetadata(task: DownloadTask) {
        val path = task.filePath ?: return
        val file = File(path)
        if (!file.exists()) return

        val s = settings.settings.value
        val wantTags = s.downloadWriteTags
        val wantCover = s.downloadWriteCover
        val wantLyric = s.downloadEmbedLyric
        if (!wantTags && !wantCover && !wantLyric) return

        if (!AudioMetadataWriter.supports(file)) {
            updateTask(task.id) {
                it.copy(
                    metaTags = MetaStatus.SKIPPED,
                    metaCover = MetaStatus.SKIPPED,
                    metaLyric = MetaStatus.SKIPPED,
                )
            }
            persist()
            return
        }

        val canEmbedLyric = AudioMetadataWriter.supportsLyric(file)
        val coverBytes = if (wantCover) fetchCoverBytes(task.song) else null
        val lyricText = if (wantLyric && canEmbedLyric) {
            runCatching { music.lyrics(task.song).toLrcText() }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        val result = runCatching {
            AudioMetadataWriter.write(
                file = file,
                metadata = AudioMetadata(
                    title = task.song.title.takeIf { wantTags },
                    artist = task.song.artist.takeIf { wantTags },
                    album = task.song.album.takeIf { wantTags && it.isNotBlank() },
                    cover = coverBytes,
                    lyric = lyricText,
                ),
            )
        }.getOrNull()

        fun statusOf(want: Boolean, ok: Boolean?): String = when {
            !want -> MetaStatus.SKIPPED
            ok == true -> MetaStatus.SUCCESS
            else -> MetaStatus.FAIL
        }

        updateTask(task.id) {
            it.copy(
                metaTags = statusOf(wantTags, result?.tags),
                metaCover = statusOf(wantCover, result?.cover),
                metaLyric = statusOf(wantLyric && canEmbedLyric, result?.lyric),
            )
        }
        persist()
    }

    private suspend fun fetchCoverBytes(song: Song): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val url = song.coverUrl
            if (url.isBlank()) return@runCatching null
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Http.DEFAULT_UA)
                .build()
            Http.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.bytes()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /* ---------------- 内部工具 ---------------- */

    private fun resetToWaiting(task: DownloadTask) {
        // 清理可能残留的部分文件
        task.filePath?.let {
            runCatching { File(it).delete() }
            runCatching { File("$it.part").delete() }
        }
        _tasks.update { list ->
            list.map {
                if (it.id == task.id) {
                    it.copy(
                        status = DownloadTaskStatus.WAITING,
                        errorMsg = null,
                        progress = 0f,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        speedBytesPerSec = 0L,
                        notes = emptyList(),
                        finishedAt = null,
                        filePath = null,
                        fileName = "",
                        lyricsSaved = false,
                        metaTags = MetaStatus.PENDING,
                        metaCover = MetaStatus.PENDING,
                        metaLyric = MetaStatus.PENDING,
                    )
                } else {
                    it
                }
            }
        }
        persist()
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** 节流持久化（800ms 合并） */
    private fun persist() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800)
            runCatching {
                val file = File(context.filesDir, FILE_NAME)
                file.writeText(AppJson.encodeToString(_tasks.value))
            }
        }
    }

    private suspend fun load() {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return
            val list = AppJson.decodeFromString<List<DownloadTask>>(file.readText())
            // 中断的任务 → 已暂停（可手动恢复）
            val normalized = list.map {
                if (it.status == DownloadTaskStatus.WAITING || it.status == DownloadTaskStatus.DOWNLOADING) {
                    it.copy(status = DownloadTaskStatus.PAUSED, speedBytesPerSec = 0L)
                } else {
                    it
                }
            }
            val existing = _tasks.value
            _tasks.value = normalized.filter { n -> existing.none { it.id == n.id } } + existing
        }
    }

    private companion object {
        const val FILE_NAME = "download_tasks.json"
    }
}
