package com.dpmusic.app.core.script

import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.PlayQuality
import com.dpmusic.app.core.model.ScriptKind
import com.dpmusic.app.core.model.ScriptOrder
import com.dpmusic.app.core.model.ScriptRef
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.chainFor
import com.dpmusic.app.core.net.ResolveException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「脚本按序尝试」的端到端行为测试。
 *
 * 用假引擎把「第一个脚本失败 → 自动落到第二个」这条核心承诺钉住 —— 这正是把
 * 「单激活脚本」改成「多实例池」的全部理由，却最难靠肉眼验证：
 * 界面上只显示最终成功，看不出中间落过谁、试过几个档位。
 */
class ScriptMusicResolverOrderTest {

    /**
     * 假脚本引擎：只对「服务某平台 + 声明 musicUrl」的脚本返回就绪状态。
     *
     * @param respondUrl 非空 = 解析成功并返回它；null = 每个档位都抛错
     * @param failQualityIds 这些档位强制失败（用来验证「降到下一档」）
     * @param servedPlatforms 声明支持的 LX 平台代号（默认只支持 wy）
     */
    private class FakeScriptEngine(
        val id: String,
        var respondUrl: String? = null,
        private val failQualityIds: Set<String> = emptySet(),
        servedPlatforms: List<String> = listOf(MusicPlatform.WY.lxSource),
    ) : ScriptEngine {
        private val _status = MutableStateFlow<ScriptEngineStatus>(
            ScriptEngineStatus.Ready(id, servedPlatforms.associateWith { listOf("musicUrl") }),
        )
        override val status: StateFlow<ScriptEngineStatus> = _status

        /** 依次收到的音质档请求（用于断言「试了几个档位」） */
        val requestedQualities = mutableListOf<String>()

        override fun load(
            scriptId: String,
            name: String,
            description: String,
            version: String,
            author: String,
            homepage: String,
            script: String,
        ) = Unit

        override fun destroy() = Unit
        override fun queryMemoryUsage(onResult: (Long) -> Unit) = onResult(0L)

        override suspend fun requestScript(data: JSONObject, timeoutMs: Long): JSONObject {
            val q = data.getJSONObject("info").optString("type")
            requestedQualities += q
            val url = respondUrl
            if (url != null && q !in failQualityIds) {
                return JSONObject().put("status", true).put(
                    "result",
                    JSONObject().put("data", JSONObject().put("url", url)),
                )
            }
            throw ResolveException("假脚本 $id 在 $q 档解析失败", MusicPlatform.WY)
        }
    }

    private val song = Song(id = "1", platform = MusicPlatform.WY, title = "t", artist = "a")

    private fun orderOf(vararg ids: String) = ScriptOrder(
        platformId = MusicPlatform.WY.id,
        kind = ScriptKind.SCRIPT.id,
        refs = ids.map { ScriptRef(it) },
    )

    private fun loadReq(id: String) =
        ScriptLoadRequest(id, id, "", "1", "", "", "// $id")

    /**
     * 建一个池，让它按「脚本名」分发到预先准备好的假引擎。
     *
     * 池的 `reconcile` 只传 id，工厂函数按名取实例即可 ——
     * 这样既不碰真实 QuickJS，又能让池的内部状态（engines / status）与生产一致。
     */
    private fun poolOf(vararg engines: FakeScriptEngine): ScriptEnginePool {
        val byId = engines.associateBy { it.id }
        return ScriptEnginePool { byId.getValue(createdIds.removeFirst()) }
    }

    /** 记录下一批要创建的引擎 id —— `reconcile` 按传入顺序创建 */
    private val createdIds = ArrayDeque<String>()

    private fun ScriptEnginePool.loadAll(vararg ids: String) {
        createdIds.clear()
        createdIds.addAll(ids)
        reconcile(ids.map { loadReq(it) })
    }

    /* ---------------- 落到下一项 ---------------- */

    @Test
    fun `第一个脚本全部档失败时自动落到第二个`() = runBlocking {
        val first = FakeScriptEngine("first")                       // 每个档都失败
        val second = FakeScriptEngine("second", respondUrl = "http://second-ok")
        val pool = poolOf(first, second)
        pool.loadAll("first", "second")

        val resolver = ScriptMusicResolver(pool, { listOf(orderOf("first", "second")) })
        val resolved = resolver.resolve(song, PlayQuality.STANDARD)

        assertEquals("应从第二个脚本拿到地址", "http://second-ok", resolved.url)
        assertEquals(
            "第一个脚本应把当前档到最低档全试过",
            PlayQuality.STANDARD.chainFor(MusicPlatform.WY).size,
            first.requestedQualities.size,
        )
        assertEquals("第二个脚本命中第一个档位就停", 1, second.requestedQualities.size)
    }

    @Test
    fun `顺序即优先级：第一个能成功就不问第二个`() = runBlocking {
        val first = FakeScriptEngine("first", respondUrl = "http://first-ok")
        val second = FakeScriptEngine("second", respondUrl = "http://second-ok")
        val pool = poolOf(first, second)
        pool.loadAll("first", "second")

        val resolver = ScriptMusicResolver(pool, { listOf(orderOf("first", "second")) })
        val resolved = resolver.resolve(song, PlayQuality.STANDARD)

        assertEquals("http://first-ok", resolved.url)
        assertTrue("第二个脚本不该被问到", second.requestedQualities.isEmpty())
    }

    @Test
    fun `交换顺序结果随之改变（用户调顺序真的生效）`() = runBlocking {
        val first = FakeScriptEngine("first", respondUrl = "http://first-ok")
        val second = FakeScriptEngine("second", respondUrl = "http://second-ok")
        val pool = poolOf(first, second)
        pool.loadAll("first", "second")

        val resolver = ScriptMusicResolver(pool, { listOf(orderOf("second", "first")) })
        val resolved = resolver.resolve(song, PlayQuality.STANDARD)

        assertEquals("反序后应由第二个脚本胜出", "http://second-ok", resolved.url)
    }

    @Test
    fun `单个脚本的档位降档链有效（高档失败降到低档）`() = runBlocking {
        val flacFail = PlayQuality.LOSSLESS.chainFor(MusicPlatform.WY).first()
        val engine = FakeScriptEngine("s", respondUrl = "http://320", failQualityIds = setOf(flacFail))
        val pool = poolOf(engine)
        pool.loadAll("s")

        val resolver = ScriptMusicResolver(pool, { listOf(orderOf("s")) })
        val resolved = resolver.resolve(song, PlayQuality.LOSSLESS)

        assertEquals("应从 flac 降到 320k 成功", "http://320", resolved.url)
        assertEquals(flacFail, engine.requestedQualities.first())
        assertEquals("320k", engine.requestedQualities[1])
    }

    /* ---------------- 能力过滤 ---------------- */

    @Test
    fun `链上停用与不支持的项被静默跳过`() = runBlocking {
        val unsupported = FakeScriptEngine("unsupported", respondUrl = "http://nope", servedPlatforms = listOf("kg"))
        val ok = FakeScriptEngine("ok", respondUrl = "http://ok")
        val pool = poolOf(unsupported, ok)
        pool.loadAll("unsupported", "ok")

        // unsupported 在链上但只声明 kg；停用项 disabled
        val order = ScriptOrder(
            MusicPlatform.WY.id,
            ScriptKind.SCRIPT.id,
            listOf(ScriptRef("unsupported"), ScriptRef("disabled", enabled = false), ScriptRef("ok")),
        )
        val resolver = ScriptMusicResolver(pool, { listOf(order) })
        val resolved = resolver.resolve(song, PlayQuality.STANDARD)

        assertEquals("只有 ok 该被采纳", "http://ok", resolved.url)
        assertTrue("不支持 wy 的脚本不该被问到", unsupported.requestedQualities.isEmpty())
    }

    @Test
    fun `链上无可用脚本时抛出带平台信息的错误`() = runBlocking {
        val pool = ScriptEnginePool { error("不该创建任何引擎") }
        val resolver = ScriptMusicResolver(pool, { listOf(orderOf()) })

        val error = runCatching { resolver.resolve(song, PlayQuality.STANDARD) }.exceptionOrNull()
        assertTrue("应是 ResolveException", error is ResolveException)
        assertEquals(MusicPlatform.WY, (error as ResolveException).platform)
    }

    @Test
    fun `canResolve 只判能力不触发解析`() = runBlocking {
        val engine = FakeScriptEngine("s")
        val pool = poolOf(engine)
        pool.loadAll("s")

        val resolver = ScriptMusicResolver(pool, { listOf(orderOf("s")) })
        assertTrue(resolver.canResolve(MusicPlatform.WY))
        assertTrue("只判能力，不该发过请求", engine.requestedQualities.isEmpty())
    }

    /* ---------------- 试听（不走链、不兜底） ---------------- */

    @Test
    fun `resolveWithScript 只问指定脚本，失败不兜底到别人`() = runBlocking {
        val broken = FakeScriptEngine("broken")                     // 全失败
        val healthy = FakeScriptEngine("healthy", respondUrl = "http://healthy")
        val pool = poolOf(broken, healthy)
        pool.loadAll("broken", "healthy")

        val resolver = ScriptMusicResolver(pool, { listOf(orderOf("broken", "healthy")) })
        val error = runCatching { resolver.resolveWithScript("broken", song, PlayQuality.STANDARD) }
            .exceptionOrNull()

        assertTrue("应直接失败", error is ResolveException)
        assertTrue("试听不该去问健康脚本", healthy.requestedQualities.isEmpty())
    }

    @Test
    fun `resolveWithScript 对未驻留脚本报未启用`() = runBlocking {
        val pool = ScriptEnginePool { error("不该创建") }
        val resolver = ScriptMusicResolver(pool, { listOf(orderOf("x")) })

        val error = runCatching { resolver.resolveWithScript("x", song, PlayQuality.STANDARD) }
            .exceptionOrNull()
        assertTrue(error is ResolveException)
        assertEquals("该脚本当前未启用", error?.message)
    }

    /* ---------------- 缓存说明 ---------------- */
    //
    // 这里**不测缓存**：解析器用的 `android.util.LruCache` 在本地单测（returnDefaultValues=true）
    // 里是空实现，put/get 都不生效，测出来的「每次都重新解析」是环境假象，不是真实行为。
}