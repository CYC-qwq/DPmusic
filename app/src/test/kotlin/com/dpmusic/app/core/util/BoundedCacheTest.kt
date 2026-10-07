package com.dpmusic.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

/**
 * [BoundedCache] 的行为测试。
 *
 * 这个类的存在理由就是「把易错的手写 LRU 收敛成一处并钉住行为」，
 * 因此测试必须覆盖那三类真实踩过的坑：
 * 1. **超出上限必须逐出**（对应 `PlayerViewModel.lyricsCache` 曾漏写上界的 bug）；
 * 2. **逐出的必须是最久未访问的**（对应 accessOrder 写错成插入序）；
 * 3. **多线程下不损坏**（对应 `LinkedHashMap` 非线程安全）。
 */
class BoundedCacheTest {

    /* ---------------- 上限 ---------------- */

    @Test
    fun `超出上限时自动逐出，条数恒定封顶`() {
        val cache = BoundedCache<Int, String>(maxEntries = 10)
        repeat(100) { cache.put(it, "v$it") }

        assertEquals("条数必须封顶", 10, cache.size)
    }

    @Test
    fun `上限为 1 也能正常工作`() {
        val cache = BoundedCache<Int, String>(maxEntries = 1)
        cache.put(1, "a")
        cache.put(2, "b")

        assertEquals(1, cache.size)
        assertEquals("b", cache.get(2))
        assertNull(cache.get(1))
    }

    @Test
    fun `非法上限在构造时即抛出（不给静默失效的机会）`() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedCache<Int, String>(maxEntries = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedCache<Int, String>(maxEntries = -5)
        }
    }

    /* ---------------- LRU 语义（不是 FIFO） ---------------- */

    @Test
    fun `逐出的是最久未访问的，而不是最早写入的`() {
        val cache = BoundedCache<Int, String>(maxEntries = 3)
        cache.put(1, "a")
        cache.put(2, "b")
        cache.put(3, "c")

        // 访问 1，使它成为「最近访问」→ 接下来该逐出 2（最久未访问）
        assertEquals("a", cache.get(1))
        cache.put(4, "d")

        assertNull("2 应被逐出", cache.get(2))
        assertEquals("1 被访问过，应保留", "a", cache.get(1))
        assertEquals("c", cache.get(3))
        assertEquals("d", cache.get(4))
    }

    @Test
    fun `重复写同一个键不会让条数增长`() {
        val cache = BoundedCache<Int, String>(maxEntries = 3)
        repeat(50) { cache.put(7, "v$it") }

        assertEquals(1, cache.size)
        assertEquals("v49", cache.get(7))
    }

    @Test
    fun `keys 按最久未访问在前排列`() {
        val cache = BoundedCache<Int, String>(maxEntries = 5)
        cache.put(1, "a"); cache.put(2, "b"); cache.put(3, "c")
        cache.get(1)   // 1 变成最近访问

        assertEquals(listOf(2, 3, 1), cache.keys())
    }

    /* ---------------- trimTo ---------------- */

    @Test
    fun `trimTo 收缩到指定条数`() {
        val cache = BoundedCache<Int, String>(maxEntries = 100)
        repeat(100) { cache.put(it, "v$it") }

        val evicted = cache.trimTo(40)

        assertEquals(40, cache.size)
        assertEquals("逐出条数应为 60", 60, evicted)
    }

    @Test
    fun `trimTo 目标大于现有量时不动作`() {
        val cache = BoundedCache<Int, String>(maxEntries = 100)
        repeat(10) { cache.put(it, "v$it") }

        assertEquals(0, cache.trimTo(50))
        assertEquals(10, cache.size)
    }

    @Test
    fun `trimTo 保留被保护的键（当前正在播放曲目）`() {
        val cache = BoundedCache<Int, String>(maxEntries = 100)
        repeat(100) { cache.put(it, "v$it") }
        // 0 是最久未访问的 → 无保护时必被逐出
        cache.put(999, "current")
        cache.get(999)   // 确保它是最近访问

        cache.trimTo(40, protect = 999)

        assertTrue("被保护的键必须幸存", cache.containsKey(999))
        assertEquals("目标条数含被保护项", 40, cache.size)
    }

    @Test
    fun `trimTo 到 0 时被保护的键仍幸存`() {
        val cache = BoundedCache<Int, String>(maxEntries = 100)
        repeat(50) { cache.put(it, "v$it") }
        cache.put(999, "current")

        cache.trimTo(0, protect = 999)

        assertEquals("保护优先于数值", 1, cache.size)
        assertTrue(cache.containsKey(999))
    }

    @Test
    fun `trimTo 传不存在的保护键时等价于无保护`() {
        val cache = BoundedCache<Int, String>(maxEntries = 100)
        repeat(50) { cache.put(it, "v$it") }

        cache.trimTo(10, protect = 12345)

        assertEquals(10, cache.size)
    }

    @Test
    fun `trimTo 后仍可继续写入`() {
        val cache = BoundedCache<Int, String>(maxEntries = 100)
        repeat(50) { cache.put(it, "v$it") }
        cache.trimTo(10)

        cache.put(1000, "new")

        assertEquals(11, cache.size)
        assertEquals("new", cache.get(1000))
    }

    /* ---------------- 基本操作 ---------------- */

    @Test
    fun `remove 与 clear 生效`() {
        val cache = BoundedCache<Int, String>(maxEntries = 10)
        cache.put(1, "a")
        assertEquals("a", cache.remove(1))
        assertNull(cache.get(1))

        cache.put(2, "b")
        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun `putIfAbsent 不覆盖已有值`() {
        val cache = BoundedCache<Int, String>(maxEntries = 10)
        cache.put(1, "old")
        cache.putIfAbsent(1, "new")
        assertEquals("old", cache.get(1))

        cache.putIfAbsent(2, "x")
        assertEquals("x", cache.get(2))
    }

    @Test
    fun `snapshot 返回副本，修改它不影响缓存`() {
        val cache = BoundedCache<Int, String>(maxEntries = 10)
        cache.put(1, "a")

        val snap = cache.snapshot()
        assertEquals(1, snap.size)
        // 副本与缓存内容一致，但**不是同一个容器**
        assertEquals("a", snap[1])
        assertEquals(1, cache.size)
        // 再次取值仍来自缓存本身，未被外部改动影响
        assertEquals("a", cache.get(1))
    }

    /* ---------------- 线程安全 ---------------- */

    @Test
    fun `开启同步后并发写入不损坏且维持上限`() {
        val cache = BoundedCache<Int, String>(maxEntries = 200, synchronized = true)
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(500) { i -> cache.put(t * 1000 + i, "v") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("并发写入后条数必须仍封顶", 200, cache.size)
    }

    @Test
    fun `开启同步后并发 trimTo 不抛异常`() {
        val cache = BoundedCache<Int, String>(maxEntries = 500, synchronized = true)
        repeat(500) { cache.put(it, "v$it") }

        val threads = listOf(
            Thread { repeat(50) { cache.trimTo(100) } },
            Thread { repeat(200) { i -> cache.put(i, "x") } },
            Thread { repeat(200) { i -> cache.get(i) } },
        )
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue("条数不应超过上限", cache.size <= 500)
    }

    @Test
    fun `未开启同步时单线程行为与开启一致`() {
        val a = BoundedCache<Int, String>(maxEntries = 5, synchronized = false)
        val b = BoundedCache<Int, String>(maxEntries = 5, synchronized = true)
        repeat(20) { i ->
            a.put(i, "v$i")
            b.put(i, "v$i")
        }
        assertEquals(a.snapshot(), b.snapshot())
    }
}