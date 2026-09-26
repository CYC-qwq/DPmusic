package com.dpmusic.app.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * DPmusic 动效系统（Motion Design Tokens）
 *
 * 设计原则（对齐 Material 3 Motion + Apple HIG 的"响应式动效"）：
 *
 * 1. **反馈必须即时**：任何用户操作的视觉反馈都要在 ~100ms 内开始，
 *    否则手感会"发黏"。所以按压 / 涟漪 / 图标切换一律用 [Fast] 档。
 * 2. **时长按语义分档**，而不是随手写数字：
 *    - 反馈类（按压、开关、图标）→ [Instant] / [Fast]
 *    - 过渡类（淡入淡出、内容切换）→ [Medium]
 *    - 空间类（位移、抽屉、共享元素）→ [Slow] / [Emphasized] / [Layout]
 * 3. **缓动分方向**：进入用 decelerate（快起慢停，感觉"轻快"），
 *    退出用 accelerate（慢起快走，让位给新内容）。
 * 4. **跟手用弹簧，播放用补间**：手势驱动的量（拖拽 / 滑块）用 [snappy]，
 *    时序驱动的量（进度条）必须用线性，否则会出现"进度跳动"。
 *
 * 全局复用本文件，避免各处硬编码 `tween(400)` 这类魔法数字。
 */
object DPMotion {

    // ---------------------------------------------------------------- 时长（ms）--

    /** 即时反馈：按压、涟漪、触觉伴随时长 */
    const val Instant = 90

    /** 快速：图标切换、开关、小元素淡入 */
    const val Fast = 140

    /** 常规：内容淡入淡出、状态切换 */
    const val Medium = 220

    /** 慢速：卡片展开、列表位移 */
    const val Slow = 320

    /** 强调：页面级过渡、大块内容进出 */
    const val Emphasized = 420

    /** 布局形变：共享元素、导航形态演进、抽屉 */
    const val Layout = 500

    // ------------------------------------------------------------------ 缓动 --

    /** M3 Standard：通用过渡曲线，两端都平滑 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** M3 Emphasized Decelerate：元素"进入 / 展开"——快起慢停 */
    val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** M3 Emphasized Accelerate：元素"退出 / 收起"——慢起快走 */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 线性：进度类专用（进度条、骨架扫光） */
    val Linear: Easing = LinearEasing

    // ------------------------------------------------------------ 常用补间 spec --

    /** 快速进入（图标 / 小元素出现） */
    fun <T> fastIn(): TweenSpec<T> = tween(Fast, easing = Decelerate)

    /** 快速退出 */
    fun <T> fastOut(): TweenSpec<T> = tween(Fast, easing = Accelerate)

    /** 常规进入（内容淡入、状态切换） */
    fun <T> enter(): TweenSpec<T> = tween(Medium, easing = Decelerate)

    /** 常规退出（比进入略快，让位新内容） */
    fun <T> exit(): TweenSpec<T> = tween(Fast + 40, easing = Accelerate)

    /** 强调过渡（页面级、大块内容） */
    fun <T> emphasized(): TweenSpec<T> = tween(Emphasized, easing = Standard)

    /** 线性（进度、扫光） */
    fun <T> linear(durationMillis: Int = Medium): TweenSpec<T> =
        tween(durationMillis, easing = LinearEasing)

    // -------------------------------------------------------------- 弹簧 spec --

    /** 跟手：拖拽 / 滑块松手后的归位——无明显过冲，快速稳定 */
    fun snappy(): SpringSpec<Float> =
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** 吸附：轻微回弹，有物理感（适合"落位"这类动作） */
    fun bouncy(): SpringSpec<Float> =
        spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)

    /** 平稳：列表 / 内容位移，绝不抖动 */
    fun gentle(): SpringSpec<Float> =
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    // ------------------------------------------------------------ 布局形变专用 --

    /** 共享元素 / 形态演进弹簧：低刚度 + 低阻尼，慢而柔的"流体"感 */
    fun morph(): SpringSpec<Float> =
        spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)
}

/**
 * 层级化入场延迟：同一屏内第 [index] 个元素的入场延迟。
 *
 * 交错入场（stagger）能显著提升"内容正在出现"的感知，
 * 但延迟必须**封顶**：超过第 8 项后不再累加，
 * 否则长列表滚动时后面的项会明显"迟到"。
 */
fun staggerDelayMillis(index: Int, stepMillis: Int = 40, maxMillis: Int = 320): Int =
    (index.coerceAtLeast(0) * stepMillis).coerceAtMost(maxMillis)
