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
 * 逐平台解析链路的核心规则测试。
 *
 * 这些规则全都直接影响**用户按播放键之后会发生什么**，但都藏在 UI 后面、
 * 肉眼看不出来，所以用单测钉住：顺序语义、启停语义、以及旧设置迁移。
 */
class SourceChainTest {

    /* ---------------- 顺序语义 ---------------- */

    @Test
    fun `默认链路包含该平台支持的全部引擎`() {
        SourceEngine.entries.forEach { engine ->
            // 酷狗概念版只该出现在酷狗链路里
            val kre = SourceChain.defaultFor(MusicPlatform.KG)
            val wy = SourceChain.defaultFor(MusicPlatform.WY)
            if (engine.supports(MusicPlatform.KG)) {
                assertTrue("酷狗链路应含 ${engine.id}", engine.id in kre.engines)
            }
            if (!engine.supports(MusicPlatform.WY)) {
                assertFalse("网易云链路不该含 ${engine.id}", engine.id in wy.engines)
            }
        }
    }

    @Test
    fun `默认链路酷狗概念版排在首位（开箱可播的免费通道）`() {
        val kg = SourceChain.defaultFor(MusicPlatform.KG)
        assertEquals(SourceEngine.KGLITE.id, kg.engines.first())
        assertEquals(SourceEngine.KGLITE, kg.activeEngines().first())
    }

    @Test
    fun `上移下移改变尝试顺序`() {
        var chain = SourceChain.defaultFor(MusicPlatform.WY)
        val first = chain.order.first()
        val second = chain.order[1]

        chain = chain.move(first, up = false)   // 把第一项下移
        assertEquals(listOf(second, first), chain.order.take(2))
    }

    @Test
    fun `越界移动不改变链路（顶项上移与底项下移都是空操作）`() {
        val chain = SourceChain.defaultFor(MusicPlatform.WY)
        assertEquals(chain, chain.move(chain.order.first(), up = true))
        assertEquals(chain, chain.move(chain.order.last(), up = false))
    }

    /* ---------------- 启停语义 ---------------- */

    @Test
    fun `停用项仍留在原位但不再参与解析`() {
        val chain = SourceChain.defaultFor(MusicPlatform.KG)
        val target = chain.order[1]          // 取第二项，确保不是首项
        val off = chain.toggle(target)

        // 位置保留（这样用户重新打开开关时不会跑到别处）
        assertEquals(chain.engines, off.engines)
        // 但不出现在生效链里
        assertFalse(off.activeEngines().contains(target))
        assertFalse(off.isEnabled(target))
    }

    @Test
    fun `不允许关掉最后一项（否则该平台彻底无法解析）`() {
        // 构造只剩一项的链路
        var chain = SourceChain.defaultFor(MusicPlatform.WY)
        chain.order.drop(1).forEach { chain = chain.toggle(it) }
        assertEquals(1, chain.activeEngines().size)

        val last = chain.activeEngines().single()
        // 试图关掉最后一项 → 被拒绝，链路保持不变
        assertEquals(chain, chain.toggle(last))
        assertEquals(1, chain.activeEngines().size)
    }

    @Test
    fun `不支持的引擎不出现在生效链里（概念版对网易云）`() {
        // 即便强行把 kglite 塞进网易云链路，也不该生效
        val chain = SourceChain(
            platformId = MusicPlatform.WY.id,
            engines = listOf(SourceEngine.KGLITE.id, SourceEngine.KEY.id),
        )
        assertEquals(listOf(SourceEngine.KEY), chain.activeEngines())
    }

    /* ---------------- 旧设置迁移 ---------------- */

    @Test
    fun `仅Key迁移后只剩Key引擎`() {
        val chain = SourceChain.defaultsFor(MusicPlatform.WY, SourcePriority.KEY_ONLY)
        assertEquals(listOf(SourceEngine.KEY.id), chain.engines)
    }

    @Test
    fun `仅脚本迁移后只剩脚本引擎`() {
        val chain = SourceChain.defaultsFor(MusicPlatform.WY, SourcePriority.SCRIPT_ONLY)
        assertEquals(listOf(SourceEngine.SCRIPT.id), chain.engines)
    }

    @Test
    fun `脚本优先迁移后脚本排第一`() {
        val chain = SourceChain.defaultsFor(MusicPlatform.WY, SourcePriority.SCRIPT_FIRST)
        assertEquals(SourceEngine.SCRIPT.id, chain.engines.first())
        // 其余引擎一个不丢（比较 id 集合；`engines` 是 List<String>，直接比会类型不匹配）
        assertEquals(
            SourceEngine.entries.filter { it.supports(MusicPlatform.WY) }.map { it.id }.toSet(),
            chain.engines.toSet(),
        )
    }

    @Test
    fun `无旧设置时用默认链`() {
        assertEquals(
            SourceChain.defaultFor(MusicPlatform.WY),
            SourceChain.defaultsFor(MusicPlatform.WY, null),
        )
    }

    /* ---------------- 持久化容错 ---------------- */

    @Test
    fun `sanitize 补齐缺失平台并丢弃非法引擎`() {
        val stored = listOf(
            SourceChain(
                platformId = MusicPlatform.WY.id,
                engines = listOf("不存在的引擎", SourceEngine.KEY.id),
            ),
        )
        val result = SourceChain.sanitize(stored, legacy = null)

        // 每个平台都有一条
        assertEquals(MusicPlatform.entries.size, result.size)
        // 非法项被丢掉、合法项保留
        val wy = result.first { it.platformId == MusicPlatform.WY.id }
        assertEquals(SourceEngine.KEY.id, wy.engines.first())
        assertFalse(wy.engines.contains("不存在的引擎"))
        // 缺失的引擎被补到末尾（升级后新增引擎也能参与解析）
        assertTrue(wy.engines.contains(SourceEngine.SCRIPT.id))
    }

    @Test
    fun `sanitize 丢弃平台不支持的引擎`() {
        val stored = listOf(
            SourceChain(
                platformId = MusicPlatform.QQ.id,
                engines = listOf(SourceEngine.KGLITE.id, SourceEngine.KEY.id),
            ),
        )
        val qq = SourceChain.sanitize(stored, null).first { it.platformId == MusicPlatform.QQ.id }
        assertFalse("酷狗概念版不该出现在 QQ 链路", qq.engines.contains(SourceEngine.KGLITE.id))
        assertTrue(qq.engines.contains(SourceEngine.KEY.id))
    }

    @Test
    fun `空存储时按旧优先级生成默认链`() {
        val result = SourceChain.sanitize(emptyList(), SourcePriority.SCRIPT_ONLY)
        result.forEach { chain ->
            assertEquals(
                "每个平台都该只剩脚本",
                listOf(SourceEngine.SCRIPT.id),
                chain.engines,
            )
        }
    }

    /* ---------------- JSON 往返 ---------------- */

    @Test
    fun `链路可 JSON 往返且内容不变`() {
        val chains = MusicPlatform.entries.map { SomeEdits.edit(SourceChain.defaultFor(it)) }
        val json = AppJson.encodeToString(chains)
        val back = AppJson.decodeFromString<List<SourceChain>>(json)
        assertEquals(chains, back)
    }

    private object SomeEdits {
        /** 制造一份「非默认」的链路：交换前两项 + 关掉末项 */
        fun edit(chain: SourceChain): SourceChain {
            var c = chain
            if (c.engines.size >= 2) c = c.move(c.order[0], up = false)
            if (c.activeEngines().size > 1) c = c.toggle(c.order.last())
            return c
        }
    }

    @Test
    fun `edited 链路确实与默认不同（UI 圆点判定依据）`() {
        val platform = MusicPlatform.WY
        val edited = SomeEdits.edit(SourceChain.defaultFor(platform))
        assertNotEquals(SourceChain.defaultFor(platform), edited)
    }
}