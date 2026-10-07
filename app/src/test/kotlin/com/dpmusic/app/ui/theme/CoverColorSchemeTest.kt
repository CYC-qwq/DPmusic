package com.dpmusic.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「播放页封面动态取色」配色生成层测试。
 *
 * 这层是封面种子色 → MD3 配色的唯一映射点，一旦 MCU 的 scheme 构造签名或
 * 槽位映射写错，就会在播放页表现为「整页配色糊成一团」或直接崩溃，因此按
 * 风格 × 深浅全量跑一遍并校验关键槽位。
 */
class CoverColorSchemeTest {

    private val seed = Color(0xFF3F51B5)

    @Test
    fun `每种风格在深浅两种模式下都能生成完整配色`() {
        CoverColorStyle.entries.forEach { style ->
            listOf(true, false).forEach { isDark ->
                val scheme = coverColorScheme(seed, isDark, style)
                assertNotEquals("$style/$isDark primary 未解析", Color.Unspecified, scheme.primary)
                assertNotEquals("$style/$isDark onSurface 未解析", Color.Unspecified, scheme.onSurface)
                assertNotEquals("$style/$isDark outline 未解析", Color.Unspecified, scheme.outline)
                assertNotEquals("$style/$isDark surfaceContainer 未解析", Color.Unspecified, scheme.surfaceContainer)
                assertNotEquals("$style/$isDark primaryFixed 未解析", Color.Unspecified, scheme.primaryFixed)
            }
        }
    }

    @Test
    fun `深色配色确实比浅色配色暗`() {
        CoverColorStyle.entries.forEach { style ->
            val light = coverColorScheme(seed, isDark = false, style = style).surface.luminance()
            val dark = coverColorScheme(seed, isDark = true, style = style).surface.luminance()
            assertTrue("$style 的深色 surface 应比浅色更暗（浅=$light 深=$dark）", dark < light)
        }
    }

    @Test
    fun `文字色与所在表面形成足够对比`() {
        CoverColorStyle.entries.forEach { style ->
            listOf(true, false).forEach { isDark ->
                val scheme = coverColorScheme(seed, isDark, style)
                val surfaceLum = scheme.surface.luminance()
                val onSurfaceLum = scheme.onSurface.luminance()
                val ratio = (maxOf(surfaceLum, onSurfaceLum) + 0.05f) / (minOf(surfaceLum, onSurfaceLum) + 0.05f)
                // M3 标准档要求正文对比度 >= 4.5:1（深色 7:1）
                assertTrue("$style/$isDark 的 onSurface 对比度不足：$ratio", ratio >= 4.5f)
            }
        }
    }

    @Test
    fun `不同种子色推导出不同主色`() {
        val red = coverColorScheme(Color(0xFFB71C1C), isDark = false, style = CoverColorStyle.Fidelity).primary
        val green = coverColorScheme(Color(0xFF1B5E20), isDark = false, style = CoverColorStyle.Fidelity).primary
        assertNotEquals("种子色不同，primary 不应相同", red, green)
    }

    @Test
    fun `风格 id 解析对未知值回退默认风格`() {
        assertEquals(CoverColorStyle.Content, CoverColorStyle.default)
        assertEquals(CoverColorStyle.default, CoverColorStyle.fromId(null))
        assertEquals(CoverColorStyle.default, CoverColorStyle.fromId(""))
        assertEquals(CoverColorStyle.default, CoverColorStyle.fromId("no_such_style"))
    }

    @Test
    fun `每种风格的 id 唯一且可往返解析`() {
        val ids = CoverColorStyle.entries.map { it.id }
        assertEquals("风格 id 不应重复", ids.size, ids.toSet().size)
        CoverColorStyle.entries.forEach { style ->
            assertEquals(style, CoverColorStyle.fromId(style.id))
        }
    }
}
