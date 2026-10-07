package com.dpmusic.app.core.net

import android.util.LruCache
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.util.AppLogger

/**
 * 酷狗概念版音源解析器（播放链路接入层，作为 Key/脚本/插件之外的**第四条通道**）。
 *
 * 与 [LxResolver] 并列：只负责把本应用的 [Song]（需要 `hash` + `album_id`/`audio_id`）
 * 交给 [KgLiteApi]，取回真实播放地址；带 8 分钟 LRU 缓存与并发去重。
 *
 * 仅对 [MusicPlatform.KG] 生效（概念版是酷狗曲库）。
 */
class KgLiteResolver(
    private val api: KgLiteApi,
    private val enabledProvider: () -> Boolean,
) {

    private data class CachedUrl(val url: String, val qualityId: String, val at: Long)

    private val cache = object : LruCache<String, CachedUrl>(64) {}

    fun canResolve(song: Song): Boolean = enabledProvider() && song.platform == MusicPlatform.KG

    /** 解析播放地址：命中缓存 → [KgLiteApi.playUrl]（内部含降档 + 试听兜底） */
    suspend fun resolve(song: Song, quality: PlayQuality): ResolvedUrl {
        if (song.platform != MusicPlatform.KG) {
            throw ResolveException("酷狗概念版音源仅支持酷狗曲库", song.platform)
        }
        val cacheKey = "${song.stableKey}@${quality.id}"
        cache.get(cacheKey)?.let {
            if (System.currentTimeMillis() - it.at < URL_TTL_MS) return ResolvedUrl(it.url, it.qualityId)
        }

        // ⚠️ 只认 `album_audio_id`（缺失就传 0）。历史版本存的是 `audio_id`（**错误字段**），
        //    传错的 non-zero 值会让免费歌也被判受限（实测 status=3 / 35104）；传 0 反而全曲可得。
        val albumId = song.extra["album_id"].orEmpty()
        val audioId = song.extra["album_audio_id"].orEmpty()
        val result = api.playUrl(song.id, albumId, audioId, quality)
            ?: throw ResolveException("酷狗概念版未取到可播放地址（可能受版权/VIP 限制）", song.platform)

        AppLogger.d(TAG, "概念版解析成功：${song.title} 档=${result.qualityId} 试听=${result.preview}")
        cache.put(cacheKey, CachedUrl(result.url, result.qualityId, System.currentTimeMillis()))
        return ResolvedUrl(result.url, result.qualityId)
    }

    /** 使某首歌的缓存失效（重试 / 换音质场景） */
    fun invalidate(song: Song) {
        PlayQuality.entries.forEach { q -> cache.remove("${song.stableKey}@${q.id}") }
    }

    fun clearCache() = cache.evictAll()

    private companion object {
        const val TAG = "KgLiteResolver"
        const val URL_TTL_MS = 8 * 60_000L
    }
}