package com.dpmusic.app.core.model

import com.dpmusic.app.core.net.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JS 顺序模型的核心规则测试。
 *
 * 这些规则决定「按播放键后脚本按什么顺序被尝试」「哪些脚本要常驻内存」，
 * 全都藏在 UI 后面、肉眼看不出来，所以用单测钉住。
 *
 * 上一轮测试抓出的两个真实 bug（`move` 未用临时变量导致元素被复制两份；
 * 默认顺序与注释不符）都在这份测试的覆盖范围内 —— 顺序语义那几条就是回归钉子。
 */
class ScriptOrderTest {

    private fun order(vararg ids: String) = ScriptOrder(
        platformId = MusicPlatform.WY.id,
        kind = ScriptKind.SCRIPT.id,
        refs = ids.map { ScriptRef(it) },
    )

    private fun idsOf(o: ScriptOrder) = o.refs.map { it.id }

    /* ---------------- 顺序语义 ---------------- */

    @Test
    fun `上移下移交换相邻两项且位置正确`() {
        val o = order("a", "b", "c")

        val down = o.move("a", up = false)
        assertEquals(listOf("b", "a", "c"), idsOf(down))

        val up = o.move("c", up = true)
        assertEquals(listOf("a", "c", "b"), idsOf(up))
    }

    /**
     * 回归：`move` 必须用临时变量交换。
     *
     * 写成 `list[i] = list[j]; list[j] = list[i]` 的结果是「被交换的那一项复制两份、
     * 另一项凭空消失」——这个 bug 直接表现为「调一次顺序就少一个脚本、多一个重复项」，
     * 而且不会崩、只是静静地错，所以必须用「集合不变」把它钉死。
     */
    @Test
    fun `移动不重复也不丢项（回归：交换必须用临时变量）`() {
        val o = order("a", "b", "c")
        listOf(o.move("a", false), o.move("b", true), o.move("b", false), o.move("c", true))
            .forEach { moved ->
                assertEquals("元素数量不能变", 3, moved.refs.size)
                assertEquals("元素的集合不能变", setOf("a", "b", "c"), idsOf(moved).toSet())
            }
    }

    @Test
    fun `越界移动是空操作（顶项上移、底项下移）`() {
        val o = order("a", "b", "c")
        assertEquals(o, o.move("a", up = true))
        assertEquals(o, o.move("c", up = false))
    }

    @Test
    fun `移动不在链上的 id 是空操作`() {
        val o = order("a", "b")
        assertEquals(o, o.move("不存在", up = true))
    }

    /* ---------------- 启停语义 ---------------- */

    @Test
    fun `停用项保留位置但不再计数`() {
        val o = order("a", "b", "c")
        val off = o.toggle("b")

        assertEquals("位置必须保留", listOf("a", "b", "c"), idsOf(off))
        assertFalse(off.isEnabled("b"))
        assertTrue(off.isEnabled("a"))
        assertEquals(listOf("a", "c"), off.refs.filter { it.enabled }.map { it.id })
    }

    @Test
    fun `不允许关掉最后一项启用的（否则该平台脚本环节无人可用）`() {
        var o = order("a", "b")
        o = o.toggle("b")
        assertTrue(o.isEnabled("a"))
        assertFalse(o.isEnabled("b"))

        // 试图关掉最后一项 → 被拒绝
        assertEquals(o, o.toggle("a"))
        assertEquals(1, o.refs.count { it.enabled })
    }

    @Test
    fun `空链与未知 id 的切换是空操作`() {
        val empty = ScriptOrder(MusicPlatform.WY.id, ScriptKind.SCRIPT.id)
        assertEquals(empty, empty.toggle("a"))
        assertEquals(empty, empty.move("a", up = true))

        val o = order("a")
        assertEquals(o, o.toggle("不存在"))
    }

    /* ---------------- 与「已导入列表」对齐 ---------------- */

    @Test
    fun `reconcile 新项追加到末尾且默认启用、保持既有顺序`() {
        val o = order("a", "b")
        val next = o.reconcile(listOf("b", "a", "c"))

        assertEquals("既有顺序保持，新项追加到末尾", listOf("a", "b", "c"), idsOf(next))
        assertTrue("新导入的应当默认启用", next.isEnabled("c"))
    }

    @Test
    fun `reconcile 移除已删除项`() {
        val o = order("a", "b", "c")
        val next = o.reconcile(listOf("c", "a"))

        assertEquals(listOf("a", "c"), idsOf(next))
        assertFalse(idsOf(next).contains("b"))
    }

    @Test
    fun `reconcile 幂等（重复调用不再改变）`() {
        val o = order("a", "b")
        val once = o.reconcile(listOf("a", "b", "c"))
        assertEquals(once, once.reconcile(listOf("a", "b", "c")))
    }

    @Test
    fun `sanitize 为每个平台补齐顺序并清掉非法项`() {
        val stored = listOf(
            ScriptOrder(
                platformId = MusicPlatform.WY.id,
                kind = ScriptKind.SCRIPT.id,
                refs = listOf(ScriptRef("已删除的脚本"), ScriptRef("a")),
            ),
        )
        val result = ScriptOrder.sanitize(
            stored,
            mapOf(ScriptKind.SCRIPT to listOf("a", "b")),
        )

        // 每个「平台 × 类型」都有一份（与 SourceChain.sanitize 的策略一致，但多一维 kind）
        assertEquals(MusicPlatform.entries.size * ScriptKind.entries.size, result.size)
        MusicPlatform.entries.forEach { p ->
            ScriptKind.entries.forEach { k ->
                assertTrue(
                    "缺少 ${p.id}/$k",
                    result.any { it.platformId == p.id && it.kind == k.id },
                )
            }
        }

        val wy = result.first { it.platformId == MusicPlatform.WY.id }
        assertEquals("非法项被清掉、合法项保留、缺的补到末尾", listOf("a", "b"), idsOf(wy))

        // 没存过的平台得到「按可用列表全员启用」的默认顺序
        val qq = result.first { it.platformId == MusicPlatform.QQ.id }
        assertEquals(listOf("a", "b"), idsOf(qq))
    }

    @Test
    fun `sanitize 在没有可用 JS 时给出空链（初始状态，不算错误）`() {
        val result = ScriptOrder.sanitize(emptyList(), mapOf(ScriptKind.SCRIPT to emptyList()))
        assertEquals(MusicPlatform.entries.size * ScriptKind.entries.size, result.size)
        result.forEach { assertTrue(it.refs.isEmpty()) }
    }

    /* ---------------- 驻留集合推导 ---------------- */

    /**
     * 这是「驻留」策略的关键不变量：一个 JS 只要被**任一平台**启用就要常驻。
     *
     * 若写成「所有平台都启用才驻留」，用户在网易云临时关掉一个脚本、切到 QQ 又要重新
     * 加载一遍 JS（重新加载明显更慢），体验上是说不通的。
     */
    @Test
    fun `任一平台启用即驻留（在某平台关掉不影响其他平台）`() {
        val orders = listOf(
            // 网易云：a 被关掉，b 启用
            ScriptOrder(MusicPlatform.WY.id, ScriptKind.SCRIPT.id, listOf(ScriptRef("a", false), ScriptRef("b"))),
            // QQ：a 启用
            ScriptOrder(MusicPlatform.QQ.id, ScriptKind.SCRIPT.id, listOf(ScriptRef("a"), ScriptRef("b", false))),
        )

        val resident = orders.residentIds(ScriptKind.SCRIPT)
        assertTrue("a 在 QQ 上启用 → 必须驻留", "a" in resident)
        assertTrue("b 在网易云上启用 → 必须驻留", "b" in resident)
    }

    @Test
    fun `所有平台都关掉的 JS 不驻留`() {
        val orders = MusicPlatform.entries.map { p ->
            ScriptOrder(p.id, ScriptKind.SCRIPT.id, listOf(ScriptRef("a", false), ScriptRef("b")))
        }
        val resident = orders.residentIds(ScriptKind.SCRIPT)
        assertFalse("处处关闭 → 不该占用内存", "a" in resident)
        assertTrue("b 处处启用 → 驻留", "b" in resident)
    }

    @Test
    fun `enabledIds 返回的就是尝试顺序且已过滤停用项`() {
        val orders = listOf(
            ScriptOrder(
                MusicPlatform.WY.id,
                ScriptKind.SCRIPT.id,
                listOf(ScriptRef("c"), ScriptRef("a", false), ScriptRef("b")),
            ),
        )
        assertEquals(listOf("c", "b"), orders.enabledIds(MusicPlatform.WY, ScriptKind.SCRIPT))
        // 未配置的平台 → 空
        assertTrue(orders.enabledIds(MusicPlatform.KG, ScriptKind.SCRIPT).isEmpty())
    }

    @Test
    fun `脚本与插件互不干扰（kind 隔离）`() {
        val orders = listOf(
            ScriptOrder(MusicPlatform.WY.id, ScriptKind.SCRIPT.id, listOf(ScriptRef("a"))),
            ScriptOrder(MusicPlatform.WY.id, ScriptKind.PLUGIN.id, listOf(ScriptRef("p"))),
        )
        assertEquals(listOf("a"), orders.enabledIds(MusicPlatform.WY, ScriptKind.SCRIPT))
        assertEquals(listOf("p"), orders.enabledIds(MusicPlatform.WY, ScriptKind.PLUGIN))
        assertEquals(setOf("p"), orders.residentIds(ScriptKind.PLUGIN))
    }

    @Test
    fun `ScriptKind 从 id 还原且未知 id 落到脚本`() {
        assertEquals(ScriptKind.SCRIPT, ScriptKind.fromId("script"))
        assertEquals(ScriptKind.PLUGIN, ScriptKind.fromId("plugin"))
        assertEquals(ScriptKind.SCRIPT, ScriptKind.fromId("不认识的类型"))
        assertEquals(ScriptKind.SCRIPT, ScriptKind.fromId(null))
    }

    /* ---------------- 持久化往返 ---------------- */

    @Test
    fun `顺序配置可 JSON 往返且内容不变`() {
        val orders = MusicPlatform.entries.flatMap { p ->
            ScriptKind.entries.map { k ->
                ScriptOrder(
                    platformId = p.id,
                    kind = k.id,
                    refs = listOf(ScriptRef("x"), ScriptRef("y", enabled = false)),
                )
            }
        }
        val back = AppJson.decodeFromString<List<ScriptOrder>>(AppJson.encodeToString(orders))
        assertEquals(orders, back)
    }

    @Test
    fun `编辑过的顺序确实与默认不同（UI 回显默认值的依据）`() {
        val base = ScriptOrder.defaultFor(
            MusicPlatform.WY,
            ScriptKind.SCRIPT,
            listOf("a", "b"),
        )
        assertNotEquals(base, base.move("a", up = false))
        assertNotEquals(base, base.toggle("a"))
    }
}
