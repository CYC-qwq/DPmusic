package com.dpmusic.app.core.audio

import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.Http
import com.dpmusic.app.core.net.arrOrNull
import com.dpmusic.app.core.net.long
import com.dpmusic.app.core.net.objList
import com.dpmusic.app.core.net.parseJsonPayload
import com.dpmusic.app.core.net.str
import com.dpmusic.app.core.net.toHttps
import com.dpmusic.app.core.net.urlEnc
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

/**
 * 酷狗「听歌识曲」接口封装。
 *
 * 与网易云指纹方案不同：酷狗**直接上传音频**（8kHz 单声道 Int16 PCM，6~10 秒），
 * 签名 = MD5(salt + 排序参数拼接 + PCM 二进制 + salt)。
 */
object KgRecognizer {

    private const val MATCH_URL =
        "https://gateway.kugou.com/fingerprint.service/v1/music_trackid_mulit"

    /** 酷狗 Android 客户端签名盐（逆向自公开客户端协议） */
    private const val SALT = "OIlwieks28dk2k092lksi2UIkp"

    /** 单条识别候选：歌曲 + 匹配距离 + 原唱标记 */
    data class Candidate(
        val song: Song,
        val dist: Double,
        val isOriginal: Boolean,
    ) {
        /** 版本标签：原唱 / 翻唱 */
        val versionLabel: String
            get() = if (isOriginal) "原唱" else "翻唱"
    }

    /**
     * 提交 PCM 识别。
     * @param pcm8kInt16 8kHz 单声道 16bit 小端 PCM 字节数组（时长 × 8000 × 2 字节，如 10 秒 = 160000 字节）
     * @return 候选列表（按相似度排序，dist 越小越相似）
     */
    suspend fun recognize(pcm8kInt16: ByteArray): List<Candidate> {
        val mid = BigInteger(md5Hex(UUID.randomUUID().toString()), 16).toString()
        val clientTime = System.currentTimeMillis() / 1000
        val params = linkedMapOf(
            "dfid" to "-",
            "mid" to mid,
            "uuid" to "-",
            "appid" to "1005",
            "clientver" to "20489",
            "clienttime" to clientTime.toString(),
            "fpid" to System.currentTimeMillis().toString(),
            "area_code" to "1",
            "include_unpublish" to "1",
            "useid" to "0",
            "multi_result" to "1",
        )
        // 签名 = MD5(salt + 排序参数拼接 + PCM二进制 + salt)
        val sortedParams = params.entries.sortedBy { it.key }
            .joinToString("") { "${it.key}=${it.value}" }
        val signature = run {
            val md = MessageDigest.getInstance("MD5")
            val saltBytes = SALT.toByteArray(Charsets.UTF_8)
            md.update(saltBytes)
            md.update(sortedParams.toByteArray(Charsets.UTF_8))
            md.update(pcm8kInt16)
            md.update(saltBytes)
            md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        val query = (params + ("signature" to signature))
            .entries.sortedBy { it.key }
            .joinToString("&") { "${urlEnc(it.key)}=${urlEnc(it.value)}" }

        val raw = Http.postBinary(
            url = "$MATCH_URL?$query",
            body = pcm8kInt16,
            headers = mapOf(
                "user-agent" to "KuGou/11490 (Android)",
                "dfid" to "-",
                "clienttime" to clientTime.toString(),
                "mid" to mid,
                "kg-rc" to "1",
                "kg-thash" to "5d816a0",
                "kg-rec" to "1",
                "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
            ),
        )

        val data = parseJsonPayload(raw).arrOrNull("data").orEmpty()
        return data.mapNotNull { item ->
            val songName = item.str("songname") ?: return@mapNotNull null
            val hash = item.str("hash_320")?.takeIf { it.isNotBlank() }
                ?: item.str("hash_flac")?.takeIf { it.isNotBlank() }
                ?: item.str("hash_high")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val suffix = item.str("songNameSuffix").orEmpty()
            val album = item.arrOrNull("album")?.objList()?.firstOrNull()
            Candidate(
                song = Song(
                    id = hash.lowercase(),
                    platform = MusicPlatform.KG,
                    title = if (suffix.isBlank()) songName else "$songName ($suffix)",
                    artist = item.str("singername").orEmpty().ifBlank { "未知歌手" },
                    album = album?.str("albumname").orEmpty(),
                    durationMs = item.long("timelength_320")
                        ?: item.long("timelength_flac")
                        ?: 0L,
                    coverUrl = item.str("union_cover").orEmpty()
                        .replace("{size}", "480")
                        .toHttps(),
                ),
                dist = item.str("dist")?.toDoubleOrNull() ?: 1.0,
                isOriginal = item.str("is_original") == "1",
            )
        }
    }

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}