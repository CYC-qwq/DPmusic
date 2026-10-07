package com.dpmusic.app.core.net

import com.dpmusic.app.core.model.QishuiRelayException
import com.dpmusic.app.core.model.QishuiRelayResult
import com.dpmusic.app.core.util.AppLogger
import com.dpmusic.app.core.util.HmacUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 汽水 relay 客户端（《汽水音乐接入协议 v1》）。
 *
 * relay = 一台跑着**已登录汽水客户端**的家里电脑（WebSocket 连到中转），
 * 由它驱动原生客户端解析出**完整歌曲**的 CDN 地址。相比匿名 `h5/seo_track`：
 * - `is_full_length` 为 true 才播（过滤试听片段）；
 * - 音质到 `hi_res`（AAC ~634kbps）；无无损（协议 §0）。
 *
 * 鉴权：每请求带 `X-Ts` + `X-Sig`（见 [HmacUtil]）。`path` **不含查询串**。
 * 传输目前是 **HTTP 明文**（协议 §8/§11）；服务器地址由用户在设置里填写。
 */
class QishuiRelayApi(
    private val baseUrlProvider: () -> String,
    private val secretProvider: () -> String,
) {

    /** 是否已配置（地址 + 密钥都非空） */
    fun isConfigured(): Boolean =
        baseUrlProvider().isNotBlank() && secretProvider().isNotBlank()

    private val client = okhttp3.OkHttpClient.Builder()
        // 协议 §6：/v1/play 首次取链要覆盖「家机冷启动 + 解析 + 公网往返」，建议 60s
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 探测中转可达性（协议 §3.1，**不需要鉴权**）；不可达时返回 null。
     * `agent_connected`（家机是否在线）见 [isAgentConnected]。
     */
    suspend fun health(): JsonElement? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${base()}/v1/health").get().build()
            client.newCall(req).execute().use { resp ->
                parseJsonPayload(resp.body?.string().orEmpty())
            }
        }.onFailure { AppLogger.w(TAG, "health 探测失败：${it.message}") }.getOrNull()
    }

    /** 家机是否在线；无法判定时返回 null */
    suspend fun isAgentConnected(): Boolean? = health()?.bool("agent_connected")

    /**
     * 取播放地址（协议 §3.2，**主路径**）。
     *
     * @param quality `hi_res`(默认) / `spatial` / `highest` / `higher` / `medium`；传 `lossless` 必 400
     * @throws QishuiRelayException 各类协议错误（带 `code` 与 HTTP 状态）
     */
    suspend fun play(trackId: String, quality: String = DEFAULT_QUALITY): QishuiRelayResult =
        withContext(Dispatchers.IO) {
            require(trackId.isNotBlank() && trackId.all { it.isDigit() }) {
                "track_id 必须为纯数字"
            }
            val json = signedGet("/v1/play", "track_id=$trackId&quality=$quality")
            QishuiRelayResult(
                url = json.str("url").orEmpty(),
                quality = json.str("quality").orEmpty(),
                codec = json.str("codec").orEmpty(),
                durationS = json.str("duration_s")?.toDoubleOrNull() ?: 0.0,
                catalogueS = json.str("catalogue_s")?.toDoubleOrNull() ?: 0.0,
                isFullLength = json.bool("is_full_length") ?: false,
                cache = json.str("cache").orEmpty(),
                expiresHintS = json.long("expires_hint_s") ?: 0L,
                playAuth = json.str("play_auth").orEmpty(),
                encrypted = json.bool("encrypted") ?: false,
            )
        }

    /** 带签名的 GET，失败抛 [QishuiRelayException] */
    private fun signedGet(path: String, query: String): JsonElement {
        val ts = System.currentTimeMillis() / 1000
        val sig = HmacUtil.deviceSignature(secretProvider(), ts, path)
        val req = Request.Builder()
            .url("${base()}$path?$query")
            .header("X-Ts", ts.toString())
            .header("X-Sig", sig)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) return parseJsonPayload(body)
            val parsed = runCatching { parseJsonPayload(body) }.getOrNull()
            val code = parsed.str("code").orEmpty().ifBlank { "http_${resp.code}" }
            val msg = parsed.str("message").orEmpty().ifBlank { "HTTP ${resp.code}" }
            AppLogger.w(TAG, "$path 失败 HTTP=${resp.code} code=$code")
            throw QishuiRelayException(code = code, httpStatus = resp.code, message = msg)
        }
    }

    /** 规范化基址：补默认 `http://` 前缀、去尾部斜杠（协议 §3 基址形如 `http://host:port`） */
    private fun base(): String {
        val raw = baseUrlProvider().trim().trimEnd('/')
        return if (raw.isEmpty()) raw
        else if (raw.contains("://")) raw
        else "http://$raw"
    }

    companion object {
        private const val TAG = "QishuiRelay"

        /** 协议 §3.2 默认档位 */
        const val DEFAULT_QUALITY = "hi_res"
    }
}