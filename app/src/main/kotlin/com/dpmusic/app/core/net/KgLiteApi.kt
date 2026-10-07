package com.dpmusic.app.core.net

import android.net.Uri
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.util.AppLogger
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 酷狗概念版设备/账号凭据（由 DataStore 提供；默认匿名） */
data class KgLiteDevice(
    /** 设备标识（数字串，用于 tracker key 计算） */
    val mid: String,
    /**
     * 设备指纹。**实测：`"-"` 可直接取到免费歌全曲**；随机 32/24 位 hex 串会被服务端
     * 返回 `status=0`（3 轮 ×2 设备复测 6/6 失败）——故默认 `"-"`，无需走设备注册接口。
     */
    val dfid: String = DEFAULT_DFID,
    /** 账号 userid（匿名 = "0"）；填了且 token 有效时可取付费歌全曲 */
    val userId: String = "",
    /** 登录 token（匿名 = 空） */
    val token: String = "",
    /** VIP token（可选，付费高音质用） */
    val vipToken: String = "",
) {
    companion object {
        const val DEFAULT_DFID = "-"
    }
}

/** 登录态校验结果 */
data class KgLiteLoginStatus(
    val nickname: String,
    val userId: String,
    val vipType: Int,
) {
    val isVip: Boolean get() = vipType > 0
}

/** 播放地址解析结果 */
data class KgLitePlayUrl(
    val url: String,
    val preview: Boolean,
    /** 实际命中的概念版音质档（128 / 320 / flac / high / super） */
    val qualityId: String,
    val timeLengthSec: Int?,
)

/**
 * 酷狗概念版（`com.kugou.android.lite`）播放地址解析。
 *
 * 接口：`GET https://gateway.kugou.com/v5/url`（header `x-router: trackercdn.kugou.com`）
 * - 需要**两个**签名同时存在（缺任一报 `20006 err signature`）：`key`（trackerKey）+ `signature`（标准签名，须在放入 key 之后再算）。
 * - `IsFreePart=0` 请求全曲；`=1` 允许 60s 试听片段。
 *
 * 实测（已登录账号，2026-10-01）：
 * - 免费歌 128 档 → `status=1` + **完整可播 URL**（已下载验证 HTTP 200 / audio/mpeg / ID3）。
 * - **付费歌带 `token`** → 128/320/flac/high 全档 `status=1`，可取**完整全曲**
 *   （flac 已验证 HTTP 200 / audio/flac / magic `fLaC` / 26MB）。
 * - 付费歌**不带 `token`** → 任意档位 `status=2`（受限），仅 `IsFreePart=1` 可得 60s 片段。
 */
class KgLiteApi(
    private val deviceProvider: () -> KgLiteDevice,
    private val saltProvider: () -> String = { KgSign.SALT_TRACKER },
    private val appIdProvider: () -> String = { KgSign.APPID },
) {

    /**
     * 取播放地址：从请求档位逐级降档（全曲优先），全部受限时退回 60s 试听片段。
     *
     * @param hash         歌曲 hash（小写）
     * @param albumId      专辑 id（缺省 0 亦可，实测全 0 也能取到全曲）
     * @param albumAudioId album_audio_id（**必须与 hash 同源**；缺失/不匹配就传 0）
     * 注意：传**错误**的 album_audio_id 会被服务端判为受限或硬错误
     * （实测免费歌 `status=3`、付费歌 `status=0/35104`，且**连试听轮也一起失败**）；
     * 传 0 等价于省略，实测免费歌可正常取全曲。`album_id` 传错则无影响。
     */
    suspend fun playUrl(
        hash: String,
        albumId: String = "",
        albumAudioId: String = "",
        quality: PlayQuality = PlayQuality.HIGH,
    ): KgLitePlayUrl? {
        val chain = kgQualityChain(quality)
        // ① 全曲优先（preview=false）
        for (q in chain) {
            request(hash, albumId, albumAudioId, q, freePart = false)?.let { return it }
        }
        // ② 退回 60s 试听片段（preview=true）
        for (q in chain) {
            request(hash, albumId, albumAudioId, q, freePart = true)?.let { return it.copy(preview = true) }
        }
        return null
    }

    /**
     * 校验登录态：`GET /user/detail`，返回昵称即 token 有效。
     * @return 成功返回 (nickname, userid, vipType)，失败/未登录返回 null
     */
    suspend fun loginStatus(token: String, userId: String): KgLiteLoginStatus? {
        val device = deviceProvider()
        val clientTime = System.currentTimeMillis() / 1000
        val params = linkedMapOf(
            "appid" to appIdProvider(),
            "clientver" to KgSign.CLIENTVER,
            "clienttime" to clientTime.toString(),
            "mid" to device.mid,
            "dfid" to device.dfid.ifBlank { KgLiteDevice.DEFAULT_DFID },
            "uuid" to "-",
            "token" to token,
        )
        if (userId.isNotBlank()) params["userid"] = userId
        val signature = KgSign.sign(params, body = "", salt = saltProvider())
        val query = (params + ("signature" to signature))
            .entries.sortedBy { it.key }
            .joinToString("&") { "${urlEnc(it.key)}=${urlEnc(it.value)}" }
        val raw = runCatching {
            Http.get(
                url = "$URL_USER_DETAIL?$query",
                referer = "https://www.kugou.com/",
                headers = buildHeaders(device, clientTime, router = null),
            )
        }.getOrNull() ?: return null
        val json = runCatching { parseJsonPayload(raw) }.getOrNull() ?: return null
        val data = json.objOrNull("data") ?: json
        val nickname = data.str("nickname")?.takeIf { it.isNotBlank() } ?: return null
        return KgLiteLoginStatus(
            nickname = nickname,
            userId = data.str("userid") ?: userId,
            vipType = data.int("vip_type") ?: data.int("vipType") ?: 0,
        )
    }

    /** 发起一次 `/v5/url` 请求；`status=1` 且有可用 url 时返回结果，否则 null */
    private suspend fun request(
        hash: String,
        albumId: String,
        albumAudioId: String,
        quality: String,
        freePart: Boolean,
    ): KgLitePlayUrl? {
        val device = deviceProvider()
        val clientTime = System.currentTimeMillis() / 1000
        val params = linkedMapOf(
            "album_id" to (albumId.toIntOrNull() ?: 0).toString(),
            "area_code" to "1",
            "hash" to hash.lowercase(),
            "ssa_flag" to "is_fromtrack",
            "version" to "11430",
            "page_id" to KgSign.PAGE_ID,
            "quality" to quality,
            "album_audio_id" to (albumAudioId.toIntOrNull() ?: 0).toString(),
            "behavior" to "play",
            "pid" to KgSign.PID,
            "cmd" to "26",
            "pidversion" to KgSign.PIDVERSION,
            "IsFreePart" to if (freePart) "1" else "0",
            "ppage_id" to KgSign.PPAGE_ID,
            "cdnBackup" to "1",
            "module" to "",
            "clientver" to KgSign.TRACKER_CLIENTVER,
            "dfid" to device.dfid.ifBlank { KgLiteDevice.DEFAULT_DFID },
            "mid" to device.mid,
            "uuid" to "-",
            "appid" to appIdProvider(),
            "clienttime" to clientTime.toString(),
        )
        if (device.userId.isNotBlank() && device.userId != "0") {
            params["userid"] = device.userId
        }
        // 登录态：付费歌全曲**必须**带 token（实测 2026-10-01）——
        // 无 token 时付费歌任何档位都返 `status=2`（受限，只能 60s 试听）；
        // 带 token 后 128/320/flac/high 全部 `status=1`，可取完整全曲（含 FLAC 直链）。
        // token 必须在算 signature 之前放入。
        if (device.token.isNotBlank()) {
            params["token"] = device.token
        }
        // key 必须在算 signature 之前放入（顺序敏感）
        params["key"] = KgSign.trackerKey(hash, device.mid, device.userId, appIdProvider())

        val signature = KgSign.sign(params, body = "", salt = saltProvider())
        val query = (params + ("signature" to signature))
            .entries.sortedBy { it.key }
            .joinToString("&") { "${urlEnc(it.key)}=${urlEnc(it.value)}" }

        val raw = runCatching {
            Http.get(
                url = "$URL_V5?$query",
                referer = "https://www.kugou.com/",
                headers = buildHeaders(device, clientTime, router = "trackercdn.kugou.com"),
            )
        }.getOrElse {
            AppLogger.w(TAG, "取地址请求失败：${it.message}")
            return null
        }

        // 礼貌限速：概念版接口较敏感，连续请求间隔过短会触发风控
        delay(REQUEST_GAP_MS)

        return parseResult(raw, quality)
    }

    private fun buildHeaders(device: KgLiteDevice, clientTime: Long, router: String?): Map<String, String> {
        val base = KgSign.STD_HEADERS + mapOf(
            "user-agent" to KgSign.UA,
            "dfid" to device.dfid.ifBlank { KgLiteDevice.DEFAULT_DFID },
            "mid" to device.mid,
            "clienttime" to clientTime.toString(),
        )
        return if (router.isNullOrBlank()) base else base + mapOf("x-router" to router)
    }

    /** 解析 `/v5/url` 响应；url 可能在顶层或一层 data 内，且可能是数组 */
    private fun parseResult(raw: String, quality: String): KgLitePlayUrl? {
        val json = runCatching { parseJsonPayload(raw) }.getOrNull() as? JsonObject ?: return null
        if ((json["status"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.toIntOrNull()) != 1) return null

        // 定位承载 url 的节点：优先 data（对象或数组首元素），否则顶层
        val dataObj = json["data"] as? JsonObject
        val dataArr = json["data"] as? JsonArray
        val node: JsonObject = dataObj ?: (dataArr?.firstOrNull() as? JsonObject) ?: json

        val url = pickUrl(node["url"]) ?: pickUrl(json["url"])
        if (url.isNullOrBlank() || !isPlayableUrl(url)) return null
        val timeLength = (node["timeLength"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: (json["timeLength"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        return KgLitePlayUrl(url = url, preview = false, qualityId = quality, timeLengthSec = timeLength)
    }

    /** url 可能是字符串或字符串数组，取第一个非空值 */
    private fun pickUrl(el: JsonElement?): String? {
        (el as? JsonPrimitive)?.let { return it.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        val arr = el as? JsonArray ?: return null
        return arr.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
    }

    companion object {
        private const val TAG = "KgLite"
        private const val URL_V5 = "https://gateway.kugou.com/v5/url"
        private const val URL_USER_DETAIL = "https://gateway.kugou.com/user/detail"

        /** 连续请求最小间隔（避免触发风控；实测注册接口无间隔会被限流） */
        private const val REQUEST_GAP_MS = 250L

        /**
         * DPmusic 音质 → 概念版音质档（从高到低，用于自动降档）。
         * 概念版档位：128 / 320 / flac / high / super（`high` 为更高规格，`super` 最高）。
         */
        fun kgQualityChain(quality: PlayQuality): List<String> {
            val full = listOf("super", "high", "flac", "320", "128")
            val target = when (quality) {
                PlayQuality.STANDARD -> "128"
                PlayQuality.HIGH -> "320"
                PlayQuality.LOSSLESS -> "flac"
                PlayQuality.FLAC24 -> "high"
                // 概念版最高档：hires / atmos / master 均映射到 super（服务端按权益返回实际规格）
                PlayQuality.HIRES, PlayQuality.ATMOS, PlayQuality.ATMOS_PLUS, PlayQuality.MASTER -> "super"
            }
            val start = full.indexOf(target).coerceAtLeast(0)
            return full.subList(start, full.size)
        }

        /** 校验地址是否为真实媒体文件（拒绝空壳 / 仅域名） */
        fun isPlayableUrl(url: String): Boolean {
            if (url.isBlank()) return false
            val uri = Uri.parse(url)
            if (uri.scheme != "http" && uri.scheme != "https") return false
            if (uri.host.isNullOrEmpty()) return false
            val path = uri.path.orEmpty()
            return path.isNotEmpty() && path != "/"
        }
    }
}