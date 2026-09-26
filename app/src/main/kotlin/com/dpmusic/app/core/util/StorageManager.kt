package com.dpmusic.app.core.util

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.net.Http
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

/**
 * 应用存储管理（封面 / 音源图片缓存 + 临时文件）：
 *
 * - 图片缓存上限由设置决定（256MB / 512MB / 1GB / 2GB / 不限制），
 *   磁盘缓存按上限构建，超限时自动按「最久未用」逐出；
 * - **自动清理**：选定「最大可占用」后，缓存总占用一旦超过该上限即自动清理
 *   （应用启动 / 每 10 分钟 / 变更上限 / 进入设置页都会检查，未超限零开销）；
 * - 清理按多因素优先级执行：
 *   ① 可再生成性 —— 临时分享文件最先清（完全可再生）；
 *   ② 最近使用时间 —— 图片缓存由磁盘 LRU 按最久未用收缩；
 *   ③ 目标水位 —— 清理至设置上限以内（必要时进一步收缩图片缓存到上限的 80%）。
 */
object StorageManager {

    /** Coil3 磁盘缓存目录名（与默认一致，保证既有缓存无缝沿用） */
    const val IMAGE_CACHE_DIR = "coil3_disk_cache"
    private const val TEMP_DIR = "shared"
    private const val MB = 1024L * 1024L
    private const val UNLIMITED_MB = 4096L
    private const val MIN_CAP_MB = 64L

    /** 应用诊断目录：整缓存裁剪时跳过（日志 / 崩溃记录） */
    private val DIAGNOSTIC_DIRS = setOf("logs", "Crash Reports")

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
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
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
    @OptIn(coil3.annotation.DelicateCoilApi::class)
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
    fun smartClean(context: Context): CleanResult =
        cleanInternal(context, AppContainer.settings.settings.value.maxStorageMb)

    /**
     * **自动清理**：仅当缓存总占用超过设置上限时才动手，未超限直接返回 null。
     * 由 [startAutoClean] 在应用启动时与运行期周期性调用，也可在设置变更 / 进入设置页时调用。
     */
    fun autoCleanIfNeeded(context: Context): CleanResult? {
        val capMb = AppContainer.settings.settings.value.maxStorageMb
        if (dirSize(context.cacheDir) <= capBytesOf(capMb)) return null
        val result = cleanInternal(context, capMb, aggressive = true)
        lastAutoClean = result
        return result
    }

    /** 最近一次自动清理结果（供设置页展示；null = 从未触发） */
    @Volatile
    var lastAutoClean: CleanResult? = null
        private set

    /** 按「最久未用」把目录裁剪到目标字节以内（Coil3 磁盘缓存在打开时不会立即收缩，必须自己删）；返回释放字节数 */
    private fun trimDirByLru(dir: File, targetBytes: Long): Long {
        if (!dir.exists()) return 0L
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= targetBytes) return 0L
        var freed = 0L
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= targetBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                freed += len
            }
        }
        return freed
    }

    /** 清理主体：临时文件全清 + 图片缓存超上限按 LRU 收缩；[aggressive] = 自动清理时若总占用仍超限，进一步收缩到上限内 */
    private fun cleanInternal(context: Context, capMb: Int, aggressive: Boolean = false): CleanResult {
        val actions = mutableListOf<String>()
        var freed = 0L

        // ① 临时文件（可再生，最先清）
        val temp = tempDir(context)
        val tempSize = dirSize(temp)
        if (tempSize > 0L && temp.deleteRecursively()) {
            freed += tempSize
            actions += "临时分享文件"
        }

        // ② 图片缓存超限：按「最久未用」删除到上限以内（并重建加载器，让上限持续生效）
        val capBytes = capBytesOf(capMb)
        val imgFreed = trimDirByLru(imageCacheDir(context), capBytes)
        if (imgFreed > 0L) {
            applyImageCap(context, capMb)
            freed += imgFreed
            actions += "最久未用的图片缓存"
        }

        // ③ 自动清理兜底：清完临时文件后总占用仍超上限 → 把图片缓存进一步收缩到上限的 80%
        //    （图片缓存是占用大头；仅在用户设了明确上限时才这么做，避免「不限」被误收缩）
        if (aggressive && capMb > 0 && dirSize(context.cacheDir) > capBytes) {
            val target = (capBytes * 0.8).toLong()
            val extra = trimDirByLru(imageCacheDir(context), target)
            if (extra > 0L) {
                freed += extra
                actions += "图片缓存收缩至 ${capLabel((capMb * 0.8f).toInt().coerceAtLeast(MIN_CAP_MB.toInt()))}"
            }
        }

        // ④ 仍超限（超出的占用来自其它缓存子目录）→ 对整个缓存目录按「最久未用」裁剪到上限内。
        //    cacheDir 按 Android 约定随时可被系统清除，因此这里清理是安全的；
        //    但排除应用自身的诊断目录（日志 / 崩溃记录），避免把排查线索清掉。
        if (aggressive && capMb > 0 && dirSize(context.cacheDir) > capBytes) {
            val extra = trimCacheDir(context.cacheDir, (capBytes * 0.8).toLong(), DIAGNOSTIC_DIRS)
            if (extra > 0L) {
                freed += extra
                actions += "其它缓存文件"
            }
        }

        return CleanResult(freed, actions)
    }

    /** 整缓存目录 LRU 裁剪：跳过 [keepNames]（应用诊断数据），其余按最久未用删除到目标字节内 */
    private fun trimCacheDir(cacheDir: File, targetBytes: Long, keepNames: Set<String>): Long {
        val candidates = cacheDir.listFiles()?.flatMap { f ->
            when {
                f.isDirectory && f.name in keepNames -> emptyList()
                f.isDirectory -> f.walkTopDown().filter { it.isFile }.toList()
                else -> listOf(f)
            }
        } ?: emptyList()
        var total = dirSize(cacheDir)
        if (total <= targetBytes) return 0L
        var freed = 0L
        for (f in candidates.sortedBy { it.lastModified() }) {
            if (total <= targetBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                freed += len
            }
        }
        return freed
    }

    /* ---------------- 周期自动清理（应用存活期间常驻） ---------------- */

    private const val AUTO_CLEAN_INTERVAL_MS = 10 * 60 * 1000L
    private val janitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var janitorJob: Job? = null

    /**
     * 启动自动清理守护：
     * ① 跟随设置流 —— DataStore 加载完成 / 上限被修改后立即判断一次
     *    （启动瞬间读到的是默认值，不能只依赖首帧）；
     * ② 周期兜底 —— 每 10 分钟判断一次。
     * 只有超过「最大可占用」时才真正清理，未超限零开销。
     */
    fun startAutoClean(context: Context) {
        if (janitorJob?.isActive == true) return
        val app = context.applicationContext
        janitorJob = janitorScope.launch {
            // 跟随设置流：DataStore 加载完成 / 上限被修改后立即判断
            // （启动瞬间读到的是默认值，不能只依赖首帧）
            AppContainer.settings.settings.collect {
                runCatching {
                    autoCleanIfNeeded(app)?.let { r ->
                        AppLogger.i(
                            "Storage",
                            "自动清理：释放 ${formatBytes(r.freedBytes)}（${r.actions.joinToString("、")}）",
                        )
                    }
                }
            }
        }
        janitorScope.launch {
            while (isActive) {
                delay(AUTO_CLEAN_INTERVAL_MS)
                runCatching { autoCleanIfNeeded(app) }
            }
        }
    }

    fun capLabel(capMb: Int): String = when {
        capMb <= 0 -> "不限"
        capMb >= 1024 -> "${capMb / 1024}GB"
        else -> "${capMb}MB"
    }
}