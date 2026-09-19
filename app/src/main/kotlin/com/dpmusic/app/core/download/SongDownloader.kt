package com.dpmusic.app.core.download

import android.content.Context
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import com.dpmusic.app.core.model.chainFor
import com.dpmusic.app.core.net.Http
import com.dpmusic.app.core.net.ResolveException
import com.dpmusic.app.core.repo.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/** 下载异常（message 为用户可读文案） */
class DownloadException(message: String) : Exception(message)

/** 下载进度回调：progress = 0..1（-1 = 未知总长）；note = 附加提示（如降级说明） */
typealias DownloadProgress = (progress: Float, receivedBytes: Long, totalBytes: Long, note: String?) -> Unit

/** 下载结果 */
data class DownloadOutcome(
    val file: File,
    /** 实际下载到的音质（可能低于请求档位） */
    val quality: PlayQuality,
    /** 用户请求的音质 */
    val requestedQuality: PlayQuality,
    /** 实际使用的歌曲（可能已跨平台换源） */
    val song: Song,
    val platformSwitched: Boolean,
    /** 歌词文件（未下载或失败为 null） */
    val lyricsFile: File?,
    /** 歌词附加说明（如「暂无歌词」「歌词获取失败」） */
    val lyricsNote: String?,
) {
    val downgraded: Boolean get() = quality != requestedQuality
}

/**
 * 歌曲下载器：
 * 1. 按请求音质解析（解析层内部逐级降档）→ 下载字节流；
 * 2. 字节下载失败时继续向更低档位重试（层层自动降级），全程回报进度；
 * 3. 可选抓取歌词并写出同名 .lrc（翻译行与原行同时间戳）。
 */
class SongDownloader(
    private val context: Context,
    private val music: MusicRepository,
) {

    suspend fun download(
        song: Song,
        requested: PlayQuality,
        withLyrics: Boolean,
        dirRaw: String,
        onProgress: DownloadProgress,
    ): DownloadOutcome {
        val dir = DownloadPaths.resolve(context, dirRaw)
        val check = DownloadPaths.check(context, dir)
        if (check !is DownloadPaths.Check.Ok) {
            throw DownloadException(
                when (check) {
                    is DownloadPaths.Check.NeedAllFilesAccess ->
                        "下载目录需要「所有文件访问权限」：请到 设置 → 下载 中授予"
                    is DownloadPaths.Check.NeedStoragePermission ->
                        "下载目录需要存储权限：请到 设置 → 下载 中授予"
                    is DownloadPaths.Check.NotWritable -> "下载目录不可用：${check.reason}"
                    else -> "下载目录不可用，请到 设置 → 下载 中检查"
                },
            )
        }

        var target = requested
        var lastError: Exception? = null
        var attempt = 0

        while (attempt <= MAX_ATTEMPTS) {
            val resolved = try {
                music.resolveForPlayback(song, target, forceRefresh = attempt > 0)
            } catch (e: Exception) {
                lastError = e
                null
            }
            if (resolved == null) break

            // 解析阶段即降级：提示用户
            if (resolved.quality.id != target.id) {
                onProgress(0f, 0L, 0L, "「${target.label}」不可用，已降级为「${resolved.quality.label}」")
            }

            val file = uniqueFile(dir, baseName(resolved.song), guessExtension(resolved.url, resolved.quality))
            try {
                downloadBytes(resolved.url, file, refererFor(resolved.song.platform), onProgress)
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                file.delete()
                lastError = e
                // 字节下载失败 → 从实际命中的档位继续向下找更低档位
                val chain = target.chainFor(song.platform)
                val nextId = chain.getOrNull(chain.indexOf(resolved.quality.id) + 1)
                if (nextId == null) break
                target = PlayQuality.fromId(nextId)
                attempt++
                onProgress(0f, 0L, 0L, "「${resolved.quality.label}」下载失败，自动降级为「${target.label}」重试…")
                continue
            }

            // 下载成功 → 可选歌词
            var lyricsFile: File? = null
            var lyricsNote: String? = null
            if (withLyrics) {
                val lyricsResult = fetchLyrics(resolved.song, file)
                lyricsFile = lyricsResult.first
                lyricsNote = lyricsResult.second
            }

            return DownloadOutcome(
                file = file,
                quality = resolved.quality,
                requestedQuality = requested,
                song = resolved.song,
                platformSwitched = resolved.song.stableKey != song.stableKey,
                lyricsFile = lyricsFile,
                lyricsNote = lyricsNote,
            )
        }

        val message = when (val e = lastError) {
            null -> "下载失败，请稍后重试"
            is DownloadException -> e.message ?: "下载失败"
            is ResolveException -> e.message ?: "音源解析失败"
            else -> "下载失败：${e.message ?: "网络异常"}"
        }
        throw DownloadException(message)
    }

    /* ---------------- 字节流下载 ---------------- */

    private suspend fun downloadBytes(
        url: String,
        file: File,
        referer: String?,
        onProgress: DownloadProgress,
    ) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".part")
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Http.DEFAULT_UA)
                .apply { referer?.let { header("Referer", it) } }
                .build()
            Http.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw DownloadException("HTTP ${resp.code}")
                val body = resp.body ?: throw DownloadException("响应为空")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        var lastEmit = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            received += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 100) {
                                lastEmit = now
                                val p = if (total > 0) received.toFloat() / total else -1f
                                onProgress(p, received, total, null)
                            }
                        }
                    }
                }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            onProgress(1f, file.length(), file.length(), null)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /* ---------------- 歌词 ---------------- */

    /** 返回 (歌词文件, 附加说明)；失败时文件为 null */
    private suspend fun fetchLyrics(song: Song, audioFile: File): Pair<File?, String?> = runCatching {
        val lrc = music.lyrics(song).toLrcText()
        if (lrc.isBlank()) {
            null to "该歌曲暂无歌词"
        } else {
            val lrcFile = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".lrc")
            lrcFile.writeText(lrc)
            lrcFile to null
        }
    }.getOrElse { null to "歌词获取失败（${it.message ?: "网络异常"}）" }

    /* ---------------- 工具 ---------------- */

    private fun baseName(song: Song): String {
        val raw = "${song.title} - ${song.artist}"
        return raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().take(120).ifBlank { "未命名歌曲" }
    }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var candidate = File(dir, "$base.$ext")
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($index).$ext")
            index++
        }
        return candidate
    }

    private fun guessExtension(url: String, quality: PlayQuality): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in KNOWN_EXTS) return ext
        return when (quality) {
            PlayQuality.LOSSLESS, PlayQuality.FLAC24, PlayQuality.HIRES -> "flac"
            else -> "mp3"
        }
    }

    private fun refererFor(platform: MusicPlatform): String? = when (platform) {
        MusicPlatform.WY -> "https://music.163.com/"
        MusicPlatform.QQ -> "https://y.qq.com/"
        MusicPlatform.KG -> "https://www.kugou.com/"
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        val KNOWN_EXTS = setOf("mp3", "flac", "m4a", "aac", "ape", "wav", "ogg")
    }
}

/* ---------------- LRC 文本构建 ---------------- */

/** 将解析后的歌词重建为标准 LRC 文本（翻译行与原行同时间戳，紧随其后） */
internal fun SongLyrics.toLrcText(): String {
    if (lines.isEmpty()) return ""
    val sb = StringBuilder()
    lines.sortedBy { it.timeMs }.forEach { line ->
        val ts = formatLrcTime(line.timeMs)
        sb.append(ts).append(line.text).append('\n')
        val trans = line.translation
        if (!trans.isNullOrBlank()) sb.append(ts).append(trans).append('\n')
    }
    return sb.toString()
}

private fun formatLrcTime(ms: Long): String {
    val total = ms.coerceAtLeast(0)
    val minutes = total / 60_000
    val seconds = (total % 60_000) / 1_000
    val centis = (total % 1_000) / 10
    return "[%02d:%02d.%02d]".format(minutes, seconds, centis)
}
