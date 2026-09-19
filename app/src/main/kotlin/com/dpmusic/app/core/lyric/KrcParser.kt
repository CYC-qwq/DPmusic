package com.dpmusic.app.core.lyric

import android.util.Base64
import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.LyricWord
import com.dpmusic.app.core.model.SongLyrics
import com.dpmusic.app.core.net.AppJson
import com.dpmusic.app.core.net.arrOrNull
import com.dpmusic.app.core.net.objList
import com.dpmusic.app.core.net.objOrNull
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 酷狗 KRC 逐字歌词解析器。
 *
 * - 解密：`krc1` 头 + 16 字节密钥循环 XOR + zlib 解压（见 [decrypt]）；
 * - 行：`[行开始ms,行时长ms]`；字：`<相对行首偏移ms,时长ms,0>文字`（相对时间自动转绝对时间）；
 * - `[language:base64]` 段：按行号对齐的翻译 / 音译（JSON），解码失败时忽略。
 */
object KrcParser {

    private val LINE_TAG = Regex("^\\[(\\d+),(\\d+)]")
    private val WORD_TAG = Regex("<(\\d+),(\\d+),(\\d+)>")
    private val LANGUAGE_TAG = Regex("\\[language:([A-Za-z0-9+/=]+)]")

    /** krc1 XOR 密钥（16 字节循环，与官方客户端一致） */
    private val XOR_KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5E, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2D, 0xCE.toByte(), 0xD2.toByte(), 0x6E, 0x69,
    )

    /** 解密 KRC 字节流（Base64 解码后的内容）；失败返回 null */
    fun decrypt(data: ByteArray): String? {
        if (data.size <= 4) return null
        if (String(data, 0, 4, Charsets.US_ASCII) != "krc1") return null
        val body = ByteArray(data.size - 4)
        for (i in 4 until data.size) {
            body[i - 4] = (data[i].toInt() xor XOR_KEY[(i - 4) % 16].toInt()).toByte()
        }
        return inflate(body)
    }

    private fun inflate(data: ByteArray): String? = try {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0) {
                if (inf.needsInput() || inf.needsDictionary()) break
            } else {
                out.write(buf, 0, n)
            }
        }
        inf.end()
        out.toByteArray().toString(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    fun parse(krc: String): SongLyrics {
        if (krc.isBlank()) return SongLyrics.EMPTY
        val entries = mutableListOf<LyricLine>()
        krc.split('\n').forEach { raw ->
            val line = raw.trim('\r', '\uFEFF').trim()
            val m = LINE_TAG.find(line) ?: return@forEach
            val start = m.groupValues[1].toLongOrNull() ?: return@forEach
            val body = line.substring(m.range.last + 1)
            val words = parseWords(body, start)
            val text = words.joinToString("") { it.text }.ifBlank { body.trim() }
            if (text.isEmpty()) return@forEach
            entries += LyricLine(timeMs = start, text = text, words = words)
        }
        if (entries.isEmpty()) return SongLyrics.EMPTY

        // [language:] 段与主歌词按行号对齐
        val langLines = parseLanguage(krc)
        val lines = entries.mapIndexed { i, l ->
            val trans = langLines.getOrNull(i)?.takeIf { it.isNotBlank() }
            if (trans == null) l else l.copy(translation = trans)
        }.sortedBy { it.timeMs }
        return SongLyrics(
            lines = lines,
            hasTranslation = lines.any { !it.translation.isNullOrBlank() },
            hasWordTiming = lines.any { it.words.isNotEmpty() },
        )
    }

    /** 字块：`<相对偏移,时长,0>文字` —— 文字在两个时间标签之间，偏移叠加行开始时间后为绝对时间 */
    private fun parseWords(content: String, lineStartMs: Long): List<LyricWord> {
        val matches = WORD_TAG.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        val out = ArrayList<LyricWord>(matches.size)
        matches.forEachIndexed { i, m ->
            val nextStart = if (i + 1 < matches.size) matches[i + 1].range.first else content.length
            val text = content.substring(m.range.last + 1, nextStart)
            if (text.isEmpty()) return@forEachIndexed
            val offset = m.groupValues[1].toLongOrNull() ?: return@forEachIndexed
            val dur = m.groupValues[2].toLongOrNull() ?: return@forEachIndexed
            out += LyricWord(text, lineStartMs + offset, dur)
        }
        return out
    }

    /** 解析 `[language:base64]`：`content[0].lyricContent` 为逐行文本（翻译 / 音译） */
    private fun parseLanguage(krc: String): List<String> {
        val m = LANGUAGE_TAG.find(krc) ?: return emptyList()
        return try {
            val raw = Base64.decode(m.groupValues[1], Base64.DEFAULT)
            val root = AppJson.parseToJsonElement(String(raw, Charsets.UTF_8))
            val first = root.arrOrNull("content").objList().firstOrNull() ?: return emptyList()
            val lyricContent = first.arrOrNull("lyricContent") ?: return emptyList()
            lyricContent.map { lineEl ->
                (lineEl as? JsonArray)?.joinToString("") { el ->
                    (el as? JsonPrimitive)?.contentOrNull.orEmpty()
                }?.trim().orEmpty()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}