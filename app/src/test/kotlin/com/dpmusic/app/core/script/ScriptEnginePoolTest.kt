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
 * 脚本池的加载 / 卸载 / 幂等规则测试。
 *
 * 这些规则决定「用户在链上打开或关闭一个脚本之后，内存里到底驻留了什么」。
 * 之前只能靠装机点按验证，现在用假引擎把池的行为钉在 JVM 单测里。
 */
class ScriptEnginePoolTest {

    /** 假引擎：加载即同步置为就绪，便于在单测里确定性地观察池的行为 */
    private class FakeScriptEngine(
        private val readySources: Map<String, List<String>> = mapOf("wy" to listOf("musicUrl")),
        private val failLoad: Boolean = false,
        var memoryBytes: Long = 0L,
    ) : ScriptEngine {
        private val _status = MutableStateFlow<ScriptEngineStatus>(ScriptEngineStatus.Idle)
        override val status: StateFlow<ScriptEngineStatus> = _status

        /** 最后一次 load 传入的 id，便于按 id 反查实例 */
        var loadedId: String? = null
        var destroyCount = 0

        override fun load(
            scriptId: String,
            name: String,
            description: String,
            version: String,
            author: String,
            homepage: String,
            script: String,
        ) {
            loadedId = scriptId
            _status.value = if (failLoad) {
                ScriptEngineStatus.Failed(scriptId, "假引擎：加载失败")
            } else {
                ScriptEngineStatus.Ready(scriptId, readySources)
            }
        }

        override fun destroy() {
            destroyCount++
            _status.value = ScriptEngineStatus.Idle
        }

        override fun queryMemoryUsage(onResult: (Long) -> Unit) = onResult(memoryBytes)

        override suspend fun requestScript(data: JSONObject, timeoutMs: Long): JSONObject =
            JSONObject().put("status", true)
    }

    private class Harness(failLoad: Boolean = false) {
        val created = mutableListOf<FakeScriptEngine>()
        val pool = ScriptEnginePool {
            FakeScriptEngine(failLoad = failLoad).also { created += it }
        }

        fun request(id: String) = ScriptLoadRequest(
            id = id,
            name = id,
            description = "",
            version = "1",
            author = "",
            homepage = "",
            content = "// $id",
        )

        /** 取当前代表 id 的实例（后建的覆盖先建的） */
        fun engineFor(id: String): FakeScriptEngine = created.last { it.loadedId == id }

        /** 某个 id 一共被创建过几个实例（重挂会 > 1） */
        fun instanceCount(id: String): Int = created.count { it.loadedId == id }
    }

    /* ---------------- 加载 ---------------- */

    @Test
    fun `reconcile 只加载需要的脚本`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("a"), h.request("b")))

        assertEquals(setOf("a", "b"), h.pool.residentIds())
        assertTrue(h.pool.isLoaded("a"))
        assertFalse(h.pool.isLoaded("c"))
        assertNull(h.pool.engineOf("c"))
    }

    @Test
    fun `reconcile 卸载不再需要的脚本并销毁实例`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("a"), h.request("b")))
        val aEngine = h.engineFor("a")
        val bEngine = h.engineFor("b")

        h.pool.reconcile(listOf(h.request("b")))

        assertEquals(setOf("b"), h.pool.residentIds())
        assertEquals("被卸载的实例必须被销毁（否则 QuickJS 上下文泄漏）", 1, aEngine.destroyCount)
        assertEquals("仍被需要的实例不该被销毁", 0, bEngine.destroyCount)
        assertEquals("仍被需要的实例不该被重建", 1, h.instanceCount("b"))
    }

    @Test
    fun `reconcile 幂等（重复调用不重复加载）`() {
        val h = Harness()
        val wanted = listOf(h.request("a"), h.request("b"))

        h.pool.reconcile(wanted)
        val createdAfterFirst = h.created.size
        h.pool.reconcile(wanted)

        assertEquals("第二次对齐不该再创建实例", createdAfterFirst, h.created.size)
    }

    @Test
    fun `reconcile 允许空列表（清空池）`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("a")))
        h.pool.reconcile(emptyList())

        assertTrue(h.pool.residentIds().isEmpty())
    }

    /* ---------------- 能力判定 ---------------- */

    @Test
    fun `canResolve 需要引擎就绪且脚本声明了 musicUrl`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("a")))

        assertTrue("声明了 wy/musicUrl 就该可解析", h.pool.canResolve("a", MusicPlatform.WY))
        assertFalse("没声明 kg 就不可解析", h.pool.canResolve("a", MusicPlatform.KG))
        assertFalse("未驻留的脚本不可解析", h.pool.canResolve("不存在", MusicPlatform.WY))
    }

    @Test
    fun `canResolve 对加载失败的脚本返回 false`() {
        val h = Harness(failLoad = true)
        h.pool.reconcile(listOf(h.request("a")))

        assertTrue(h.pool.isLoaded("a"))
        assertFalse("失败实例虽有驻留记录，但不可解析", h.pool.canResolve("a", MusicPlatform.WY))
        assertTrue(h.pool.statusOf("a") is ScriptEngineStatus.Failed)
    }

    @Test
    fun `未驻留脚本的状态是 Idle（便于 UI 显示未加载）`() {
        val h = Harness()
        assertEquals(ScriptEngineStatus.Idle, h.pool.statusOf("不存在"))
    }

    /* ---------------- 内存占用 ---------------- */

    @Test
    fun `refreshMemoryUsage 汇总各实例占用`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("a"), h.request("b")))
        h.engineFor("a").memoryBytes = 1024L
        h.engineFor("b").memoryBytes = 2048L

        h.pool.refreshMemoryUsage()

        assertEquals(mapOf("a" to 1024L, "b" to 2048L), h.pool.memoryUsage.value)
    }

    @Test
    fun `空池刷新得到空占用`() {
        val h = Harness()
        h.pool.refreshMemoryUsage()
        assertTrue(h.pool.memoryUsage.value.isEmpty())
    }

    @Test
    fun `卸载脚本会同时清掉它的占用记录`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("a"), h.request("b")))
        h.pool.refreshMemoryUsage()
        assertTrue(h.pool.memoryUsage.value.containsKey("a"))

        h.pool.reconcile(listOf(h.request("b")))
        assertFalse("卸载后不该留下占用记录", h.pool.memoryUsage.value.containsKey("a"))
    }

    /* ---------------- 释放 ---------------- */

    @Test
    fun `destroyAll 释放全部实例并清空状态`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("a"), h.request("b")))
        h.pool.refreshMemoryUsage()

        h.pool.destroyAll()

        assertTrue(h.pool.residentIds().isEmpty())
        assertTrue(h.pool.memoryUsage.value.isEmpty())
        assertTrue(h.created.all { it.destroyCount == 1 })
    }

    @Test
    fun `重新挂载的实例与旧实例是两个对象（内存里不会叠加）`() {
        val h = Harness()
        h.pool.reconcile(listOf(h.request("a")))
        val first = h.pool.engineOf("a")
        // 池用「加载中」标记去重，该标记由 watchLoad 线程异步清除 —— 等它归位再重挂，
        // 否则测到的是「去重生效」而不是「重挂产生新实例」。
        Thread.sleep(300)

        h.pool.reconcile(emptyList())
        h.pool.reconcile(listOf(h.request("a")))
        val second = h.pool.engineOf("a")

        assertTrue("卸载后重挂必须是新实例", first !== second)
        assertEquals("共创建过两个实例", 2, h.instanceCount("a"))
    }
}