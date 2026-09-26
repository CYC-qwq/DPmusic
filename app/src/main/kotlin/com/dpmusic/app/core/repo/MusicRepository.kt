package com.dpmusic.app.core.repo

import com.dpmusic.app.core.model.CommentsPage
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SourcePriority
import com.dpmusic.app.core.net.LxResolver
import com.dpmusic.app.core.net.PlatformApi
import com.dpmusic.app.core.net.ResolveException
import com.dpmusic.app.core.net.ResolvedUrl
import com.dpmusic.app.core.script.MusicFreeResolver
import com.dpmusic.app.core.script.ScriptMusicResolver
import com.dpmusic.app.core.script.SourceTestResult
import com.dpmusic.app.core.util.AppLogger
import kotlinx.coroutines.CancellationException

/** 解析结果：实际生效的歌曲（可能已被跨平台替换）+ 播放地址 + 实际音质（降级后） */
data class ResolvedPlayback(
    val song: Song,
    val url: String,
    val quality: PlayQuality,
)

/**
 * 音乐仓库：聚合三平台 API 与 LX 解析层，向上暴露干净的统一能力。
 *
 * 播放容错链（核心「自动熔断降级」实现）：
 *   主平台解析（内部含音质降档）
 *     -> 失败则依次在另外两个平台按「歌名 + 歌手」搜索近似曲目
 *     -> 解析成功后返回替换后的歌曲信息（UI 侧提示已切换音源）
 */
class MusicRepository(
    private val apis: Map<MusicPlatform, PlatformApi>,
    private val resolver: LxResolver,
    private val scriptResolver: ScriptMusicResolver,
    private val priorityProvider: () -> SourcePriority = { SourcePriority.KEY_FIRST },
    /** MusicFree 插件解析器（可选）：Key 与脚本都失败时的最后一道音源兜底 */
    private val pluginResolver: MusicFreeResolver? = null,
) {

    fun api(platform: MusicPlatform): PlatformApi = apis.getValue(platform)

    suspend fun searchSongs(platform: MusicPlatform, keyword: String, page: Int = 1, limit: Int = 30): List<Song> =
        api(platform).searchSongs(keyword, page, limit)

    /** 搜索联想（搜索框输入预测）：失败静默返回空列表，不打扰搜索主流程 */
    suspend fun searchSuggest(platform: MusicPlatform, keyword: String): List<String> =
        runCatching { api(platform).searchSuggest(keyword) }.getOrElse { emptyList() }

    /** 热搜榜（搜索页空态展示）：失败静默返回空列表 */
    suspend fun hotSearch(platform: MusicPlatform): List<String> =
        runCatching { api(platform).hotSearch() }.getOrElse { emptyList() }

    /** 单曲详情（链接解析场景）：按平台内 ID 精确获取；无法获取时返回 null */
    suspend fun songDetail(platform: MusicPlatform, id: String): Song? = api(platform).songDetail(id)

    /** 批量歌曲详情（一起听房间歌单等场景） */
    suspend fun songsDetail(platform: MusicPlatform, ids: List<String>): List<Song> =
        api(platform).songsDetail(ids)

    suspend fun searchPlaylists(platform: MusicPlatform, keyword: String, page: Int = 1, limit: Int = 30) =
        api(platform).searchPlaylists(keyword, page, limit)

    suspend fun toplists(platform: MusicPlatform) = api(platform).toplists()

    suspend fun rankSongs(platform: MusicPlatform, rankId: String) = api(platform).rankSongs(rankId)

    suspend fun playlistSongs(platform: MusicPlatform, playlistId: String) = api(platform).playlistSongs(playlistId)

    /** 歌单元信息（链接导入场景；无法获取时返回 null） */
    suspend fun playlistMeta(platform: MusicPlatform, playlistId: String): PlaylistSummary? =
        api(platform).playlistMeta(playlistId)

    suspend fun lyrics(song: Song) = api(song.platform).lyrics(song)

    /** 歌曲评论（平台不支持时返回 null） */
    suspend fun comments(song: Song, page: Int = 1, limit: Int = 20): CommentsPage? =
        api(song.platform).comments(song.id, page, limit)

    /** 清空音源解析缓存（设置页缓存管理入口） */
    fun clearResolveCache() {
        resolver.clearCache()
        scriptResolver.clearCache()
    }

    /** 解析播放地址（脚本音源优先 + 远端代理回退 + 跨平台兜底） */
    suspend fun resolveForPlayback(
        song: Song,
        quality: PlayQuality,
        forceRefresh: Boolean = false,
    ): ResolvedPlayback {
        if (forceRefresh) {
            resolver.invalidate(song)
            scriptResolver.invalidate(song)
        }

        runCatching {
            val resolved = resolveWithFallback(song, quality)
            return ResolvedPlayback(song, resolved.url, PlayQuality.fromId(resolved.qualityId))
        }

        var lastError: Exception? = null
        for (platform in MusicPlatform.entries) {
            if (platform == song.platform) continue
            try {
                val alt = findAlternative(song, platform) ?: continue
                val resolved = resolveWithFallback(alt, quality)
                return ResolvedPlayback(alt, resolved.url, PlayQuality.fromId(resolved.qualityId))
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw ResolveException(lastError?.message ?: "所有音源均解析失败", song.platform)
    }

    /* ---------------- 音源可用性测试（供「音源管理」页逐项自检） ---------------- */

    /** 测试 Key 远端代理音源 */
    suspend fun testKeySource(song: Song, quality: PlayQuality = PlayQuality.HIGH): SourceTestResult {
        val started = System.currentTimeMillis()
        return try {
            val resolved = resolver.resolve(song, quality)
            SourceTestResult("音源 Key 代理", true, "解析成功 · ${resolved.url.take(56)}…", elapsed(started))
        } catch (e: Exception) {
            SourceTestResult("音源 Key 代理", false, e.message ?: "解析失败", elapsed(started))
        }
    }

    /** 测试 LX 自定义脚本音源 */
    suspend fun testScriptSource(song: Song, quality: PlayQuality = PlayQuality.HIGH): SourceTestResult {
        val started = System.currentTimeMillis()
        if (!scriptResolver.canResolve(song.platform)) {
            return SourceTestResult("LX 脚本音源", false, "未启用脚本或脚本不支持 ${song.platform.label}", 0L)
        }
        return try {
            val resolved = scriptResolver.resolve(song, quality)
            SourceTestResult("LX 脚本音源", true, "解析成功 · ${resolved.url.take(56)}…", elapsed(started))
        } catch (e: Exception) {
            SourceTestResult("LX 脚本音源", false, e.message ?: "解析失败", elapsed(started))
        }
    }

    /** 测试 MusicFree 插件音源 */
    suspend fun testPluginSource(song: Song): SourceTestResult =
        pluginResolver?.testResolve(song)
            ?: SourceTestResult("MusicFree 插件", false, "未导入或未启用插件", 0L)

    /** 测试某平台直连 API（搜索链路） */
    suspend fun testPlatformApi(platform: MusicPlatform, keyword: String = "晴天"): SourceTestResult {
        val started = System.currentTimeMillis()
        return try {
            val songs = api(platform).searchSongs(keyword, 1, 3)
            if (songs.isEmpty()) {
                SourceTestResult("${platform.label} API", false, "接口可用但未返回结果", elapsed(started))
            } else {
                SourceTestResult("${platform.label} API", true, "搜索到 ${songs.size} 首（如「${songs.first().title}」）", elapsed(started))
            }
        } catch (e: Exception) {
            SourceTestResult("${platform.label} API", false, e.message ?: "请求失败", elapsed(started))
        }
    }

    private fun elapsed(startedAt: Long): Long = System.currentTimeMillis() - startedAt

    /**
     * 单曲解析：先走「Key / 脚本」优先级链，两者都失败时再用 MusicFree 插件兜底。
     *
     * 插件放在最后是有意为之：插件是用户自行导入的第三方脚本，可靠性参差，
     * 让它只在官方链路救不回来时出手，既不拖慢常规解析，又能显著提高冷门歌曲的成功率。
     */
    private suspend fun resolveWithFallback(song: Song, quality: PlayQuality): ResolvedUrl {
        try {
            return resolveWithKeyOrScript(song, quality)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val plugin = pluginResolver
            if (plugin != null && plugin.canResolve()) {
                AppLogger.w(TAG, "Key / 脚本均失败，尝试插件音源：${e.message}")
                val resolved = plugin.resolve(song, quality)
                return ResolvedUrl(resolved.url, resolved.qualityId)
            }
            throw e
        }
    }

    /** 单曲解析：按「音源优先级」设置决定脚本与远端代理的先后与回退 */
    private suspend fun resolveWithKeyOrScript(song: Song, quality: PlayQuality): ResolvedUrl {
        val priority = priorityProvider()
        val scriptAvailable = scriptResolver.canResolve(song.platform)

        // 仅 Key：忽略脚本
        if (priority == SourcePriority.KEY_ONLY) {
            return resolver.resolve(song, quality)
        }
        // 仅脚本：只用脚本（不支持该平台时明确报错，不回退）
        if (priority == SourcePriority.SCRIPT_ONLY) {
            if (!scriptAvailable) {
                throw ResolveException("已设置「仅脚本」模式，但当前脚本不支持 ${song.platform.label}", song.platform)
            }
            val resolved = scriptResolver.resolve(song, quality)
            return ResolvedUrl(resolved.url, resolved.qualityId)
        }
        // 脚本不可用：直接走代理
        if (!scriptAvailable) {
            return resolver.resolve(song, quality)
        }
        // 脚本优先：脚本 → 代理回退
        if (priority == SourcePriority.SCRIPT_FIRST) {
            var scriptError: Exception? = null
            try {
                val resolved = scriptResolver.resolve(song, quality)
                return ResolvedUrl(resolved.url, resolved.qualityId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scriptError = e
                AppLogger.w(TAG, "脚本音源解析失败（${song.platform.label}）：${e.message}，回退代理")
            }
            try {
                return resolver.resolve(song, quality)
            } catch (e: Exception) {
                throw ResolveException("脚本音源解析失败：${scriptError.message}", song.platform)
            }
        }
        // Key 优先：代理 → 脚本回退
        var keyError: Exception? = null
        try {
            return resolver.resolve(song, quality)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            keyError = e
            AppLogger.w(TAG, "Key 音源解析失败（${song.platform.label}）：${e.message}，回退脚本")
        }
        try {
            val resolved = scriptResolver.resolve(song, quality)
            return ResolvedUrl(resolved.url, resolved.qualityId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ResolveException("Key 音源解析失败：${keyError.message}", song.platform)
        }
    }

    private suspend fun findAlternative(song: Song, platform: MusicPlatform): Song? {
        val keyword = "${song.title} ${song.artist}"
        val candidates = api(platform).searchSongs(keyword, 1, 8)
        return candidates.firstOrNull { similar(it, song) }
    }

    companion object {
        private const val TAG = "Music"

        private val NOISE = Regex("[\\s\\p{Punct}，。！？、：；“”‘’（）【】《》·—…]+")

        fun normalize(text: String): String = text.lowercase().replace(NOISE, "")

        /** 双字符组 Jaccard 相似度 */
        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.length < 2 || b.length < 2) return 0.0
            val aSet = a.windowed(2).toSet()
            val bSet = b.windowed(2).toSet()
            val union = aSet.union(bSet).size
            return if (union == 0) 0.0 else aSet.intersect(bSet).size.toDouble() / union
        }

        /** 判断两首歌是否近似同一首（用于跨平台兜底匹配） */
        fun similar(a: Song, b: Song): Boolean {
            val na = normalize(a.title)
            val nb = normalize(b.title)
            if (na.isEmpty() || nb.isEmpty()) return false
            val titleMatch = na.contains(nb) || nb.contains(na) || similarity(na, nb) > 0.72
            if (!titleMatch) return false

            val aa = normalize(a.artist)
            val ab = normalize(b.artist)
            if (aa.isEmpty() || ab.isEmpty()) return true
            return aa.contains(ab) || ab.contains(aa) || similarity(aa, ab) > 0.5
        }
    }
}