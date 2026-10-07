package com.dpmusic.app.core.script

import com.dpmusic.app.core.model.MusicPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件池的加载 / 卸载 / 平台覆盖判定测试。
 *
 * 与 [ScriptEnginePoolTest] 同构，重点补上插件独有的一段逻辑：
 * **平台覆盖判定**（`pluginCoversPlatform`）—— 判错了会导致每首歌都去问一个
 * 根本不服务该平台的插件、必然失败，白白拖慢解析，而界面上看不出来。
 */
class PluginEnginePoolTest {

    private class FakePluginEngine(
        private val platform: String = "网易云音乐",
        private val methods: List<String> = listOf("search", "getMediaSource"),
        var memoryBytes: Long = 0L,
    ) : PluginEngine {
        private val _status = MutableStateFlow<PluginEngineStatus>(PluginEngineStatus.Idle)
        override val status: StateFlow<PluginEngineStatus> = _status

        var loadedId: String? = null
        var destroyCount = 0

        override fun load(pluginId: String, source: String, userVariables: Map<String, String>) {
            loadedId = pluginId
            _status.value = PluginEngineStatus.Ready(
                pluginId,
                MusicFreePluginMeta(platform = platform, methods = methods),
            )
        }

        override fun destroy() {
            destroyCount++
            _status.value = PluginEngineStatus.Idle
        }

        override fun queryMemoryUsage(onResult: (Long) -> Unit) = onResult(memoryBytes)

        override suspend fun invoke(method: String, argsJson: String, timeoutMs: Long): JSONObject =
            JSONObject().put("ok", true)
    }

    private class Harness(
        platform: String = "网易云音乐",
        methods: List<String> = listOf("search", "getMediaSource"),
    ) {
        val created = mutableListOf<FakePluginEngine>()
        val pool = PluginEnginePool {
            FakePluginEngine(platform = platform, methods = methods).also { created += it }
        }

        fun request(id: String) = PluginLoadRequest(id = id, source = "// $id")

        fun engineFor(id: String): FakePluginEngine = created.last { it.loadedId == id }
    }

    /* ---------------- 加载 / 卸载 ---------------- */

    @Test
    fun `reconcile 挂载与卸载`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("p1"), h.request("p2")))
        assertEquals(setOf("p1", "p2"), h.pool.residentIds())

        val p1 = h.engineFor("p1")
        h.pool.reconcile(listOf(h.request("p2")))

        assertEquals(setOf("p2"), h.pool.residentIds())
        assertEquals("卸载的插件必须销毁", 1, p1.destroyCount)
    }

    @Test
    fun `reconcile 幂等`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("p1")))
        h.pool.reconcile(listOf(h.request("p1")))
        assertEquals("重复对齐不该重复创建", 1, h.created.size)
    }

    /**
     * `unload` 是「用户变量改了要重挂」的入口：先摘掉，下次 `reconcile` 才会重挂。
     * 若不摘掉，`reconcile` 对已在池里的项会跳过，新的用户变量永远不生效。
     */
    @Test
    fun `unload 摘掉实例后再次 reconcile 会重挂新实例`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("p1")))
        val first = h.pool.engineOf("p1")

        h.pool.unload("p1")
        assertFalse("unload 后不该还在池里", h.pool.isLoaded("p1"))

        h.pool.reconcile(listOf(h.request("p1")))
        val second = h.pool.engineOf("p1")
        assertTrue("重挂应是新实例（读取新的用户变量）", first !== second)
    }

    @Test
    fun `unload 不存在的 id 是空操作`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("p1")))
        h.pool.unload("不存在")
        assertEquals(setOf("p1"), h.pool.residentIds())
    }

    /* ---------------- 平台覆盖判定 ---------------- */

    @Test
    fun `canResolve 需要插件实现 getMediaSource`() {
        val h = Harness(methods = listOf("search"))   // 没有 getMediaSource
        h.pool.reconcile(listOf(h.request("p1")))

        assertTrue(h.pool.isLoaded("p1"))
        assertFalse("没实现 getMediaSource 的插件不能取播放地址", h.pool.canResolve("p1", MusicPlatform.WY))
    }

    @Test
    fun `canResolve 对不覆盖的平台返回 false`() {
        val h = Harness(platform = "网易云音乐")
        h.pool.reconcile(listOf(h.request("p1")))

        assertTrue(h.pool.canResolve("p1", MusicPlatform.WY))
        assertFalse("网易云插件不该被问 QQ", h.pool.canResolve("p1", MusicPlatform.QQ))
        assertFalse(h.pool.canResolve("p1", MusicPlatform.KG))
    }

    @Test
    fun `平台名互相包含都算覆盖（插件命名五花八门）`() {
        // 插件只写「网易云」，平台全称是「网易云音乐」→ 应算覆盖
        val short = Harness(platform = "网易云")
        short.pool.reconcile(listOf(short.request("p")))
        assertTrue(short.pool.canResolve("p", MusicPlatform.WY))

        // 平台代号也能命中（wy / qq / kg）
        val code = Harness(platform = "qq")
        code.pool.reconcile(listOf(code.request("p")))
        assertTrue(code.pool.canResolve("p", MusicPlatform.QQ))
    }

    @Test
    fun `插件未声明平台时不拦（交给插件自己判断）`() {
        val h = Harness(platform = "")
        h.pool.reconcile(listOf(h.request("p")))

        assertTrue(h.pool.canResolve("p", MusicPlatform.WY))
        assertTrue(h.pool.canResolve("p", MusicPlatform.QQ))
    }

    @Test
    fun `未驻留的插件不可解析`() {
        val h = Harness()
        assertFalse(h.pool.canResolve("不存在", MusicPlatform.WY))
        assertNull(h.pool.engineOf("不存在"))
        assertEquals(PluginEngineStatus.Idle, h.pool.statusOf("不存在"))
    }

    /* ---------------- 展示名与内存 ---------------- */

    @Test
    fun `labelOf 取插件声明的平台名`() {
        val h = Harness(platform = "网易云音乐")
        h.pool.reconcile(listOf(h.request("p1")))

        assertEquals("网易云音乐", h.pool.labelOf("p1"))
        assertEquals("未驻留时为空", "", h.pool.labelOf("不存在"))
    }

    @Test
    fun `refreshMemoryUsage 汇总并随卸载清理`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("p1"), h.request("p2")))
        h.engineFor("p1").memoryBytes = 4096L
        h.engineFor("p2").memoryBytes = 8192L
        h.pool.refreshMemoryUsage()
        assertEquals(mapOf("p1" to 4096L, "p2" to 8192L), h.pool.memoryUsage.value)

        h.pool.reconcile(listOf(h.request("p2")))
        assertFalse(h.pool.memoryUsage.value.containsKey("p1"))
    }

    @Test
    fun `destroyAll 释放全部`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("p1"), h.request("p2")))
        h.pool.destroyAll()

        assertTrue(h.pool.residentIds().isEmpty())
        assertTrue(h.created.all { it.destroyCount == 1 })
    }
}