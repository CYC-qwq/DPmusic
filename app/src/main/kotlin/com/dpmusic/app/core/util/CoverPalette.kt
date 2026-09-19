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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面主色提取（Palette）：
 * 供播放页「流光背景」与卡片微光使用；提取失败静默降级为主题色。
 */
object CoverPalette {

    private val cache = object : LruCache<String, List<Color>>(48) {}

    suspend fun colors(context: Context, url: String): List<Color> {
        if (url.isBlank()) return emptyList()
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(140, 140)
                    .build()
                val result = SingletonImageLoader.get(context).execute(request)
                val image = (result as? SuccessResult)?.image ?: return@runCatching emptyList()
                // 硬件位图无法读取像素：转换为软件位图后再做 Palette 提取
                val bitmap = image.toBitmap()
                val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching emptyList()
                } else {
                    bitmap
                }
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

    fun clear() = cache.evictAll()
}