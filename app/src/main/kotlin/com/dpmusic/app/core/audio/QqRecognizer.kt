package com.dpmusic.app.core.audio

import android.util.Base64
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.Http
import com.dpmusic.app.core.net.arrOrNull
import com.dpmusic.app.core.net.long
import com.dpmusic.app.core.net.objList
import com.dpmusic.app.core.net.parseJsonPayload
import com.dpmusic.app.core.net.str

/**
 * QQ 音乐「听歌识曲」/「哼歌识曲」接口封装。
 *
 * **协议来源**：QQ音乐 Android 客户端（20.8.5.8）jadx 反编译：
 * - `com.tencent.qqmusic.recognizekt.Recognizer#o2` —— 请求构造（URL + body）
 * - `RecognizeActivity#startRecognizeInner` —— 两个 Tab 的分流入口
 * - `RecognizeActivity.HUMMING_TIP` / `BaseRecognizeActivity.TAB_*` —— 哼唱 UI 文案与 Tab 常量
 *
 * **两个模式的分流（源码级证据，非推测）**：
 * ```
 * // BaseRecognizeActivity
 * public static final int TAB_MUSIC = 1;
 * public static final int TAB_HUMMING = 2;
 *
 * // RecognizeActivity#startRecognizeInner
 * if (getSelectedTab() == TAB_HUMMING) {
 *     Recognizer.a.D2();      // → E2(false) → e3(3) → o = 3
 * } else {
 *     Recognizer.a.E2(z);     // → e3(4) → o = 4
 * }
 *
 * // Recognizer.C2(int)  o = C2(入参)
 * // z.a(sessionid, S0(p), fpType)  → URL 里 fpType = (i3 + 1)
 * // Recognizer.I1(int)  最短有效时长：i2==2 ? 15s : i2==3 ? 11s : 15s
 * ```
 * → **听歌(F): `o=4` → URL `fpType=5`，最少 15 秒；哼唱(H): `o=3` → URL `fpType=4`，最少 11 秒。**
 *
 * > ⚠️ 注意：URL 参数 `recognizetype` 由 `S0(mIsBackground)` 决定（后台=1 / 前台=2），
 * > **与「听歌/哼唱」无关**。两个模式真正靠 `fpType` 区分（早期文档的表述有误，已修正）。
 *
 * **请求体**：`Base64(8kHz/16bit/mono PCM)` —— 客户端 `k2()` 里 `Base64.encode()` 后 `setContentByte()`。
 * 裸 PCM 会被拒（`subcode:-2 not_match`）。
 *
 * **网络**：`c6.y.qq.com`，**无签名、无加密、无 Cookie 依赖**（实测零请求头亦可命中）。
 */
object QqRecognizer {

    private const val SEARCH_URL = "https://c6.y.qq.com/youtu/humming/search"

    /**
     * 识别模式（对应客户端 Tab）。
     *
     * [FP_TYPE] 是各模式实测可用的 URL `fpType` 值；两个模式**用不同 fpType 区分**。
     * 名称 F/H 沿用客户端内部代号（Fingerprint: F=4 / H=3，+1 后即 urlFpType）。
     */
    enum class Mode(
        /** 客户端 Tab 常量（TAB_MUSIC / TAB_HUMMING） */
        val tab: Int,
        /** 客户端内部 fpType 代号（F=4 / H=3） */
        val qqFpType: Int,
        /** 拼进 URL 的 `fpType` 参数 = qqFpType + 1 */
        val urlFpType: Int,
        /** 服务端要求的最短音频时长（源码 `Recognizer.I1`） */
        val minSeconds: Int,
        val label: String,
    ) {
        /** 听歌识曲：识别环境/内录中播放的原曲 */
        LISTEN(
            tab = 1,
            qqFpType = 4,
            urlFpType = 5,
            minSeconds = 15,
            label = "听歌识曲",
        ),

        /** 哼歌识曲：识别用户用「嗯/啦」哼出的旋律 */
        HUMMING(
            tab = 2,
            qqFpType = 3,
            urlFpType = 4,
            minSeconds = 11,
            label = "哼歌识曲",
        ),
    }

    /** 单条识别候选：歌曲 + 服务端匹配度 + 命中位置 */
    data class Candidate(
        val song: Song,
        /** 服务端匹配度（0~100，接口原字段 `score`） */
        val score: Int,
        /** 匹配到歌曲中的起始秒（接口原字段 `offset`） */
        val offsetSec: Int,
    )

    /**
     * 提交 8kHz 单声道 16bit PCM 识别。
     *
     * @param pcm8kInt16 8kHz 单声道 16bit 小端 PCM（时长 × 16000 字节；
     *   听歌建议 ≥ 15s，哼唱建议 ≥ 11s）
     * @param mode [Mode.LISTEN] 听歌识曲 / [Mode.HUMMING] 哼歌识曲
     * @return 候选列表（服务端原序，1~6 条，最佳匹配在首位）；无匹配返回空列表
     */
    suspend fun recognize(
        pcm8kInt16: ByteArray,
        mode: Mode = Mode.LISTEN,
    ): List<Candidate> {
        val sessionId = System.currentTimeMillis().toString()
        // fpType 决定模式；recognizetype 实测对结果无影响，按前台取值 2
        val url = "$SEARCH_URL?sessionid=$sessionId&recognizetype=2&fpType=${mode.urlFpType}"

        // 请求体 = Base64(PCM)：客户端 k2() 里 Base64.encode(byte[]) 后 setContentByte()
        val body = Base64.encodeToString(pcm8kInt16, Base64.NO_WRAP).toByteArray(Charsets.UTF_8)

        val raw = Http.postBinary(
            url = url,
            body = body,
            headers = mapOf(
                "User-Agent" to "QQMusic/20.9.0.8(android)",
                "SOURCE" to "spr_qqmusic_android",
                "Content-Type" to "application/octet-stream",
            ),
        )

        // 无匹配：ret=0 且带 subcode/message=not_match；此时无 songlist 字段
        return parseJsonPayload(raw).arrOrNull("songlist")?.objList()?.mapNotNull { item ->
            val mid = item.str("songmid") ?: return@mapNotNull null
            val title = decodeField(item.str("songname")) ?: return@mapNotNull null
            Candidate(
                song = Song(
                    id = mid,
                    platform = MusicPlatform.QQ,
                    title = title,
                    artist = decodeField(item.str("singername")).orEmpty().ifBlank { "未知歌手" },
                    album = decodeField(item.str("albumname")).orEmpty(),
                    durationMs = (item.long("playtime") ?: 0L) * 1000L,
                    coverUrl = qqCover(item.str("albummid")),
                ),
                score = item.str("score")?.toIntOrNull() ?: 0,
                offsetSec = item.str("offset")?.toIntOrNull() ?: 0,
            )
        } ?: emptyList()
    }

    /**
     * 解 Base64 文本字段（`songname` / `singername` / `albumname`）。
     * 失败或非 UTF-8 时回退原文，避免因个别字段编码异常丢掉整条结果。
     */
    private fun decodeField(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
                .takeIf { it.isNotBlank() && it.none { c -> c == '\uFFFD' } }
        }.getOrNull() ?: value
    }

    /** QQ 专辑封面 CDN（与 QqApi 一致，实测可用） */
    private fun qqCover(albumMid: String?): String =
        if (albumMid.isNullOrBlank()) "" else "https://y.qq.com/music/photo_new/T002R500x500M000$albumMid.jpg"
}
