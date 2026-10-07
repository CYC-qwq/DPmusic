package com.dpmusic.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dpmusic.app.core.audio.DspEngine
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** 示波器展示模式 */
enum class VisualizerMode(val id: String, val label: String) {
    BARS("bars", "频谱柱"),
    WAVE("wave", "波形"),
    BOTH("both", "两者"),
    ;

    companion object {
        fun fromId(id: String?): VisualizerMode =
            entries.firstOrNull { it.id == id } ?: BOTH
    }
}

/**
 * 频谱柱的**起振 / 回落时间常数**（秒）—— 二阶临界阻尼级联（两级同 τ 单极点）。
 *
 * 每帧执行（`s1` 是中间级，`y` 是要画的柱高）：
 * ```
 * α = 1 - e^(-dt/τ)                 // τ 按「输入高于还是低于输出」取起振/回落值
 * s1[i] += (D - s1[i]) * α
 * y[i]  += (s1[i] - y[i]) * α
 * ```
 * `D` 是当帧最新的频谱采样值（[PUBLISH_HZ]≈240Hz 连续更新，远高于屏幕刷新率）。
 *
 * ⚠️ 为什么必须是**二阶**，而不是一阶单极点 `y += (D - y) * α`：
 * DSP 侧是**瞬时起振**（`SpectrumAudioProcessor.analyze`：`if (t >= shown) t`），
 * 于是 `D` 对柱子是**阶跃**。一阶系统对阶跃的响应速度是 `(A/τ)·e^(-t/τ)` ——
 * 在阶跃发生的**那一帧**速度从 0 直接冲到最大 `A/τ`：速度不连续。
 * 逐帧数据看得清清楚楚（阶跃 0.10→0.90）：
 * ```
 * 一阶 τ=45ms :  100.0ms  0.1000  +0.00000
 *                108.3ms  0.2352  +0.13524   ← 首帧直接跳 0.135，速度从 0 瞬间冲满
 * 二阶 25/55  :  100.0ms  0.1000  +0.00000
 *                108.3ms  0.1929  +0.09289   ← 首帧只走 0.093，速度从 0 连续起步
 * ```
 * 二阶（临界阻尼）对阶跃的响应速度是 `ω²t·e^(-ωt)`，**t=0 处速度为 0** → 速度连续，
 * 起振是「软启动」。实测（4 个随机种子、6000ms 真实信号）：
 *
 * | 方案            | 首帧跳 | 上升 90% | 最大急动 | 可感知反转 |
 * |-----------------|--------|----------|----------|------------|
 * | 一阶 τ=45ms     | 0.135  | 108ms    | 0.0347   | 25         |
 * | **二阶 25/55**  | 0.064  | 100ms    | 0.0231   | **0**      |
 *
 * 也就是：起振手感几乎不变（100 vs 108ms），但首帧跳变减半、可感知的「猛地一抖」清零。
 *
 * 取值：起振 τ 越小越跟手（越小越跳），回落 τ 越大尾巴越长。
 * 想更「爆」→ 起振降到 0.015（首帧跳回到 ~0.14，会重新出现轻微突跳感）；
 * 想更「柔」→ 起振升到 0.035（首帧跳仅 0.036，但上升变慢到 ~133ms）。
 */
private const val BAR_ATTACK_TAU_SEC = 0.025f
private const val BAR_RELEASE_TAU_SEC = 0.055f

/**
 * 相邻柱的空间平滑权重（中间 + 两侧各一份）。
 *
 * 频谱柱是 48 个独立频段，FFT 噪声会让相邻柱忽高忽低、看起来像锯齿在抖。
 * 用一次 3 抽头平滑让邻近柱互相「带一下」，整排柱子就是一条连贯起伏的曲线。
 * 权重刻意取得**极轻**（0.06 / 0.88 / 0.06）：参考实现里每根柱是**各自独立跳动**的，
 * 平滑越重越像「一整块果冻」、失去活泼感；这里只留一点点，防止 FFT 噪声造成的单柱乱跳。
 */
private const val BAR_SPATIAL_CENTER = 0.88f
private const val BAR_SPATIAL_SIDE = 0.06f

/** 单帧时长的兜底值（拿不到上一帧时间时用，约 60Hz） */
private const val FALLBACK_FRAME_SEC = 1f / 60f

/** 单帧时长夹紧范围：避免暂停恢复后 dt 巨大导致柱子瞬间跳变（上限 100ms） */
private const val MIN_FRAME_SEC = 1f / 240f
private const val MAX_FRAME_SEC = 0.1f

/** 柱子「还在动」的判定阈值：低于它就认为已经收敛，可以停止重绘 */
private const val BAR_MOVED_EPS = 1e-4f

/** 柱状渐变笔刷缓存的量化档数（越高越精确；256 档在 60dp 高度上约 0.9px 一档） */
private const val BAR_BRUSH_LEVELS = 256

/** 柱体渐变的上下端点透明度（同色系两档：顶亮底淡） */
private const val BAR_TOP_ALPHA = 0.95f
private const val BAR_BOTTOM_ALPHA = 0.40f

/**
 * 波形自动增益（峰值保持）。
 *
 * 包络取的是**原始样本值**（-1..1），而真实音乐的瞬时峰值随母带电平差异很大
 * （0.3~0.95 都常见）—— 固定增益要么放不满（看着「细小」），要么顶到边框削掉细节。
 *
 * 所以跟踪「近期峰值」再反推增益：峰值**快升慢降**（[WAVE_PEAK_HOLD_TAU_SEC]），
 * 增益因此不会随乐句呼吸而抖动；再用 [WAVE_PEAK_FLOOR] 兜住静音段，
 * 避免一停歌就把底噪放大到满屏「炸毛」。
 */
private class WaveAutoGain {
    /** 近期峰值（保持值，不是当前帧峰值） */
    var peak = WAVE_PEAK_FLOOR
    /** 当前生效的显示增益 */
    var gain = 1f
}

/** 自动增益的目标峰值（占可用半幅的比例；留 8% 余量，不贴边） */
private const val WAVE_TARGET_PEAK = 0.92f

/** 增益上限：安静段落最多放大到几倍 */
private const val WAVE_MAX_GAIN = 2.2f

/** 峰值保持的回落时间常数（秒）：约 0.8s 半衰 → 增益不随乐句抖动 */
private const val WAVE_PEAK_HOLD_TAU_SEC = 0.8f

/** 峰值下限：接近静音时不让增益无限增大 */
private const val WAVE_PEAK_FLOOR = 0.06f

/**
 * 波形跨列平滑半径（列）。
 *
 * 数据侧给的是每列 `(min, max)` 的**极值** —— 极值对高频瞬态极其敏感，
 * 直接连起来是一条毛刺线（视觉上像「毛线」而不是水流）。
 * 用半径 3 的三角核沿时间方向平滑（≈3ms），抹掉毛刺但保留乐句起伏。
 */
private const val WAVE_SMOOTH_RADIUS = 3

/** 水体填充的透明度：中心亮、上下边缘淡 → 有体积感 */
private const val WAVE_CORE_ALPHA = 0.42f
private const val WAVE_EDGE_ALPHA = 0.04f

/**
 * 柱状渐变笔刷缓存。
 *
 * 每根柱一个 `Brush.verticalGradient` 时，等于**每帧新建 48 个原生 Shader**，
 * 而且是在 UI 线程上（Compose 的绘制指令录制阶段）——120Hz 下帧预算只有 8.3ms，
 * 这笔开销足以把动画挤掉帧。
 *
 * 按高度量化后缓存：同一档高度复用同一个 Brush，渐变端点最多偏 0.9px（肉眼不可见）。
 * 配色 / 尺寸 / 亮度（dim）变化时整体失效重建 —— 这三者都极少变。
 */
private class BarBrushCache {
    private var barsH = Float.NaN
    private var primary: Color = Color.Unspecified
    private var tertiary: Color = Color.Unspecified
    private var dim = Float.NaN
    private val brushes = arrayOfNulls<Brush>(BAR_BRUSH_LEVELS + 1)

    /** 尺寸 / 配色 / 亮度没变就继续用缓存 */
    fun matches(height: Float, primary: Color, tertiary: Color, dim: Float): Boolean =
        barsH == height && this.primary == primary && this.tertiary == tertiary && this.dim == dim

    fun reset(height: Float, primary: Color, tertiary: Color, dim: Float) {
        barsH = height
        this.primary = primary
        this.tertiary = tertiary
        this.dim = dim
        brushes.fill(null)
    }

    /** 取（或按需创建）某一档高度的渐变笔刷 */
    fun brushFor(level: Int): Brush =
        brushes[level] ?: Brush.verticalGradient(
            colors = listOf(
                // 同色系两档（顶亮底淡）。
                // 原来是 primary → tertiary 跨色相渐变 —— 封面取色后两者差异很大
                // （截图里就是「棕 → 卡其绿」），柱子看起来发脏、不像一个整体。
                primary.copy(alpha = (BAR_TOP_ALPHA * dim).coerceIn(0f, 1f)),
                primary.copy(alpha = (BAR_BOTTOM_ALPHA * dim).coerceIn(0f, 1f)),
            ),
            startY = barsH * (1f - level.toFloat() / BAR_BRUSH_LEVELS),
            endY = barsH,
        ).also { brushes[level] = it }
}

/**
 * 播放页示波器：FFT 频谱柱 + 实时波形。
 *
 * 数据来自 [DspEngine] 内的 `SpectrumAudioProcessor`（跑在 Media3 音频链上，
 * 拿到的是「EQ 之后、写入 AudioTrack 之前」的样本 —— 所见即所听）。
 *
 * ## 为什么看起来「顺」
 * 1. **数据侧 240Hz 发布**（≥ 屏幕刷新率；本机是 120Hz）：每个 vsync 都必然拿到新快照，
 *    不会出现「走一帧、停一帧」的时间混叠 —— 这是「一卡一卡」的真正来源；
 * 2. **UI 侧逐帧二阶临界阻尼跟随**（[BAR_ATTACK_TAU_SEC] / [BAR_RELEASE_TAU_SEC]）：
 *    渲染值以两级级联单极点跟随当帧最新采样值（α 与帧率无关：60/90/120Hz 观感一致）。
 *    用**二阶**是因为 DSP 侧瞬时起振 → 目标值是阶跃，一阶系统对阶跃的响应速度会
 *    在跳变那一帧从 0 突冲到最大（速度不连续 = 观感「突然跳到某个值」）；
 *    二阶的响应速度从 0 连续起步，起振软启动、全程无突跳（详见 [BAR_ATTACK_TAU_SEC]）。
 *    **也刻意不做「关键帧采样 + 段内补间」** —— 那类机制把 240Hz 连续数据降采样成
 *    离散关键帧并强制精确落值，落值瞬间速度突变，是同一个病根的另一种写法。
 *    再用 3 抽头空间平滑（[BAR_SPATIAL_CENTER]）让相邻柱互相带一下，
 *    整排柱子连成一条起伏曲线而不是一排抖动的锯齿；
 * 3. **笔刷跨帧复用**：柱状渐变按高度量化缓存，省掉每帧 48 次原生 Shader 创建；
 * 4. **无变化不重绘**：暂停 / 静音时彻底停下，不再空转 60fps 白烧 GPU 与电量。
 *
 * ## 视觉
 * 波形**不使用卡片/背景**：直接画在页面流光底上。
 * 数据侧给的 `(min, max)` 先合成**对称幅度包络**（取 |min| / |max| 的较大者），
 * 再沿时间方向做三角核平滑（[WAVE_SMOOTH_RADIUS]）→ 得到一条干净的水带：
 * 上边缘是亮的主色描边（水面），下边缘同色更淡（水底），
 * 中间填充「中心亮、边缘淡」的渐变（体积感）。
 * 配色统一在 primary 同色系内 —— 跨色相（primary → tertiary）在封面取色后
 * 会呈现「棕 + 卡其绿」这类发脏的搭配。
 * 横轴是最近 [WAVE_SPAN_SEC] 秒的连续时间：新样本从右侧进入、整幅向左流动，
 * 与「玻璃风格」的沉浸感一致（避免把可视化框成一块独立面板）。
 */
@Composable
fun SpectrumVisualizer(
    enabled: Boolean,
    mode: VisualizerMode,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = enabled,
        enter = expandVertically(
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top,
        ) + fadeIn(tween(220, delayMillis = 60)),
        exit = shrinkVertically(tween(200)) + fadeOut(tween(120)),
        modifier = modifier,
    ) {
        VisualizerCanvas(mode = mode)
    }
}

@Composable
private fun VisualizerCanvas(mode: VisualizerMode) {
    val barCount = DspEngine.barCount
    val waveColumns = DspEngine.waveColumns
    // 每列是 (min, max) 两个值 → 发布长度是列数的两倍
    val waveFloats = waveColumns * 2

    // 音频侧快照（一次拷满，UI 侧再拆开）
    val snapshot = remember { FloatArray(barCount + waveFloats) }
    // 渲染值：二阶临界阻尼级联 —— barStage1 是中间级，bars 是绘制值。
    // 逐帧跟随最新采样、速度连续（见 [BAR_ATTACK_TAU_SEC] 的说明），无采样-保持跳变。
    val barStage1 = remember { FloatArray(barCount) }
    val bars = remember { FloatArray(barCount) }
    // 空间平滑后的绘制值。
    // ⚠️ 必须是**独立数组**：如果就地平滑 `bars`，平滑结果会反馈进下一帧的缓动状态，
    // 形成递归模糊 —— 静音后柱子会「拖尾」很久，鼓点也会被自己抹平。
    val spatial = remember { FloatArray(barCount) }
    val wave = remember { FloatArray(waveFloats) }
    // 幅度包络：数据侧给的是每列 (min, max) 两个值，这里转成**对称的单值**包络
    val waveAmpRaw = remember { FloatArray(waveColumns) }
    val waveAmp = remember { FloatArray(waveColumns) }
    // 波形自动增益状态（普通对象：绘制侧读取，靠下面的 frame 计数触发重绘）
    val waveGain = remember { WaveAutoGain() }

    var hasSignal by remember { mutableStateOf(false) }

    // ⚠️ 重绘驱动：Canvas 读的是普通 FloatArray（不是 SnapshotState），
    // Compose 追踪不到它的内容变化 —— 必须用一个 state 计数来触发重组/重绘。
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var lastIdx = -1
        var lastFrameNs = 0L
        while (true) {
            val nowNs = withFrameNanos { it }
            val dtSec = if (lastFrameNs == 0L) {
                FALLBACK_FRAME_SEC
            } else {
                ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat()
                    .coerceIn(MIN_FRAME_SEC, MAX_FRAME_SEC)
            }
            lastFrameNs = nowNs

            val idx = DspEngine.copySpectrum(snapshot)
            // 有新数据才更新目标值；平滑每帧都做（所以即使数据没变画面也在过渡）
            val fresh = idx != lastIdx
            lastIdx = idx

            // ---- 柱子：二阶临界阻尼级联，逐帧跟随最新采样值 ----
            // 数据源是 [PUBLISH_HZ]≈240Hz 的连续发布，每帧都有新值——但 DSP 侧是
            // 「瞬时起振」，所以 D 对柱子是阶跃。一阶平滑对阶跃的响应速度在跳变那一帧
            // 从 0 直接冲到最大（速度不连续 → 观感「突然跳到某个值」）；
            // 二阶（两级级联）的阶跃响应速度从 0 连续起步 → 起振「软启动」、全程无突跳。
            // 详见 [BAR_ATTACK_TAU_SEC]。α 与帧率无关（120/60Hz 观感一致）。
            val alphaA = (1.0 - exp(-dtSec / BAR_ATTACK_TAU_SEC)).toFloat().coerceIn(0f, 1f)
            val alphaR = (1.0 - exp(-dtSec / BAR_RELEASE_TAU_SEC)).toFloat().coerceIn(0f, 1f)

            var any = false
            var moved = false
            for (i in 0 until barCount) {
                val target = snapshot[i]
                val prev = bars[i]
                // τ 按「目标高于还是低于当前输出」取起振/回落值（电平表手感）
                val alpha = if (target > prev) alphaA else alphaR
                val mid = barStage1[i] + (target - barStage1[i]) * alpha
                barStage1[i] = mid
                val next = prev + (mid - prev) * alpha
                if (abs(next - prev) > BAR_MOVED_EPS) moved = true
                bars[i] = next
                if (next > 0.012f) any = true
            }
            // 相邻柱空间平滑（权重见 [BAR_SPATIAL_CENTER]，和恒为 1 → 整体响度不变）：
            // 48 个独立频段受 FFT 噪声影响会忽高忽低；让邻近柱互相「带」一点点，
            // 抑制单柱乱跳，但保留每根柱各自起伏的活泼感。两端夹紧（频谱不是环形）。
            for (i in 0 until barCount) {
                val l = bars[if (i == 0) 0 else i - 1]
                val r = bars[if (i == barCount - 1) i else i + 1]
                spatial[i] = bars[i] * BAR_SPATIAL_CENTER + (l + r) * BAR_SPATIAL_SIDE
            }
            // 波形自动增益：跟踪包络峰值（快升慢降），据此反推显示增益。
            // 峰值取「近期最大值」而不是「当前帧最大值」—— 否则每个安静间隙增益都会暴涨，
            // 画面随乐句一缩一放地「呼吸」。
            var pk = 0f
            for (k in 0 until waveFloats) {
                val a = abs(snapshot[barCount + k])
                if (a > pk) pk = a
            }
            val held = (waveGain.peak * exp(-dtSec / WAVE_PEAK_HOLD_TAU_SEC))
                .coerceAtLeast(WAVE_PEAK_FLOOR)
            waveGain.peak = if (pk > held) pk else held
            waveGain.gain = (WAVE_TARGET_PEAK / waveGain.peak).coerceIn(1f, WAVE_MAX_GAIN)

            // 波形：**直接拷贝**，不做逐点缓动。
            // 原因：滚动波形的横轴是连续时间，相邻两帧整幅只平移不到 1% 宽度；
            // 逐点插值反而会把两帧之间新进来的样本抹掉（画面发虚）。
            // 流动的连续性靠 240Hz 数据率（≥ 屏幕刷新率）保证，不靠插值。
            System.arraycopy(snapshot, barCount, wave, 0, waveFloats)

            // 幅度包络：取 |min| 与 |max| 的较大者 → **上下对称**。
            // 为什么不用 min/max 双包络：两者分离后画面像两条互不相干的乱线
            // （截图里就是「上棕下淡绿」的两团毛线），而且极值毛刺让轮廓发毛。
            // 对称化后视觉重心稳在中线，才像一条流动的水带。
            for (c in 0 until waveColumns) {
                val mn = wave[c * 2]
                val mx = wave[c * 2 + 1]
                waveAmpRaw[c] = if (-mn > mx) -mn else mx
            }
            // 跨列三角核平滑（半径 [WAVE_SMOOTH_RADIUS] 列 ≈ 3ms）：
            // 抹掉极值毛刺，保留乐句起伏。两端夹紧取值（时间轴不是环形）。
            val r = WAVE_SMOOTH_RADIUS
            val norm = ((r + 1) * (r + 1)).toFloat()
            for (c in 0 until waveColumns) {
                var acc = 0f
                for (k in -r..r) {
                    val j = (c + k).coerceIn(0, waveColumns - 1)
                    acc += waveAmpRaw[j] * (r + 1 - abs(k))
                }
                waveAmp[c] = acc / norm
            }

            if (fresh) hasSignal = any
            // 只有「有新数据」或「柱子仍在收敛」才请求重绘：
            // 暂停 / 静音时彻底停下，不再空转 60fps 白烧 GPU 与电量。
            if (fresh || moved) frame++
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val dim = if (hasSignal) 1f else 0.30f
    // 画布高度：「两者」模式给柱子 + 波形更多纵向空间（原来 104/88dp 偏挤，
    // 波形区被压到只剩 30% → 看着「细小」；现在波形区拿到 40%）。
    val h = if (mode == VisualizerMode.BOTH) 116.dp else 104.dp

    // 柱状渐变笔刷缓存（跨帧复用，避免每帧 48 次原生 Shader 创建）
    val barBrushes = remember { BarBrushCache() }
    // 波形主线的横向渐变（同色系三档，中段略亮）。
    // 横向渐变本身就是「流动感」的来源：线条像有方向，而不是一根死线。
    val waveLineBrush = remember(primary, dim) {
        Brush.horizontalGradient(
            colors = listOf(
                primary.copy(alpha = (0.72f * dim).coerceIn(0f, 1f)),
                primary.copy(alpha = (0.95f * dim).coerceIn(0f, 1f)),
                primary.copy(alpha = (0.72f * dim).coerceIn(0f, 1f)),
            ),
        )
    }
    // 水底：同色更淡的一档，形成「水体厚度」
    val waveLineBrushSoft = remember(primary, dim) {
        Brush.horizontalGradient(
            colors = listOf(
                primary.copy(alpha = (0.30f * dim).coerceIn(0f, 1f)),
                primary.copy(alpha = (0.45f * dim).coerceIn(0f, 1f)),
                primary.copy(alpha = (0.30f * dim).coerceIn(0f, 1f)),
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(h)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(h)) {
            // 读 frame 建立「重绘依赖」：帧计数一变，这段绘制 lambda 就会重新执行
            @Suppress("UNUSED_EXPRESSION")
            frame

            val w = size.width
            val hh = size.height
            val showBars = mode != VisualizerMode.WAVE
            val showWave = mode != VisualizerMode.BARS
            // 柱区高度：两者同屏时柱子只占上面 55%，否则占满
            val barsH = if (mode == VisualizerMode.BOTH) hh * 0.55f else hh
            // 波形区：**只有「两者」模式才需要给柱子让位**。
            // 原来这里无条件用 `barsH + hh*10%`，而「仅波形」模式下 barsH 已经是 hh，
            // 于是 waveH 算成 -0.1hh（负高度）→ amp 为负 → 整条波形被压到画布外，
            // 只剩一条贴着底边的细线。默认模式是「两者」，所以一直没暴露。
            val waveTop = if (showBars && showWave) barsH + hh * 0.05f else 0f
            val waveH = hh - waveTop

            // ---- 频谱柱：无底板，直接从流光底上「长」出来 ----
            if (showBars) {
                val gap = w * 0.005f
                val bw = ((w - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
                // 尺寸 / 配色 / 亮度变化时整体失效重建（都极少变）
                if (!barBrushes.matches(barsH, primary, tertiary, dim)) {
                    barBrushes.reset(barsH, primary, tertiary, dim)
                }
                for (i in 0 until barCount) {
                    // 读空间平滑后的值（`spatial`），而不是原始缓动值：
                    // 后者相邻柱各自为政，画出来是锯齿；前者是连贯曲线。
                    val v = spatial[i].coerceIn(0f, 1f)
                    if (v <= 0.004f) continue
                    val bh = (barsH * v).coerceAtLeast(1.5f)
                    val x = i * (bw + gap)
                    // 按量化高度取缓存笔刷：渐变端点最多偏 ~0.9px（肉眼不可见），
                    // 但省掉每帧 48 次原生 Shader 创建（原本全落在 UI 线程上）。
                    val level = (v * BAR_BRUSH_LEVELS).roundToInt().coerceIn(1, BAR_BRUSH_LEVELS)
                    drawRoundRect(
                        brush = barBrushes.brushFor(level),
                        topLeft = Offset(x, barsH - bh),
                        size = Size(bw, bh),
                        cornerRadius = CornerRadius(bw * 0.5f),
                    )
                }
            }

            // ---- 滚动波形：**对称幅度包络**围成的一条水带，整幅向左流动 ----
            if (showWave) {
                val mid = waveTop + waveH / 2f
                val amp = (waveH / 2f) * 0.96f
                val step = if (waveColumns > 1) w / (waveColumns - 1) else w
                // 幅度 → 半幅像素：乘自动增益后夹到 [0,1]（增益上限 2.2，仍可能溢出）
                val g = waveGain.gain
                fun halfOf(c: Int) = (waveAmp[c] * g).coerceIn(0f, 1f) * amp

                // 上下边缘：同一条包络的镜像 → 形状天然对称、重心稳在中线
                // （中线本身也就不需要了：对称图形的视觉重心已经在线上）
                val crest = Path()
                val trough = Path()
                for (c in 0 until waveColumns) {
                    val x = c * step
                    val y = halfOf(c)
                    val hi = mid - y
                    val lo = mid + y
                    if (c == 0) {
                        crest.moveTo(x, hi)
                        trough.moveTo(x, lo)
                    } else {
                        crest.lineTo(x, hi)
                        trough.lineTo(x, lo)
                    }
                }

                // 水体：crest + 反向的 trough 闭合成面。
                // 垂直渐变「中心亮、边缘淡」→ 有体积感，像一条有厚度的水带，
                // 而不是两根线夹一块死板的色块。
                val body = Path().apply {
                    addPath(crest)
                    for (c in waveColumns - 1 downTo 0) {
                        lineTo(c * step, mid + halfOf(c))
                    }
                    close()
                }
                drawPath(
                    path = body,
                    brush = Brush.verticalGradient(
                        0f to primary.copy(alpha = (WAVE_EDGE_ALPHA * dim).coerceIn(0f, 1f)),
                        0.5f to primary.copy(alpha = (WAVE_CORE_ALPHA * dim).coerceIn(0f, 1f)),
                        1f to primary.copy(alpha = (WAVE_EDGE_ALPHA * dim).coerceIn(0f, 1f)),
                        startY = mid - amp,
                        endY = mid + amp,
                    ),
                )
                // 水面（上边缘）：最亮的一条线，滚动时最抢眼
                drawPath(
                    path = crest,
                    brush = waveLineBrush,
                    style = Stroke(
                        width = 1.8.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                // 水底（下边缘）：同色更淡 → 形成「厚度」，不抢水面的焦点
                drawPath(
                    path = trough,
                    brush = waveLineBrushSoft,
                    style = Stroke(
                        width = 1.2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }

        if (!hasSignal) {
            Text(
                text = "播放后显示实时频谱",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }
    }
}
