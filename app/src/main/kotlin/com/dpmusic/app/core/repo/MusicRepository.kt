package com.dpmusic.app.core.repo

import com.dpmusic.app.core.model.CommentsPage
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.PlaylistSummary
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SourceChain
import com.dpmusic.app.core.model.SourceEngine
import com.dpmusic.app.core.model.SourcePriority
import com.dpmusic.app.core.model.SongLyrics
import com.dpmusic.app.core.net.KgLiteResolver
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
 * 某个链路引擎**当前不可用**（而非「尝试了但失败」）。
 *
 * 典型场景：没配 Key、没导入插件、脚本不支持该平台、非酷狗曲目遇到概念版。
 * 链路遍历时遇到它应**静默跳过**，不算一次失败 —— 否则用户会看到一串
 * 「Key 解析失败」的误导日志，其实只是他没配 Key。
 */
class EngineUnavailable(val engine: SourceEngine) :
    Exception("未配置或当前平台不支持：${engine.label}")

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
    /**
     * 逐平台解析链路提供者（见 [SourceChain]）—— 用户可排序 / 逐项启停。
     * 默认空列表：此时 [resolveWithFallback] 会得到空链路并明确报错，
     * 提醒调用方必须注入真实的链路（AppContainer 负责）。
     */
    private val chainProvider: () -> List<SourceChain> = { emptyList() },
    /** 「全部引擎失败 → 跨平台找同名曲」总开关 */
    private val crossPlatformFallbackProvider: () -> Boolean = { true },
    /**
     * 当前**已启用**的平台提供者（见 `AppSettings.enabledPlatforms`）。
     *
     * 跨平台兜底（播放地址与歌词）只在这些平台里找同名曲 —— 用户关掉的音源
     * 不应「以兜底之名」被偷偷使用。
     * 默认返回空列表，视作「不限制」，与注入前的行为一致。
     */
    private val enabledPlatformsProvider: () -> List<MusicPlatform> = { emptyList() },
    /** MusicFree 插件解析器（可选）：插件音源通道，位置由链路决定 */
    private val pluginResolver: MusicFreeResolver? = null,
    /** 酷狗概念版解析器（可选）：仅酷狗可用的免费全曲通道，位置由链路决定 */
    private val kgLiteResolver: KgLiteResolver? = null,
    /**
     * 汽水音乐解析器（可选）：**直连**通道（`h5/seo_track`，匿名免签）。
     * 仅对 [MusicPlatform.QS] 生效，且只在主链路失败时兜底；汽水曲目自身走这里即可成功。
     */
    private val qishuiResolver: com.dpmusic.app.core.net.QishuiResolver? = null,
    /**
     * 哔哩哔哩解析器（可选）：**直连**通道（DASH 音频流，匿名）。
     * 仅对 [MusicPlatform.BB] 生效 —— B 站曲目只有这一条通道能播。
     */
    private val biliResolver: com.dpmusic.app.core.net.BiliResolver? = null,
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

    /**
     * 取歌词：本平台优先，为空时**跨平台兜底**。
     *
     * 为什么需要兜底：各平台歌词库覆盖并不一致。实测网易云对部分曲目
     * （新上架 / 日本版权区 / 未收藏）会返回空歌词（`lrc.lyric` 为空串、
     * 带 `uncollected:true`），而同一首歌在 QQ 音乐**有完整歌词**。
     * 用户要的是歌词本身，不该因为「这首歌恰好来自网易云」就显示「暂无歌词」。
     *
     * 兜底规则（与播放地址的跨平台兜底保持同一套约束）：
     * 1. **只在已启用平台里找** —— 用户关掉的音源不参与；
     * 2. **必须通过 [similar] 校验**（歌名 + 歌手）—— 宁可没有，也不要错配的歌词；
     * 3. 命中后把来源平台写进 [SongLyrics.sourcePlatform]，UI 显示来源提示。
     *
     * @return 歌词；本平台与兜底均无结果时返回 [SongLyrics.EMPTY]。
     */
    suspend fun lyrics(song: Song): SongLyrics {
        val local = runCatching { api(song.platform).lyrics(song) }.getOrNull()
        if (local != null && !local.isEmpty) return local
        return lyricsFromOtherPlatforms(song) ?: SongLyrics.EMPTY
    }

    /**
     * 跨平台歌词兜底：按平台顺序找一个「同名曲」，取它的歌词。
     *
     * 平台顺序刻意固定为 [MusicPlatform.QQ] → [MusicPlatform.KG] → [MusicPlatform.WY]：
     * 实测 QQ 对中文/日文曲目的歌词覆盖最全（本次抽样的 6 首全部命中），
     * 酷狗的歌词下载接口对多数曲目返回空 content，故排在其后。
     *
     * 为什么不并发：兜底是低频路径（仅本平台无歌词时触发），
     * 串行在命中第一个平台后就返回，反而比并发更快也更省流量。
     */
    private suspend fun lyricsFromOtherPlatforms(song: Song): SongLyrics? {
        val enabled = enabledPlatformsProvider()
        val candidates = LYRICS_FALLBACK_ORDER.filter {
            it != song.platform && (enabled.isEmpty() || it in enabled)
        }
        for (platform in candidates) {
            val fetched = runCatching { lyricsFrom(song, platform) }.getOrNull() ?: continue
            if (!fetched.isEmpty) return fetched
        }
        return null
    }

    /** 在指定平台按「歌名 + 歌手」找同名曲并取其歌词；无匹配 / 无歌词时返回 null */
    private suspend fun lyricsFrom(song: Song, platform: MusicPlatform): SongLyrics? {
        val alt = findAlternative(song, platform) ?: return null
        val lyrics = api(platform).lyrics(alt)
        if (lyrics.isEmpty) return null
        // 标注来源：UI 据此显示「歌词来自 XX」，不冒充原平台
        return lyrics.copy(sourcePlatform = platform)
    }

    /** 歌曲评论（平台不支持时返回 null） */
    suspend fun comments(song: Song, page: Int = 1, limit: Int = 20): CommentsPage? =
        api(song.platform).comments(song.id, page, limit)

    /** 清空音源解析缓存（设置页缓存管理入口） */
    fun clearResolveCache() {
        resolver.clearCache()
        scriptResolver.clearCache()
        kgLiteResolver?.clearCache()
        qishuiResolver?.clearCache()
        biliResolver?.clearCache()
    }

    /** 解析播放地址：按平台链路（用户自定义顺序）依次尝试 + 可选跨平台兜底 */
    suspend fun resolveForPlayback(
        song: Song,
        quality: PlayQuality,
        forceRefresh: Boolean = false,
    ): ResolvedPlayback {
        if (forceRefresh) {
            resolver.invalidate(song)
            scriptResolver.invalidate(song)
            kgLiteResolver?.invalidate(song)
            qishuiResolver?.invalidate(song)
            biliResolver?.invalidate(song)
        }

        val allowCrossPlatform = crossPlatformFallbackProvider()

        runCatching {
            val resolved = resolveWithFallback(song, quality, allowCrossPlatform)
            return ResolvedPlayback(song, resolved.url, PlayQuality.fromId(resolved.qualityId))
        }

        // 跨平台兜底：换到别的平台找同名曲。用户可关（关掉后失败就直接报错）。
        if (!allowCrossPlatform) {
            throw ResolveException("已关闭跨平台兜底，且本平台音源均解析失败", song.platform)
        }

        var lastError: Exception? = null
        // 只在**已启用**的平台里兜底：用户关掉的音源不参与替换（空列表视作不限制）
        val enabled = enabledPlatformsProvider().toSet()
        val candidates = MusicPlatform.entries.filter { enabled.isEmpty() || it in enabled }
        for (platform in candidates) {
            if (platform == song.platform) continue
            try {
                val alt = findAlternative(song, platform) ?: continue
                val resolved = resolveWithFallback(alt, quality, allowCrossPlatform)
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

    /** 测试酷狗概念版音源（对指定酷狗歌曲跑一次真实解析；需开启且为酷狗平台） */
    suspend fun testKgLiteSource(song: Song): SourceTestResult {
        val started = System.currentTimeMillis()
        val resolver = kgLiteResolver
        if (resolver == null || !resolver.canResolve(song)) {
            return SourceTestResult("酷狗概念版", false, "未启用或非酷狗曲库歌曲", 0L)
        }
        return try {
            val resolved = resolver.resolve(song, PlayQuality.STANDARD)
            SourceTestResult("酷狗概念版", true, "解析成功 · ${resolved.url.take(56)}…", elapsed(started))
        } catch (e: Exception) {
            SourceTestResult("酷狗概念版", false, e.message ?: "解析失败", elapsed(started))
        }
    }

    /** 测试汽水音乐音源（对指定汽水曲目跑一次真实解析；需开启且为汽水平台） */
    suspend fun testQishuiSource(song: Song): SourceTestResult {
        val started = System.currentTimeMillis()
        val resolver = qishuiResolver
        if (resolver == null || !resolver.canResolve(song)) {
            return SourceTestResult("汽水音乐直连", false, "未启用或非汽水曲库歌曲", 0L)
        }
        return try {
            val resolved = resolver.resolve(song, PlayQuality.HIGH)
            SourceTestResult("汽水音乐直连", true, "解析成功 · ${resolved.url.take(56)}…", elapsed(started))
        } catch (e: Exception) {
            SourceTestResult("汽水音乐直连", false, e.message ?: "解析失败", elapsed(started))
        }
    }

    /** 测试哔哩哔哩音源（对指定 B 站曲目跑一次真实解析） */
    suspend fun testBiliSource(song: Song): SourceTestResult {
        val started = System.currentTimeMillis()
        val resolver = biliResolver
        if (resolver == null || !resolver.canResolve(song)) {
            return SourceTestResult("哔哩哔哩直连", false, "非 B 站曲库歌曲", 0L)
        }
        return try {
            val resolved = resolver.resolve(song, PlayQuality.HIGH)
            SourceTestResult("哔哩哔哩直连", true, "解析成功 · ${resolved.url.take(56)}…", elapsed(started))
        } catch (e: Exception) {
            SourceTestResult("哔哩哔哩直连", false, e.message ?: "解析失败", elapsed(started))
        }
    }

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
     * 单曲解析：**按该平台的用户自定义链路依次尝试各引擎**，全失败时可选跨平台兜底。
     *
     * 链路（[SourceChain]）由用户在「音源管理 → 解析链路」里逐平台配置：顺序即优先级，
     * 每项可单独启停。某项「不可用」（例如脚本不支持该平台、未导入插件、非酷狗曲目遇到
     * 概念版）会被**静默跳过**，不计为失败；真正抛错的才记日志落到下一项。
     *
     * @param allowCrossPlatform 是否允许「全部引擎都失败」时跨平台兜底。
     *   由调用方（[resolveForPlayback]）按用户的总开关传入 —— 跨平台替换会让播放的
     *   曲目**变成另一个平台的同名曲**，属于用户明确可见的行为变化，必须可控。
     */
    private suspend fun resolveWithFallback(
        song: Song,
        quality: PlayQuality,
        allowCrossPlatform: Boolean,
    ): ResolvedUrl {
        // 汽水直连：汽水曲目只可能走此通道（且需开关开启），命中即可直接返回。
        // ⚠️ 不放在链路里 —— 汽水没有可排序的引擎，也没有 Key/脚本/概念版通道。
        val qishui = qishuiResolver?.takeIf { it.canResolve(song) }
        if (qishui != null) {
            try {
                return qishui.resolve(song, quality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "汽水直连解析失败：${e.message}")
                // 汽水曲目在其他链路必然也失败，直接抛错（不浪费一轮无意义的重试）
                if (!allowCrossPlatform) throw e
            }
        }

        // B 站直连：同理，B 站曲目只有 DASH 音频这一条通道。
        val bili = biliResolver?.takeIf { it.canResolve(song) }
        if (bili != null) {
            try {
                return bili.resolve(song, quality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "B 站直连解析失败：${e.message}")
                if (!allowCrossPlatform) throw e
            }
        }

        val chain = chainProvider().firstOrNull { it.platformId == song.platform.id }
        val engines = chain?.activeEngines().orEmpty()

        var lastError: Exception? = null
        for (engine in engines) {
            try {
                return resolveWithEngine(engine, song, quality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineUnavailable) {
                // 该引擎当前不可用（未配置 / 不支持该平台）→ 静默跳过，不算失败
                AppLogger.w(TAG, "${engine.label} 不可用，跳过：${e.message}")
            } catch (e: Exception) {
                lastError = e
                AppLogger.w(TAG, "${engine.label} 解析失败（${song.platform.label}）：${e.message}")
            }
        }

        throw lastError ?: ResolveException(
            if (engines.isEmpty()) {
                "该平台未启用任何音源，请到「音源管理 → 解析链路」开启至少一项"
            } else {
                "已启用的音源均解析失败"
            },
            song.platform,
        )
    }

    /** 该引擎当前是否**有能力**参与解析（不抛错，只做能力判定）。 */
    private fun isEngineAvailable(engine: SourceEngine, song: Song): Boolean = when (engine) {
        SourceEngine.KEY -> resolver.isConfigured()
        SourceEngine.SCRIPT -> scriptResolver.canResolve(song.platform)
        SourceEngine.PLUGIN -> {
            val plugin = pluginResolver
            plugin != null && plugin.supportsPlatform(song.platform) && plugin.canResolve()
        }
        SourceEngine.KGLITE -> kgLiteResolver?.canResolve(song) == true
    }

    /**
     * 在**配置好的引擎**上解析一首歌 —— 配置页的「试听此项」用。
     *
     * 与播放链路的区别：这里**只试这一个引擎、不兜底**，用户改完顺序后点一下
     * 就能知道「这条链上的这一项到底行不行」。
     */
    suspend fun resolveWithSingleEngine(
        engine: SourceEngine,
        song: Song,
        quality: PlayQuality = PlayQuality.HIGH,
    ): ResolvedUrl = resolveWithEngine(engine, song, quality)

    /**
     * 路由到具体引擎。
     *
     * @throws EngineUnavailable 该引擎当前不可用（未配置 Key / 未导入插件 / 非酷狗曲目
     *   遇到概念版）—— 调用方据此**静默跳过**，而不是当作解析失败去报错。
     */
    private suspend fun resolveWithEngine(
        engine: SourceEngine,
        song: Song,
        quality: PlayQuality,
    ): ResolvedUrl {
if (!isEngineAvailable(engine, song)) throw EngineUnavailable(engine)
        return when (engine) {
            SourceEngine.KEY -> resolver.resolve(song, quality)
            SourceEngine.SCRIPT -> scriptResolver.resolve(song, quality)
                .let { ResolvedUrl(it.url, it.qualityId) }
            SourceEngine.PLUGIN -> pluginResolver!!.resolve(song, quality)
                .let { ResolvedUrl(it.url, it.qualityId) }
            SourceEngine.KGLITE -> kgLiteResolver!!.resolve(song, quality)
        }
    }
    /* ---------------- 单 JS 试听（配置页「试听此项」用） ---------------- */

    /**
     * 只用**指定脚本**解析（不走链、不兜底）。
     *
     * @throws ResolveException 未启用 / 解析失败
     */
    suspend fun resolveWithScript(
        scriptId: String,
        song: Song,
        quality: PlayQuality = PlayQuality.HIGH,
    ): ResolvedUrl = scriptResolver.resolveWithScript(scriptId, song, quality)
        .let { ResolvedUrl(it.url, it.qualityId) }

    /** 只用**指定插件**测试解析（不走链、不兜底） */
    suspend fun testPluginSource(pluginId: String, song: Song): SourceTestResult =
        pluginResolver?.testPlugin(pluginId, song)
            ?: SourceTestResult("MusicFree 插件", false, "未启用插件池", 0L)

    /** 供 UI 展示「这条链路实际会怎么走」 */
    fun describeChain(platform: MusicPlatform, song: Song? = null): List<Pair<SourceEngine, Boolean>> {
        val chain = chainProvider().firstOrNull { it.platformId == platform.id } ?: return emptyList()
        return chain.order.map { engine ->
            val configured = chain.isEnabled(engine) && engine.supports(platform) &&
                (song == null || isEngineAvailable(engine, song))
            engine to configured
        }
    }

    /* ---------------- 旧「全局优先级」链路（已被逐平台链路取代） ---------------- */
    // 原 resolveWithKeyOrScript 已删除：其「Key / 脚本 二选一 + 回退」的语义
    // 现在由 SourceChain 表达（同一平台可排多引擎、逐项启停）。
    // 旧的 source_priority 设置仍保留，仅作为首次生成链路的迁移依据。

    private suspend fun findAlternative(song: Song, platform: MusicPlatform): Song? {
        val keyword = "${song.title} ${song.artist}"
        val candidates = api(platform).searchSongs(keyword, 1, 8)
        return candidates.firstOrNull { similar(it, song) }
    }

    companion object {
        private const val TAG = "Music"

        /**
         * 歌词跨平台兜底的平台尝试顺序。
         *
         * **只放 QQ**，理由来自实测（2026-10）：
         * - QQ：6/6 命中（含网易云拿不到的日文 OP），且返回带 `[ti:][ar:][al:]`
         *   与假名注音，两次复测结果一致；
         * - 酷狗：`krcs.search` 能搜到候选，但 `lyrics.kugou.com/download`
         *   对匿名请求一律返回空 content（实测 0/6），留在链里只会白白多跑
         *   2-8 个请求；
         * - 汽水 / B 站：前者歌词只随 `seo_track` 单曲详情下发（无按名搜索通道），
         *   后者本身不提供 LRC 歌词。
         *
         * 都不命中时直接返回空（UI 显示「暂无歌词」），不做无谓的继续尝试。
         */
        private val LYRICS_FALLBACK_ORDER = listOf(MusicPlatform.QQ)

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
            // 歌名归一化后为空 = 原名几乎全是符号（如 `$・¥・€`、`!!!`）。
            // 此时不能用相似度（空串与任何串都不相似，会把同一首歌判成不同），
            // 退化为「原始歌名完全相同」判定 —— 仍要求歌名逐字一致，不会误配。
            if (na.isEmpty() || nb.isEmpty()) {
                if (a.title.trim() != b.title.trim()) return false
            } else {
                val titleMatch = na.contains(nb) || nb.contains(na) || similarity(na, nb) > 0.72
                if (!titleMatch) return false
            }

            val aa = normalize(a.artist)
            val ab = normalize(b.artist)
            if (aa.isEmpty() || ab.isEmpty()) return true
            return aa.contains(ab) || ab.contains(aa) || similarity(aa, ab) > 0.5
        }
    }
}