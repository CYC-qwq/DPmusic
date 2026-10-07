package com.dpmusic.app.core.util

import android.content.ComponentCallbacks2
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 内存压力响应的行为测试。
 *
 * 这些规则决定「系统说内存紧张时，应用到底丢不丢东西、丢哪一档」——
 * 全在系统回调后面，肉眼与 UI 都看不出来，故用单测钉住。
 *
 * 重点钉三件事：
 * 1. **档位映射**：系统 6 个 trim 常量各自对应本应用的哪一档；
 * 2. **分级调度**：低档位不能触发高档位的动作（省着丢，别一紧张就清空）；
 * 3. **同档去重**：系统重复投递同一档时不能反复清理（否则界面会抖动）。
 */
class MemoryPressureCenterTest {

    private val calls = mutableListOf<String>()

    @Before
    fun setUp() {
        MemoryPressureCenter.resetForTest()
        calls.clear()
    }

    @After
    fun tearDown() {
        MemoryPressureCenter.resetForTest()
    }

    /* ---------------- 档位映射 ---------------- */

    @Test
    fun `系统 trim 常量映射到四个档位`() {
        assertEquals(
            MemoryPressureCenter.Level.LIGHT,
            MemoryPressureCenter.levelOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE),
        )
        assertEquals(
            MemoryPressureCenter.Level.MODERATE,
            MemoryPressureCenter.levelOf(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN),
        )
        assertEquals(
            MemoryPressureCenter.Level.SEVERE,
            MemoryPressureCenter.levelOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW),
        )
        assertEquals(
            MemoryPressureCenter.Level.SEVERE,
            MemoryPressureCenter.levelOf(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND),
        )
        assertEquals(
            MemoryPressureCenter.Level.CRITICAL,
            MemoryPressureCenter.levelOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL),
        )
        assertEquals(
            MemoryPressureCenter.Level.CRITICAL,
            MemoryPressureCenter.levelOf(ComponentCallbacks2.TRIM_MEMORY_MODERATE),
        )
        assertEquals(
            MemoryPressureCenter.Level.CRITICAL,
            MemoryPressureCenter.levelOf(ComponentCallbacks2.TRIM_MEMORY_COMPLETE),
        )
    }

    @Test
    fun `未知 trim 常量不触发任何动作`() {
        assertEquals(MemoryPressureCenter.Level.NONE, MemoryPressureCenter.levelOf(12345))
        assertEquals(MemoryPressureCenter.Level.NONE, MemoryPressureCenter.handle(12345))
        assertTrue("未知档不该调用降载器", calls.isEmpty())
    }

    /* ---------------- 分级调度 ---------------- */

    @Test
    fun `低档位只触发低档降载器`() {
        register("light", MemoryPressureCenter.Level.LIGHT)
        register("severe", MemoryPressureCenter.Level.SEVERE)

        MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)

        assertEquals("只该跑 light", listOf("light"), calls)
    }

    @Test
    fun `高档位触发所有不高于它的降载器`() {
        register("light", MemoryPressureCenter.Level.LIGHT)
        register("moderate", MemoryPressureCenter.Level.MODERATE)
        register("severe", MemoryPressureCenter.Level.SEVERE)

        MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

        assertEquals(
            "CRITICAL 应带上全部三档",
            setOf("light", "moderate", "severe"),
            calls.toSet(),
        )
    }

    @Test
    fun `降载器收到的 level 是当前档位`() {
        var seen: MemoryPressureCenter.Level? = null
        MemoryPressureCenter.register("probe", MemoryPressureCenter.Level.LIGHT) { seen = it }

        MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertEquals(MemoryPressureCenter.Level.SEVERE, seen)
    }

    /* ---------------- 同档去重 ---------------- */

    @Test
    fun `同一档重复投递只执行一次`() {
        register("light", MemoryPressureCenter.Level.LIGHT)

        repeat(5) { MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) }

        assertEquals("重复投递不该反复清理", listOf("light"), calls)
        assertEquals(1, MemoryPressureCenter.totalTrims)
    }

    @Test
    fun `降档不重跑（已达 CRITICAL 后再来 LIGHT 忽略）`() {
        register("light", MemoryPressureCenter.Level.LIGHT)

        MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        calls.clear()
        MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)

        assertTrue("低档不该在已处理高档后重跑", calls.isEmpty())
    }

    @Test
    fun `升级档位会执行更高档的动作`() {
        register("light", MemoryPressureCenter.Level.LIGHT)
        register("severe", MemoryPressureCenter.Level.SEVERE)

        MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(listOf("light"), calls)

        MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals("升级后应补跑 severe（light 会重跑，故用集合判断）", true, calls.contains("severe"))
    }

    @Test
    fun `reset 后允许再次响应同档`() {
        register("light", MemoryPressureCenter.Level.LIGHT)

        MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        MemoryPressureCenter.reset()
        MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)

        assertEquals(2, calls.size)
        assertEquals(2, MemoryPressureCenter.totalTrims)
    }

    /* ---------------- 健壮性 ---------------- */

    @Test
    fun `单个降载器抛异常不影响其它降载器`() {
        MemoryPressureCenter.register("boom", MemoryPressureCenter.Level.LIGHT) {
            throw IllegalStateException("降载时炸了")
        }
        register("after", MemoryPressureCenter.Level.LIGHT)

        MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)

        assertEquals("后面的降载器仍须执行", listOf("after"), calls)
    }

    @Test
    fun `同名注册覆盖旧的（幂等装配）`() {
        val first = AtomicInteger()
        val second = AtomicInteger()
        MemoryPressureCenter.register("dup", MemoryPressureCenter.Level.LIGHT) { first.incrementAndGet() }
        MemoryPressureCenter.register("dup", MemoryPressureCenter.Level.LIGHT) { second.incrementAndGet() }

        MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)

        assertEquals("旧的不该再被调用", 0, first.get())
        assertEquals(1, second.get())
        assertEquals(1, MemoryPressureCenter.registeredCount)
    }

    @Test
    fun `unregister 后不再被调用`() {
        register("temp", MemoryPressureCenter.Level.LIGHT)
        MemoryPressureCenter.unregister("temp")

        MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `没有降载器时也不崩`() {
        val level = MemoryPressureCenter.handle(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(MemoryPressureCenter.Level.CRITICAL, level)
    }

    @Test
    fun `snapshot 返回非空且堆上限大于零`() {
        val snap = MemoryPressureCenter.snapshot()
        assertNotNull(snap)
        assertTrue("JVM 堆上限应大于 0", snap.javaMaxBytes > 0)
        assertTrue("已用堆不应为负", snap.javaUsedBytes >= 0)
    }

    private fun register(name: String, level: MemoryPressureCenter.Level) {
        MemoryPressureCenter.register(name, level) { calls += name }
    }
}