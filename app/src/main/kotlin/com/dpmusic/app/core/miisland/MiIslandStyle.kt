package com.dpmusic.app.core.miisland

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette

/**
 * 超级岛卡片的**布局样式**。
 *
 * 每个样式是对 focus-api V3 模板里 `island.bigIslandArea`（展开态大岛）与
 * `smallIslandArea`（折叠态小胶囊）不同子字段的组合。字段全部来自 focus-api 1.4
 * 的实际 API（`BigIslandArea` 只提供 textInfo / picInfo / imageTextInfoLeft /
 * imageTextInfoRight / progressTextInfo / fixedWidthDigitInfo / sameWidthDigitInfo 七种插槽）。
 *
 * 之所以用「插槽组合」而不是随便拼文案：HyperOS 只渲染它认得的结构，
 * 未填充的插槽会塌陷，所以每个样式都必须至少填满大岛的**一个**插槽。
 */
enum class MiIslandLayout(
    val id: String,
    val label: String,
    val description: String,
    /** 该样式是否在岛上显示歌词（供设置页提示「无歌词」样式） */
    val showsLyric: Boolean,
) {
    /** 左：封面 + 歌名/时间；右：当前歌词。信息量最均衡。 */
    LYRIC_SPLIT(
        "lyric_split",
        "歌词·左右分栏",
        "左侧封面与歌名，右侧实时歌词",
        showsLyric = true,
    ),

    /** 大岛正中央一颗大字歌词，歌名退为副行。歌词最具存在感。 */
    LYRIC_HERO(
        "lyric_hero",
        "歌词·大字",
        "整块大岛只显示当前歌词，歌名作副行",
        showsLyric = true,
    ),

    /** 环形进度 + 歌名/歌词：把播放进度做成环，信息密度最高。 */
    PROGRESS_RING(
        "progress_ring",
        "进度环·歌词",
        "封面环形播放进度，右侧歌名与歌词",
        showsLyric = true,
    ),

    /** 封面 + 歌名 + 歌手，不含歌词。适合不想让歌词刷屏的用户。 */
    COVER_TITLE(
        "cover_title",
        "封面·歌名",
        "封面配歌名与歌手，不显示歌词",
        showsLyric = false,
    ),

    /** 极简：只有歌名一行，折叠态仅剩封面。 */
    MINIMAL(
        "minimal",
        "极简·仅歌名",
        "只显示歌名，界面最安静",
        showsLyric = false,
    );

    companion object {
        val default: MiIslandLayout = LYRIC_SPLIT

        fun fromId(id: String?): MiIslandLayout =
            entries.firstOrNull { it.id == id } ?: default
    }
}

/**
 * 超级岛配色。
 *
 * 四个颜色分别对应载荷里的不同字段域（不是同一处换色）：
 * - [accentHex]：进度环 `colorReach` / 进度条 `color` / `island.highlightColor` / 高亮文字底色；
 * - [bgHex]：展开卡与 `bgInfo.colorBg` 的底色；
 * - [textPrimaryHex]：主文字（歌名、歌词）；
 * - [textSecondaryHex]：副文字（歌手、时间）。
 */
data class MiIslandColors(
    val accentHex: String,
    val bgHex: String,
    val textPrimaryHex: String,
    val textSecondaryHex: String,
)

/**
 * 配色方案。
 *
 * [COVER] 会按当前封面主色**动态推导**整组配色，其余为固定预设。
 * 固定预设也全部走同一套派生逻辑（见 [derive]），只是种子色为常量 ——
 * 这样「预设」与「跟随封面」的观感一致，不会出现预设好看、跟随封面难看的两套体系。
 */
enum class MiIslandPalette(
    val id: String,
    val label: String,
    /** 固定种子色；[COVER] 与 [CUSTOM] 为 null（运行时决定） */
    private val seed: Int?,
) {
    COVER("cover", "跟随封面", null),
    WHITE("white", "原版白", null),
    MIUI_BLUE("miui_blue", "晴空蓝", 0xFF3482FF.toInt()),
    GREEN("green", "青柠绿", 0xFF31C27C.toInt()),
    PURPLE("purple", "夜紫", 0xFF8B5CF6.toInt()),
    ORANGE("orange", "落日橙", 0xFFFF7A45.toInt()),
    ROSE("rose", "玫瑰粉", 0xFFFF4D8D.toInt()),
    MONO("mono", "中性灰", 0xFF8A8F99.toInt()),
    CUSTOM("custom", "自定义", null);

    companion object {
        val default: MiIslandPalette = COVER

        fun fromId(id: String?): MiIslandPalette =
            entries.firstOrNull { it.id == id } ?: default

        /**
         * 解析出最终配色。
         *
         * @param coverSeed 封面主色（ARGB int，未提取到时传 0）
         * @param customSeed 自定义种子色（仅 [CUSTOM] 使用）
         */
        fun resolve(
            palette: MiIslandPalette,
            coverSeed: Int,
            customSeed: Int,
        ): MiIslandColors {
            // 原版白走独立分支：它要的是"完全不染色"的系统原生观感，
            // 若走通用派生，白色会被 ensureSaturation（提饱和）与 darkenForText（压暗）
            // 双双改写，最终得到一块灰而非原版白。
            if (palette == WHITE) return NATIVE_WHITE

            val effective = when (palette) {
                CUSTOM -> customSeed.takeIf { it != 0 } ?: FALLBACK_SEED
                COVER -> coverSeed.takeIf { it != 0 } ?: FALLBACK_SEED
                // WHITE 已在上方提前返回，此处仅为穷尽 when
                WHITE -> FALLBACK_SEED
                else -> palette.seed ?: FALLBACK_SEED
            }
            return derive(effective)
        }

        /**
         * 原版白：不染色的中性配色 —— 深色底 + 纯白文字 / 强调。
         *
         * 底色取 [COLOR_BG_NATIVE]，与系统原生媒体岛、XCmusic 的深色底一致；
         * 强调色用纯白，进度环与折叠态高亮歌词都是白色，画面最干净。
         */
        private val NATIVE_WHITE = MiIslandColors(
            accentHex = "#FFFFFF",
            bgHex = COLOR_BG_NATIVE,
            textPrimaryHex = "#FFFFFF",
            textSecondaryHex = "#B3FFFFFF",
        )

        /** 原版白 / 各预设共用的深色底（白字可读性已由设计保证，无需再压暗） */
        private const val COLOR_BG_NATIVE = "#191B22"

        /** 提取失败时的兜底种子：品牌绿（与项目主色一致） */
        private const val FALLBACK_SEED = 0xFF31C27C.toInt()

        /**
         * 由种子色派生整组配色。
         *
         * 关键约束：底色必须压暗到能让白字满足可读性 —— 超级岛的文字颜色由载荷给定
         * （`colorXxx` 系列），一旦底色过亮又配白字，在浅色封面上会直接糊成一片。
         * 这里统一按亮度把底色压到受控区间，文字则固定白 / 半透明白。
         */
        private fun derive(seed: Int): MiIslandColors {
            val accent = ensureSaturation(seed)
            return MiIslandColors(
                accentHex = toHex(accent),
                bgHex = toHex(darkenForText(accent)),
                textPrimaryHex = "#FFFFFF",
                textSecondaryHex = "#B3FFFFFF",
            )
        }

        /** 种子色饱和度过低（接近灰）时，轻微提饱和，避免进度环与底色分不开 */
        private fun ensureSaturation(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            if (hsv[1] < 0.25f) hsv[1] = 0.35f
            return Color.HSVToColor(hsv)
        }

        /**
         * 把颜色压暗到「白字可读」的亮度上限。
         *
         * 白字在 WCAG AA 下要求背景相对亮度 ≲ 0.18（对比度约 4.6:1）。
         * 这里用 HSV 的 value 通道近似控制亮度：先取种子色的色相/饱和度，
         * 再把 value 压到上限，保留色彩个性同时保证可读。
         */
        private fun darkenForText(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[2] = hsv[2].coerceAtMost(MAX_BG_VALUE)
            return Color.HSVToColor(hsv)
        }

        /** 底色亮度上限（0..1）；再高白字就开始发灰 */
        private const val MAX_BG_VALUE = 0.26f

        /** ARGB int → `#RRGGBB`（载荷字段要求这种格式，不认 alpha） */
        fun toHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
    }
}

/**
 * 封面主色提取。
 *
 * 与 [com.dpmusic.app.core.util.CoverPalette] 的分工：那个服务于 Compose UI（走 Coil、
 * 返回 `Color` 列表、结果给播放页流光背景用）；这里服务于**通知载荷构建**，
 * 输入是 [MiIslandController] 已经下载好的封面位图，不再二次网络请求。
 *
 * Palette 要求 ARGB_8888：若传入硬件位图或 RGB_565 会拿不到结果，因此调用方
 * 必须保证位图可读像素（见 `MiIslandController.downloadBitmap`）。
 */
internal object CoverSeedExtractor {

    /** 提取封面主色；失败返回 0 */
    fun extract(bitmap: Bitmap?): Int {
        if (bitmap == null || bitmap.isRecycled) return 0
        if (bitmap.config == Bitmap.Config.HARDWARE) return 0
        return try {
            val palette = Palette.from(bitmap).clearFilters().generate()
            // 依次降级：鲜亮 → 柔和 → 暗鲜亮。全部缺失时返回 0，由调用方兜底。
            val candidates = listOf(
                palette.getVibrantColor(0),
                palette.getMutedColor(0),
                palette.getDarkVibrantColor(0),
                palette.getDominantColor(0),
            )
            candidates.firstOrNull { it != 0 } ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
