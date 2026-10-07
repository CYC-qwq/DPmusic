package com.dpmusic.app.core.util

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Debug
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程内内存压力响应中心。
 *
 * ## 解决什么问题
 *
 * 改动前，本应用对系统内存压力**完全无感**：全项目没有任何 `onTrimMemory` /
 * `onLowMemory` 响应。系统反复告知「内存紧张」，应用仍抱着已播放歌曲的歌词、
 * 封面位图、脚本引擎不放，直到被系统直接杀死进程 —— 表现为**后台播放中断**。
 *
 * 这里把人人都能省的资源收敛到一处，按系统档位分级释放。
 *
 * ## 设计：注册表 + 分级调度
 *
 * 各组件把自己的降载动作注册进来（[register]），中心只负责「按档位调度」。
 * 好处：
 * - **可单测**：注册一个假的降载器，驱动 [handle] 即可断言「该档是否被调用」，
 *   不依赖系统的回调投递，也不需要真实内存紧张；
 * - **可扩展**：新增可降载资源时不必改本文件；
 * - **不越权**：中心不认识任何具体组件，组件自己决定「我这档该丢什么」。
 *
 * ## 只做「解除引用」，不做 `System.gc()`
 *
 * 主动 GC 会把 CPU 花在「回收本就该回收的对象」上并引发卡顿。真正有效的是
 * 丢弃自己持有的**强引用**，让下次 GC 自然回收。
 *
 * ## 同档去重
 *
 * 系统会重复投递同一档（`RUNNING_LOW` 常连续来几次）。用 [reachedLevel] 记录
 * 已达过的最高档，**同档不重复执行**，避免无谓抖动；压力解除后 [reset] 允许再次响应。
 *
 * ## 明确不做的事
 *
 * 不释放音频缓冲 —— Media3 的 `LoadControl` 只在 ExoPlayer 构建时生效，
 * **没有运行时裁剪已缓冲数据的公开 API**。宣称能释放它是做不到的事。
 */
object MemoryPressureCenter {

    private const val TAG = "MemPressure"

    /** 压力档位（升序：数值越大，参与降载的组件越多） */
    enum class Level(val value: Int) {
        NONE(0),
        LIGHT(1),
        MODERATE(2),
        SEVERE(3),
        CRITICAL(4),
    }

    /**
     * 一个可降载资源。
     *
     * @param name 诊断名（日志 / 设置页展示）
     * @param level 从该档起参与降载；更高档会重复调用（允许逐级加码）
     * @param trim 执行降载。会在后台线程调用，实现里不必自行切线程。
     */
    fun interface Trimmer {
        fun trim(level: Level)
    }

    private class Entry(val name: String, val level: Level, val action: Trimmer)

    private val entries = CopyOnWriteArrayList<Entry>()

    private val reachedLevel = AtomicInteger(Level.NONE.value)
    private val trimCount = AtomicInteger(0)

    @Volatile
    private var appContext: Context? = null

    /** 本进程已达过的最高档（[Level.value]，0 = 未响应过） */
    val lastLevel: Int get() = reachedLevel.get()

    /** 累计响应次数 */
    val totalTrims: Int get() = trimCount.get()

    /** 已注册的降载器数量（供诊断） */
    val registeredCount: Int get() = entries.size

    /**
     * 注册一个降载器（幂等：同名只保留一个）。
     *
     * 由各组件在初始化时调用，例如歌词中心注册「收缩缓存」。
     */
    fun register(name: String, level: Level, action: Trimmer) {
        entries.removeAll { it.name == name }
        entries += Entry(name, level, action)
    }

    fun unregister(name: String) {
        entries.removeAll { it.name == name }
    }

    /**
     * 接入系统回调（由 [com.dpmusic.app.DPmusicApp] 调用一次）。
     *
     * 用 `registerComponentCallbacks` 而非改 Application 继承：只需要一个观察点，
     * 且让本中心能被单测直接驱动 [handle]，不依赖系统投递。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        if (appContext != null) return
        appContext = app
        runCatching {
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                /** 老 API（<14）与「已无内存可分配」的兜底：按最高档处理 */
                override fun onLowMemory() {
                    handle(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                }

                override fun onTrimMemory(level: Int) {
                    handle(level)
                }
            })
        }.onFailure { Log.w(TAG, "注册内存回调失败：${it.message}") }
    }

    /** 系统 `onTrimMemory` 档位 → 本中心 [Level] */
    fun levelOf(trimLevel: Int): Level = when (trimLevel) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> Level.LIGHT
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> Level.MODERATE
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
        -> Level.SEVERE
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        -> Level.CRITICAL
        else -> Level.NONE
    }

    /**
     * 处理一次内存压力（**核心入口，可直接单测**）。
     *
     * @return 实际生效的档位；[Level.NONE] 表示该档已处理过（去重）
     */
    fun handle(trimLevel: Int): Level {
        val level = levelOf(trimLevel)
        if (level == Level.NONE) return Level.NONE
        val prev = reachedLevel.get()
        if (level.value <= prev) return Level.NONE
        if (!reachedLevel.compareAndSet(prev, level.value)) return Level.NONE

        trimCount.incrementAndGet()
        dispatch(level)
        return level
    }

    /**
     * 立即对本档执行降载（同步；单测直接用）。
     *
     * 注意：[handle] 里也是同步调用它 —— 降载动作本身必须快（清引用 / 删条目），
     * 真正的重量级 IO 由各 [Trimmer] 自己决定要不要异步。
     */
    fun dispatch(level: Level) {
        for (e in entries) {
            if (level.value < e.level.value) continue
            runCatching { e.action.trim(level) }
                .onFailure { Log.w(TAG, "降载失败[${e.name}]：${it.message}") }
        }
        Log.i(TAG, "已响应内存压力：${level.name}（累计 $totalTrims 次）")
    }

    /** 压力解除（回到前台且内存充裕）：允许后续再次响应同档 */
    fun reset() = reachedLevel.set(Level.NONE.value)

    /** 仅测试用：清空注册表与计数 */
    internal fun resetForTest() {
        entries.clear()
        reachedLevel.set(Level.NONE.value)
        trimCount.set(0)
    }

    /** 当前进程内存概况（供设置页 / 诊断日志） */
    fun snapshot(): Snapshot {
        val rt = Runtime.getRuntime()
        val info = Debug.MemoryInfo()
        runCatching { Debug.getMemoryInfo(info) }
        return Snapshot(
            javaUsedBytes = rt.totalMemory() - rt.freeMemory(),
            javaMaxBytes = rt.maxMemory(),
            totalPssKb = info.totalPss,
            level = lastLevel,
            trims = totalTrims,
        )
    }

    data class Snapshot(
        val javaUsedBytes: Long,
        val javaMaxBytes: Long,
        /** 单位 KB（`Debug.MemoryInfo.totalPss` 的原生单位） */
        val totalPssKb: Int,
        val level: Int,
        val trims: Int,
    )
}