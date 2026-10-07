package com.dpmusic.app.core.util

import android.content.Context
import android.util.Log
import coil3.SingletonImageLoader
import com.dpmusic.app.core.lyric.LyricsHub

/**
 * 把本应用各处的**可降载缓存**登记到 [MemoryPressureCenter]。
 *
 * ## 为什么单独一个文件
 *
 * [MemoryPressureCenter] 是通用调度器，不该认识任何具体业务组件。
 * 这里做「谁在什么档位丢什么」的**唯一装配处**，好处：
 * 1. 一屏看清本应用到底有哪些可降载资源（原先散落各处，没人知道全貌）；
 * 2. 调整策略只改这一个文件，不必翻遍代码找「哪里持有大对象」；
 * 3. 单测可以直接断言「某档位触发了哪些降载器」。
 *
 * ## 各资源的档位依据
 *
 * | 资源 | 档位 | 代价 |
 * |---|---|---|
 * | Coil 图片**内存**缓存 | LIGHT | 下次显示要重新解码（网络缓存仍在，无需重新下载） |
 * | 歌词缓存（收缩到下限） | MODERATE | 回看久远歌词要重新联网 |
 * | 歌词缓存（只留当前曲） | CRITICAL | 仅当前曲可秒开，其余需重新拉 |
 *
 * **刻意不登记的东西**：
 * - **脚本 / 插件引擎实例**：它们是用户主动启用才驻留的，被清掉后下次解析
 *   要重新加载整个 JS 上下文（含网络），代价是「解析变慢」这类可感知的退化。
 *   内存紧张时清掉用户的音源配置，比占那几 MB 更糟。
 * - **ExoPlayer 音频缓冲**：Media3 的 `LoadControl` 只在构建时生效，
 *   **没有运行时裁剪的公开 API**。做不到的事不写。
 * - **当前播放曲目相关的一切**：任何降载都不能让正在播放的音乐中断。
 */
object MemoryPolicy {

    private const val TAG = "MemoryPolicy"

    /** 低内存时歌词缓存保留条数（够回看最近听过的，又不至于占内存） */
    private const val LYRIC_KEEP_ON_MODERATE = 40

    /** 极端内存时只保当前曲的歌词 */
    private const val LYRIC_KEEP_ON_CRITICAL = 1

    @Volatile
    private var assembled = false

    /**
     * 装配全部降载器（幂等）。由 [com.dpmusic.app.DPmusicApp] 在 `onCreate` 调用。
     *
     * 必须在 [MemoryPressureCenter.install] **之后**调用，否则注册表可能还没建立
     * 系统回调通道（顺序不影响注册表本身，但保持「先建通道再填内容」的直觉更清晰）。
     */
    fun assemble(context: Context) {
        if (assembled) return
        assembled = true
        val app = context.applicationContext

        // ① Coil 图片内存缓存：纯可再生（磁盘缓存仍在），最轻微的一档
        MemoryPressureCenter.register("coil-memory", MemoryPressureCenter.Level.LIGHT) {
            runCatching {
                SingletonImageLoader.get(app).memoryCache?.clear()
            }.onFailure { Log.w(TAG, "清理图片内存缓存失败：${it.message}") }
        }

        // ② 歌词缓存收缩：界面已不可见，保留最近听过的一批
        MemoryPressureCenter.register("lyrics", MemoryPressureCenter.Level.MODERATE) { level ->
            val keep = if (level.value >= MemoryPressureCenter.Level.CRITICAL.value) {
                LYRIC_KEEP_ON_CRITICAL
            } else {
                LYRIC_KEEP_ON_MODERATE
            }
            runCatching { LyricsHub.trimTo(keep) }
                .onFailure { Log.w(TAG, "收缩歌词缓存失败：${it.message}") }
        }

        Log.i(TAG, "内存降载器已装配：${MemoryPressureCenter.registeredCount} 项")
    }
}