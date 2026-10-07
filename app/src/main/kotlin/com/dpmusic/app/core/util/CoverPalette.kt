package com.dpmusic.app.core.util

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.dpmusic.app.core.mcu.hct.Hct
import com.dpmusic.app.core.mcu.quantize.QuantizerCelebi
import com.dpmusic.app.core.mcu.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面取色：
 * - [colors]：Palette 多色提取，供播放页「流光背景」与卡片微光使用（要求氛围感，可多色）；
 * - [seed]：Material Color Utilities 单种子色，供「封面动态取色」生成一整套 MD3 配色
 *   （要求单一、可推导，故走 Celebi 量化 + Score 排序，与 Color.kt 内置色板同源）。
 * 两者提取失败均静默降级（返回空 / null），由调用方回退主题色。
 */
object CoverPalette {

    private val cache = object : LruCache<String, List<Color>>(48) {}
    private val seedCache = object : LruCache<String, Color>(48) {}

    suspend fun colors(context: Context, url: String): List<Color> {
        if (url.isBlank()) return emptyList()
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val software = loadSoftwareBitmap(context, url) ?: return@runCatching emptyList()
                val palette = Palette.from(software).generate()
                val colors = listOf(
                    palette.getVibrantColor(0),
                    palette.getDarkVibrantColor(0),
                    palette.getLightVibrantColor(0),
                    palette.getMutedColor(0),
                ).filter { it != 0 }.map { Color(it) }
                cache.put(url, colors)
                colors
            }.getOrElse { emptyList() }
        }
    }

    /**
     * 封面种子色（MCU）：取量化结果中「最适合当 UI 主色」的那一个。
     *
     * 返回 null 的两种情况：提取失败；封面近乎无彩色（[MIN_SEED_CHROMA] 以下）——
     * 后者若照常生成配色，会得到一套灰扑扑、与主题色无异的方案，不如让调用方沿用当前主题。
     * 「无种子」结果也进缓存（以 [NO_SEED] 哨兵表示），避免每首歌重复量化灰图。
     */
    suspend fun seed(context: Context, url: String): Color? {
        if (url.isBlank()) return null
        seedCache.get(url)?.let { return it.takeIf { c -> c != NO_SEED } }
        return withContext(Dispatchers.IO) {
            runCatching {
                val software = loadSoftwareBitmap(context, url) ?: return@runCatching null
                val pixels = IntArray(software.width * software.height)
                software.getPixels(pixels, 0, software.width, 0, 0, software.width, software.height)
                val ranked = Score.score(QuantizerCelebi.quantize(pixels, MAX_QUANTIZE_COLORS))
                val argb = ranked.firstOrNull() ?: return@runCatching null
                val chroma = Hct.fromInt(argb).chroma
                val result = if (chroma < MIN_SEED_CHROMA) null else Color(argb)
                seedCache.put(url, result ?: NO_SEED)
                result
            }.getOrElse { null }
        }
    }

    /** Coil 取图 → 软件位图（硬件位图读不到像素，必须转换） */
    private suspend fun loadSoftwareBitmap(context: Context, url: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(140, 140)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val image = (result as? SuccessResult)?.image ?: return null
        val bitmap = image.toBitmap()
        return if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
    }

    fun clear() {
        cache.evictAll()
        seedCache.evictAll()
    }

    /** 量化目标色数（MCU 官方推荐值） */
    private const val MAX_QUANTIZE_COLORS = 128

    /** 低于此彩度视为「无彩色封面」，不生成配色 */
    private const val MIN_SEED_CHROMA = 8.0

    private val NO_SEED = Color(0)
}