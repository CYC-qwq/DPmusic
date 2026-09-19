package com.dpmusic.app.core.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dpmusic.app.core.net.Http
import com.dpmusic.app.core.playback.NowPlaying
import com.dpmusic.app.core.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 桌面播放控件推送器（进程级单例，由 AppContainer 装配）：
 * - 监听 [PlayerConnection.nowPlaying]（快照去重）→ 持久化 + 刷新全部小组件；
 * - 封面异步加载（OkHttp + 降采样，最长边 ≤ 256px）并缓存最近一张；
 * - 进程冷启动时由 Provider 调用 [refreshCoverIfNeeded] 恢复封面。
 */
class WidgetUpdater(
    private val context: Context,
    private val player: PlayerConnection,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastSnapshot: WidgetSnapshot? = null

    /** 当前期望的封面 URL（用于丢弃过期的异步加载结果） */
    private var desiredCoverUrl: String? = null
    private var coverJob: Job? = null

    init {
        scope.launch {
            player.nowPlaying.collect { np -> onState(np) }
        }
    }

    /** 冷启动恢复：按持久化快照重新应用封面（无缓存时异步拉取） */
    fun refreshCoverIfNeeded() {
        val snapshot = MusicWidgetProvider.readSnapshot(context)
        val url = snapshot.coverUrl
        if (url.isBlank()) return
        val cached = MusicWidgetProvider.cachedCover(url)
        if (cached != null) {
            MusicWidgetProvider.updateCover(context, cached)
            return
        }
        loadCover(url)
    }

    private fun onState(np: NowPlaying?) {
        val snapshot = WidgetSnapshot(
            title = np?.song?.title.orEmpty().ifBlank { "DPmusic" },
            artist = np?.song?.artist.orEmpty().ifBlank { "未在播放" },
            isPlaying = np?.isPlaying == true,
            coverUrl = np?.song?.coverUrl.orEmpty(),
        )
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot
        MusicWidgetProvider.persist(context, snapshot)
        MusicWidgetProvider.renderAll(context, snapshot)
        if (snapshot.coverUrl.isBlank()) {
            coverJob?.cancel()
        } else if (MusicWidgetProvider.cachedCover(snapshot.coverUrl) == null) {
            loadCover(snapshot.coverUrl)
        }
    }

    private fun loadCover(url: String) {
        coverJob?.cancel()
        desiredCoverUrl = url
        coverJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetchScaledCover(url) } ?: return@launch
            // 加载期间歌曲已切换：放弃过期封面
            if (desiredCoverUrl != url) return@launch
            MusicWidgetProvider.cacheCover(url, bitmap)
            MusicWidgetProvider.updateCover(context, bitmap)
        }
    }

    /** 拉取封面并降采样到最长边 ≤ 256px（先探测尺寸算采样率，再按需解码） */
    private fun fetchScaledCover(url: String): Bitmap? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DEFAULT_UA)
            .build()
        val bytes = Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()?.takeIf { it.isNotEmpty() }
        } ?: return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxSide <= 0) return@runCatching null
        var sample = 1
        while (maxSide / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return@runCatching null
        val side = maxOf(decoded.width, decoded.height)
        if (side <= MAX_SIDE) return@runCatching decoded
        val scale = MAX_SIDE.toFloat() / side
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != decoded) decoded.recycle()
        scaled
    }.getOrNull()

    private companion object {
        const val MAX_SIDE = 256
    }
}
