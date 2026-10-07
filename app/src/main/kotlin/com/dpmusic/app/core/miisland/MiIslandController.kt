package com.dpmusic.app.core.miisland

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.lyric.LyricsHub
import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.SongLyrics
import com.dpmusic.app.core.net.Http
import okhttp3.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 小米超级岛的数据准备：进程级单例，只负责「上岛要显示什么」。
 *
 * 与 [com.dpmusic.app.core.playback.MusicService] 的分工：
 * - 这里：当前歌词行、封面位图（带缓存，切歌才重新拉取）、模式同步；
 * - MusicService：通知构建 + 周期回写 + 断网魔法（它持有 Player 与通知 provider）。
 *
 * 之所以把歌词/封面抽出来：歌词要在切歌后异步拉取（网络），而通知构建是同步的；
 * 两者节奏不同，分开后通知侧永远只读现成状态，不会因为等歌词而阻塞。
 *
 * 歌词来源是全局歌词中心 [LyricsHub] —— 与车载歌词 / 桌面歌词共用同一份数据与缓存。
 * 「是否接入歌词中心」由 [AppContainer] 统一决策（三者任一启用即接入），此处不重复判断。
 */
object MiIslandController {

    private const val TAG = "MiIslandController"

    /** 与歌词显示层一致的提前量：让上岛歌词与 App 内歌词同步切换 */
    private const val LYRIC_LEAD_MS = 300L

    /** 封面内存缓存容量（张）。8 张足够覆盖「来回切几首」的场景，内存可控。 */
    private const val COVER_CACHE_MAX = 8

    /**
     * 封面位图的最大边长（px）。
     *
     * 两个硬约束：
     * 1. 通知 extras 走 Binder，**单个事务上限 1MB** —— 原图（1000×1000 RGB_565 ≈ 2MB）
     *    放进去会直接抛 `TransactionTooLargeException`，通知发送失败；
     * 2. 超级岛上的封面显示尺寸只有几十 dp，256px 已经足够清晰（还能省内存与流量）。
     */
    private const val COVER_MAX_PX = 256

    /** 通知 ID：与 Media3 DefaultMediaNotificationProvider 保持一致（super max） */
    const val NOTIFICATION_ID = 1001

    /**
     * 立即重发钩子，由 [com.dpmusic.app.core.playback.MusicService] 注册。
     *
     * 存在的理由：发布器按「歌曲 + 歌词行 + 播放态」内容键去重，而样式 / 配色 / 标题格式
     * 这类偏好的变更**不改变内容键** —— 不主动作废去重键的话，用户在设置页改完样式要等到
     * 下一行歌词才会生效。这里只做「请求」，具体重发由服务侧的发布器执行。
     */
    @Volatile
    var refreshHook: (() -> Unit)? = null

    /** 请求立即重发超级岛通知（偏好变更后调用；未注册钩子时静默忽略） */
    fun requestRefresh() {
        runCatching { refreshHook?.invoke() }
    }

    private var scope: CoroutineScope? = null

    /** 歌曲缓存（[lyrics] / [cover]）对应的曲目 key；读取时用它自校验 */
    @Volatile
    private var songKey: String? = null

    /**
     * 歌词与封面是**跨线程**读写的：IO 协程拉取、通知构建线程 / 主线程读取。
     * 不加 `@Volatile` 时，构建通知的线程可能长期读到旧引用（JMM 允许缓存）。
     */
    @Volatile
    private var lyrics: SongLyrics = SongLyrics.EMPTY

    @Volatile
    private var cover: Bitmap? = null

    /** 串行化 [onSongChanged] 的「比较 + 重置」，避免并发读取时重复发起拉取 */
    private val songLock = Any()

    /**
     * 封面内存缓存（key → 位图，LRU）。
     *
     * 切歌是高频操作（上一首 / 下一首 / 循环），每次重新下载会有几百毫秒的
     * 「岛上还是应用图标」空白期；缓存后回切能**同步**命中，直接出图。
     */
    private val coverCache = object : LinkedHashMap<String, Bitmap>(
        COVER_CACHE_MAX, 0.75f, true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > COVER_CACHE_MAX
    }

    /**
     * 进程启动时接入（幂等）：
     * - 接入全局歌词中心，让歌词随切歌自动加载（与车载 / 桌面歌词共用同一份数据）；
     * - 从 DataStore 同步一次模式到 SharedPreferences（通知侧同步读取）。
     */
    fun attach(context: Context) {
        val app = context.applicationContext
        if (scope == null) {
            MiIslandPrefs.init(app)
            val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = s
            s.launch {
                AppContainer.settings.settings.collect { settings ->
                    MiIslandPrefs.setMode(settings.miIslandMode)
                }
            }
        }
    }

    /** 当前曲目（取自播放连接层，进程内共享同一份状态）。 */
    fun currentSong(): Song? = AppContainer.player.nowPlaying.value?.song

    /**
     * 读取前自校验：当前曲目与缓存不一致时，立刻重置歌词 / 封面并异步拉取。
     *
     * ⚠️ 为什么必须有这一层，而不能只依赖 MusicService 的 `onMediaItemTransition`：
     * Media3 的 `Player.Listener` 按**注册顺序**回调，而 MusicService 的监听器在
     * `Service.onCreate` 注册，PlayerConnection 的要等 Activity 连接后才注册 ——
     * 于是切歌回调执行时 `nowPlaying` 仍是**上一首**，[onSongChanged] 因 key 相同
     * 直接 return，歌词 / 封面就永久停在旧歌上
     * （表现为「切歌后超级岛还是上一首的封面和歌词」）。
     *
     * 改成「读取时自校验」后，缓存刷新不再依赖任何回调时序 —— 谁来读、什么时候读都正确。
     */
    private fun ensureCurrentSong() {
        val song = currentSong()
        if (song?.stableKey == songKey) return
        onSongChanged(song)
    }

    /** 当前封面位图（可能为 null，构建时会回退到应用图标）。 */
    fun currentCover(): Bitmap? {
        ensureCurrentSong()
        return cover
    }

    /**
     * 当前应上岛的歌词行（已应用长度上限）。空字符串 = 无歌词 / 未就绪。
     *
     * @param positionMs 播放位置（由调用方提供，避免此处再读一次播放器）
     */
    fun currentLyricText(positionMs: Long): String {
        ensureCurrentSong()
        if (lyrics.isEmpty) return ""
        val index = lineIndexAt(lyrics.lines, positionMs + LYRIC_LEAD_MS)
        val text = lyrics.lines.getOrNull(index)?.text.orEmpty()
        if (text.isBlank()) return ""
        if (!MiIslandPrefs.isLyricLengthLimitEnabled()) return text
        val max = MiIslandPrefs.getMaxLyricLength()
        return if (text.length > max) text.take(max) else text
    }

    /**
     * 切歌时调用：重置缓存并按需异步拉取歌词与封面。
     *
     * 网络失败不抛出：最坏情况只是岛上没有歌词，通知本身不受影响。
     */
    fun onSongChanged(song: Song?) {
        val key = song?.stableKey
        // 「比较 + 重置」必须在锁内：本方法可能被通知构建线程（读取侧自校验）
        // 与 IO 协程并发调用，否则会重复发起拉取。
        synchronized(songLock) {
            if (key == songKey) return
            songKey = key
            lyrics = SongLyrics.EMPTY
            cover = null
        }
        if (song == null || key == null) return

        // 同步命中封面缓存：来回切歌时立刻出图，不出现「应用图标」空白期
        synchronized(coverCache) { coverCache[key] }?.let { hit ->
            synchronized(songLock) { if (songKey == key) cover = hit }
        }

        val s = scope
        if (s == null) {
            // 未 attach：直接同步命中全局歌词中心缓存，避免额外网络请求
            lyrics = cachedLyrics(key) ?: SongLyrics.EMPTY
            return
        }
        s.launch {
            val fetched = cachedLyrics(key) ?: runCatching {
                AppContainer.musicRepository.lyrics(song)
            }.getOrElse {
                Log.w(TAG, "歌词拉取失败: ${it.message}")
                SongLyrics.EMPTY
            }
            // 写入前再校验一次 key：期间可能又切歌了（慢网络下尤其明显）。
            // 过期结果必须丢弃，否则会出现「新歌配上一首的歌词」这种更隐蔽的错乱。
            var landed = false
            synchronized(songLock) {
                if (songKey == key) {
                    lyrics = fetched
                    landed = true
                }
            }

            val bmp: Bitmap? = synchronized(coverCache) { coverCache[key] }
                ?: song.coverUrl.takeIf { it.isNotBlank() }
                    ?.let { url -> downloadBitmap(url) }
                    ?.also { downloaded -> synchronized(coverCache) { coverCache[key] = downloaded } }
            synchronized(songLock) {
                if (songKey == key) {
                    cover = bmp
                    landed = true
                }
            }

            // 内容就绪 → 立刻回写一次，不等下一个 1s 周期。
            // 封面是网络下载（常见几百 ms），不主动重发的话，岛上会在这段时间里
            // 挂着应用图标（或更糟：上一首的封面）。
            if (landed) requestRefresh()
        }
    }

    /** 命中全局歌词中心缓存（当前曲且已加载） */
    private fun cachedLyrics(key: String): SongLyrics? =
        LyricsHub.lyrics.value.takeIf {
            it.isEmpty.not() && LyricsHub.currentKey.value == key
        }

    /** 二分查找当前歌词行（与 DesktopLyricView / 播放页同款手感） */
    private fun lineIndexAt(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /**
     * 下载并解码封面位图。
     *
     * ⚠️ 必须带 App 统一 UA（[Http.DEFAULT_UA]）：封面 CDN（QQ / 酷狗 / 网易等）会拒绝
     * Java 默认的 `Dalvik/...` UA，直接返回 403 —— 而 403 在过去是**静默**返回 null，
     * 表现为「超级岛一直没有封面 / 还挂着上一次的图」却查不到任何日志。
     *
     * 尺寸按 [COVER_MAX_PX] 降采样：通知 extras 走 Binder（单事务 1MB），原图会直接超限。
     */
    private fun downloadBitmap(url: String): Bitmap? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.DEFAULT_UA)
            .build()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "封面下载失败: HTTP ${resp.code}")
                return null
            }
            val bytes = resp.body?.bytes() ?: return null
            if (bytes.isEmpty()) return null
            // 先只读图片头部拿原始尺寸，再决定降采样倍率
            // （避免把整张原图解码进内存 —— 通知 extras 只有 1MB 事务预算）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val minSide = minOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (minSide / (sample * 2) >= COVER_MAX_PX) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "封面下载失败: ${e.message}")
        null
    }
}