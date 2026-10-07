package com.dpmusic.app.core.script

import android.util.LruCache
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.ScriptOrder
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
 * ## 从「单插件」到「按序尝试多个插件」
 * 与 [ScriptMusicResolver] 同构：持有 [PluginEnginePool]，按用户为该平台排的顺序
 * 依次尝试 —— 前一个失败自动落到下一个。插件是否「有意义」也由
 * 「已挂载 + 实现了 `getMediaSource` + 声明覆盖该平台」三者共同决定。
 *
 * 插件返回的请求头会登记进 [PlaybackHeaderStore]，由播放侧在发请求时注入。
 */
class MusicFreeResolver(
    private val pool: PluginEnginePool,
    /** 当前顺序配置（逐平台） */
    private val orders: () -> List<ScriptOrder>,
) {

    private data class CachedUrl(val url: String, val at: Long)

    private val cache = object : LruCache<String, CachedUrl>(64) {}

    /** 该平台当前**可参与解析**的插件 id（按用户顺序，已过滤未启用 / 不覆盖该平台者） */
    fun availablePluginIds(platform: MusicPlatform): List<String> =
        orders().firstOrNull { it.platformId == platform.id && it.kind == PLUGIN_KIND }
            ?.refs.orEmpty()
            .filter { it.enabled && pool.canResolve(it.id, platform) }
            .map { it.id }

    /** 链上是否有能接管该平台的插件（只判能力、不尝试） */
    fun canResolve(platform: MusicPlatform): Boolean = availablePluginIds(platform).isNotEmpty()

    /** 兼容旧调用点：不区分平台时，任意平台有可用插件即算「可解析」 */
    fun canResolve(): Boolean = MusicPlatform.entries.any { canResolve(it) }

    /**
     * 插件是否覆盖该平台 —— 用「链上是否有该插件的可用项」判定。
     *
     * 单个插件的覆盖判定在 [PluginEnginePool.canResolve] 内部（比平台名字符串）。
     */
    fun supportsPlatform(platform: MusicPlatform): Boolean = canResolve(platform)

    /** 链上插件的展示名（多个时取第一个可用的，用于提示文案） */
    fun pluginLabel(): String =
        MusicPlatform.entries.asSequence()
            .flatMap { availablePluginIds(it).asSequence() }
            .firstOrNull()
            ?.let { pool.labelOf(it) }
            .orEmpty()
            .ifBlank { "MusicFree 插件" }

    /** 解析播放地址：按用户顺序逐个插件尝试，首个成功即返回（音质逐级降档 + 8 分钟缓存） */
    suspend fun resolve(song: Song, quality: PlayQuality): ScriptResolvedUrl {
        val cacheKey = "${song.stableKey}@${quality.id}"
        cache.get(cacheKey)?.let {
            if (System.currentTimeMillis() - it.at < URL_TTL_MS) {
                return ScriptResolvedUrl(it.url, quality.id)
            }
        }
        val candidates = availablePluginIds(song.platform)
        if (candidates.isEmpty()) {
            throw ResolveException("没有可用的 MusicFree 插件（未启用或不覆盖 ${song.platform.label}）", song.platform)
        }

        var lastError: Exception? = null
        candidates.forEach { pluginId ->
            val engine = pool.engineOf(pluginId) ?: return@forEach
            for (mfQuality in qualityChain(quality)) {
                try {
                    val result = requestMediaSource(engine, song, mfQuality)
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
            AppLogger.w(TAG, "插件解析失败，落到下一项：$pluginId（${lastError?.message}）")
        }
        throw ResolveException(lastError?.message ?: "插件音源解析失败", song.platform)
    }

    /** 只用**指定**插件解析（配置页「试听此项」用；不走链、不兜底） */
    suspend fun resolveWithPlugin(
        pluginId: String,
        song: Song,
        quality: PlayQuality,
    ): ScriptResolvedUrl {
        val engine = pool.engineOf(pluginId)
            ?: throw ResolveException("该插件当前未启用", song.platform)
        var lastError: Exception? = null
        for (mfQuality in qualityChain(quality)) {
            try {
                val url = requestMediaSource(engine, song, mfQuality).first
                if (url.isNotBlank()) return ScriptResolvedUrl(url, quality.id)
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
        if (!canResolve(song.platform)) {
            return SourceTestResult(label, false, "无可用插件（未启用或不覆盖 ${song.platform.label}）", 0L)
        }
        return try {
            val resolved = resolve(song, PlayQuality.HIGH)
            SourceTestResult(
                label, true,
                "解析成功 · ${resolved.url.take(56)}…",
                System.currentTimeMillis() - started,
            )
        } catch (e: Exception) {
            SourceTestResult(label, false, e.message ?: "解析失败", System.currentTimeMillis() - started)
        }
    }

    /** 只用**指定**插件解析 —— 供配置页「试听此项」 */
    suspend fun testPlugin(pluginId: String, song: Song): SourceTestResult {
        val started = System.currentTimeMillis()
        val label = pool.labelOf(pluginId).ifBlank { "MusicFree 插件" }
        if (!pool.canResolve(pluginId, song.platform)) {
            return SourceTestResult(label, false, "未启用或不覆盖 ${song.platform.label}", 0L)
        }
        return try {
            val resolved = resolveWithPlugin(pluginId, song, PlayQuality.HIGH)
            SourceTestResult(
                label, true,
                "解析成功 · ${resolved.url.take(56)}…",
                System.currentTimeMillis() - started,
            )
        } catch (e: Exception) {
            SourceTestResult(label, false, e.message ?: "解析失败", System.currentTimeMillis() - started)
        }
    }

    /** 调用插件 getMediaSource；返回 (url, headers) */
    private suspend fun requestMediaSource(
        engine: PluginEngine,
        song: Song,
        mfQuality: String,
    ): Pair<String, Map<String, String>> {
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
            // MusicFree 插件档位只有 low/standard/high/super，无损及以上统一请求 super
            else -> "super"
        }
        val all = listOf("super", "high", "standard", "low")
        val start = all.indexOf(target).coerceAtLeast(0)
        return all.subList(start, all.size)
    }

    private companion object {
        const val METHOD_GET_MEDIA_SOURCE = "getMediaSource"
        const val URL_TTL_MS = 8 * 60_000L
        const val TAG = "MusicFreeResolver"

        /** 与 [com.dpmusic.app.core.model.ScriptKind.PLUGIN] 的 id 保持一致 */
        const val PLUGIN_KIND = "plugin"
    }
}