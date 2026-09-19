package com.dpmusic.app.core.util

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.net.Http
import java.io.File
import okio.Path.Companion.toPath

/**
 * 应用存储管理（封面 / 音源图片缓存 + 临时文件）：
 *
 * - 图片缓存上限由设置决定（256MB / 512MB / 1GB / 2GB / 不限制），
 *   磁盘缓存按上限构建，超限时自动按「最久未用」逐出；
 * - 智能清理按多因素优先级执行：
 *   ① 可再生成性 —— 临时分享文件最先清（完全可再生）；
 *   ② 最近使用时间 —— 图片缓存由磁盘 LRU 按最久未用收缩；
 *   ③ 目标水位 —— 清理至设置上限以内。
 */
object StorageManager {

    /** Coil3 磁盘缓存目录名（与默认一致，保证既有缓存无缝沿用） */
    const val IMAGE_CACHE_DIR = "coil3_disk_cache"
    private const val TEMP_DIR = "shared"
    private const val MB = 1024L * 1024L
    private const val UNLIMITED_MB = 4096L
    private const val MIN_CAP_MB = 64L

    data class Usage(
        val imageCacheBytes: Long = 0L,
        val tempBytes: Long = 0L,
        val totalCacheBytes: Long = 0L,
    )

    data class CleanResult(
        val freedBytes: Long,
        val actions: List<String>,
    )

    fun imageCacheDir(context: Context): File = File(context.cacheDir, IMAGE_CACHE_DIR)

    private fun tempDir(context: Context): File = File(context.cacheDir, TEMP_DIR)

    fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach { f ->
            total += if (f.isDirectory) dirSize(f) else f.length()
        }
        return total
    }

    fun usage(context: Context): Usage = Usage(
        imageCacheBytes = dirSize(imageCacheDir(context)),
        tempBytes = dirSize(tempDir(context)),
        totalCacheBytes = dirSize(context.cacheDir),
    )

    /** 设置值（MB）→ 字节；0 = 不限制（按 4GB 执行） */
    fun capBytesOf(capMb: Int): Long {
        val mb = if (capMb <= 0) UNLIMITED_MB else capMb.toLong()
        return mb.coerceAtLeast(MIN_CAP_MB) * MB
    }

    fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.1f MB", bytes / mb)
            bytes >= kb -> String.format("%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    /** 按指定上限构建图片加载器（默认读取设置值） */
    fun buildImageLoader(
        context: Context,
        capMb: Int = AppContainer.settings.settings.value.maxStorageMb,
    ): ImageLoader {
        val capBytes = capBytesOf(capMb)
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { Http.client }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(IMAGE_CACHE_DIR).absolutePath.toPath())
                    .maxSizeBytes(capBytes)
                    .build()
            }
            .build()
    }

    /**
     * 运行时应用新的上限：重建单例加载器并立即物化新缓存。
     * 新磁盘缓存实例打开时会按 LRU 将最久未用的条目收缩到上限以内，
     * 后续写入也会持续自动维持。
     */
    fun applyImageCap(context: Context, capMb: Int) {
        runCatching {
            val old = runCatching { SingletonImageLoader.get(context).diskCache }.getOrNull()
            SingletonImageLoader.setUnsafe { ctx -> buildImageLoader(ctx, capMb) }
            runCatching { old?.shutdown() }
            // 立即物化新缓存：触发打开与 LRU 收缩
            SingletonImageLoader.get(context).diskCache
        }
    }

    /**
     * 智能清理（多因素优先级）：
     * ① 临时分享文件 → 全清（可再生）；
     * ② 图片缓存超上限 → 重建缓存按「最久未用」收缩。
     * 返回释放字节数 + 执行的动作列表。
     */
    fun smartClean(context: Context): CleanResult {
        val actions = mutableListOf<String>()
        var freed = 0L

        // ① 临时文件（可再生，最先清）
        val temp = tempDir(context)
        val tempSize = dirSize(temp)
        if (tempSize > 0L && temp.deleteRecursively()) {
            freed += tempSize
            actions += "临时分享文件"
        }

        // ② 图片缓存超限：按上限收缩（LRU 逐出最久未用）
        val capMb = AppContainer.settings.settings.value.maxStorageMb
        val capBytes = capBytesOf(capMb)
        val before = dirSize(imageCacheDir(context))
        if (before > capBytes) {
            applyImageCap(context, capMb)
            val after = dirSize(imageCacheDir(context))
            val delta = (before - after).coerceAtLeast(0L)
            freed += delta
            actions += if (delta > 0L) {
                "最久未用的图片缓存"
            } else {
                "图片缓存收缩（上限 ${capLabel(capMb)}）"
            }
        }

        return CleanResult(freed, actions)
    }

    fun capLabel(capMb: Int): String = when {
        capMb <= 0 -> "不限"
        capMb >= 1024 -> "${capMb / 1024}GB"
        else -> "${capMb}MB"
    }
}