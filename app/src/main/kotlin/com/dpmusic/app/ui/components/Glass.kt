package com.dpmusic.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dpmusic.app.ui.theme.LocalGlass
import com.dpmusic.app.ui.theme.glassPanelColor
import kotlin.math.roundToInt

/**
 * Liquid Glass（液态玻璃）组件：
 * - [GlassSurface]：通用玻璃面板（真实模糊 + 边缘折射 + 边缘高光 + 斜向光泽）；玻璃关闭时回退标准 Surface；
 * - [GlassBackdrop]：全局流光底（底色 + 封面模糊层 + 漂移光球 + 边缘渐隐），仅玻璃模式绘制；
 * - 折射 / 边缘高光需 Android 13+（API33，AGSL RuntimeShader），模糊需 Android 12+（API31），
 *   低版本自动降级为「半透明 + 高光描边 + 流光底」等效方案。
 */

/** 共享背景层（由宿主提供 / GlassBackdrop 写入）：玻璃面板采样它做真实模糊；null = 不可用 */
val LocalGlassBlur = staticCompositionLocalOf<GraphicsLayer?> { null }

/**
 * 玻璃采样偏移（Liquid Glass）：
 * 覆盖在内容之上的玻璃面板（Mini 条 / 底栏）没有内容从它下面经过（页面内容被 Scaffold 内边距顶开），
 * 这里让面板改为采样「自己高度之上」的内容 —— 视觉上等价于「内容从玻璃下面滚过去」，
 * 这正是参考实现（AndroidLiquidGlass）玻璃底栏的观感来源。
 */
val LocalGlassSampleOffset = staticCompositionLocalOf { 0.dp }

/** 通用磨砂面板：参数与 M3 Surface 对齐，玻璃模式自动换装 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    /** 强调档：更大的不透明度（歌词页 / 弹层等需要可读性的场景） */
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val glass = LocalGlass.current
    if (!glass.enabled) {
        if (onClick != null) {
            Surface(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = shape,
                color = color,
                contentColor = contentColor,
                tonalElevation = tonalElevation,
                shadowElevation = shadowElevation,
                border = border,
                interactionSource = interactionSource,
                content = content,
            )
        } else {
            Surface(
                modifier = modifier,
                shape = shape,
                color = color,
                contentColor = contentColor,
                tonalElevation = tonalElevation,
                shadowElevation = shadowElevation,
                border = border,
                content = content,
            )
        }
        return
    }

    // Liquid Glass 配方：真实模糊 + 边缘折射 + 边缘高光 + 半透明着色 + 斜向光泽
    // （半透明面板不使用投影：Compose 的投影画在面板之下，会从边缘向内透出「脏影」）
    val panelColor = glassPanelColor(color, strong = strong)
    // API33+ 边缘高光由 AGSL 着色器沿轮廓绘制（加法混合），替代原来的均匀描边
    val stroke = border ?: if (runtimeShaderSupported) null else BorderStroke(1.dp, glass.borderColor)

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            color = Color.Transparent,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            shadowElevation = 0.dp,
            border = stroke,
            interactionSource = interactionSource,
        ) {
            GlassFillStack(shape, glass.sheenColor, glass.rimColor, panelColor, content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = Color.Transparent,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            shadowElevation = 0.dp,
            border = stroke,
        ) {
            GlassFillStack(shape, glass.sheenColor, glass.rimColor, panelColor, content)
        }
    }
}

/**
 * 玻璃面板内容栈（自底向上）：
 * 1) 真实背景模糊 + 边缘折射采样（宿主提供的共享背景层；Android 12+ 生效）
 * 2) 半透明磨砂着色（白玻璃 / 烟熏玻璃）
 * 3) 斜向光泽
 * 4) 面板内容
 * 5) 边缘高光（AGSL，加法混合；Android 13+ 生效）
 */
@Composable
private fun GlassFillStack(
    shape: Shape,
    sheenColor: Color,
    rimColor: Color,
    tint: Color,
    content: @Composable () -> Unit,
) {
    Box {
        GlassBlurSample(shape)
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind { drawRect(tint) },
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(sheenColor, Color.Transparent),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height * 0.9f),
                        ),
                    )
                },
        )
        content()
        GlassRimLight(shape, rimColor)
    }
}

/** 边缘高光：沿面板轮廓描一圈强度随方向变化的亮边（与参考实现同款着色器 + 加法混合） */
@Composable
private fun BoxScope.GlassRimLight(shape: Shape, color: Color) {
    if (!runtimeShaderSupported) return
    val rim = rememberLiquidRim()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val widthPx = with(density) { LiquidLens.rimWidth.toPx() }
    Box(
        modifier = Modifier
            .matchParentSize()
            .drawBehind { rim.draw(this, shape, color, widthPx, density, layoutDirection) },
    )
}

/** 真实背景采样：把共享背景层按本面板屏幕位置平移绘制 + 边缘折射环 + 真实模糊（液态玻璃） */
@Composable
private fun BoxScope.GlassBlurSample(shape: Shape) {
    val glass = LocalGlass.current
    if (!glass.blurSupported) return
    val layer = LocalGlassBlur.current ?: return
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val sampleOffset = LocalGlassSampleOffset.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .matchParentSize()
            .onGloballyPositioned { pos = it.positionInRoot() }
            // 模糊在内层绘制之上（与库的「模糊 ⇒ 折射」顺序等价）
            .blur(glass.panelBlurRadius)
            .liquidBackdropSample(
                layer = layer,
                shape = shape,
                density = density,
                layoutDirection = layoutDirection,
                position = pos,
                sampleOffsetPx = with(density) { sampleOffset.toPx() },
                refractionHeightPx = with(density) { LiquidLens.refractionHeight.toPx() },
                refractionAmountPx = with(density) { LiquidLens.refractionAmount.toPx() },
            ),
    )
}

/** 光球颜色强化：按最大通道归一化提升饱和度；浅色模式轻微混白成粉彩，深色模式保持浓郁 */
private fun vividGlow(c: Color, dark: Boolean): Color {
    val m = maxOf(c.red, c.green, c.blue).coerceAtLeast(0.01f)
    val saturated = Color(c.red / m, c.green / m, c.blue / m, c.alpha)
    return if (dark) saturated else lerp(saturated, Color.White, 0.12f)
}

/**
 * 全局流光底（外壳层，位于所有页面之下）：
 * 1) 基础底色 —— 各页面背景透明后由它兜底；
 * 2) 封面模糊层 —— 当前歌曲封面真实模糊（Android 12+），静态成像、开销可控；
 * 3) 漂移光球 —— 跟随当前歌曲调色板缓慢漂移（延续播放页 AmbientBackdrop 语言）；
 * 4) 边缘渐隐 —— 顶 / 底轻微压暗，提升状态栏与底栏区域层次。
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    coverUrl: String? = null,
    isPlaying: Boolean = false,
    glowColors: List<Color> = emptyList(),
) {
    val glass = LocalGlass.current
    if (!glass.enabled) return

    // 共享背景层：本组件负责写入，玻璃面板负责采样做真实模糊
    val providedLayer = LocalGlassBlur.current
    val fallbackLayer = rememberGraphicsLayer()
    val sourceLayer = providedLayer ?: fallbackLayer

    val scheme = MaterialTheme.colorScheme
    val base = scheme.surfaceContainerLowest
    // 光球颜色强化：饱和度提升（浅色模式再混白成粉彩），保证任何封面上都能透出可见的流光色
    val glow1 = vividGlow(glowColors.getOrNull(0) ?: scheme.primary, glass.dark)
    val glow2 = vividGlow(glowColors.getOrNull(1) ?: scheme.tertiary, glass.dark)
    val glow3 = vividGlow(glowColors.getOrNull(2) ?: scheme.secondary, glass.dark)
    val breathing = if (isPlaying) 1f else 0.8f

    val transition = rememberInfiniteTransition(label = "glassBackdropDrift")
    // 漂移值不在组合期读取（避免逐帧重组）：绘制阶段按播放状态读取——
    // 播放时逐帧漂移；暂停时使用静态相位，完全不产生逐帧重绘。
    val drift1 = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 26000, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "glassDrift1",
    )
    val drift2 = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 34000, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "glassDrift2",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                // 记录背景内容 → 共享背景层（供玻璃面板采样做真实模糊）
                sourceLayer.record(this, layoutDirection, IntSize(size.width.roundToInt(), size.height.roundToInt())) {
                    this@drawWithContent.drawContent()
                }
                // 正常显示背景（未模糊）
                drawLayer(sourceLayer)
            },
    ) {
        // 1) 基础底色
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(base) },
        )

        // 2) 封面模糊层（Android 12+ 真实模糊；低版本自动跳过）
        // Liquid Glass 需要「有东西可透」：封面作为大面积彩色底，透明度与模糊都取更浓的档位
        if (glass.blurSupported && !coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(28.dp)
                    .alpha(0.85f),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            Brush.verticalGradient(
                                listOf(
                                    base.copy(alpha = 0.22f),
                                    base.copy(alpha = 0.10f),
                                    base.copy(alpha = 0.30f),
                                ),
                            ),
                        )
                    },
            )
        }

        // 3) 漂移光球 + 边缘渐隐
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    // Liquid Glass 需要「有结构可折射」：光球半径收小、数量增多 → 形成可见色块而非均匀洗白
                    val r = size.maxDimension * 0.42f
                    // 绘制阶段读取漂移值（暂停时不读取 → 无逐帧重绘；播放时逐帧漂移）
                    val d1 = if (isPlaying) drift1.value else 0.5f
                    val d2 = if (isPlaying) drift2.value else 0.5f
                    val c1 = Offset(w * (0.82f + 0.06f * d1), h * (0.10f + 0.06f * d2))
                    val c2 = Offset(w * (0.12f + 0.08f * d2), h * (0.78f + 0.06f * d1))
                    val c3 = Offset(w * (0.90f - 0.10f * d1), h * (0.55f + 0.08f * d2))
                    val c4 = Offset(w * (0.30f + 0.08f * d1), h * (0.32f - 0.06f * d2))
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glow1.copy(alpha = glass.glowAlpha * breathing), Color.Transparent),
                            center = c1,
                            radius = r,
                        ),
                        radius = r,
                        center = c1,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glow2.copy(alpha = glass.glowAlpha * 0.9f), Color.Transparent),
                            center = c2,
                            radius = r * 0.9f,
                        ),
                        radius = r * 0.9f,
                        center = c2,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glow3.copy(alpha = glass.glowAlpha * 0.7f), Color.Transparent),
                            center = c3,
                            radius = r * 0.8f,
                        ),
                        radius = r * 0.8f,
                        center = c3,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glow1.copy(alpha = glass.glowAlpha * 0.55f), Color.Transparent),
                            center = c4,
                            radius = r * 0.7f,
                        ),
                        radius = r * 0.7f,
                        center = c4,
                    )
                    drawRect(
                        Brush.verticalGradient(
                            0f to base.copy(alpha = 0.35f),
                            0.25f to Color.Transparent,
                            0.75f to Color.Transparent,
                            1f to base.copy(alpha = 0.30f),
                        ),
                    )
                },
        )
    }
}
