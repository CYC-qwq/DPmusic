package com.dpmusic.app.core.net

import android.util.LruCache
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.playback.PlaybackHeaderStore
import com.dpmusic.app.core.util.AppLogger

/**
 * 哔哩哔哩音源解析器（播放链路接入层，直连通道）。
 *
 * 与 [QishuiResolver] / [KgLiteResolver] 并列：把本应用的 [Song]（bvid）交给
 * [BiliApi.dash]，从 DASH 音频轨道里挑一条，取回真实播放地址。
 *
 * ## 关键约束：必须带 Referer
 * B 站的音频流（`*.bilivideo.com` / `*.mcdn.bilivideo.cn`）做**防盗链**校验：
 * 直接请求会返回 403，必须带 `Referer: https://www.bilibili.com/`。
 * 由于 Media3 的 `MediaItem` 无法携带请求头，这里把地址与头登记进
 * [PlaybackHeaderStore]，由播放侧在发请求前注入（与 MusicFree 插件同一机制）。
 *
 * ## 音质档位（**由账号会员态决定**）
 * - **匿名 / 非大会员**：只能取 AAC —— `30280` ≈192k / `30232` ≈132k / `30216` ≈64k
 * - **大会员 + 视频本身有该档**：可解锁 `dash.flac`（无损）/ `dash.dolby`（全景声）
 *
 * 因此「最高可用档位」不是固定的：由 [BiliRepository.isVip]（账号）与 `playurl` 实际返回共同决定。
 * 请求端一次要全部档位（`fnval=4048`），拿到什么用什么 —— 下游按请求档位挑选。
 *
 * ## 单P vs 多P
 * 搜索结果默认取第 1 个分P（`view` 的 `cid`）。多P视频的其它分P不在此通道展开
 * （B 站的「歌」多数是单P投稿）。
 */
class BiliResolver(
    /** 账号会员态提供者：true = 大会员（可解锁无损 / 全景声）。默认非会员（匿名） */
    private val vipProvider: () -> Boolean = { false },
    /**
     * 音源启用开关提供者：关闭后 [canResolve] 恒为 false，B 站曲目一律走「无法解析」，
     * 而不是继续静默联网取流。
     *
     * 声明为 `var` 是为了让 `AppContainer` 在**构造后**接线（与 `BiliApi.cookieProvider`
     * 同一做法）——这样 [MusicRepository] 的构造签名不必随开关增加而变动。
     */
    var enabledProvider: () -> Boolean = { true },
) {
    private data class CachedUrl(val url: String, val qualityId: String, val at: Long)

    private val cache = object : LruCache<String, CachedUrl>(64) {}
    /** 已启用 且 是非空 bvid 的 B 站曲目，才尝试解析 */
    fun canResolve(song: Song): Boolean =
        enabledProvider() && song.platform == MusicPlatform.BB && song.id.isNotBlank()

    /**
     * 解析播放地址。
     *
     * 流程：`view`（拿 aid）→ `dash`（拿音频轨）→ 按请求档位挑一条 → 登记 Referer。
     *
     * @throws ResolveException 取不到可播放地址（视频下架 / 区域限制 / 会员限制）
     */
    suspend fun resolve(song: Song, quality: PlayQuality): ResolvedUrl {
        if (song.platform != MusicPlatform.BB) {
            throw ResolveException("哔哩哔哩音源仅支持 B 站曲库", song.platform)
        }
        val cacheKey = "${song.stableKey}@${quality.id}"
        cache.get(cacheKey)?.let {
            if (System.currentTimeMillis() - it.at < URL_TTL_MS) return ResolvedUrl(it.url, it.qualityId)
        }

        val bvid = song.id
        // 搜索结果里通常已缓存 aid/cid；缺失时补一次 view
        val aid = song.extra["bb_aid"].orEmpty().ifBlank {
            BiliApi.view(bvid)?.str("aid").orEmpty()
        }.ifBlank {
            throw ResolveException("B 站视频信息缺失（可能已下架）", song.platform)
        }
        val cid = song.extra["bb_cid"].orEmpty().ifBlank {
            BiliApi.view(bvid)?.str("cid").orEmpty()
        }.ifBlank {
            throw ResolveException("B 站视频分P信息缺失", song.platform)
        }

        val dash = BiliApi.dash(aid, cid)
            ?: throw ResolveException("B 站未返回播放信息（可能为付费/受限视频）", song.platform)

        val pick = selectTrack(dash, quality)
            ?: throw ResolveException("B 站未返回可用的音频流", song.platform)

        // 关键：登记 Referer，否则播放时 403
        PlaybackHeaderStore.put(pick.url, mapOf("Referer" to REFERER, "User-Agent" to BiliApi.UA))

        AppLogger.d(TAG, "B 站解析成功：${song.title} 档=${pick.qualityId} itag=${pick.itag}")
        cache.put(cacheKey, CachedUrl(pick.url, pick.qualityId, System.currentTimeMillis()))
        return ResolvedUrl(pick.url, pick.qualityId)
    }

    /** 使某首歌的缓存失效（重试 / 换音质场景） */
    fun invalidate(song: Song) {
        PlayQuality.entries.forEach { q -> cache.remove("${song.stableKey}@${q.id}") }
    }

    fun clearCache() = cache.evictAll()

    /** 一条候选音频轨 */
    private data class AudioTrack(val url: String, val qualityId: String, val bandwidth: Long, val itag: Int)

    /**
     * 从 DASH 里挑音频轨。
     *
     * 优先级（**受账号会员态约束**）：
     * - 大会员：`flac`（无损）→ `dolby`（全景声）→ 按请求档位在 AAC 三档里选；
     * - 匿名 / 非大会员：**跳过** flac/dolby（服务端通常也不下发），只在 AAC 三档里选。
     *
     * 档位映射（对应 [com.dpmusic.app.core.model.qualityOrder] 的 BB 定义）：
     * - 请求 `320k`（HIGH）→ itag 30280
     * - 请求 `128k`（STANDARD）→ itag 30232，缺失则退 30216
     * - 更高档（无损 / 全景声）→ 直接取最高码率
     */
    private fun selectTrack(dash: kotlinx.serialization.json.JsonElement, quality: PlayQuality): AudioTrack? {
        // ① 无损 / 全景声：仅大会员可用（非会员即便服务端误下发也不采用，保证「按账号定档」）
        if (vipProvider()) {
            listOf("flac", "dolby").forEach { key ->
                val node = dash.objOrNull(key) ?: return@forEach
                val audio = node.objOrNull("audio") ?: node.arrOrNull("audio")?.firstOrNull()
                val url = audio?.str("baseUrl") ?: audio?.str("base_url")
                if (!url.isNullOrBlank()) {
                    val q = if (key == "flac") PlayQuality.LOSSLESS.id else PlayQuality.ATMOS.id
                    return AudioTrack(url, q, audio.long("bandwidth") ?: 0L, audio.int("id") ?: 0)
                }
            }
        }

        // ② 普通 AAC 轨：`audio[]`
        val tracks = dash.arrOrNull("audio")?.mapNotNull { a ->
            val url = a.str("baseUrl") ?: a.str("base_url") ?: return@mapNotNull null
            val id = a.int("id") ?: 0
            AudioTrack(url, itagToQuality(id), a.long("bandwidth") ?: 0L, id)
        }.orEmpty()
        if (tracks.isEmpty()) return null

        // 先按请求档位精确匹配 itag，再按「不低于请求档」的可用轨兜底，最后取最高码率
        return when (quality) {
            PlayQuality.STANDARD -> tracks.firstOrNull { it.itag == 30232 }
                ?: tracks.firstOrNull { it.itag == 30216 }
                ?: tracks.minByOrNull { it.bandwidth }
            PlayQuality.HIGH -> tracks.firstOrNull { it.itag == 30280 }
                ?: tracks.firstOrNull { it.itag == 30232 }
                ?: tracks.maxByOrNull { it.bandwidth }
            else -> tracks.maxByOrNull { it.bandwidth }
        }
    }

    /** B 站音频 itag → 本项目音质档 id（30280 对应 320k，30232/30216 归入 128k） */
    private fun itagToQuality(id: Int): String = when (id) {
        30280 -> PlayQuality.HIGH.id
        else -> PlayQuality.STANDARD.id
    }

    private companion object {
        const val TAG = "BiliResolver"
        const val REFERER = "https://www.bilibili.com/"

        /**
         * B 站 DASH 地址带 `deadline` 签名，实测有效期约 2 小时；
         * 本地缓存取 8 分钟（与其它通道一致，避免地址过期导致播放中断）。
         */
        const val URL_TTL_MS = 8 * 60_000L
    }
}