package com.dpmusic.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.dpmusic.app.core.lyric.withSimulatedVerbatim
import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.LyricWord
import com.dpmusic.app.core.model.SongLyrics
import com.dpmusic.app.core.playback.PlayerConnection
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * 动态歌词视图：
 * - 当前行平滑高亮（颜色 / 缩放 / 透明度三重阻尼过渡）；
 * - 自动滚动跟随：拖动中暂停，松手 4s 后自动恢复（可打断，不会卡死）；
 *   首次定位 / 大跨度跳转即时完成，相邻行切换用平滑动画；
 * - 点击某行 seek 到对应时间（onLineClick），并立即恢复自动跟随；
 * - verbatim=true 时当前行按字级时间轴逐字渐变填充（卡拉OK效果，无逐字数据自动回退整行）；
 * - simulatedVerbatim=true 且无字级数据时：按行时长匀速切分模拟逐字（配合 verbatim 生效）；
 * - 右下角小按钮控制「字号 / 行距」面板展开与收起（静置 4s 自动收纳）；
 * - interactive=false 时完全让出手势（供封面模式下穿透外层切换 / 收起手势）。
 */
@Composable
fun LyricsView(
    lyrics: SongLyrics,
    positionMs: Long,
    /** 是否正在播放：逐字填充的预测式补间需要；暂停 / 缓冲时退回实际位置 */
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** 逐字歌词：行激活时按字级时间轴渐变填充 */
    verbatim: Boolean = false,
    /** 无字级数据时按行时长匀速模拟逐字（需配合 verbatim 使用） */
    simulatedVerbatim: Boolean = false,
    onLineClick: ((Long) -> Unit)? = null,
    interactive: Boolean = true,
    fontScale: Float = 1f,
    onFontScaleChange: ((Float) -> Unit)? = null,
    spacingScale: Float = 1f,
    onSpacingScaleChange: ((Float) -> Unit)? = null,
) {
    // 无字级数据时的匀速逐字兜底：按行时长把每行切分为模拟字块（仅逐字渲染开启时生效）
    val displayLyrics = remember(lyrics, verbatim, simulatedVerbatim) {
        if (verbatim && simulatedVerbatim) lyrics.withSimulatedVerbatim() else lyrics
    }
    val listState = rememberLazyListState()
    val currentIndex = remember(displayLyrics, positionMs) { findCurrentLine(displayLyrics.lines, positionMs) }

    // 自动跟随开关：拖动中暂停；松手 4s 后恢复（期间再次拖动则重新计时）
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var autoFollow by remember { mutableStateOf(true) }
    LaunchedEffect(isDragged) {
        if (isDragged) {
            autoFollow = false
        } else {
            delay(4000)
            autoFollow = true
        }
    }

    // 歌词样式面板：右下角小按钮控制展开 / 收起；展开后静置 4s 自动收纳（调节期间保持）
    var panelOpen by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(panelOpen, interactionTick) {
        if (panelOpen) {
            delay(4000)
            panelOpen = false
        }
    }

    // 滚动跟随：首次 / 大跨度用即时定位（避免长距离动画滚动），相邻行用平滑动画
    var lastScrolledIndex by remember { mutableIntStateOf(Int.MIN_VALUE) }
    LaunchedEffect(currentIndex, autoFollow) {
        if (autoFollow && currentIndex >= 0) {
            // 滚动到当前行本身：其顶部对齐「定位线」（见下方动态 contentPadding）
            val target = currentIndex
            val distance = if (lastScrolledIndex == Int.MIN_VALUE) Int.MAX_VALUE
            else abs(target - lastScrolledIndex)
            if (distance > 10) {
                listState.scrollToItem(target)
            } else {
                listState.animateScrollToItem(target)
            }
            lastScrolledIndex = target
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 动态定位线：当前行中心稳定落在视口 45% 高度处——
        // 自适应横竖屏 / 分屏等任意尺寸（横屏空间小时不再偏下）
        val viewportHeight = maxHeight
        val anchor = viewportHeight * 0.45f
        val topPadding = (anchor - 35.dp).coerceAtLeast(24.dp)
        val bottomPadding = (viewportHeight - topPadding).coerceAtLeast(24.dp)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            userScrollEnabled = interactive,
        ) {
            itemsIndexed(
                items = displayLyrics.lines,
                key = { index, line -> "$index-${line.timeMs}" },
            ) { index, line ->
                LyricLineItem(
                    line = line,
                    isActive = index == currentIndex,
                    compact = compact,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    verbatim = verbatim,
                    clickEnabled = interactive && onLineClick != null,
                    onClick = {
                        // 主动选择行：立即恢复自动跟随（此后列表会跟随播放进度滚动）
                        autoFollow = true
                        onLineClick?.invoke(line.timeMs)
                    },
                    fontScale = fontScale,
                    spacingScale = spacingScale,
                )
            }
        }

        // 歌词样式调节：右下角小按钮常驻（点击展开 / 收起），面板在按钮上方弹出
        if (interactive && onFontScaleChange != null && onSpacingScaleChange != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                AnimatedVisibility(
                    visible = panelOpen,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
                ) {
                    LyricStyleControl(
                        fontScale = fontScale,
                        onFontScaleChange = { scale ->
                            // 调节期间保持展开
                            interactionTick++
                            onFontScaleChange?.invoke(scale)
                        },
                        spacingScale = spacingScale,
                        onSpacingScaleChange = { scale ->
                            interactionTick++
                            onSpacingScaleChange?.invoke(scale)
                        },
                    )
                }

                Spacer(Modifier.height(6.dp))

                // 快捷开关：收起态半透明弱化存在感，展开态实体化
                val buttonAlpha by animateFloatAsState(
                    targetValue = if (panelOpen) 0.92f else 0.5f,
                    label = "lyricPanelButtonAlpha",
                )
                Surface(
                    modifier = Modifier
                        .size(38.dp)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = { panelOpen = !panelOpen },
                        ),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = buttonAlpha),
                    tonalElevation = 3.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (panelOpen) Icons.Filled.Close else Icons.Filled.FormatSize,
                            contentDescription = if (panelOpen) "收起歌词样式调节" else "歌词样式调节",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricLineItem(
    line: LyricLine,
    isActive: Boolean,
    compact: Boolean,
    positionMs: Long,
    isPlaying: Boolean,
    verbatim: Boolean,
    clickEnabled: Boolean,
    onClick: () -> Unit,
    fontScale: Float,
    spacingScale: Float,
) {
    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    val alpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.38f,
        animationSpec = springSpec,
        label = "lyricAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.04f else 1f,
        animationSpec = springSpec,
        label = "lyricScale",
    )
    val color by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "lyricColor",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
            .then(
                // 禁用时完全移除点击节点（而非 disabled）——
                // Compose 中 disabled clickable 仍会 consume 事件，会阻断外层手势
                if (clickEnabled) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = if (compact) 32.dp else 40.dp,
                vertical = (if (compact) 6.dp else 10.dp) * spacingScale,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (verbatim && isActive && line.words.isNotEmpty()) {
            // 逐字模式：预测式平滑 —— 播放位置每 500ms 才推送一次（ticker），直接补间到旧值会恒定滞后一个周期（≈一个字）；
            // 改为补间到「当前位置 + 一个周期」，恰好在下一次采样时追平真实进度（稳态误差 ≈0）；暂停 / 缓冲时不外推。
            val smoothPos = remember { Animatable(positionMs.toFloat()) }
            LaunchedEffect(positionMs, isPlaying) {
                val target = if (isPlaying) positionMs + PlayerConnection.TICK_MS else positionMs
                smoothPos.animateTo(
                    targetValue = target.toFloat(),
                    animationSpec = tween(
                        durationMillis = PlayerConnection.TICK_MS.toInt(),
                        easing = LinearEasing,
                    ),
                )
            }
            LyricWordsFlow(
                words = line.words,
                smoothPositionMs = smoothPos.value,
                baseStyle = scaleLyricStyle(
                    if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                    fontScale,
                ),
                highlightColor = MaterialTheme.colorScheme.primary,
                dimColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = line.text,
                style = scaleLyricStyle(
                    if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                    fontScale,
                ),
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
                textAlign = TextAlign.Center,
            )
        }
        if (!line.translation.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = line.translation,
                style = scaleLyricStyle(MaterialTheme.typography.bodySmall, fontScale),
                color = color.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/* ---------------- 逐字歌词渲染 ---------------- */

/** 逐字歌词行：按字排布（FlowRow 自动换行），仅当前激活行使用 */
@Composable
private fun LyricWordsFlow(
    words: List<LyricWord>,
    smoothPositionMs: Float,
    baseStyle: TextStyle,
    highlightColor: Color,
    dimColor: Color,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.Center,
    ) {
        words.forEach { word ->
            LyricWordItem(
                word = word,
                smoothPositionMs = smoothPositionMs,
                baseStyle = baseStyle,
                highlightColor = highlightColor,
                dimColor = dimColor,
            )
        }
    }
}

/** 单个字 / 词：已唱 → 高亮色；未唱 → 暗色；正在唱 → 左→右渐变填充 + 轻微弹起 */
@Composable
private fun LyricWordItem(
    word: LyricWord,
    smoothPositionMs: Float,
    baseStyle: TextStyle,
    highlightColor: Color,
    dimColor: Color,
) {
    val fraction = wordFillFraction(word, smoothPositionMs)
    val singing = word.durationMs > 0L && smoothPositionMs >= word.startMs && smoothPositionMs < word.endMs
    val popScale by animateFloatAsState(
        targetValue = if (singing) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "lyricWordPop",
    )

    val base = baseStyle.copy(fontWeight = FontWeight.SemiBold)
    val style = if (fraction > 0f && fraction < 1f) {
        // 正在唱：渐变填充（卡拉OK 扫过效果），渐变坐标贴合本字宽度
        base.copy(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to highlightColor,
                    fraction to highlightColor,
                    fraction to dimColor,
                    1f to dimColor,
                ),
            ),
        )
    } else {
        base
    }

    Text(
        text = word.text,
        style = style,
        color = if (fraction >= 1f) highlightColor else dimColor,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.graphicsLayer {
            if (singing) {
                scaleX = popScale
                scaleY = popScale
            }
        },
    )
}

/** 字填充比例：0 = 未唱，1 = 唱完（时长无效时按开始点二分） */
private fun wordFillFraction(word: LyricWord, positionMs: Float): Float {
    if (word.durationMs <= 0L) return if (positionMs >= word.startMs) 1f else 0f
    return ((positionMs - word.startMs.toFloat()) / word.durationMs.toFloat()).coerceIn(0f, 1f)
}

/** 二分查找当前行（带 300ms 提前量，让高亮更贴合人声） */
private fun findCurrentLine(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    val target = positionMs + 300L
    var lo = 0
    var hi = lines.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (lines[mid].timeMs <= target) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

/** 歌词字号缩放：按比例缩放字号与行高（跳过 Unspecified，防止乘算异常） */
private fun scaleLyricStyle(style: TextStyle, scale: Float): TextStyle {
    if (scale == 1f) return style
    return style.copy(
        fontSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize * scale else style.fontSize,
        lineHeight = if (style.lineHeight != TextUnit.Unspecified) style.lineHeight * scale else style.lineHeight,
    )
}

/* ---------------- 歌词字号 / 行距调节胶囊 ---------------- */

private const val LYRIC_SCALE_MIN = 0.75f
private const val LYRIC_SCALE_MAX = 1.6f
private const val LYRIC_SCALE_STEP = 0.1f
private const val LYRIC_SPACING_MIN = 0.5f
private const val LYRIC_SPACING_MAX = 2f
private const val LYRIC_SPACING_STEP = 0.1f

/** 歌词字号 / 行距调节：右下角悬浮胶囊（− 字号 ＋ / − 行距 ＋）；横竖屏分别记忆由宿主负责 */
@Composable
private fun LyricStyleControl(
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    spacingScale: Float,
    onSpacingScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        // 整体消费点击：避免在歌词模式里误触外层「切回封面」手势
        modifier = modifier.clickable(
            interactionSource = null,
            indication = null,
            onClick = {},
        ),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LyricAdjustRow(
                label = "字号",
                onDecrease = { onFontScaleChange((fontScale - LYRIC_SCALE_STEP).coerceIn(LYRIC_SCALE_MIN, LYRIC_SCALE_MAX)) },
                onIncrease = { onFontScaleChange((fontScale + LYRIC_SCALE_STEP).coerceIn(LYRIC_SCALE_MIN, LYRIC_SCALE_MAX)) },
            )
            LyricAdjustRow(
                label = "行距",
                onDecrease = { onSpacingScaleChange((spacingScale - LYRIC_SPACING_STEP).coerceIn(LYRIC_SPACING_MIN, LYRIC_SPACING_MAX)) },
                onIncrease = { onSpacingScaleChange((spacingScale + LYRIC_SPACING_STEP).coerceIn(LYRIC_SPACING_MIN, LYRIC_SPACING_MAX)) },
            )
        }
    }
}

/** 调节行：− 标签 ＋ */
@Composable
private fun LyricAdjustRow(
    label: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onDecrease,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = "减小$label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(30.dp),
        )
        IconButton(
            onClick = onIncrease,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "增大$label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
