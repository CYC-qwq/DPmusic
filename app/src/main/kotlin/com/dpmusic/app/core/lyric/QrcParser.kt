package com.dpmusic.app.core.lyric

import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.LyricWord
import com.dpmusic.app.core.model.SongLyrics
import kotlin.math.abs

/**
 * QQ 音乐 QRC 逐字歌词解析器。
 *
 * 输入为解密后的 XML（`<Lyric_1 LyricContent="...">`）或裸内容：
 * - 行：`[行开始ms,行时长ms]`
 * - 字：`文字(绝对开始ms,时长ms)`（文字在时间标签之前）
 *
 * 翻译歌词为 LRC 格式，按时间戳（±400ms）与逐字行合并。
 */
object QrcParser {

    private val LINE_TAG = Regex("^\\[(\\d+),(\\d+)]")
    private val WORD_TAG = Regex("\\((\\d+),(\\d+)\\)")
    private val CONTENT_CLOSE = Regex("\"\\s*/>")

    /** 提取 XML 中的 LyricContent；无 XML 包裹时原样返回 */
    fun extractContent(xml: String): String {
        val start = xml.indexOf("LyricContent=\"")
        if (start < 0) return xml
        val contentStart = start + "LyricContent=\"".length
        val end = CONTENT_CLOSE.find(xml, contentStart)?.range?.first ?: return xml
        return if (end > contentStart) xml.substring(contentStart, end) else xml
    }

    fun parse(xmlOrContent: String, translation: String = ""): SongLyrics {
        val content = extractContent(xmlOrContent)
        if (content.isBlank()) return SongLyrics.EMPTY
        val lines = mutableListOf<LyricLine>()
        content.split('\n').forEach { raw ->
            val line = raw.trim('\r', '\uFEFF').trim()
            val m = LINE_TAG.find(line) ?: return@forEach
            val body = line.substring(m.range.last + 1)
            val words = parseWords(body)
            val text = words.joinToString("") { it.text }.ifBlank { body.trim() }
            if (text.isEmpty()) return@forEach
            lines += LyricLine(
                timeMs = m.groupValues[1].toLongOrNull() ?: return@forEach,
                text = text,
                words = words,
            )
        }
        if (lines.isEmpty()) return SongLyrics.EMPTY
        val sorted = lines.sortedBy { it.timeMs }

        // 翻译合并：与行级 LRC 相同的 ±400ms 时间戳匹配规则
        val trans = if (translation.isNotBlank()) LrcParser.parseLines(translation) else emptyList()
        val hasTrans = trans.isNotEmpty()
        val merged = if (!hasTrans) {
            sorted
        } else {
            sorted.map { l ->
                val t = trans.firstOrNull { abs(it.timeMs - l.timeMs) <= 400L }?.text
                if (t.isNullOrBlank()) l else l.copy(translation = t)
            }
        }
        return SongLyrics(
            lines = merged,
            hasTranslation = hasTrans,
            hasWordTiming = merged.any { it.words.isNotEmpty() },
        )
    }

    /** 字块：`文字(开始,时长)` —— 文字在两个时间标签之间（逐字块之前） */
    private fun parseWords(content: String): List<LyricWord> {
        val matches = WORD_TAG.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        val out = ArrayList<LyricWord>(matches.size)
        var prevEnd = 0
        matches.forEach { m ->
            val text = content.substring(prevEnd, m.range.first)
            prevEnd = m.range.last + 1
            if (text.isNotEmpty()) {
                val start = m.groupValues[1].toLongOrNull()
                val dur = m.groupValues[2].toLongOrNull()
                if (start != null && dur != null) out += LyricWord(text, start, dur)
            }
        }
        return out
    }
}