package com.dpmusic.app.core.script

import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.ScriptOrder
import com.dpmusic.app.core.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 加载一个脚本所需的全部输入 */
data class ScriptLoadRequest(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val homepage: String,
    val content: String,
)

/**
 * **自定义音源脚本池**：让多个 JS 脚本同时驻留，从而支持「按序尝试」。
 *
 * ## 为什么需要它
 * 原来引擎是「单 QuickJS 上下文 + 单激活脚本」：同一时刻只有一个脚本能解析。
 * 用户要的是「A 失败自动落到 B」，那就必须 **B 此刻也在内存里、也已上报过自身的
 * 平台能力（`sources`）** —— 单上下文做不到，只能每个脚本一个独立引擎实例。
 *
 * ## 生命周期
 * - [reconcile]：把「启用列表」与池对齐 —— 缺的加载、多的卸载。幂等。
 * - 只卸载「不再被任何平台启用」的脚本；仍被某平台启用的会保留驻留
 *   （否则在 A 平台关掉、切到 B 平台又要重新加载一次，很慢）。
 * - 每个实例独立 QuickJS 上下文，加载失败只影响它自己（标记失败并跳过）。
 *
 * ## 内存
 * 每个 QuickJS 上下文都要一块独立的运行时内存。池**不设硬上限**（用户要的是自由度），
 * 改为把真实占用透出去（[memoryUsage]），由 UI 提示「开太多了」。
 */
class ScriptEnginePool(
    /** 创建单个脚本引擎实例；由调用方注入，便于测试与将来换实现 */
    private val factory: () -> ScriptEngine,
) {
    /** 所有驻留实例：scriptId → 引擎 */
    private val engines = ConcurrentHashMap<String, ScriptEngine>()

    /** 正在加载中的脚本 id（避免连点「启用」重复加载） */
    private val loading = ConcurrentHashMap.newKeySet<String>()

    private val _memoryUsage = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** 每个脚本的**实时**内存占用（字节），键为 scriptId。由 [refreshMemoryUsage] 刷新。 */
    val memoryUsage: StateFlow<Map<String, Long>> = _memoryUsage.asStateFlow()

    /** 当前驻留的脚本 id 集合 */
    fun residentIds(): Set<String> = engines.keys.toSet()

    fun isLoaded(id: String): Boolean = engines.containsKey(id)

    /** 该脚本当前是否可解析某平台（引擎就绪且声明了 musicUrl 能力） */
    fun canResolve(scriptId: String, platform: MusicPlatform): Boolean {
        val engine = engines[scriptId] ?: return false
        val status = engine.status.value as? ScriptEngineStatus.Ready ?: return false
        val actions = status.sources[platform.lxSource] ?: return false
        return actions.contains("musicUrl")
    }

    /** 取某脚本的引擎（未驻留则为 null）；[ScriptMusicResolver] 用它下发解析请求 */
    fun engineOf(scriptId: String): ScriptEngine? = engines[scriptId]

    /** 某脚本的当前加载状态（未驻留返回 Idle，便于 UI 显示「未加载」） */
    fun statusOf(scriptId: String): ScriptEngineStatus =
        engines[scriptId]?.status?.value ?: ScriptEngineStatus.Idle

    /**
     * 把池与「启用列表」对齐：缺的加载、多的卸载。幂等（重复调用不会重复加载）。
     *
     * @param wanted 需要驻留的脚本（含加载所需的内容）
     */
    fun reconcile(wanted: List<ScriptLoadRequest>) {
        val wantedIds = wanted.map { it.id }.toSet()

        // ① 卸载不再需要的
        engines.keys.filter { it !in wantedIds }.forEach { id ->
            engines.remove(id)?.destroy()
            _memoryUsage.value = _memoryUsage.value - id
            AppLogger.i(TAG, "脚本已从池中卸载：$id")
        }

        // ② 加载缺失的
        wanted.forEach { req ->
            if (engines.containsKey(req.id) || !loading.add(req.id)) return@forEach
            val engine = factory()
            engines[req.id] = engine
            engine.load(
                scriptId = req.id,
                name = req.name,
                description = req.description,
                version = req.version,
                author = req.author,
                homepage = req.homepage,
                script = req.content,
            )
            watchLoad(engine, req.id)
        }
    }

    /**
     * 等某个实例上报加载结果，然后解除「加载中」标记。
     *
     * 为什么用轮询：引擎内部只在 `status` 这个 StateFlow 上反映结果，没有对外的事件回调；
     * 轮询间隔 100ms、上限 15s，代价可忽略，换来的是不必改动引擎内部的状态机。
     */
    private fun watchLoad(engine: ScriptEngine, id: String) {
        Thread {
            val deadline = System.currentTimeMillis() + LOAD_WATCH_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                when (engine.status.value) {
                    is ScriptEngineStatus.Ready, is ScriptEngineStatus.Failed -> {
                        loading.remove(id)
                        return@Thread
                    }
                    else -> runCatching { Thread.sleep(LOAD_POLL_MS) }
                }
            }
            loading.remove(id)
            AppLogger.w(TAG, "脚本加载等待超时：$id（可能仍在加载）")
        }.apply { isDaemon = true }.start()
    }

    /**
     * 刷新各实例的**真实**内存占用。
     *
     * QuickJS 的占用只能在引擎自己的工作线程上读，所以这里是「发一批查询、等回调」。
     * 调用方（UI）按需调用即可，不必每帧刷。
     */
    fun refreshMemoryUsage(onDone: (() -> Unit)? = null) {
        val snapshot = engines.toMap()
        if (snapshot.isEmpty()) {
            _memoryUsage.value = emptyMap()
            onDone?.invoke()
            return
        }
        val pending = AtomicInteger(snapshot.size)
        val acc = ConcurrentHashMap<String, Long>()
        snapshot.forEach { (id, engine) ->
            engine.queryMemoryUsage { bytes ->
                acc[id] = bytes
                if (pending.decrementAndGet() == 0) {
                    _memoryUsage.value = acc.toMap()
                    onDone?.invoke()
                }
            }
        }
    }

    /** 全部释放 */
    fun destroyAll() {
        engines.values.forEach { runCatching { it.destroy() } }
        engines.clear()
        loading.clear()
        _memoryUsage.value = emptyMap()
    }

    private companion object {
        const val TAG = "ScriptEnginePool"
        const val LOAD_WATCH_TIMEOUT_MS = 15_000L
        const val LOAD_POLL_MS = 100L
    }
}