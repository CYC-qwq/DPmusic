package com.dpmusic.app.core.lyric

import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.SongLyrics
import kotlin.math.abs

/**
 * LRC 歌词解析器。
 * - 支持一行多时间标签 `[00:01.00][00:05.00]歌词`；
 * - 支持 `[mm:ss]` / `[mm:ss.x]` / `[mm:ss.xx]` / `[mm:ss.xxx]` 精度；
 * - 支持主歌词 + 翻译歌词按时间戳（±400ms）合并；
 * - 自动过滤元数据标签（ti/ar/al/by/offset 等）。
 */
object LrcParser {

    private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    fun parse(lrc: String, translation: String = ""): SongLyrics {
        val main = parseSingle(lrc)
        if (main.isEmpty()) return SongLyrics.EMPTY
        val trans = parseSingle(translation)
        val hasTrans = trans.isNotEmpty()
        val merged = if (!hasTrans) {
            main
        } else {
            main.map { line ->
                val t = trans.firstOrNull { abs(it.timeMs - line.timeMs) <= 400L }?.text
                if (t.isNullOrBlank()) line else line.copy(translation = t)
            }
        }
        return SongLyrics(merged, hasTrans)
    }

    /** 仅解析歌词行（不合并翻译）——供逐字解析器（YRC / QRC）复用翻译行解析 */
    fun parseLines(text: String): List<LyricLine> = parseSingle(text)

    private fun parseSingle(text: String): List<LyricLine> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<LyricLine>()
        text.split('\n').forEach { rawLine ->
            val line = rawLine.trim('\r', ' ', '\uFEFF')
            val matches = TIME_TAG.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val content = line.substring(matches.last().range.last + 1).trim()
            if (content.isEmpty() || content.startsWith("//")) return@forEach
            matches.forEach inner@{ m ->
                val minutes = m.groupValues[1].toLongOrNull() ?: return@inner
                val seconds = m.groupValues[2].toLongOrNull() ?: return@inner
                val frac = parseFraction(m.groupValues[3])
                out += LyricLine(minutes * 60_000L + seconds * 1_000L + frac, content)
            }
        }
        return out.sortedBy { it.timeMs }.distinctBy { it.timeMs to it.text }
    }

    /** 小数部分归一化为毫秒：.5 -> 500ms，.50 -> 500ms，.500 -> 500ms */
    private fun parseFraction(raw: String): Long = when {
        raw.isEmpty() -> 0L
        raw.length == 1 -> (raw.toLongOrNull() ?: 0L) * 100L
        raw.length == 2 -> (raw.toLongOrNull() ?: 0L) * 10L
        else -> raw.take(3).toLongOrNull() ?: 0L
    }
}