package com.dpmusic.app.core.net

import android.util.LruCache
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.QishuiRelayException
import com.dpmusic.app.core.model.QishuiRelayResult
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.util.AppLogger
import kotlinx.coroutines.CancellationException

/**
 * 汽水音乐音源解析器（播放链路接入层，作为 Key / 脚本 / 概念版 / 插件之外的**直连通道**）。
 *
 * 解析优先级：
 * 1. **relay 通道**（若用户在设置里配了中转地址 + 设备密钥）——
 *    由「家机上已登录的汽水客户端」解析，返回 `is_full_length=true` 的**完整歌**；
 *    若返回的是 CENC 加密流，则用 `play_auth` **本地解密到临时文件**后播放（协议 §3.4）；
 * 2. **匿名 `h5/seo_track` 通道**（免登录免签）——relay 未配置 / 家机离线 / 解析失败时回退；
 *    热门曲常只给 30s 试听片段。
 *
 * 仅对 [MusicPlatform.QS] 生效。
 */
class QishuiResolver(
    private val enabledProvider: () -> Boolean,
    /** relay 客户端；未配置时 [QishuiRelayApi.isConfigured] 为 false，自动跳过 relay */
    private val relay: QishuiRelayApi? = null,
    /** 加密流本地解密器；为空时遇到加密流只能降档/回退 */
    private val cencFetcher: SodaCencFetcher? = null,
    /**
     * 是否优先请求**真无损**（`lossless`，FLAC ~1004 kbps / 单曲 ~28 MB）。
     * 默认关（协议 §5「无损体积大，移动网络慎用」）。
     */
    private val losslessPreferredProvider: () -> Boolean = { false },
) {

    private data class CachedUrl(val url: String, val qualityId: String, val at: Long)

    private val cache = object : LruCache<String, CachedUrl>(64) {}

    fun canResolve(song: Song): Boolean =
        enabledProvider() && song.platform == MusicPlatform.QS && song.id.isNotBlank()

    /** relay 通道是否可用（设置里填了「地址 + 密钥」） */
    fun isRelayConfigured(): Boolean = relay?.isConfigured() == true

    /** 解析播放地址：relay 优先 → 匿名 `h5/seo_track` 回退；均带 8 分钟 LRU 缓存 */
    suspend fun resolve(song: Song, quality: PlayQuality): ResolvedUrl {
        if (song.platform != MusicPlatform.QS) {
            throw ResolveException("汽水音乐音源仅支持汽水曲库", song.platform)
        }
        val cacheKey = "${song.stableKey}@${quality.id}"
        cache.get(cacheKey)?.let {
            if (System.currentTimeMillis() - it.at < URL_TTL_MS) return ResolvedUrl(it.url, it.qualityId)
        }

        if (relay != null && relay.isConfigured()) {
            try {
                return resolveViaRelay(song, cacheKey, quality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: QishuiRelayException) {
                // 协议 §3.2：no_tier 建议「重试一次」；agent_unavailable 是家机离线
                if (e.code == QishuiRelayException.CODE_NO_TIER) {
                    AppLogger.w(TAG, "relay no_tier，重试一次")
                    try {
                        return resolveViaRelay(song, cacheKey, quality)
                    } catch (e2: CancellationException) {
                        throw e2
                    } catch (e2: Exception) {
                        AppLogger.w(TAG, "relay 重试仍失败：${e2.message}")
                    }
                } else {
                    AppLogger.w(TAG, "relay 取链失败（${e.code}），回退匿名通道：${e.message}")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "relay 取链异常，回退匿名通道：${e.message}")
            }
        }
        return resolveViaNative(song, cacheKey)
    }

    /**
     * relay 主路径。
     *
     * 硬校验：
     * 1. `is_full_length` 必须为 true（协议 §4）；
     * 2. 若返回的是 **CENC 加密流**（`encrypted=true`），必须用 `play_auth` 本地解密，
     *    **不能把加密 url 直接交给播放器**（会永久缓冲）。
     *
     * 档位：按用户的 [requested] 生成尝试顺序（见 [relayOrder]），并**以 relay 实际返回的
     * `quality` 回填 [ResolvedUrl.qualityId]** —— 这里过去写死 `320k`，导致 UI 永远显示
     * 「高品 320K」，看起来像音质被降级。
     */
    private suspend fun resolveViaRelay(
        song: Song,
        cacheKey: String,
        requested: PlayQuality,
    ): ResolvedUrl {
        for (q in relayOrder(requested)) {
            val r = relay!!.play(song.id, q)
            if (!r.isFullLength || r.url.isBlank()) {
                throw QishuiRelayException(
                    code = "not_full_length",
                    httpStatus = 200,
                    message = "relay 返回非完整曲目（is_full_length=${r.isFullLength}）",
                )
            }
            // 用 relay 实际返回的档位（可能低于请求档位，协议 §3.2），而不是写死值
            val qualityId = playQualityOf(r.quality).id

            // 加密流：本地解密到临时文件
            if (r.encrypted || r.playAuth.isNotBlank()) {
                val local = cencFetcher?.fetchDecrypted(r.url, r.playAuth, "${song.title}[${song.id}]")
                if (local != null) {
                    AppLogger.d(
                        TAG,
                        "汽水 relay 解密成功：${song.title}[${song.id}] 档=${r.quality}(${r.qualityLabel}) " +
                            "时长=${r.durationS}s 请求=$q",
                    )
                    val url = local.toURI().toString()
                    cache.put(cacheKey, CachedUrl(url, qualityId, System.currentTimeMillis()))
                    return ResolvedUrl(url, qualityId)
                }
                // 解密失败：继续降档试（不同档位编码/密钥不同，AAC 档可能更顺）
                AppLogger.w(TAG, "relay 加密流解密失败，降档重试：${song.title}[${song.id}] 档=${r.quality}")
                continue
            }

            AppLogger.d(
                TAG,
                "汽水 relay 解析成功（明文）：${song.title}[${song.id}] 档=${r.quality}(${r.qualityLabel}) " +
                    "codec=${r.codec} 时长=${r.durationS}s/${r.catalogueS}s cache=${r.cache} 请求=$q",
            )
            cache.put(cacheKey, CachedUrl(r.url, qualityId, System.currentTimeMillis()))
            return ResolvedUrl(r.url, qualityId)
        }
        throw QishuiRelayException(
            code = "decrypt_failed",
            httpStatus = 200,
            message = "relay 对《${song.title}》返回的加密流无法解密",
        )
    }

    /**
     * 按用户请求档位生成 relay 档位尝试顺序（从高到低）。
     *
     * - 请求**无损**（`flac`/`flac24bit`）或开了「无损优先」→ 先试 `lossless`，再逐级降；
     * - 请求**标准（128k）**→ 直接从 `medium` 起，不去要更大的体积；
     * - 其余（320k 及以上）→ 从 `hi_res` 起逐级降。
     */
    private fun relayOrder(requested: PlayQuality): List<String> {
        val wantsLossless = losslessPreferredProvider() ||
            requested == PlayQuality.LOSSLESS ||
            requested == PlayQuality.FLAC24
        if (wantsLossless) return listOf(RELAY_QUALITY_LOSSLESS) + RELAY_QUALITY_ORDER
        return when (requested) {
            PlayQuality.STANDARD -> listOf("medium")
            PlayQuality.HIGH -> listOf("highest", "higher", "medium")
            else -> RELAY_QUALITY_ORDER
        }
    }

    /** 供测试：把请求档位 → relay 尝试顺序 */
    internal fun relayOrderForTest(requested: PlayQuality): List<String> = relayOrder(requested)

    /** 供测试：relay 档位串 → 本地 [PlayQuality] */
    internal fun playQualityOfForTest(relayQuality: String): PlayQuality = playQualityOf(relayQuality)

    /** relay 返回的档位串 → 本地 [PlayQuality]（仅用于 UI 显示真实档位） */
    private fun playQualityOf(relayQuality: String): PlayQuality = when (relayQuality.lowercase()) {
        "lossless" -> PlayQuality.LOSSLESS
        "hi_res", "spatial", "highest" -> PlayQuality.HIRES
        "higher" -> PlayQuality.HIGH
        "medium" -> PlayQuality.STANDARD
        else -> PlayQuality.HIGH
    }

    /** 匿名回退通道：`h5/seo_track`（浏览器 UA，免签；VIP 曲目仅 30s 试听） */
    private suspend fun resolveViaNative(song: Song, cacheKey: String): ResolvedUrl {
        val result = QishuiApi.playUrl(song.id)
            ?: throw ResolveException("汽水未取到可播放地址（曲目不存在或已下架）", song.platform)

        // seo_track 的 video_list 顺序不保证，按码率取最高档；全为 0 时退回首条
        val best = result.qualities.maxByOrNull { it.bitrate }
        val url = best?.url?.takeIf { it.isNotBlank() }
            ?: result.url.takeIf { it.isNotBlank() }
            ?: result.backupUrl.takeIf { it.isNotBlank() }
            ?: throw ResolveException("汽水返回空播放地址", song.platform)

        val qualityId = PlayQuality.HIGH.id
        AppLogger.d(TAG, "汽水解析成功：${song.title} 档=${best?.quality ?: "?"} 码率=${best?.bitrate ?: 0}")
        cache.put(cacheKey, CachedUrl(url, qualityId, System.currentTimeMillis()))
        return ResolvedUrl(url, qualityId)
    }

    /** 使某首歌的缓存失效（重试 / 换音质场景） */
    fun invalidate(song: Song) {
        PlayQuality.entries.forEach { q -> cache.remove("${song.stableKey}@${q.id}") }
    }

    fun clearCache() = cache.evictAll()

    private companion object {
        const val TAG = "QishuiResolver"

        /** seo_track 地址 `expire_at` 约 24h；本地缓存取 8 分钟（与其它通道一致，避免地址过期） */
        const val URL_TTL_MS = 8 * 60_000L

        /**
         * relay 档位尝试顺序（从高到低）。
         *
         * 协议 v2：六档全部 CENC 加密、**都能本地解密**，所以这里按用户偏好「从高到低」
         * 尝试，解密成功即采用（不再因加密而降档）。
         *
         * ⚠️ **`lossless` 不放在默认顺序里**：它单曲 ~28 MB（FLAC ~1004 kbps），
         * 属 opt-in。由调用方（设置里的「无损优先」开关）显式请求。
         */
        val RELAY_QUALITY_ORDER = listOf("hi_res", "highest", "higher", "medium")

        /** 真无损档（协议 v2，opt-in；frma 为 `fLaC`） */
        const val RELAY_QUALITY_LOSSLESS = "lossless"
    }
}