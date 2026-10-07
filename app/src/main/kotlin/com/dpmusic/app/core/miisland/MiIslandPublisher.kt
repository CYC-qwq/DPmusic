package com.dpmusic.app.core.miisland

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 超级岛发布器：把「重建通知 → 断网魔法发送 → 恢复网络」串成一条稳定的流水线。
 *
 * 为什么必须有自己的刷新循环，而不能只依赖 Media3 的通知回调：
 * 歌词行变化时播放器状态（曲目、播放态、元数据）**并没有变化**，
 * Media3 不会重建通知，超级岛也就停在旧歌词上。因此必须由我们按歌词节奏主动回写。
 *
 * 周期取 1s：超级岛只关心「当前歌词行」（离散变化，通常 2-5s 一行），
 * 按行变化触发既够快也省电；若跟随播放位置（500ms）刷新，会在同一行内反复
 * 断网 → 发通知 → 恢复，得不偿失。
 */
class MiIslandPublisher(
    private val context: Context,
    /** 重建通知：复用 Media3 原生 provider 的构建路径（媒体按钮 / session token 一致） */
    private val rebuild: () -> Notification?,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 最近一次实际发出的内容键：歌曲 + 歌词行 + 播放态。用于去重，避免重复断网。 */
    private var lastPostedKey: String = ""

    /** 串行化「断网 → 发通知 → 恢复」：断网是全局副作用，不能并发。 */
    private val postMutex = Mutex()

    /** 最近一次魔法发送时刻（elapsedRealtime），用于 [onNotificationBuilt] 的最小间隔兜底 */
    @Volatile
    private var lastMagicAtMs: Long = 0L

    /** 释放资源（服务销毁时调用）。 */
    fun release() {
        job?.cancel()
        job = null
        scope.cancel()
    }

    /** 启动周期刷新（幂等）。 */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                runCatching { tick() }.onFailure {
                    Log.w(TAG, "刷新异常: ${it.message}")
                }
            }
        }
    }

    private suspend fun tick() {
        if (!MiIslandPrefs.usesLyricIsland()) return

        val song = MiIslandController.currentSong() ?: return
        val state = com.dpmusic.app.AppContainer.player.nowPlaying.value ?: return
        val lyric = MiIslandController.currentLyricText(state.positionMs)

        // 内容键：任一变化才值得发一次（一次发送 = 一次断网）
        val key = buildString {
            append(song.stableKey).append('\n')
            append(lyric).append('\n')
            append(state.isPlaying)
        }
        if (key == lastPostedKey) return

        val notification = rebuild() ?: run {
            Log.w(TAG, "重建通知失败，本轮跳过（不推进去重键）")
            return
        }
        // 只在真正发出后才推进去重键：rebuild 失败（如线程/Looper 异常）时不得吞掉本次变化，
        // 否则这一行歌词将永远不会再被尝试发送。
        if (post(notification)) lastPostedKey = key
    }

    /**
     * 发送通知：优先走断网魔法，不可用时降级为普通发送。
     *
     * 降级是刻意设计：Shizuku 未授权时功能仍应「能用」，只是没有超级岛外观，
     * 而不是整个通知栏失效。
     *
     * @return 是否成功发出（魔法路径与降级路径都算成功）
     */
    private suspend fun post(notification: Notification): Boolean =
        postMutex.withLock {
            if (MiSuperIslandMagic.isAvailable(context)) {
                var posted = false
                val ok = MiSuperIslandMagic.executeWithMagic(context) {
                    posted = notifyNow(notification)
                }
                if (ok && posted) {
                    lastMagicAtMs = SystemClock.elapsedRealtime()
                    Log.i(TAG, "已通过断网魔法发送超级岛通知")
                    return@withLock true
                }
                Log.w(TAG, "魔法路径未生效（magic=$ok posted=$posted），降级为普通发送")
                if (posted) return@withLock true
            }
            return@withLock notifyNow(notification)
        }

    /** 实际投递通知；返回是否未抛异常 */
    private fun notifyNow(notification: Notification): Boolean = runCatching {
        NotificationManagerCompat.from(context).notify(MiIslandController.NOTIFICATION_ID, notification)
    }.onFailure {
        Log.w(TAG, "通知发送失败: ${it.message}")
    }.isSuccess

    /** 强制下一次刷新重新发送（切歌 / 模式切换后调用） */
    fun invalidate() {
        lastPostedKey = ""
    }

    /**
     * 立即重发（设置页改样式 / 配色后调用）。
     *
     * 与 [invalidate] 的区别：那个只作废内容键，要等下一个 1s 周期才发；这里**立刻**跑一轮，
     * 让用户改完设置切回播放器就能看到新样式，而不用等一行歌词过去。
     * 仍走 [tick] 的「内容键 + 重建 + 魔法发送」完整路径，模式为关闭时自然空转。
     */
    fun refreshNow() {
        lastPostedKey = ""
        scope.launch { runCatching { tick() } }
    }

    /**
     * 由通知 Provider 在每次（含 Media3 自身触发的）通知构建后调用：立即用魔法重发。
     *
     * 为什么必须有这个入口：Media3 在播放 / 暂停 / 切歌时会**自己**重建并投递通知，
     * 这条投递路径不经过「断网魔法」，HyperOS 只会把它渲染成普通媒体岛，
     * 从而**覆盖掉**我们上一次魔法发送的超级岛。仅靠 1s 的歌词刷新循环去抢，
     * 中间会出现明显的「退回普通媒体岛」空窗。
     *
     * 这里刻意**不做内容键去重**：每次构建都是一条新的普通通知，都必须立刻用魔法重发，
     * 否则它就会覆盖掉超级岛。去重只在 [tick] 的歌词刷新路径上做。
     * 仅用 [MIN_MAGIC_INTERVAL_MS] 兜底，防止极端情况下（如快速切歌）断网风暴。
     *
     * 本方法**不阻塞**调用线程（Media3 回调线程）——魔法执行在协程内完成。
     */
    fun onNotificationBuilt(notification: Notification) {
        if (!MiIslandPrefs.usesLyricIsland()) return
        scope.launch {
            val now = SystemClock.elapsedRealtime()
            if (now - lastMagicAtMs < MIN_MAGIC_INTERVAL_MS) return@launch
            val key = contentKey()
            if (post(notification) && key != null) lastPostedKey = key
        }
    }

    /** 当前内容键（歌曲 + 歌词行 + 播放态）；无曲目时返回 null */
    private fun contentKey(): String? {
        val song = MiIslandController.currentSong() ?: return null
        val state = com.dpmusic.app.AppContainer.player.nowPlaying.value ?: return null
        return buildString {
            append(song.stableKey).append('\n')
            append(MiIslandController.currentLyricText(state.positionMs)).append('\n')
            append(state.isPlaying)
        }
    }

    private companion object {
        const val TAG = "MiIslandPublisher"
        const val REFRESH_INTERVAL_MS = 1_000L
        /** 两次魔法发送的最小间隔：防止 Media3 连续重建通知时反复断网 XMSF */
        const val MIN_MAGIC_INTERVAL_MS = 300L
    }
}