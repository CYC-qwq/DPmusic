package com.dpmusic.app.core.net

import android.net.Uri
import android.util.LruCache
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.chainFor
import com.dpmusic.app.core.util.CircuitBreaker

/** 音源解析异常 */
open class ResolveException(message: String, val platform: MusicPlatform? = null) : Exception(message)

/** Key 失效 / 权限不足：无需在降档链上继续尝试 */
class AuthFailureException(message: String) : ResolveException(message)

/** 触发限速：应立即停止重试 */
class RateLimitedException(message: String) : ResolveException(message)

/** 解析结果：实际生效的播放地址 + 实际命中的音质档位（可能低于请求档位） */
data class ResolvedUrl(val url: String, val qualityId: String)

/**
 * LX 音源代理解析层（实测已连通三平台）：
 * `GET https://source.shiqianjiang.cn/api/music/url?source=&songId=&quality=`
 * Header: X-API-Key。
 *
 * 容错策略：
 * 1. 内存 LRU 缓存（签名 URL 有效期内复用，默认 8 分钟）；
 * 2. 平台熔断器（连续失败 4 次进入 30s 冷却）；
 * 3. 音质自动降档链（如 flac -> 320k -> 128k）；
 *    注意：部分平台在「该档位无资源」时会返回 code=200 + 空壳地址（如 QQ 音乐
 *    hires/flac24bit 返回 `https://aqqmusic.tc.qq.com/`），此类响应会被判为
 *    解析失败并继续降档，避免把无效地址透传给播放器。
 * 4. 403/429 直接终止本次降档尝试。
 *
 * 音源 Key 不再内置：由 [apiKeyProvider] 从设置中读取（设置 → 音频偏好）。
 */
class LxResolver(private val apiKeyProvider: () -> String) {

    private val breakers: Map<MusicPlatform, CircuitBreaker> =
        MusicPlatform.entries.associateWith { CircuitBreaker() }

    private data class CachedUrl(val url: String, val qualityId: String, val at: Long)

    private val cache = object : LruCache<String, CachedUrl>(64) {}

    suspend fun resolve(song: Song, quality: PlayQuality): ResolvedUrl {
        val cacheKey = "${song.stableKey}@${quality.id}"
        cache.get(cacheKey)?.let {
            if (System.currentTimeMillis() - it.at < URL_TTL_MS) return ResolvedUrl(it.url, it.qualityId)
        }

        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            throw AuthFailureException("未配置音源 Key：请在「设置 → 音频偏好」中填写")
        }

        val breaker = breakers.getValue(song.platform)
        if (!breaker.allowRequest()) {
            throw ResolveException("${song.platform.label} 音源暂时熔断，请稍后重试", song.platform)
        }

        var lastError: Exception? = null
        for (q in quality.chainFor(song.platform)) {
            try {
                val url = requestUrl(song.platform.lxSource, song.id, q, apiKey)
                breaker.recordSuccess()
                cache.put(cacheKey, CachedUrl(url, q, System.currentTimeMillis()))
                return ResolvedUrl(url, q)
            } catch (e: ResolveException) {
                lastError = e
                if (e is AuthFailureException || e is RateLimitedException) break
            }
        }
        breaker.recordFailure()
        throw ResolveException(lastError?.message ?: "音源解析失败", song.platform)
    }

    /** 使某首歌的所有缓存失效（重试 / 换音质场景） */
    fun invalidate(song: Song) {
        PlayQuality.entries.forEach { q -> cache.remove("${song.stableKey}@${q.id}") }
    }

    fun clearCache() = cache.evictAll()

    private suspend fun requestUrl(source: String, songId: String, quality: String, apiKey: String): String {
        val url = "$API_BASE/url?source=${urlEnc(source)}&songId=${urlEnc(songId)}&quality=${urlEnc(quality)}"
        val raw = Http.get(
            url,
            headers = mapOf(
                "X-API-Key" to apiKey,
                "Content-Type" to "application/json",
                "User-Agent" to "lx-music-request/1.0",
            ),
        )
        val json = parseJsonPayload(raw)
        return when (json.int("code")) {
            200 -> json.str("url")?.trim().orEmpty().takeIf { isPlayableUrl(it) }
                ?: throw ResolveException("音源返回无效地址（该档位暂不可用）")
            403 -> throw AuthFailureException("音源 Key 失效或权限不足")
            429 -> throw RateLimitedException("音源请求过速，请稍后再试")
            else -> throw ResolveException(json.str("message") ?: "音源未知错误")
        }
    }

    private companion object {
        const val API_BASE = "https://source.shiqianjiang.cn/api/music"
        const val URL_TTL_MS = 8 * 60_000L

        /**
         * 校验代理返回的地址是否为「真实媒体文件」：
         * 拒绝空串、非 http(s) 协议，以及仅域名 / 根路径的空壳地址
         * （实测 QQ 音乐 hires / flac24bit 档位会返回 `https://aqqmusic.tc.qq.com/`，
         * 该地址访问 403 且无文件，必须判为无效以触发降档链继续向下尝试）。
         */
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