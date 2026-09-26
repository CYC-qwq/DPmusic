package com.dpmusic.app.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Liquid Glass（液态玻璃）渲染内核。
 *
 * 效果与量级严格对齐参考实现 `io.github.kyant0:backdrop`（AndroidLiquidGlass，Apache-2.0）：
 * 1. 边缘折射（lens）：以圆角矩形 SDF 到边缘的距离为参数，按 `circleMap` 位移剖面
 *    把采样点朝面板内部拉近（[LiquidLens.refractionHeight] / [LiquidLens.refractionAmount]）；
 * 2. 真实模糊：库示例 `blur(4.dp)`（重模糊会直接抹掉折射细节）；
 * 3. 鲜艳度：库 `vibrancy()` ≡ `colorControls(saturation = 1.5f)`；
 * 4. 边缘高光（rim light）：库 `Highlight.Default` 同款 AGSL 着色器 —— SDF 梯度与 45° 法线
 *    点积的 `|dot|^falloff` 强度场，沿轮廓加法混合描边。
 *
 * 实现说明（重要）：
 * 参考实现用 `RenderEffect.createRuntimeShaderEffect(shader, "content")` 把折射写成「采样输入着色器」的
 * RenderEffect。本机实测（Android 16 / Compose 1.9，探针见 docs）该路径的 **输入着色器读不到内容**
 * （`content.eval()` 恒为黑），且行为随 Compose 图层结构变化。因此这里改用**等价且稳定**的做法：
 * 把背景层按「距边缘距离」分环、逐环以不同缩放重绘并裁剪到该环 —— 缩放比例由 circleMap 剖面反解，
 * 与库的位移逐点一致，且两端（边缘处最大、折射带内边界处为 0）连续无接缝。
 */

/* ------------------------------------------------------------------ */
/* AGSL：边缘高光                                                       */
/* ------------------------------------------------------------------ */

private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}
"""

private const val RIM_SHADER = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float angle;
uniform float falloff;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    return color * intensity;
}
"""

/* ------------------------------------------------------------------ */
/* 效果参数（与库示例同量级）                                           */
/* ------------------------------------------------------------------ */

/** Liquid Glass 效果参数：默认值即参考实现文档示例的取值 */
internal object LiquidLens {

    /** 折射带高度（自面板边缘向内）：库示例 `lens(16.dp, 32.dp)` 的第一个参数 */
    val refractionHeight = 16.dp

    /** 折射位移量：库示例 `lens(16.dp, 32.dp)` 的第二个参数 */
    val refractionAmount = 32.dp

    /** 面板真实模糊半径：库示例 `blur(4.dp)` */
    val blurRadius = 4.dp

    /** 鲜艳度：库 `vibrancy()` ≡ `colorControls(saturation = 1.5f)` */
    const val vibrancySaturation = 1.5f

    /**
     * 折射环数量：折射带按「位移量等分」切成 N 环，逐环用不同缩放重绘。
     * N 越大越接近连续的 circleMap 剖面（8 环的位移台阶为 amount/8 ≈ 4dp，配合 4dp 模糊不可见）。
     */
    const val ringCount = 8

    /** 边缘高光宽度：库 `Highlight.Default.width` */
    val rimWidth = 0.5.dp

    /** 边缘高光透明度：库 `HighlightStyle.Default.color = White α0.5` */
    const val rimAlpha = 0.5f

    /** 边缘高光方向：库 `HighlightStyle.Default.angle = 45f` */
    const val rimAngleDegrees = 45f

    /** 边缘高光衰减指数：库 `HighlightStyle.Default.falloff = 1f` */
    const val rimFalloff = 1f
}

/** 真实模糊（RenderEffect）门槛 */
internal val renderEffectSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** AGSL（RuntimeShader）门槛：边缘高光 */
internal val runtimeShaderSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/* ------------------------------------------------------------------ */
/* 工具                                                                 */
/* ------------------------------------------------------------------ */

/** 取面板四角半径（px），顺序 TL / TR / BR / BL —— 与库的 `cornerRadii` uniform 一致 */
internal fun cornerRadiiOf(
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
): FloatArray {
    if (size.minDimension <= 0f) return FloatArray(4)
    return when (val outline = shape.createOutline(size, layoutDirection, density)) {
        is Outline.Rounded -> {
            val r = outline.roundRect
            floatArrayOf(
                r.topLeftCornerRadius.x,
                r.topRightCornerRadius.x,
                r.bottomRightCornerRadius.x,
                r.bottomLeftCornerRadius.x,
            )
        }

        is Outline.Rectangle -> FloatArray(4)

        // 自定义 Path 形状：按胶囊半径近似（库同样只支持圆角矩形）
        else -> FloatArray(4) { size.minDimension / 2f }
    }
}

/** 生成「面板内缩 inset」的圆角矩形路径（圆角半径同步收缩） */
private fun buildInsetPath(path: Path, width: Float, height: Float, inset: Float, baseRadius: Float) {
    path.reset()
    val r = (baseRadius - inset).coerceAtLeast(0f)
    path.addRoundRect(
        RoundRect(
            rect = Rect(inset, inset, width - inset, height - inset),
            cornerRadius = CornerRadius(r, r),
        ),
    )
}

/** 在给定颜色滤镜下绘制图层（Compose 的 `drawLayer` 没有 colorFilter 重载，用 saveLayer 实现）
 *  注意：[bounds] 必须是「当前绘制坐标系」下的绘制区域 —— 该函数在 translate 之后调用，
 *  坐标系已切到 root，因此必须传入 root 坐标下的面板矩形，否则 saveLayer 会把绘制裁掉。 */
private fun DrawScope.drawLayerFiltered(layer: GraphicsLayer, filter: ColorFilter?, bounds: Rect) {
    if (filter == null) {
        drawLayer(layer)
        return
    }
    val paint = Paint().apply { colorFilter = filter }
    drawContext.canvas.saveLayer(bounds, paint)
    drawLayer(layer)
    drawContext.canvas.restore()
}

/* ------------------------------------------------------------------ */
/* 折射：背景层采样 + 边缘逐环缩放重绘                                    */
/* ------------------------------------------------------------------ */

/**
 * 玻璃面板的背景采样：
 * 1) 整块原样绘制共享背景层（按面板 root 位置平移，与旧毛玻璃一致）；
 * 2) 边缘折射带内，按「位移量等分」的 N 环逐环以不同缩放重绘 —— 复刻库的 circleMap 位移剖面。
 *
 * @param position 面板在 root 坐标系中的位置
 */
internal fun Modifier.liquidBackdropSample(
    layer: GraphicsLayer,
    shape: Shape,
    density: Density,
    layoutDirection: LayoutDirection,
    position: Offset,
    refractionHeightPx: Float,
    refractionAmountPx: Float,
    sampleOffsetPx: Float = 0f,
    vibrancySaturation: Float = LiquidLens.vibrancySaturation,
): Modifier = this.drawBehind {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawBehind

    val radii = cornerRadiiOf(shape, size, layoutDirection, density)
    val baseRadius = radii.min()
    val height = refractionHeightPx.coerceAtMost(baseRadius)
    val amount = refractionAmountPx.coerceAtMost(minOf(w, h) * 0.5f)
    val colorFilter: ColorFilter? = if (vibrancySaturation != 1f) {
        ColorMatrixColorFilter(ColorMatrix().apply { setToSaturation(vibrancySaturation) })
    } else {
        null
    }
    // 当前绘制坐标系（root）下的面板矩形：供 saveLayer 使用
    val rootRect = Rect(position.x, position.y, position.x + w, position.y + h)

    translate(-position.x, -position.y + sampleOffsetPx) {
        // 1) 整块原样采样
        drawLayerFiltered(layer, colorFilter, rootRect)
    }

    // 2) 边缘折射带：逐环缩放重绘
    //    注意：裁剪路径使用「面板局部坐标」，因此 clipPath 必须在 translate 之外；
    //    而图层绘制使用 root 坐标，故 translate/scale 放在 clipPath 之内。
    if (height > 0.5f && amount > 1f) {
        val steps = LiquidLens.ringCount
        val pivot = Offset(position.x + w * 0.5f, position.y + h * 0.5f)
        val outerPath = Path()
        val innerPath = Path()
        var prevInset = 0f
        for (j in 0 until steps) {
            val uOut = j.toFloat() / steps
            val uIn = (j + 1f) / steps
            // 位移剖面：disp(u) = amount * (1 - u)，u = 1 - d/height；按位移等分 → 内边界 d
            val insetIn = height * (1f - sqrt(1f - uIn * uIn))
            val disp = amount * (1f - (uOut + uIn) * 0.5f)
            if (disp > 0.5f) {
                val dm = (prevInset + insetIn) * 0.5f
                // 该环中点处的等效缩放：让「距边缘 dm 处」显示「距边缘 dm + disp 处」的内容
                val sx = (w * 0.5f - dm) / (w * 0.5f - dm - disp).coerceAtLeast(0.01f)
                val sy = (h * 0.5f - dm) / (h * 0.5f - dm - disp).coerceAtLeast(0.01f)
                buildInsetPath(innerPath, w, h, insetIn, baseRadius)
                buildInsetPath(outerPath, w, h, prevInset, baseRadius)
                clipPath(innerPath, clipOp = ClipOp.Difference) {
                    clipPath(outerPath, clipOp = ClipOp.Intersect) {
                        translate(-position.x, -position.y + sampleOffsetPx) {
                            scale(sx, sy, pivot = pivot) {
                                // 环内不再套 saveLayer（每环一个面板大小的离屏缓冲，代价过高）：
                                // 鲜艳度只作用于整块底色，边缘带内的饱和度差异在 16dp 内不可见
                                drawLayer(layer)
                            }
                        }
                    }
                }
            }
            prevInset = insetIn
        }
    }
}

/* ------------------------------------------------------------------ */
/* 边缘高光                                                             */
/* ------------------------------------------------------------------ */

/** 边缘高光渲染器：与库 `Highlight.Default` 同款着色器 + 描边 + 加法混合 */
internal class LiquidRimRenderer(private val errorFile: File?) {

    private var shader: RuntimeShader? = null
    private var shaderFailed = false
    private val paint = Paint().apply { style = PaintingStyle.Stroke }

    private fun shaderOrNull(): RuntimeShader? {
        if (shaderFailed || !runtimeShaderSupported) return null
        shader?.let { return it }
        return try {
            RuntimeShader(RIM_SHADER).also { shader = it }
        } catch (t: Throwable) {
            shaderFailed = true
            try {
                errorFile?.appendText("rim shader compile failed: ${t.message}\n")
            } catch (_: Throwable) {
            }
            null
        }
    }

    /** 沿 [shape] 轮廓描一圈强度随方向变化的亮边（加法混合） */
    fun draw(
        scope: DrawScope,
        shape: Shape,
        color: Color,
        widthPx: Float,
        density: Density,
        layoutDirection: LayoutDirection,
        angleDegrees: Float = LiquidLens.rimAngleDegrees,
        falloff: Float = LiquidLens.rimFalloff,
    ) {
        val s = shaderOrNull() ?: return
        val size = scope.size
        if (size.minDimension <= 0f || widthPx <= 0f) return

        s.setFloatUniform("size", size.width, size.height)
        s.setFloatUniform("cornerRadii", cornerRadiiOf(shape, size, layoutDirection, density))
        s.setColorUniform("color", color.copy(alpha = 1f).toArgb())
        s.setFloatUniform("angle", angleDegrees * (Math.PI.toFloat() / 180f))
        s.setFloatUniform("falloff", falloff)

        paint.strokeWidth = ceil(widthPx).coerceAtMost(size.minDimension / 2f) * 2f
        paint.shader = s
        paint.alpha = color.alpha
        paint.blendMode = BlendMode.Plus
        val blurRadius = widthPx / 2f
        paint.asFrameworkPaint().maskFilter = if (blurRadius > 0f) {
            BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        } else {
            null
        }

        val outline = shape.createOutline(size, layoutDirection, density)
        val path = Path()
        when (outline) {
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Rectangle -> path.addRect(outline.rect)
            is Outline.Generic -> path.addPath(outline.path)
        }
        scope.drawContext.canvas.drawPath(path, paint)
    }
}

/** 取（并记住）边缘高光渲染器 */
@Composable
internal fun rememberLiquidRim(): LiquidRimRenderer {
    val context = LocalContext.current
    return remember(context) { LiquidRimRenderer(File(context.cacheDir, "liquid_glass_error.txt")) }
}