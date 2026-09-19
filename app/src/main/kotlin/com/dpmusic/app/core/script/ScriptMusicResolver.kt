package com.dpmusic.app.core.script

import android.util.LruCache
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.chainFor
import com.dpmusic.app.core.net.ResolveException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** 脚本解析结果：实际生效的播放地址 + 命中的音质档位 */
data class ScriptResolvedUrl(val url: String, val qualityId: String)

/**
 * 自定义音源脚本解析器（播放链路接入层）：
 * - 当激活脚本声明支持某平台的 musicUrl 能力时，优先由脚本解析播放地址；
 * - 构造与 LX Music 对齐的 musicInfo（id / name / singer / source / interval / meta）；
 * - 音质自动降档链（如 flac -> 320k -> 128k）与远端代理策略一致；
 * - 解析结果缓存 8 分钟（与远端代理缓存策略一致）。
 */
class ScriptMusicResolver(private val engine: UserApiEngine) {

    private data class CachedUrl(val url: String, val qualityId: String, val at: Long)

    private val cache = object : LruCache<String, CachedUrl>(64) {}

    /** 脚本是否可接管该平台（引擎就绪且声明 musicUrl 能力） */
    fun canResolve(platform: MusicPlatform): Boolean {
        val status = engine.status.value as? ScriptEngineStatus.Ready ?: return false
        val actions = status.sources[platform.lxSource] ?: return false
        return actions.contains("musicUrl")
    }

    /** 解析播放地址（含音质降档链 + 缓存） */
    suspend fun resolve(song: Song, quality: PlayQuality): ScriptResolvedUrl {
        val cacheKey = "${song.stableKey}@${quality.id}"
        cache.get(cacheKey)?.let {
            if (System.currentTimeMillis() - it.at < URL_TTL_MS) {
                return ScriptResolvedUrl(it.url, it.qualityId)
            }
        }
        var lastError: Exception? = null
        for (q in quality.chainFor(song.platform)) {
            try {
                val url = requestMusicUrl(song, q)
                cache.put(cacheKey, CachedUrl(url, q, System.currentTimeMillis()))
                return ScriptResolvedUrl(url, q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw ResolveException(lastError?.message ?: "脚本音源解析失败", song.platform)
    }

    /** 使某首歌的解析缓存失效（重试 / 换音质场景） */
    fun invalidate(song: Song) {
        PlayQuality.entries.forEach { q -> cache.remove("${song.stableKey}@${q.id}") }
    }

    fun clearCache() = cache.evictAll()

    private suspend fun requestMusicUrl(song: Song, qualityId: String): String {
        val data = JSONObject().apply {
            put("source", song.platform.lxSource)
            put("action", "musicUrl")
            put("info", JSONObject().apply {
                put("type", qualityId)
                put("musicInfo", buildMusicInfo(song))
            })
        }
        val response = engine.requestScript(data)
        if (!response.optBoolean("status", false)) {
            throw ResolveException(response.optString("errorMessage", "脚本解析失败"), song.platform)
        }
        val url = response.optJSONObject("result")
            ?.optJSONObject("data")
            ?.optString("url")
            .orEmpty()
        if (url.isBlank()) throw ResolveException("脚本返回了空播放地址", song.platform)
        return url
    }

    /** 构造 LX Music 对齐的 musicInfo */
    private fun buildMusicInfo(song: Song): JSONObject = JSONObject().apply {
        put("id", song.id)
        put("name", song.title)
        put("singer", song.artist)
        put("source", song.platform.lxSource)
        put("interval", formatInterval(song.durationMs))
        put("meta", JSONObject().apply {
            put("songId", song.id)
            put("albumName", song.album)
            put("picUrl", song.coverUrl)
            for ((k, v) in song.extra) {
                if (k.isNotBlank()) put(k, v)
            }
        })
    }

    /** 时长 → 'mm:ss'（对齐 LX interval 格式） */
    private fun formatInterval(durationMs: Long): String {
        if (durationMs <= 0) return ""
        val totalSeconds = durationMs / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private companion object {
        const val URL_TTL_MS = 8 * 60_000L
    }
}
