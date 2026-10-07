package com.dpmusic.app.core.lyric

import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import org.json.JSONObject

/**
 * 系统「歌词外发」桥：把当前歌词写进 [androidx.media3.common.MediaMetadata] 的 extras，
 * 供锁屏 / 息屏 / 灵动岛 / 车机等系统组件读取。
 *
 * **双键并行**，一次写入同时喂两套互不冲突的生态：
 *
 * 1. [KEY_MIUI] = `android.media.metadata.LYRIC` —— MIUI / HyperOS。
 *    HyperOS 由 `com.miui.maml.elements.MusicLyricParser`（锁屏/AOD 共用）解析，
 *    键名定义于 `com.miui.internal.os.MiuiHooks.METADATA_KEY_LYRIC`。
 *    载荷是**标准 LRC**，且**行必须以 CRLF 结尾**（解析器按 `\r\n` 切分），
 *    支持 `[ti:][ar:][al:][by:][ve:][offset:]` 头部 —— 详见 [buildMiuLrc]。
 *
 * 2. [KEY_LYRIC_INFO] = `lyricInfo` —— ColorOS 锁屏岛等（LyricInfo 模块约定）。
 *    载荷是 JSON，`lyric` 放原文逐行 LRC，`rawLyric` 放增强逐字，`translation` 独立成 lane。
 *    详见 [encode]。
 *
 * 两个 key 的值互不相同、各自独立解析，同时写入不会互相干扰；消费方只用自己认识的那个。
 */
object CarLyricInfo {

    /**
     * MIUI / HyperOS 歌词键。
     *
     * HyperOS 的锁屏 / 息屏 / 灵动岛歌词由 `com.miui.maml.elements.MusicLyricParser`
     * 解析，其读取的元数据键即此值（定义于 `com.miui.internal.os.MiuiHooks.METADATA_KEY_LYRIC`）。
     */
    const val KEY_MIUI = "android.media.metadata.LYRIC"

    /** ColorOS / 通用系统歌词组件键（LyricInfo 模块约定，JSON 载荷） */
    const val KEY_LYRIC_INFO = "lyricInfo"

    /**
     * 当前歌词行在整首 LRC 里的**索引**键。
     *
     * 消费方拿到整首带时间轴的 LRC 后，若还想知道「此刻唱到第几行」，不必自己解析时间轴 ——
     * 直接读这个索引即可。参考实现（NeriPlayer / Melodia）也都额外携带了这一信息。
     */
    private const val KEY_LINE_INDEX = "lineIndex"

    /** MIUI 解析器按此切分歌词行，必须使用 CRLF。 */
    private const val CRLF = "\r\n"

    /** 行内字级时间标签 `<mm:ss.xxx>`（增强 LRC / ELRC，用于 lyricInfo 的 rawLyric） */
    private val ELRC_TAG = Regex("<\\d{2,3}:\\d{2}\\.\\d{2,3}>")

    /**
     * 构造写入 MediaMetadata 的 extras。
     *
     * 两条载荷**相互独立**，任一可用即返回 Bundle：
     * - `lyricInfo` JSON（当前行 + 整首 LRC + 翻译 lane）：只要歌词与基础信息齐全就写；
     * - MIUI 的 `android.media.metadata.LYRIC`（整首 CRLF LRC）：仅在 [fullLrc] 为真时写。
     *
     * 返回 null 的三种情形，调用方都应**移除**旧 extras，避免上一首的歌词残留：
     * 1. [enabled] 为假（功能关闭）；
     * 2. 整首歌都没拉到歌词；
     * 3. 歌曲基础信息不完整（`lyricInfo` 契约要求 `songName` / `artist`）。
     *
     * @param currentLine 当前歌词行文本（同时会被写进标题，此处随 JSON 一并携带，便于消费方
     *   只读一个键就拿到「此刻唱的是哪句」）；null = 无当前行（暂停 / 无歌词）。
     * @param fullLrc 是否同时写入 MIUI 的整首 LRC 键 —— 由用户开关控制，
     *   因为该键对蓝牙链路无意义，且会显著增大每次元数据更新的载荷。
     */
    fun extrasFor(
        song: Song,
        lyrics: SongLyrics?,
        enabled: Boolean,
        currentLine: String? = null,
        fullLrc: Boolean = false,
    ): android.os.Bundle? {
        if (!enabled) return null
        val lines = lyrics?.lines.orEmpty()
        val json = encode(song, lyrics, currentLine)
        val miuLrc = if (fullLrc) buildMiuLrc(song, lines) else ""
        if (miuLrc.isEmpty() && json == null) return null
        return android.os.Bundle().apply {
            if (miuLrc.isNotEmpty()) putString(KEY_MIUI, miuLrc)
            if (json != null) putString(KEY_LYRIC_INFO, json)
        }
    }

    /**
     * 构造 HyperOS 可解析的歌词文本：标准 LRC，**行以 CRLF 结尾**。
     *
     * 依据 `MusicLyricParser` 实测逻辑：
     * - 行由 `split("\r\n")` 切分（所以必须 CRLF，单 `\n` 会导致整段只解析出一行）；
     * - 时间标签 `[mm:ss.xx]`，末段按秒 ×1000、前面的段按 ×60 累加；
     * - `[...]` 标签之后到行尾是歌词文本；
     * - 可选头部 `[ti:][ar:][al:]`。
     */
    private fun buildMiuLrc(song: Song, lines: List<com.dpmusic.app.core.model.LyricLine>): String {
        if (lines.isEmpty()) return ""
        val body = StringBuilder()
        lines.sortedBy { it.timeMs }.forEach { line ->
            val text = line.text
            if (text.isBlank()) return@forEach
            body.append(formatMiuTime(line.timeMs)).append(sanitize(text)).append(CRLF)
        }
        if (body.isEmpty()) return ""
        val sb = StringBuilder()
        val title = song.title.trim()
        if (title.isNotEmpty()) sb.append("[ti:").append(sanitize(title)).append(']').append(CRLF)
        val artist = song.artist.trim()
        if (artist.isNotEmpty()) sb.append("[ar:").append(sanitize(artist)).append(']').append(CRLF)
        val album = song.album.trim()
        if (album.isNotEmpty()) sb.append("[al:").append(sanitize(album)).append(']').append(CRLF)
        return sb.append(body).toString()
    }

    /**
     * 清掉会被解析器误判的字符：`<...>` 会被当作增强标签剥离，
     * 歌词文本里的 `[` / `]` 会破坏「最后一个 `]` 之后才是正文」的定位。
     */
    private fun sanitize(text: String): String =
        text.replace('<', '＜').replace('>', '＞').replace('[', '【').replace(']', '】')

    /** `[mm:ss.xx]`：分钟不补零位宽上限，秒保留两位小数（与解析器 Double 秒一致）。 */
    private fun formatMiuTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "[%02d:%05.2f]".format(t / 60_000, (t % 60_000) / 1000.0)
    }

    /**
     * 把歌词编码为 lyricInfo JSON；基础字段缺失或无歌词时返回 null。
     *
     * @param currentLine 当前歌词行；写进 `currentLine` 字段，并据此推出 `lineIndex`。
     *   「当前行」与「整首 LRC」放在同一个 JSON 里，消费方**只读这一个键**就能
     *   同时满足两种用法（只显示当前句 / 自己滚动整首）。
     */
    fun encode(song: Song, lyrics: SongLyrics?, currentLine: String? = null): String? {
        val songName = song.title.trim().takeIf { it.isNotEmpty() } ?: return null
        val artist = song.artist.trim().takeIf { it.isNotEmpty() } ?: return null
        val lines = lyrics?.lines.orEmpty()
        if (lines.isEmpty()) return null

        val lineLrc = buildLineLrc(lines)
        if (lineLrc.isBlank()) return null

        val line = currentLine?.trim()?.takeIf { it.isNotEmpty() }

        return JSONObject().apply {
            put("songName", songName)
            put("artist", artist)
            put("lyric", lineLrc)
            putOptional("songId", song.id)
            putOptional("album", song.album)
            putOptional("rawLyric", buildWordElrc(lines))
            putOptional("translation", buildTranslationLane(lines))
            if (line != null) {
                put("currentLine", line)
                // 索引让消费方免去自己解析时间轴；找不到（例如空行被跳过）时不写该键
                lines.indexOfFirst { it.text.trim() == line }
                    .takeIf { it >= 0 }
                    ?.let { put(KEY_LINE_INDEX, it) }
            }
        }.toString()
    }

    private fun JSONObject.putOptional(key: String, value: String?) {
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { put(key, it) }
    }

    /** 原文逐行 LRC：仅原文，翻译单独成 lane。 */
    private fun buildLineLrc(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        lines.sortedBy { it.timeMs }.forEach { line ->
            val text = line.text
            if (text.isBlank()) return@forEach
            sb.append(formatLrcTime(line.timeMs)).append(text).append('\n')
        }
        return sb.toString().trim()
    }

    /** 原文增强逐字 LRC（ELRC）：整行时间 + 行内 `<mm:ss.xxx>` 字级标签。 */
    private fun buildWordElrc(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        lines.sortedBy { it.timeMs }.forEach { line ->
            val text = line.text
            if (text.isBlank()) return@forEach
            val words = line.words
            if (words.isEmpty()) {
                sb.append(formatLrcTime(line.timeMs)).append(text).append('\n')
            } else {
                sb.append(formatLrcTime(line.timeMs))
                words.forEach { word ->
                    if (word.text.isEmpty()) return@forEach
                    sb.append(formatElrcTag(word.startMs)).append(word.text)
                }
                sb.append('\n')
            }
        }
        val out = sb.toString().trim()
        // 无任何字级数据时不必输出增强 lane（与逐行 LRC 重复无意义）
        return if (ELRC_TAG.containsMatchIn(out)) out else ""
    }

    /** 独立翻译 lane（逐行 LRC）。 */
    private fun buildTranslationLane(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        lines.sortedBy { it.timeMs }.forEach { line ->
            val trans = line.translation
            if (trans.isNullOrBlank()) return@forEach
            sb.append(formatLrcTime(line.timeMs)).append(trans).append('\n')
        }
        return sb.toString().trim()
    }

    private fun formatLrcTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "[%02d:%02d.%03d]".format(t / 60_000, (t % 60_000) / 1000, t % 1000)
    }

    private fun formatElrcTag(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "<%02d:%02d.%03d>".format(t / 60_000, (t % 60_000) / 1000, t % 1000)
    }
}
