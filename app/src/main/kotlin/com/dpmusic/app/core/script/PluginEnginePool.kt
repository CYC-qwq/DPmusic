package com.dpmusic.app.core.script

import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 挂载一个 MusicFree 插件所需的全部输入 */
data class PluginLoadRequest(
    val id: String,
    val source: String,
    val userVariables: Map<String, String> = emptyMap(),
)

/**
 * **MusicFree 插件池**：让多个插件同时挂载，从而支持「按序尝试」。
 *
 * 与 [ScriptEnginePool] 同构（都是「多个 JS 运行时按序尝试」），所以刻意保持
 * 相同的形状与方法命名 —— 两个池的调用点、UI、记忆负担都只有一份。
 *
 * 每个插件一个独立 [MusicFreeEngine]（独立 QuickJS 上下文）。
 * 池不设硬上限，实时内存占用经 [memoryUsage] 透给 UI 做提示。
 */
class PluginEnginePool(
    private val factory: () -> PluginEngine,
) {
    private val engines = ConcurrentHashMap<String, PluginEngine>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    private val _memoryUsage = MutableStateFlow<Map<String, Long>>(emptyMap())
    val memoryUsage: StateFlow<Map<String, Long>> = _memoryUsage.asStateFlow()

    fun residentIds(): Set<String> = engines.keys.toSet()

    fun isLoaded(id: String): Boolean = engines.containsKey(id)

    /** 该插件是否可解析该平台（已挂载 + 实现了 getMediaSource + 声明覆盖该平台） */
    fun canResolve(pluginId: String, platform: MusicPlatform): Boolean {
        val engine = engines[pluginId] ?: return false
        val meta = (engine.status.value as? PluginEngineStatus.Ready)?.meta ?: return false
        if (!meta.supports(METHOD_GET_MEDIA_SOURCE)) return false
        return pluginCoversPlatform(meta.platform, platform)
    }

    fun engineOf(pluginId: String): PluginEngine? = engines[pluginId]

    fun statusOf(pluginId: String): PluginEngineStatus =
        engines[pluginId]?.status?.value ?: PluginEngineStatus.Idle

    /** 插件名（UI 提示用） */
    fun labelOf(pluginId: String): String =
        (engines[pluginId]?.status?.value as? PluginEngineStatus.Ready)?.meta?.platform.orEmpty()

    /**
     * 插件是否覆盖某平台。
     *
     * MusicFree 插件用 `platform` 字段声明它服务哪个平台（如「网易云音乐」）。
     * 不覆盖的平台应**静默跳过** —— 否则每首歌都去问一次插件、必然失败，白白拖慢解析。
     *
     * 判定偏宽松：插件名与平台名任一互相包含即算覆盖（插件命名五花八门：
     * 「网易云」「网易云音乐」「NetEase」…，无法穷举精确匹配）。
     */
    private fun pluginCoversPlatform(pluginPlatform: String, platform: MusicPlatform): Boolean {
        val name = pluginPlatform.trim()
        if (name.isEmpty()) return true   // 未声明 → 不拦，交给插件自己判断
        return name.contains(platform.label) ||
            platform.label.contains(name) ||
            name.contains(platform.shortLabel) ||
            name.equals(platform.id, ignoreCase = true) ||
            name.equals(platform.lxSource, ignoreCase = true)
    }

    /** 把池与「启用列表」对齐：缺的挂载、多的卸载。幂等。 */
    fun reconcile(wanted: List<PluginLoadRequest>) {
        val wantedIds = wanted.map { it.id }.toSet()

        engines.keys.filter { it !in wantedIds }.forEach { id ->
            engines.remove(id)?.destroy()
            _memoryUsage.value = _memoryUsage.value - id
            AppLogger.i(TAG, "插件已从池中卸载：$id")
        }

        wanted.forEach { req ->
            if (engines.containsKey(req.id) || !loading.add(req.id)) return@forEach
            val engine = factory()
            engines[req.id] = engine
            engine.load(req.id, req.source, req.userVariables)
            watchLoad(engine, req.id)
        }
    }

    /** 轮询等挂载结果（引擎只通过 status 反映，无事件回调；理由同 [ScriptEnginePool]） */
    private fun watchLoad(engine: PluginEngine, id: String) {
        Thread {
            val deadline = System.currentTimeMillis() + LOAD_WATCH_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                when (engine.status.value) {
                    is PluginEngineStatus.Ready, is PluginEngineStatus.Failed -> {
                        loading.remove(id)
                        return@Thread
                    }
                    else -> runCatching { Thread.sleep(LOAD_POLL_MS) }
                }
            }
            loading.remove(id)
            AppLogger.w(TAG, "插件挂载等待超时：$id")
        }.apply { isDaemon = true }.start()
    }

    /** 刷新各插件的**真实**内存占用（QuickJS 只能在各自工作线程上读） */
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

    /**
     * 卸载单个插件。
     *
     * 用途：插件的**用户变量变了**必须重新挂载才会生效（插件在 `load` 时读取变量），
     * 而 [reconcile] 对「已在池里」的项会跳过 —— 得先摘掉，下一次 [reconcile] 才会重挂。
     */
    fun unload(id: String) {
        engines.remove(id)?.let { engine ->
            runCatching { engine.destroy() }
            _memoryUsage.value = _memoryUsage.value - id
            loading.remove(id)
            AppLogger.i(TAG, "插件已卸载（待重新挂载）：$id")
        }
    }

    private companion object {
        const val TAG = "PluginEnginePool"
        const val LOAD_WATCH_TIMEOUT_MS = 15_000L
        const val LOAD_POLL_MS = 100L

        /** 取源能力的方法名（与 [MusicFreeEngine] 约定的插件接口） */
        const val METHOD_GET_MEDIA_SOURCE = "getMediaSource"
    }
}