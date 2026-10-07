package com.dpmusic.app.core.script

import java.security.MessageDigest

/**
 * 音源内容指纹（导入去重核心）。
 *
 * 取**归一化后内容**的 SHA-256 前 16 字节（32 位 hex）作为指纹，用于识别重复导入。
 *
 * 判定语义（刻意保守，避免误杀）：
 * - 同一份文件被重复选中（含同批次内选了两个副本）→ **命中**；
 * - 内容相同但换行符 / 行尾空白 / 首尾空行不同 → **命中**（归一化消除这类假差异）；
 * - 内容有任何实质改动（改版本号、改逻辑、改注释文字）→ **不命中**，允许并存
 *   （用户可能确实想保留多个版本）。
 *
 * 刻意**不做**「同名即重复」判定：MusicFree 插件的 `name` 来自 `platform` 字段，
 * 不同作者的插件常同名（如都叫「网易云」），按名字去重会误删用户真正想要的插件。
 *
 * 归一化规则：
 * 1. `\r\n` / `\r` 统一为 `\n`；
 * 2. 每行去掉尾部空白（编辑器常自动 trim）；
 * 3. 掐掉首尾空行。
 */
internal object SourceFingerprint {

    /** 计算内容指纹；内容为空时返回空串（调用方应在此之前拦掉空内容） */
    fun of(content: String): String {
        val normalized = normalize(content)
        if (normalized.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    private fun normalize(content: String): String =
        content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim('\n', ' ', '\t')

    private val HEX = "0123456789abcdef".toCharArray()
}