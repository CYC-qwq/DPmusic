package com.dpmusic.app.core.script

import android.util.LruCache
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.ResolveException
import com.dpmusic.app.core.playback.PlaybackHeaderStore
import com.dpmusic.app.core.util.AppLogger
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** 音源可用性测试结果 */
data class SourceTestResult(
    val sourceName: String,
    val success: Boolean,
    val detail: String,
    val latencyMs: Long,
)

/**
 * MusicFree 插件解析器（播放链路接入层）。
 *
 * 与 [ScriptMusicResolver]（LX 脚本）并列：把本应用的 [Song] 映射为 MusicFree 的
 * `musicItem`、把 [PlayQuality] 映射为 MusicFree 的音质档位（low/standard/high/super），
 * 再调用插件的 `getMediaSource(musicItem, quality)` 取真实播放地址。
 *
 * 插件返回的请求头会登记进 [PlaybackHeaderStore]，由播放侧在发请求时注入。
 */
class MusicFreeResolver(private val engine: MusicFreeEngine) {

    private data class CachedUrl(val url: String, val at: Long)

    private val cache = object : LruCache<String, CachedUrl>(64) {}

    /** 插件是否已就绪且实现了取源能力 */
    fun canResolve(): Boolean {
        val status = engine.status.value as? PluginEngineStatus.Ready ?: return false
        return status.meta.supports(METHOD_GET_MEDIA_SOURCE)
    }

    /** 当前插件名（用于提示文案） */
    fun pluginLabel(): String =
        (engine.status.value as? PluginEngineStatus.Ready)?.meta?.platform.orEmpty().ifBlank { "MusicFree 插件" }

    /** 解析播放地址（音质逐级降档 + 8 分钟缓存） */
    suspend fun resolve(song: Song, quality: PlayQuality): ScriptResolvedUrl {
        val cacheKey = "${song.stableKey}@${quality.id}"
        cache.get(cacheKey)?.let {
            if (System.currentTimeMillis() - it.at < URL_TTL_MS) {
                return ScriptResolvedUrl(it.url, quality.id)
            }
        }
        var lastError: Exception? = null
        for (mfQuality in qualityChain(quality)) {
            try {
                val result = requestMediaSource(song, mfQuality)
                val url = result.first
                if (url.isNotBlank()) {
                    cache.put(cacheKey, CachedUrl(url, System.currentTimeMillis()))
                    return ScriptResolvedUrl(url, quality.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw ResolveException(lastError?.message ?: "插件音源解析失败", song.platform)
    }

    /** 使某首歌的缓存失效 */
    fun invalidate(song: Song) {
        PlayQuality.entries.forEach { q -> cache.remove("${song.stableKey}@${q.id}") }
    }

    fun clearCache() = cache.evictAll()

    /**
     * 音源可用性测试：对指定歌曲跑一次真实解析（绕开缓存），返回耗时与结果。
     * 供「音源可用性测试」页面调用。
     */
    suspend fun testResolve(song: Song): SourceTestResult {
        val started = System.currentTimeMillis()
        val label = pluginLabel()
        if (!canResolve()) {
            return SourceTestResult(label, false, "插件未就绪或未实现 getMediaSource", 0L)
        }
        return try {
            val result = requestMediaSource(song, "standard")
            val elapsed = System.currentTimeMillis() - started
            val url = result.first
            if (url.isBlank()) {
                SourceTestResult(label, false, "插件返回了空地址", elapsed)
            } else {
                SourceTestResult(label, true, "解析成功（${url.take(64)}…）", elapsed)
            }
        } catch (e: Exception) {
            SourceTestResult(label, false, e.message ?: "解析失败", System.currentTimeMillis() - started)
        }
    }

    /** 调用插件 getMediaSource；返回 (url, headers) */
    private suspend fun requestMediaSource(song: Song, mfQuality: String): Pair<String, Map<String, String>> {
        val args = JSONArray().apply {
            put(buildMusicItem(song))
            put(mfQuality)
        }
        val response = engine.invoke(METHOD_GET_MEDIA_SOURCE, args.toString())
        val raw = response.opt("result")
        val url: String
        val headers = mutableMapOf<String, String>()
        when (raw) {
            is JSONObject -> {
                url = raw.optString("url")
                raw.optJSONObject("headers")?.let { obj ->
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = obj.optString(key)
                        if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
                    }
                }
            }
            is String -> url = raw
            else -> url = ""
        }
        if (url.isBlank()) throw ResolveException("插件未返回播放地址", song.platform)
        if (headers.isNotEmpty()) PlaybackHeaderStore.put(url, headers)
        return url to headers
    }

    /** 构造 MusicFree 的 musicItem */
    private fun buildMusicItem(song: Song): JSONObject = JSONObject().apply {
        put("id", song.id)
        put("platform", song.platform.id)
        put("title", song.title)
        put("artist", song.artist)
        put("album", song.album)
        put("artwork", song.coverUrl)
        put("duration", song.durationMs / 1000)
        for ((k, v) in song.extra) {
            if (k.isNotBlank()) put(k, v)
        }
    }

    /**
     * 音质降档链（MusicFree 档位：low / standard / high / super）。
     * 例如请求无损时依次尝试 super → high → standard → low。
     */
    private fun qualityChain(quality: PlayQuality): List<String> {
        val target = when (quality) {
            PlayQuality.STANDARD -> "standard"
            PlayQuality.HIGH -> "high"
            else -> "super"
        }
        val all = listOf("super", "high", "standard", "low")
        val start = all.indexOf(target).coerceAtLeast(0)
        return all.subList(start, all.size)
    }

    private companion object {
        const val METHOD_GET_MEDIA_SOURCE = "getMediaSource"
        const val URL_TTL_MS = 8 * 60_000L
    }
}