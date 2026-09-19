package com.dpmusic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.dpmusic.app.ui.theme.glassPanelColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpmusic.app.core.audio.AudioEffectsManager
import kotlin.math.abs

/** 推子拇指直径（曲线与推子共用，保证对齐） */
private val BandThumbSize = 18.dp

/**
 * 音效均衡器面板：
 * - 总开关 + 状态说明；
 * - 频段响应曲线（平滑样条 + 渐变填充）+ 垂直推子（拖动 / 点击定位）；
 * - 中文预设（按对数频率插值适配任意频段数）；
 * - 低音增强 / 环绕声强度滑杆 + 重置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(onDismiss: () -> Unit) {
    val state by AudioEffectsManager.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerLow, strong = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            // ---- 标题 + 总开关 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "音效均衡器",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (state.attached) "已挂载当前播放会话 · 即时生效" else "播放音乐后自动生效",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { AudioEffectsManager.setEnabled(it) },
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // ---- 频段区 ----
            if (state.attached && state.bandCount > 0) {
                EqualizerBands(state = state)
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    ) {
                        Text(
                            text = "播放任意歌曲后，这里会出现可拖动的频段推子与响应曲线",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            }

            // ---- 预设 ----
            Spacer(Modifier.height(6.dp))
            Text(
                text = "预设",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.isCustom) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("自定义") },
                    )
                }
                AudioEffectsManager.presets.forEach { preset ->
                    FilterChip(
                        selected = state.presetId == preset.id,
                        onClick = { AudioEffectsManager.setPreset(preset.id) },
                        label = { Text(preset.label) },
                    )
                }
            }

            // ---- 低音 / 环绕 ----
            Spacer(Modifier.height(10.dp))
            EffectSliderRow(
                title = "低音增强",
                value = state.bassStrength,
                supported = state.bassSupported,
                attached = state.attached,
                onChange = { AudioEffectsManager.setBassStrength(it) },
            )
            EffectSliderRow(
                title = "环绕声",
                value = state.surroundStrength,
                supported = state.surroundSupported,
                attached = state.attached,
                onChange = { AudioEffectsManager.setSurroundStrength(it) },
            )

            // ---- 重置 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { AudioEffectsManager.reset() }) {
                    Text("重置全部")
                }
            }
        }
    }
}

/** 频段区：响应曲线 + 垂直推子 + 频率 / 数值标签 */
@Composable
private fun EqualizerBands(state: AudioEffectsManager.State) {
    val dim = if (state.enabled) 1f else 0.45f
    val range = state.bandMinMb..state.bandMaxMb

    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp)
                .padding(horizontal = 20.dp),
        ) {
            ResponseCurve(
                levels = state.bandLevelsMb,
                range = range,
                dim = dim,
                modifier = Modifier.fillMaxSize(),
            )
            Row(modifier = Modifier.fillMaxSize()) {
                state.bandLevelsMb.forEachIndexed { index, level ->
                    VerticalBandSlider(
                        value = level,
                        valueRange = range,
                        onValueChange = { AudioEffectsManager.setBandLevel(index, it) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .alpha(dim),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 6.dp, end = 20.dp),
        ) {
            state.bandLevelsMb.forEachIndexed { index, level ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = formatFreq(state.bandFreqsHz.getOrNull(index) ?: 0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatDb(level),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            level > 50f -> MaterialTheme.colorScheme.primary
                            level < -50f -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** 平滑响应曲线（样条穿过各推子位置 + 渐变填充 + 节点） */
@Composable
private fun ResponseCurve(
    levels: List<Float>,
    range: ClosedFloatingPointRange<Float>,
    dim: Float,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    androidx.compose.foundation.Canvas(modifier = modifier) {
        val n = levels.size
        if (n == 0) return@Canvas
        val w = size.width
        val h = size.height
        val insetPx = BandThumbSize.toPx() / 2f
        val travel = (h - 2f * insetPx).coerceAtLeast(1f)

        // 0dB 基准线
        val zeroNorm = ((0f - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        val zeroY = insetPx + (1f - zeroNorm) * travel
        drawLine(
            color = primary.copy(alpha = 0.14f),
            start = Offset(0f, zeroY),
            end = Offset(w, zeroY),
            strokeWidth = 1.dp.toPx(),
        )

        if (n < 2) return@Canvas

        val pts = levels.mapIndexed { i, mb ->
            val x = w * (i + 0.5f) / n
            val norm = ((mb - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Offset(x, insetPx + (1f - norm) * travel)
        }

        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until n) {
            val prev = pts[i - 1]
            val cur = pts[i]
            val midX = (prev.x + cur.x) / 2f
            path.cubicTo(midX, prev.y, midX, cur.y, cur.x, cur.y)
        }

        drawPath(
            path = path,
            color = primary.copy(alpha = 0.35f + 0.55f * dim),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val fill = Path().apply {
            addPath(path)
            lineTo(pts.last().x, h)
            lineTo(pts.first().x, h)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(
                    primary.copy(alpha = 0.20f * dim),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = h,
            ),
        )

        pts.forEach { point ->
            drawCircle(color = tertiary.copy(alpha = 0.9f * dim), radius = 3.dp.toPx(), center = point)
        }
    }
}

/** 垂直推子：点击定位 + 拖动调节；轨道 / 渐变填充 / 圆形拇指 */
@Composable
private fun VerticalBandSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    val norm = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .onSizeChanged { sizePx = it }
            .pointerInput(valueRange) {
                detectTapGestures { pos ->
                    if (sizePx.height > 0) {
                        onValueChange(
                            valueFromY(pos.y, sizePx.height, valueRange, BandThumbSize.toPx() / 2f),
                        )
                    }
                }
            }
            .pointerInput(valueRange) {
                detectDragGestures { change, _ ->
                    if (sizePx.height > 0) {
                        onValueChange(
                            valueFromY(change.position.y, sizePx.height, valueRange, BandThumbSize.toPx() / 2f),
                        )
                        change.consume()
                    }
                }
            },
    ) {
        val inset = BandThumbSize / 2
        val travel = (maxHeight - BandThumbSize).coerceAtLeast(0.dp)
        val thumbCenterY = inset + travel * (1f - norm)

        // 轨道
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .height(travel)
                .clip(RoundedCornerShape(2.dp))
                .background(trackColor.copy(alpha = 0.6f)),
        )
        // 渐变填充（自底部到拇指）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = inset)
                .width(3.dp)
                .height(travel * norm)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(primary.copy(alpha = 0.4f), primary),
                        startY = 0f,
                    ),
                ),
        )
        // 拇指
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = thumbCenterY - inset)
                .size(BandThumbSize)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, primary, CircleShape),
        )
    }
}

/** 低音 / 环绕强度行 */
@Composable
private fun EffectSliderRow(
    title: String,
    value: Int,
    supported: Boolean,
    attached: Boolean,
    onChange: (Int) -> Unit,
) {
    val usable = supported || !attached
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (!usable) "设备不支持" else "${value / 10}%",
                style = MaterialTheme.typography.labelSmall,
                color = if (!usable) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..1000f,
            enabled = usable,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/* ---------------- 格式化 ---------------- */

private fun valueFromY(y: Float, hPx: Int, range: ClosedFloatingPointRange<Float>, insetPx: Float): Float {
    val travel = (hPx - 2f * insetPx).coerceAtLeast(1f)
    val norm = ((hPx - insetPx - y) / travel).coerceIn(0f, 1f)
    return range.start + norm * (range.endInclusive - range.start)
}

private fun formatDb(mb: Float): String {
    val db = mb / 100f
    return if (abs(db) < 0.05f) "0" else "%+.1f".format(db)
}

private fun formatFreq(hz: Int): String = when {
    hz <= 0 -> ""
    hz < 1000 -> "${hz}Hz"
    hz < 10_000 -> {
        val k = hz / 1000f
        if (k == k.toInt().toFloat()) "${k.toInt()}K" else "%.1fK".format(k)
    }
    else -> "${hz / 1000}K"
}