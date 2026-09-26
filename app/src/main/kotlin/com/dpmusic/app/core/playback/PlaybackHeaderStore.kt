package com.dpmusic.app.core.playback

/**
 * 播放地址请求头登记表（URL → HTTP 头）。
 *
 * 背景：部分音源（尤其 MusicFree 插件解析出的地址）必须携带 Referer / User-Agent 等
 * 请求头才能播放，而 Media3 的 `MediaItem` 无法携带请求头。
 * 因此解析成功后把「地址 → 头」登记在这里，播放侧通过
 * `ResolvingDataSource` 在真正发请求前注入（见 MusicService）。
 *
 * 容量有上限（FIFO 淘汰），仅保存最近解析过的地址。
 */
object PlaybackHeaderStore {

    private const val MAX_ENTRIES = 64

    private val entries = LinkedHashMap<String, Map<String, String>>()

    @Synchronized
    fun put(url: String, headers: Map<String, String>) {
        if (url.isBlank() || headers.isEmpty()) return
        entries.remove(url)
        entries[url] = headers
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    @Synchronized
    fun headersFor(url: String): Map<String, String> {
        if (url.isBlank()) return emptyMap()
        entries[url]?.let { return it }
        // 命中不到精确地址时按前缀兜底（部分音源会带 ?token= 等动态参数）
        val prefix = url.substringBefore('?')
        entries.entries.firstOrNull { it.key.substringBefore('?') == prefix }?.let { return it.value }
        return emptyMap()
    }

    @Synchronized
    fun clear() = entries.clear()
}