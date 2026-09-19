package com.dpmusic.app.ui.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 毛玻璃外观令牌（Glassmorphism）：
 * - [enabled] 由设置「外观 → 毛玻璃外观」驱动，经 [LocalGlass] 注入全局；
 * - 关闭时全部玻璃助手原样返回，经典外观零影响；
 * - [blurSupported]：Android 12+（API31）支持 RenderEffect 真实模糊；
 *   低版本自动降级为「半透明 + 高光描边 + 流光底」等效方案。
 */
data class GlassTokens(
    val enabled: Boolean = false,
    val dark: Boolean = true,
) {
    /** 真实模糊支持（Android 12+） */
    val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** 普通面板透明度（卡片 / 导航条 / Mini 条） */
    val panelAlpha: Float get() = if (dark) 0.46f else 0.50f

    /** 强调面板透明度（底部弹层 / 对话框） */
    val strongAlpha: Float get() = if (dark) 0.82f else 0.86f

    /** 玻璃边缘高光描边 */
    val borderColor: Color
        get() = if (dark) Color.White.copy(alpha = 0.18f)
        else Color.White.copy(alpha = 0.90f)

    /** 顶部斜向光泽（绘制在面板内容之下的玻璃反光） */
    val sheenColor: Color
        get() = if (dark) Color.White.copy(alpha = 0.07f)
        else Color.White.copy(alpha = 0.20f)

    /** 流光底光球透明度 */
    val glowAlpha: Float get() = if (dark) 0.30f else 0.50f

    /** 浅色模式面板向白色偏移（形成「亮玻璃」层次；深色模式保持烟熏玻璃） */
    val panelWhiteBlend: Float get() = if (dark) 0f else 0.72f

    /** 玻璃面板真实模糊半径（Android 12+ RenderEffect；iOS 式重模糊） */
    val panelBlurRadius: Dp get() = 48.dp
}

/** 全局毛玻璃令牌；默认关闭（经典外观） */
val LocalGlass = staticCompositionLocalOf { GlassTokens() }

/** 玻璃面板底色：关闭时原样返回；开启时按强度叠加透明度 */
@Composable
fun glassPanelColor(base: Color, strong: Boolean = false): Color {
    val glass = LocalGlass.current
    if (!glass.enabled) return base
    val tinted = if (glass.panelWhiteBlend > 0f) lerp(base, Color.White, glass.panelWhiteBlend) else base
    return tinted.copy(alpha = if (strong) glass.strongAlpha else glass.panelAlpha)
}
