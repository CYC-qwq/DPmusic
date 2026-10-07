package com.dpmusic.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.dpmusic.app.core.mcu.dynamiccolor.DynamicScheme
import com.dpmusic.app.core.mcu.dynamiccolor.MaterialDynamicColors
import com.dpmusic.app.core.mcu.hct.Hct
import com.dpmusic.app.core.mcu.scheme.SchemeContent
import com.dpmusic.app.core.mcu.scheme.SchemeExpressive
import com.dpmusic.app.core.mcu.scheme.SchemeFidelity
import com.dpmusic.app.core.mcu.scheme.SchemeFruitSalad
import com.dpmusic.app.core.mcu.scheme.SchemeMonochrome
import com.dpmusic.app.core.mcu.scheme.SchemeNeutral
import com.dpmusic.app.core.mcu.scheme.SchemeRainbow
import com.dpmusic.app.core.mcu.scheme.SchemeTonalSpot
import com.dpmusic.app.core.mcu.scheme.SchemeVibrant

/**
 * 播放页「封面动态取色」的配色风格。
 *
 * 均为 Material Color Utilities 的官方 scheme，直接复用 SPEC_2021——与
 * [themePalettes] 里 8 套内置色板同一套规范，因此封面取色后的观感与既有主题一致。
 */
enum class CoverColorStyle(val id: String, val label: String) {
    /** 主色落在 primaryContainer：最贴近封面原色，观感稳定（M3 默认风格） */
    Content("content", "贴近封面"),
    /** 主色即封面主色本身，收敛其他角色（饱和度高，封面辨识度最强） */
    Fidelity("fidelity", "原色"),
    /** 低饱和、克制 */
    TonalSpot("tonal_spot", "柔和"),
    /** 更高饱和，色彩更鲜明 */
    Vibrant("vibrant", "鲜明"),
    /** 色相大幅偏移，更有设计感 */
    Expressive("expressive", "表现"),
    /** 取色相但大幅提高彩度 */
    Rainbow("rainbow", "缤纷"),
    /** 相邻色相 + 中等彩度 */
    FruitSalad("fruit_salad", "清新"),
    /** 纯灰阶，只保留明度层次 */
    Monochrome("monochrome", "黑白"),
    /** 极低彩度，接近中性 */
    Neutral("neutral", "素雅"),
    ;

    companion object {
        val default: CoverColorStyle = Content

        fun fromId(id: String?): CoverColorStyle =
            entries.firstOrNull { it.id == id } ?: default
    }
}

/**
 * 由封面种子色推导整套 MD3 配色。
 *
 * 这里是「封面动态取色」的落点：播放页把封面种子色交进来，换出一套 [ColorScheme]，
 * 播放页整体换用该配色（而非全局主题，避免为了看播放页而改动整个应用外观）。
 *
 * [Hct.fromInt] 会把种子色转换到 HCT 空间，`chroma` 即彩度；调用方（[com.dpmusic.app.core.util.CoverPalette.seed]）
 * 已过滤掉近无彩色封面，此处不再重复判断。
 */
fun coverColorScheme(
    seed: Color,
    isDark: Boolean,
    style: CoverColorStyle = CoverColorStyle.default,
): ColorScheme {
    val hct = Hct.fromInt(seed.toArgb())
    val scheme = dynamicScheme(style, hct, isDark)
    val dynamic = MaterialDynamicColors()
    fun role(color: com.dpmusic.app.core.mcu.dynamiccolor.DynamicColor?): Color =
        color?.let { Color(it.getArgb(scheme)) } ?: Color.Unspecified

    val roles = mapOf(
        "primary" to role(dynamic.primary),
        "onPrimary" to role(dynamic.onPrimary),
        "primaryContainer" to role(dynamic.primaryContainer),
        "onPrimaryContainer" to role(dynamic.onPrimaryContainer),
        "inversePrimary" to role(dynamic.inversePrimary),
        "secondary" to role(dynamic.secondary),
        "onSecondary" to role(dynamic.onSecondary),
        "secondaryContainer" to role(dynamic.secondaryContainer),
        "onSecondaryContainer" to role(dynamic.onSecondaryContainer),
        "tertiary" to role(dynamic.tertiary),
        "onTertiary" to role(dynamic.onTertiary),
        "tertiaryContainer" to role(dynamic.tertiaryContainer),
        "onTertiaryContainer" to role(dynamic.onTertiaryContainer),
        "error" to role(dynamic.error),
        "onError" to role(dynamic.onError),
        "errorContainer" to role(dynamic.errorContainer),
        "onErrorContainer" to role(dynamic.onErrorContainer),
        "background" to role(dynamic.background),
        "onBackground" to role(dynamic.onBackground),
        "surface" to role(dynamic.surface),
        "onSurface" to role(dynamic.onSurface),
        "surfaceVariant" to role(dynamic.surfaceVariant),
        "onSurfaceVariant" to role(dynamic.onSurfaceVariant),
        "outline" to role(dynamic.outline),
        "outlineVariant" to role(dynamic.outlineVariant),
        "scrim" to role(dynamic.scrim),
        "inverseSurface" to role(dynamic.inverseSurface),
        "inverseOnSurface" to role(dynamic.inverseOnSurface),
        "surfaceTint" to role(dynamic.surfaceTint),
        "surfaceBright" to role(dynamic.surfaceBright),
        "surfaceDim" to role(dynamic.surfaceDim),
        "surfaceContainerLowest" to role(dynamic.surfaceContainerLowest),
        "surfaceContainerLow" to role(dynamic.surfaceContainerLow),
        "surfaceContainer" to role(dynamic.surfaceContainer),
        "surfaceContainerHigh" to role(dynamic.surfaceContainerHigh),
        "surfaceContainerHighest" to role(dynamic.surfaceContainerHighest),
        "primaryFixed" to role(dynamic.primaryFixed),
        "primaryFixedDim" to role(dynamic.primaryFixedDim),
        "onPrimaryFixed" to role(dynamic.onPrimaryFixed),
        "onPrimaryFixedVariant" to role(dynamic.onPrimaryFixedVariant),
        "secondaryFixed" to role(dynamic.secondaryFixed),
        "secondaryFixedDim" to role(dynamic.secondaryFixedDim),
        "onSecondaryFixed" to role(dynamic.onSecondaryFixed),
        "onSecondaryFixedVariant" to role(dynamic.onSecondaryFixedVariant),
        "tertiaryFixed" to role(dynamic.tertiaryFixed),
        "tertiaryFixedDim" to role(dynamic.tertiaryFixedDim),
        "onTertiaryFixed" to role(dynamic.onTertiaryFixed),
        "onTertiaryFixedVariant" to role(dynamic.onTertiaryFixedVariant),
    )

    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = roles.getValue("primary"),
        onPrimary = roles.getValue("onPrimary"),
        primaryContainer = roles.getValue("primaryContainer"),
        onPrimaryContainer = roles.getValue("onPrimaryContainer"),
        inversePrimary = roles.getValue("inversePrimary"),
        secondary = roles.getValue("secondary"),
        onSecondary = roles.getValue("onSecondary"),
        secondaryContainer = roles.getValue("secondaryContainer"),
        onSecondaryContainer = roles.getValue("onSecondaryContainer"),
        tertiary = roles.getValue("tertiary"),
        onTertiary = roles.getValue("onTertiary"),
        tertiaryContainer = roles.getValue("tertiaryContainer"),
        onTertiaryContainer = roles.getValue("onTertiaryContainer"),
        error = roles.getValue("error"),
        onError = roles.getValue("onError"),
        errorContainer = roles.getValue("errorContainer"),
        onErrorContainer = roles.getValue("onErrorContainer"),
        background = roles.getValue("background"),
        onBackground = roles.getValue("onBackground"),
        surface = roles.getValue("surface"),
        onSurface = roles.getValue("onSurface"),
        surfaceVariant = roles.getValue("surfaceVariant"),
        onSurfaceVariant = roles.getValue("onSurfaceVariant"),
        outline = roles.getValue("outline"),
        outlineVariant = roles.getValue("outlineVariant"),
        scrim = roles.getValue("scrim"),
        inverseSurface = roles.getValue("inverseSurface"),
        inverseOnSurface = roles.getValue("inverseOnSurface"),
        surfaceTint = roles.getValue("surfaceTint"),
        surfaceBright = roles.getValue("surfaceBright"),
        surfaceDim = roles.getValue("surfaceDim"),
        surfaceContainerLowest = roles.getValue("surfaceContainerLowest"),
        surfaceContainerLow = roles.getValue("surfaceContainerLow"),
        surfaceContainer = roles.getValue("surfaceContainer"),
        surfaceContainerHigh = roles.getValue("surfaceContainerHigh"),
        surfaceContainerHighest = roles.getValue("surfaceContainerHighest"),
        primaryFixed = roles.getValue("primaryFixed"),
        primaryFixedDim = roles.getValue("primaryFixedDim"),
        onPrimaryFixed = roles.getValue("onPrimaryFixed"),
        onPrimaryFixedVariant = roles.getValue("onPrimaryFixedVariant"),
        secondaryFixed = roles.getValue("secondaryFixed"),
        secondaryFixedDim = roles.getValue("secondaryFixedDim"),
        onSecondaryFixed = roles.getValue("onSecondaryFixed"),
        onSecondaryFixedVariant = roles.getValue("onSecondaryFixedVariant"),
        tertiaryFixed = roles.getValue("tertiaryFixed"),
        tertiaryFixedDim = roles.getValue("tertiaryFixedDim"),
        onTertiaryFixed = roles.getValue("onTertiaryFixed"),
        onTertiaryFixedVariant = roles.getValue("onTertiaryFixedVariant"),
    )
}

/** 按风格构造 MCU 官方 scheme（对比度固定为标准档 0.0） */
private fun dynamicScheme(
    style: CoverColorStyle,
    hct: Hct,
    isDark: Boolean,
): DynamicScheme = when (style) {
    CoverColorStyle.Content -> SchemeContent(hct, isDark, STANDARD_CONTRAST)
    CoverColorStyle.Fidelity -> SchemeFidelity(hct, isDark, STANDARD_CONTRAST)
    CoverColorStyle.TonalSpot -> SchemeTonalSpot(hct, isDark, STANDARD_CONTRAST)
    CoverColorStyle.Vibrant -> SchemeVibrant(hct, isDark, STANDARD_CONTRAST)
    CoverColorStyle.Expressive -> SchemeExpressive(hct, isDark, STANDARD_CONTRAST)
    CoverColorStyle.Rainbow -> SchemeRainbow(hct, isDark, STANDARD_CONTRAST)
    CoverColorStyle.FruitSalad -> SchemeFruitSalad(hct, isDark, STANDARD_CONTRAST)
    CoverColorStyle.Monochrome -> SchemeMonochrome(hct, isDark, STANDARD_CONTRAST)
    CoverColorStyle.Neutral -> SchemeNeutral(hct, isDark, STANDARD_CONTRAST)
}

/** 对比度：0.0 = M3 标准档（-1 最低 / 1 最高） */
private const val STANDARD_CONTRAST = 0.0
