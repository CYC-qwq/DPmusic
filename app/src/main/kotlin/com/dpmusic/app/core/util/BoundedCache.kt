package com.dpmusic.app.core.util

/**
 * 一个**明确的、有上限的** LRU 缓存。
 *
 * ## 为什么需要它
 *
 * 「手写 `LinkedHashMap` + `removeEldestEntry`」在本项目出现过 5 次
 * （歌词、封面字节、音质记录、超级岛封面、播放页歌词）。这种写法有 3 个坑，
 * 每一个都真实踩过或差点踩到：
 *
 * 1. **忘记写上界判断** —— `PlayerViewModel.lyricsCache` 曾直接写成
 *    `mutableMapOf`，只增不减，播放页开着听一路就持续累积；
 * 2. **访问序与插入序混淆** —— `LinkedHashMap(cap, loadFactor, accessOrder)`
 *    第三个参数为 `false` 时是插入序（FIFO），"LRU" 名不副实；
 * 3. **多线程误用** —— `LinkedHashMap` 非线程安全，并发写会损坏内部链表
 *    （表现为死循环或丢数据），而它不会像 `ConcurrentHashMap` 那样给出一致的语义。
 *
 * 这个类把上述三点一次性封装：**上限必填**（构造参数）、**固定访问序**、
 * **可选线程安全**（[synchronized] 参数）。用它替换手写版本，
 * 新增缓存就没机会再漏写上界。
 *
 * ## 与 `android.util.LruCache` 的关系
 *
 * 后者按**条目字节数**计量，适合已知单条大小的场景（本项目 5 个 resolver 用它）。
 * 本类按**条目数**计量，适合「每条大小不一、只关心条数」的场景（歌词、音质记录）。
 * 两者并存，各取所长 —— 不是重复造轮子。
 *
 * ## 不做的事
 *
 * 不实现 `Map` 接口：只暴露真正需要的操作（[get] / [put] / [size] / [containsKey] /
 * [keys] / [remove] / [clear]），避免调用方无意中用 `forEach`、`entries` 等
 * 绕过线程保护的遍历方式。
 *
 * @param maxEntries 容量上限（须 > 0）。超出后自动逐出**最久未访问**的条目。
 * @param synchronized 访问是否加锁。单线程（如限定在某个 Looper/dispatcher 上）
 *   场景传 `false` 可省去锁开销；跨线程场景必须传 `true`。
 */
class BoundedCache<K : Any, V : Any>(
    val maxEntries: Int,
    private val synchronized: Boolean = false,
) {
    init {
        require(maxEntries > 0) { "maxEntries 必须为正数，收到 $maxEntries" }
    }

    // accessOrder = true：迭代顺序为「最久未访问 → 最近访问」，
    // removeEldestEntry 逐出的正是最久未访问的那个（真 LRU）。
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    private inline fun <T> locked(block: () -> T): T =
        if (synchronized) kotlin.synchronized(map, block) else block()

    /** 读取（命中会把该条标记为「最近访问」，影响逐出顺序） */
    fun get(key: K): V? = locked { map[key] }

    /** 写入；超上限时自动逐出最久未访问的 */
    fun put(key: K, value: V) = locked { map[key] = value }

    /** 仅在缺失时写入（避免覆盖更新的值） */
    fun putIfAbsent(key: K, value: V) = locked { map.putIfAbsent(key, value) }

    fun remove(key: K): V? = locked { map.remove(key) }

    fun containsKey(key: K): Boolean = locked { map.containsKey(key) }

    fun clear() = locked { map.clear() }

    val size: Int get() = locked { map.size }

    /**
     * 快照当前全部键（**按最久未访问在前**）。
     *
     * 返回副本而非视图：调用方通常在拿到键之后要逐个 `remove`，
     * 直接遍历视图会抛 `ConcurrentModificationException`。
     */
    fun keys(): List<K> = locked { map.keys.toList() }

    /**
     * 收缩到 [keep] 条，[protect] 指定的键（如"当前正在播放的曲目"）永不逐出。
     *
     * 语义：**目标条数就是 [keep]**（含被保护的那一条）。
     * 若 [keep] 为 0 且存在待保护的键，最终会剩下 1 条 —— 保护优先于数值。
     *
     * @return 实际逐出的条数
     */
    fun trimTo(keep: Int, protect: K? = null): Int = locked {
        if (map.size <= keep) return@locked 0
        // 要逐出的总数 = 当前条数 - 目标条数。
        // 注意不能写成「目标条数 - 保护条数」再去算差值：那样会多逐出一条。
        val victims = map.keys.asSequence()
            .filter { it != protect }
            .take((map.size - keep).coerceAtLeast(0))
            .toList()
        victims.forEach { map.remove(it) }
        victims.size
    }

    /** 供诊断 / 测试：全部键值快照（顺序同 [keys]） */
    fun snapshot(): Map<K, V> = locked { LinkedHashMap(map) }
}