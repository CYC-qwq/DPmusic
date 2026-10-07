package com.dpmusic.app.core.script

import android.util.LruCache
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.ScriptOrder
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.chainFor
import com.dpmusic.app.core.net.ResolveException
import com.dpmusic.app.core.util.AppLogger
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** 脚本解析结果：实际生效的播放地址 + 命中的音质档位 */
data class ScriptResolvedUrl(val url: String, val qualityId: String)

/**
 * 自定义音源脚本解析器（播放链路接入层）。
 *
 * ## 从「单脚本」到「按序尝试多个脚本」
 * 以前这里持有一个 [UserApiEngine]，只能问「那个激活脚本」要地址。现在持有
 * [ScriptEnginePool]，按**用户为该平台排的顺序**依次尝试 —— 前一个失败自动落到下一个。
 *
 * 「谁有资格解析这个平台」也随之从「激活脚本的能力」变成「链上启用项的能力」：
 * 脚本没上报 `musicUrl` 能力、或压根没驻留（未启用），都会被静默跳过。
 *
 * 其余不变：构造与 LX Music 对齐的 musicInfo、音质自动降档链（flac → 320k → 128k）、
 * 解析结果缓存 8 分钟。
 */
class ScriptMusicResolver(
    private val pool: ScriptEnginePool,
    /** 当前顺序配置（逐平台）；由 AppContainer 注入设置的实时快照 */
    private val orders: () -> List<ScriptOrder>,
) {

    private data class CachedUrl(val url: String, val qualityId: String, val at: Long)

    private val cache = object : LruCache<String, CachedUrl>(64) {}

    /** 该平台当前**可参与解析**的脚本 id（按用户顺序，已过滤未启用 / 无能力者） */
    fun availableScriptIds(platform: MusicPlatform): List<String> =
        orders().firstOrNull { it.platformId == platform.id && it.kind == SCRIPT_KIND }
            ?.refs.orEmpty()
            .filter { it.enabled && pool.canResolve(it.id, platform) }
            .map { it.id }

    /**
     * 链上是否有能接管该平台的脚本。
     *
     * 与 [resolve] 的区别：这里**只判能力、不尝试**。链路遍历靠它决定
     * 「脚本这一环节要不要参与」，避免对没有脚本可用的平台白跑一轮。
     */
    fun canResolve(platform: MusicPlatform): Boolean = availableScriptIds(platform).isNotEmpty()

    /** 解析播放地址：按用户顺序逐个脚本尝试，首个成功即返回（含音质降档链 + 缓存） */
    suspend fun resolve(song: Song, quality: PlayQuality): ScriptResolvedUrl {
        val cacheKey = "${song.stableKey}@${quality.id}"
        cache.get(cacheKey)?.let {
            if (System.currentTimeMillis() - it.at < URL_TTL_MS) {
                return ScriptResolvedUrl(it.url, it.qualityId)
            }
        }

        val candidates = availableScriptIds(song.platform)
        if (candidates.isEmpty()) {
            throw ResolveException(
                "没有可用的音源脚本（未启用，或未声明支持 ${song.platform.label}）",
                song.platform,
            )
        }

        var lastError: Exception? = null
        candidates.forEach { scriptId ->
            val engine = pool.engineOf(scriptId) ?: return@forEach
            for (q in quality.chainFor(song.platform)) {
                try {
                    val url = requestMusicUrl(engine, song, q)
                    cache.put(cacheKey, CachedUrl(url, q, System.currentTimeMillis()))
                    return ScriptResolvedUrl(url, q)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 本档位失败 → 继续降档；降到最低档还失败 → 换下一个脚本
                    lastError = e
                }
            }
            AppLogger.w(TAG, "脚本解析失败，落到下一项：$scriptId（${lastError?.message}）")
        }
        throw ResolveException(lastError?.message ?: "脚本音源解析失败", song.platform)
    }

    /**
     * 只用**指定**脚本解析。
     *
     * 与 [resolve] 的区别：不走链、不兜底。配置页的「试听此项」用它 —— 用户排完顺序后
     * 最想知道的就是「这一项单独到底行不行」，混进兜底就看不出来了。
     */
    suspend fun resolveWithScript(
        scriptId: String,
        song: Song,
        quality: PlayQuality,
    ): ScriptResolvedUrl {
        val engine = pool.engineOf(scriptId)
            ?: throw ResolveException("该脚本当前未启用", song.platform)
        var lastError: Exception? = null
        for (q in quality.chainFor(song.platform)) {
            try {
                return ScriptResolvedUrl(requestMusicUrl(engine, song, q), q)
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

    private suspend fun requestMusicUrl(
        engine: ScriptEngine,
        song: Song,
        qualityId: String,
    ): String {
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
        const val TAG = "ScriptMusicResolver"
        const val URL_TTL_MS = 8 * 60_000L

        /** 与 [com.dpmusic.app.core.model.ScriptKind.SCRIPT] 的 id 保持一致 */
        const val SCRIPT_KIND = "script"
    }
}
