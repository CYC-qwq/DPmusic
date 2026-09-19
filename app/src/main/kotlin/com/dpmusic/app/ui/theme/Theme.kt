package com.dpmusic.app.ui.theme

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

internal val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
)

internal val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
)

/** 主题过渡时长（毫秒）：柔和不拖沓 */
private const val ThemeTransitionMillis = 500

/**
 * DPmusic 主题：
 * - 支持 M3 Dynamic Color（Android 12+，可在设置中关闭）；
 * - 支持主题色板切换（默认品牌配色 + 8 套 MD3 官方配色，设置页可选）；
 * - 支持 跟随系统 / 浅色 / 深色 三态；
 * - 深浅色切换带全槽位颜色过渡动画（单进度驱动，低开销不卡顿）；
 * - 全量接入自定义字阶与 32dp 大圆角形态体系。
 */
@Composable
fun DPmusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    themeColor: String = "default",
    /** 毛玻璃外观模式：透明底 + 全局流光底 + 半透明磨砂面板 */
    glass: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val targetScheme = remember(darkTheme, dynamicColor, themeColor, context) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            else -> {
                val palette = themePaletteById(themeColor)
                if (darkTheme) palette.dark else palette.light
            }
        }
    }

    // 全槽位颜色过渡：深浅切换 / 动态取色开关均平滑过渡，消除瞬时跳变
    val colorScheme = animateColorScheme(key = Triple(darkTheme, dynamicColor, themeColor), target = targetScheme)

    // 毛玻璃模式：背景让位给全局流光底（由外壳统一绘制 GlassBackdrop），各页面 Scaffold 自然透出
    val effectiveScheme = if (glass) colorScheme.copy(background = Color.Transparent) else colorScheme
    CompositionLocalProvider(LocalGlass provides GlassTokens(enabled = glass, dark = darkTheme)) {
        MaterialTheme(
            colorScheme = effectiveScheme,
            typography = DPType,
            shapes = DPShapes,
            content = content,
        )
    }
}

/**
 * 主题过渡：单一进度驱动 + 逐槽位 lerp。
 * 相比 48 个独立颜色动画：每帧仅 1 次状态写入 / 1 个动画协程，开销更低、中途打断更平滑。
 */
@Composable
private fun animateColorScheme(key: Any, target: ColorScheme): ColorScheme {
    val progress = remember { Animatable(1f) }
    var fromScheme by remember { mutableStateOf(target) }
    var toScheme by remember { mutableStateOf(target) }
    var firstRun by remember { mutableStateOf(true) }

    LaunchedEffect(key) {
        if (firstRun) {
            // 首次组合直接对齐目标，不播动画
            firstRun = false
            fromScheme = target
            toScheme = target
            return@LaunchedEffect
        }
        // 以「当前插值状态」为新起点：动画中途切换目标也能平滑接续
        fromScheme = lerpColorScheme(fromScheme, toScheme, progress.value)
        toScheme = target
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = ThemeTransitionMillis, easing = FastOutSlowInEasing),
        )
    }

    return lerpColorScheme(fromScheme, toScheme, progress.value)
}

/** 按进度 t 在两套 ColorScheme 之间逐槽位插值（t>=1 时直接返回目标，零分配） */
private fun lerpColorScheme(from: ColorScheme, to: ColorScheme, t: Float): ColorScheme {
    if (t >= 1f) return to
    return from.copy(
        primary = lerp(from.primary, to.primary, t),
        onPrimary = lerp(from.onPrimary, to.onPrimary, t),
        primaryContainer = lerp(from.primaryContainer, to.primaryContainer, t),
        onPrimaryContainer = lerp(from.onPrimaryContainer, to.onPrimaryContainer, t),
        inversePrimary = lerp(from.inversePrimary, to.inversePrimary, t),
        secondary = lerp(from.secondary, to.secondary, t),
        onSecondary = lerp(from.onSecondary, to.onSecondary, t),
        secondaryContainer = lerp(from.secondaryContainer, to.secondaryContainer, t),
        onSecondaryContainer = lerp(from.onSecondaryContainer, to.onSecondaryContainer, t),
        tertiary = lerp(from.tertiary, to.tertiary, t),
        onTertiary = lerp(from.onTertiary, to.onTertiary, t),
        tertiaryContainer = lerp(from.tertiaryContainer, to.tertiaryContainer, t),
        onTertiaryContainer = lerp(from.onTertiaryContainer, to.onTertiaryContainer, t),
        background = lerp(from.background, to.background, t),
        onBackground = lerp(from.onBackground, to.onBackground, t),
        surface = lerp(from.surface, to.surface, t),
        onSurface = lerp(from.onSurface, to.onSurface, t),
        surfaceVariant = lerp(from.surfaceVariant, to.surfaceVariant, t),
        onSurfaceVariant = lerp(from.onSurfaceVariant, to.onSurfaceVariant, t),
        surfaceTint = lerp(from.surfaceTint, to.surfaceTint, t),
        inverseSurface = lerp(from.inverseSurface, to.inverseSurface, t),
        inverseOnSurface = lerp(from.inverseOnSurface, to.inverseOnSurface, t),
        error = lerp(from.error, to.error, t),
        onError = lerp(from.onError, to.onError, t),
        errorContainer = lerp(from.errorContainer, to.errorContainer, t),
        onErrorContainer = lerp(from.onErrorContainer, to.onErrorContainer, t),
        outline = lerp(from.outline, to.outline, t),
        outlineVariant = lerp(from.outlineVariant, to.outlineVariant, t),
        scrim = lerp(from.scrim, to.scrim, t),
        surfaceBright = lerp(from.surfaceBright, to.surfaceBright, t),
        surfaceDim = lerp(from.surfaceDim, to.surfaceDim, t),
        surfaceContainer = lerp(from.surfaceContainer, to.surfaceContainer, t),
        surfaceContainerHigh = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, t),
        surfaceContainerHighest = lerp(from.surfaceContainerHighest, to.surfaceContainerHighest, t),
        surfaceContainerLow = lerp(from.surfaceContainerLow, to.surfaceContainerLow, t),
        surfaceContainerLowest = lerp(from.surfaceContainerLowest, to.surfaceContainerLowest, t),
        primaryFixed = lerp(from.primaryFixed, to.primaryFixed, t),
        primaryFixedDim = lerp(from.primaryFixedDim, to.primaryFixedDim, t),
        onPrimaryFixed = lerp(from.onPrimaryFixed, to.onPrimaryFixed, t),
        onPrimaryFixedVariant = lerp(from.onPrimaryFixedVariant, to.onPrimaryFixedVariant, t),
        secondaryFixed = lerp(from.secondaryFixed, to.secondaryFixed, t),
        secondaryFixedDim = lerp(from.secondaryFixedDim, to.secondaryFixedDim, t),
        onSecondaryFixed = lerp(from.onSecondaryFixed, to.onSecondaryFixed, t),
        onSecondaryFixedVariant = lerp(from.onSecondaryFixedVariant, to.onSecondaryFixedVariant, t),
        tertiaryFixed = lerp(from.tertiaryFixed, to.tertiaryFixed, t),
        tertiaryFixedDim = lerp(from.tertiaryFixedDim, to.tertiaryFixedDim, t),
        onTertiaryFixed = lerp(from.onTertiaryFixed, to.onTertiaryFixed, t),
        onTertiaryFixedVariant = lerp(from.onTertiaryFixedVariant, to.onTertiaryFixedVariant, t),
    )
}