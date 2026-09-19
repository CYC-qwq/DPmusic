package com.dpmusic.app.core.lyric

import com.dpmusic.app.core.data.AppSettings

/** 桌面歌词背景样式 */
enum class DesktopLyricBackground(val id: String, val label: String) {
    /** 跟随预设 */
    PRESET("preset", "预设"),

    /** 无背景（纯文字） */
    NONE("none", "无"),

    /** 纯色底板 */
    SOLID("solid", "纯色"),

    /** 毛玻璃胶囊（半透明 + 高光描边） */
    GLASS("glass", "毛玻璃"),
    ;

    companion object {
        fun fromId(id: String): DesktopLyricBackground =
            entries.firstOrNull { it.id == id } ?: PRESET
    }
}

/**
 * 桌面歌词样式预设：一组开箱即用的视觉方案。
 *
 * 选择预设时会把这一整套参数写入设置（用户随后可逐项微调），
 * 因此渲染层只需读设置即可，预设仅决定「初始值 + 当前选中项展示」。
 */
enum class DesktopLyricPreset(
    val id: String,
    val label: String,
    val description: String,
    /** 预览色点（ARGB） */
    val swatch: Int,
    val background: DesktopLyricBackground,
    /** 背景基色（ARGB） */
    val backgroundColor: Int,
    val backgroundAlpha: Float,
    val corner: Float,
    val strokeWidth: Float,
    val strokeColor: Int,
    val shadow: Boolean,
    val textColor: Int,
    /** 高亮色（0 = 跟随主题色） */
    val highlightColor: Int,
    val bold: Boolean,
    val letterSpacing: Float,
) {
    AURORA(
        id = "aurora",
        label = "流光",
        description = "半透磨砂胶囊 · 主题色逐字高亮",
        swatch = 0xFF7C5CFF.toInt(),
        background = DesktopLyricBackground.PRESET,
        backgroundColor = 0xFF14121F.toInt(),
        backgroundAlpha = 0.42f,
        corner = 24f,
        strokeWidth = 0f,
        strokeColor = 0xFF2A2440.toInt(),
        shadow = true,
        textColor = 0xFFF3F1FF.toInt(),
        highlightColor = 0,
        bold = true,
        letterSpacing = 0.02f,
    ),
    VINYL(
        id = "vinyl",
        label = "黑胶",
        description = "深色实底 · 暖金高亮",
        swatch = 0xFFE8B457.toInt(),
        background = DesktopLyricBackground.SOLID,
        backgroundColor = 0xFF12100E.toInt(),
        backgroundAlpha = 0.74f,
        corner = 18f,
        strokeWidth = 0f,
        strokeColor = 0xFF3A2E1E.toInt(),
        shadow = true,
        textColor = 0xFFF5EFE4.toInt(),
        highlightColor = 0xFFE8B457.toInt(),
        bold = false,
        letterSpacing = 0.05f,
    ),
    NEON(
        id = "neon",
        label = "霓虹",
        description = "透明底 · 发光描边",
        swatch = 0xFF19F0FF.toInt(),
        background = DesktopLyricBackground.NONE,
        backgroundColor = 0xFF000000.toInt(),
        backgroundAlpha = 0f,
        corner = 0f,
        strokeWidth = 1.2f,
        strokeColor = 0xFF19F0FF.toInt(),
        shadow = false,
        textColor = 0xFFE9F9FF.toInt(),
        highlightColor = 0xFF19F0FF.toInt(),
        bold = true,
        letterSpacing = 0.06f,
    ),
    GLASS(
        id = "glass",
        label = "玻璃",
        description = "毛玻璃高光 · 主题色高亮",
        swatch = 0x99FFFFFF.toInt(),
        background = DesktopLyricBackground.GLASS,
        backgroundColor = 0xFFFFFFFF.toInt(),
        backgroundAlpha = 0.18f,
        corner = 28f,
        strokeWidth = 0.8f,
        strokeColor = 0x66FFFFFF,
        shadow = true,
        textColor = 0xFFFFFFFF.toInt(),
        highlightColor = 0,
        bold = true,
        letterSpacing = 0.02f,
    ),
    INK(
        id = "ink",
        label = "墨韵",
        description = "极简无底 · 细描边高对比",
        swatch = 0xFFF0F0F0.toInt(),
        background = DesktopLyricBackground.NONE,
        backgroundColor = 0xFF000000.toInt(),
        backgroundAlpha = 0f,
        corner = 0f,
        strokeWidth = 0.6f,
        strokeColor = 0xCC000000.toInt(),
        shadow = false,
        textColor = 0xFFF7F7F7.toInt(),
        highlightColor = 0xFFFFFFFF.toInt(),
        bold = false,
        letterSpacing = 0.1f,
    ),
    CANDY(
        id = "candy",
        label = "糖果",
        description = "柔雾圆润胶囊 · 亮粉高亮",
        swatch = 0xFFFF7BC0.toInt(),
        background = DesktopLyricBackground.SOLID,
        backgroundColor = 0xFF2A1B33.toInt(),
        backgroundAlpha = 0.62f,
        corner = 34f,
        strokeWidth = 0f,
        strokeColor = 0xFFFF7BC0.toInt(),
        shadow = true,
        textColor = 0xFFFFF3FA.toInt(),
        highlightColor = 0xFFFF7BC0.toInt(),
        bold = true,
        letterSpacing = 0.03f,
    ),
    ;

    companion object {
        fun fromId(id: String): DesktopLyricPreset = entries.firstOrNull { it.id == id } ?: AURORA
    }
}

/** 解析后的桌面歌词渲染样式（颜色均为 ARGB Int，供 Canvas 绘制使用） */
data class DesktopLyricStyle(
    val fontSizeSp: Float,
    val letterSpacing: Float,
    val opacity: Float,
    val background: DesktopLyricBackground,
    val backgroundColor: Int,
    val backgroundAlpha: Float,
    val cornerDp: Float,
    val textColor: Int,
    val highlightColor: Int,
    val strokeWidthDp: Float,
    val strokeColor: Int,
    val shadow: Boolean,
    val bold: Boolean,
    val verbatim: Boolean,
    val showTranslation: Boolean,
    val showNextLine: Boolean,
    val controls: Boolean,
    val locked: Boolean,
    val touchThrough: Boolean,
    val offsetX: Float,
    val offsetY: Float,
) {
    /** 是否绘制底板 */
    val drawsBackground: Boolean
        get() = background != DesktopLyricBackground.NONE && backgroundAlpha > 0.01f

    companion object {
        /** 兜底样式（设置尚未读到时的初始值，视觉等同「流光」预设） */
        val DEFAULT = DesktopLyricStyle(
            fontSizeSp = 22f,
            letterSpacing = 0.02f,
            opacity = 1f,
            background = DesktopLyricBackground.PRESET,
            backgroundColor = 0xFF14121F.toInt(),
            backgroundAlpha = 0.42f,
            cornerDp = 24f,
            textColor = 0xFFF3F1FF.toInt(),
            highlightColor = 0xFF7C5CFF.toInt(),
            strokeWidthDp = 0f,
            strokeColor = 0xFF2A2440.toInt(),
            shadow = true,
            bold = true,
            verbatim = true,
            showTranslation = true,
            showNextLine = true,
            controls = true,
            locked = false,
            touchThrough = false,
            offsetX = -1f,
            offsetY = -1f,
        )

        const val MIN_FONT_SP = 12f
        const val MAX_FONT_SP = 44f
        const val MIN_OPACITY = 0.3f
        const val MAX_OPACITY = 1f
        const val MAX_STROKE_DP = 6f
        const val MAX_CORNER_DP = 44f

        /**
         * 由设置解析最终样式。
         * @param themeHighlight 主题高亮色（ARGB；设置未指定高亮色时使用）
         */
        fun from(settings: AppSettings, themeHighlight: Int): DesktopLyricStyle {
            val preset = DesktopLyricPreset.fromId(settings.desktopLyricPreset)
            return DesktopLyricStyle(
                fontSizeSp = settings.desktopLyricFontSize.coerceIn(MIN_FONT_SP, MAX_FONT_SP),
                letterSpacing = settings.desktopLyricLetterSpacing.coerceIn(-0.05f, 0.3f),
                opacity = settings.desktopLyricOpacity.coerceIn(MIN_OPACITY, MAX_OPACITY),
                background = DesktopLyricBackground.fromId(settings.desktopLyricBackground),
                backgroundColor = pick(
                    settings.desktopLyricBackgroundColor,
                    preset.backgroundColor,
                    0xFF14141C.toInt(),
                ),
                backgroundAlpha = settings.desktopLyricBackgroundAlpha.coerceIn(0f, 1f),
                cornerDp = settings.desktopLyricCorner.coerceIn(0f, MAX_CORNER_DP),
                textColor = pick(
                    settings.desktopLyricTextColor,
                    preset.textColor,
                    0xFFFFFFFF.toInt(),
                ),
                highlightColor = pick(
                    settings.desktopLyricHighlightColor,
                    preset.highlightColor,
                    themeHighlight,
                ),
                strokeWidthDp = settings.desktopLyricStrokeWidth.coerceIn(0f, MAX_STROKE_DP),
                strokeColor = pick(
                    settings.desktopLyricStrokeColor,
                    preset.strokeColor,
                    0xFF000000.toInt(),
                ),
                shadow = settings.desktopLyricShadow,
                bold = preset.bold,
                verbatim = settings.desktopLyricVerbatim,
                showTranslation = settings.desktopLyricShowTranslation,
                showNextLine = settings.desktopLyricShowNextLine,
                controls = settings.desktopLyricControls,
                locked = settings.desktopLyricLocked,
                touchThrough = settings.desktopLyricTouchThrough,
                offsetX = settings.desktopLyricOffsetX,
                offsetY = settings.desktopLyricOffsetY,
            )
        }

        /** 0 = 未自定义 → 取预设值 → 取兜底色 */
        private fun pick(value: Int, presetValue: Int, fallback: Int): Int = when {
            value != 0 -> value
            presetValue != 0 -> presetValue
            else -> fallback
        }
    }
}