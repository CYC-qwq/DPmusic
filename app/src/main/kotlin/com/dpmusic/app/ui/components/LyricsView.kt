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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.dpmusic.app.core.lyric.withSimulatedVerbatim
import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.LyricWord
import com.dpmusic.app.core.model.MusicPlatform
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

    // 每行字号自适应用的测量器：整个歌词视图共用一个实例（内部带布局缓存）
    val measurer = rememberTextMeasurer()

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

        // 行的横向换行约束：视口宽 - 两侧留白（点击框留白 + 换行留白）。
        // 传下行项，让「点击框包住文字」的同时，长句换行位置与改动前一致。
        val lineMaxWidth = (
            maxWidth - (LYRIC_HIT_PADDING_H + if (compact) {
                LYRIC_WRAP_PADDING_H_COMPACT
            } else {
                LYRIC_WRAP_PADDING_H
            }) * 2
            ).coerceAtLeast(120.dp)

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
                    maxLineWidth = lineMaxWidth,
                    measurer = measurer,
                )
            }
        }

        // 跨平台兜底提示：固定在歌词区顶部（不随滚动消失），
        // 告知用户「这份歌词不是本平台提供的」，不冒充原平台。
        if (displayLyrics.isCrossPlatform) {
            CrossPlatformLyricBadge(
                sourcePlatform = displayLyrics.sourcePlatform!!,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
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

/**
 * 跨平台歌词来源提示。
 *
 * 为什么需要它：本平台没有歌词时，我们会去别的平台兜一份回来（见
 * `MusicRepository.lyrics`）。但「从 QQ 音乐拿网易云的歌的歌词」是用户
 * 未必预期的事 —— 直接显示会让人以为原平台本来就有。这里明确标注来源，
 * 把选择权交回用户（不满意可关掉对应音源开关）。
 *
 * 只做「告知」，不做「操作」：不放跳转按钮，避免把歌词区变成导航入口。
 */
@Composable
private fun CrossPlatformLyricBadge(
    sourcePlatform: MusicPlatform,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 小圆点用平台品牌色：比纯文字更快让人意识到「来源变了」
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Color(sourcePlatform.brandColor), RoundedCornerShape(50)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "歌词来自 ${sourcePlatform.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    /** 行的横向换行约束（由宿主按视口宽算出；不参与点击命中，见下方 modifier 顺序） */
    maxLineWidth: Dp,
    /** 每行字号自适应用的测量器（跨行复用同一实例 → 共享布局缓存） */
    measurer: TextMeasurer,
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

    val wrapPadding = if (compact) LYRIC_WRAP_PADDING_H_COMPACT else LYRIC_WRAP_PADDING_H

    // 每行字号自适应：短行放大、长行缩小，让整行尽量落在 [LYRIC_AUTOSIZE_MAX_LINES] 行内。
    // 注意**不缩放行高** —— 行高保持主题值、与字号无关，于是整屏的行间距恒定，
    // 不会因为这一行「字大 / 字小」而把上下行推来推去。
    val themeStyle = if (compact) {
        MaterialTheme.typography.bodyLarge
    } else {
        MaterialTheme.typography.titleMedium
    }
    val autoSize = rememberAutoSizeLyricFontSize(
        measurer = measurer,
        text = remember(line) { lyricTextForMeasure(line) },
        baseStyle = themeStyle,
        fontScale = fontScale,
        maxLineWidth = maxLineWidth,
    )
    val lineStyle = themeStyle.copy(fontSize = autoSize)

    // 顺序（自外向内，决定「谁包住谁」）：
    //   wrapContentWidth → 外层不再强制满宽，行项包裹内容
    //   graphicsLayer    → 高亮缩放（放在点击框以内，动画不影响命中区）
    //   clickable        → 点击框（此时"内容"= 下方 widthIn 定出的文本块）
    //   padding(HIT)     → 点击框相对文字外扩这点留白
    //   widthIn          → 换行宽度约束（不参与命中）
    //   padding(WRAP)。
    //
    // 关键点：原实现在最外层用 fillMaxWidth()，点击框就横跨整屏 ——
    // 点歌词左右两侧的空白也会 seek。这里换成「点击框只包住文字」。
    Column(
        modifier = Modifier
            .wrapContentWidth()
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
                horizontal = LYRIC_HIT_PADDING_H,
                vertical = (if (compact) 6.dp else 10.dp) * spacingScale,
            )
            .widthIn(max = maxLineWidth)
            .padding(horizontal = wrapPadding),
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
                baseStyle = lineStyle,
                highlightColor = MaterialTheme.colorScheme.primary,
                dimColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = line.text,
                style = lineStyle,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
                textAlign = TextAlign.Center,
            )
        }
        if (!line.translation.isNullOrBlank()) {
            // 译文行不做自适应：它本来就用更小的固定字号，跟着正文缩放反而会
            // 在「大字行」下变得和正文一样大、抢戏。
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

/**
 * 按**绝对倍数**缩放字号（与 [scaleLyricStyle] 的区别：这里只缩放字体，不动行高）。
 *
 * 回调侧传 `1f` 表示「未手动调过」→ 直接沿用主题字号，不做任何 em 换算，
 * 避免无谓的 `TextUnit` 重建。
 */
private fun lyricFontSize(base: TextStyle, fontScale: Float): TextUnit {
    val size = base.fontSize
    if (fontScale == 1f || size == TextUnit.Unspecified) return size
    return size * fontScale
}

/**
 * 测量用文本。
 *
 * 有字级数据（含 [withSimulatedVerbatim] 补出来的模拟字）时，逐字路径是 FlowRow
 * 拼接各个 `word.text`，与整行 `Text(line.text)` 的断行位置**不完全一致**
 * （FlowRow 不合并字距、断行只发生在 word 之间）。所以测量时也按同样的方式拼接，
 * 让两条渲染路径共用同一套换行假设 —— 否则自动字号会在两套布局间「估错」。
 */
private fun lyricTextForMeasure(line: LyricLine): String =
    if (line.words.isNotEmpty()) line.words.joinToString("") { it.text } else line.text

/**
 * 每行字号自适应：**字号恒定（不按行高缩放，垂直节奏统一）**，但把短行放大、长行缩小，
 * 使整行尽量落在 [maxLineWidth] 的 *[LYRIC_AUTOSIZE_MAX_LINES] 行* 以内。
 *
 * 为什么要自适应（而不是固定字号）：固定字号下短句只占屏幕一小段、长句要换三行，
 * 视觉上「一行一个大小」，阅读节奏被切碎。
 *
 * 算法：先按主题字号 × `fontScale` 测一次；溢出才二分收缩，没溢出则二分放大
 * （上限 [LYRIC_AUTOSIZE_MAX_GROWTH]）。两条路径都是「离线测量」——
 * [androidx.compose.ui.text.TextMeasurer] 会缓存布局结果，每行只在文本 / 宽度 /
 * 字号变化时重算一次，正常滚动命中缓存。
 *
 * 返回值：恒为**绝对值**（不是字号的倍数），可直接塞进 `TextStyle.fontSize`。
 */
@Composable
private fun rememberAutoSizeLyricFontSize(
    measurer: TextMeasurer,
    text: String,
    baseStyle: TextStyle,
    fontScale: Float,
    maxLineWidth: Dp,
): TextUnit {
    val density = LocalDensity.current
    return remember(measurer, text, baseStyle, fontScale, maxLineWidth, density) {
        val base = lyricFontSize(baseStyle, fontScale)
        if (base == TextUnit.Unspecified || text.isBlank()) return@remember base

        val widthPx = with(density) {
            (maxLineWidth * LYRIC_AUTOSIZE_WIDTH_SAFETY).toPx()
        }.toInt()
        if (widthPx <= 0) return@remember base

        // 判据必须用 **lineCount**（与实际字号无关），不能用「高度 ≤ 单行高度 × N」：
        // 放大字号会等比放大行高，那样连「确实只有一行」都会被误判成溢出，
        // 结果所有行都退化成缩小分支。
        val fits = { fs: TextUnit ->
            measurer.measure(
                text = text,
                style = baseStyle.copy(fontSize = fs),
                constraints = Constraints(maxWidth = widthPx),
            ).lineCount <= LYRIC_AUTOSIZE_MAX_LINES
        }

        if (fits(base)) {
            // 没溢出 → 放大到「刚好还是 N 行」的最大字号
            var lo = base.value
            var hi = base.value * LYRIC_AUTOSIZE_MAX_GROWTH
            if (fits(base * LYRIC_AUTOSIZE_MAX_GROWTH)) {
                lo = hi
            } else {
                repeat(LYRIC_AUTOSIZE_SEARCH_STEPS) {
                    val mid = (lo + hi) / 2f
                    if (fits(base * (mid / base.value))) lo = mid else hi = mid
                }
            }
            base * (lo / base.value)
        } else {
            // 溢出 → 收缩到「刚好落下」的最大字号，但不小于下限
            var lo = base.value * LYRIC_AUTOSIZE_MIN_SHRINK
            var hi = base.value
            if (!fits(base * LYRIC_AUTOSIZE_MIN_SHRINK)) {
                // 连下限都放不下：就用下限（继续换行，不再缩）
                return@remember base * LYRIC_AUTOSIZE_MIN_SHRINK
            }
            repeat(LYRIC_AUTOSIZE_SEARCH_STEPS) {
                val mid = (lo + hi) / 2f
                if (fits(base * (mid / base.value))) lo = mid else hi = mid
            }
            base * (lo / base.value)
        }
    }
}

/* ---------------- 歌词字号 / 行距调节胶囊 ---------------- */

private const val LYRIC_SCALE_MIN = 0.75f
private const val LYRIC_SCALE_MAX = 1.6f
private const val LYRIC_SCALE_STEP = 0.1f
private const val LYRIC_SPACING_MIN = 0.5f
private const val LYRIC_SPACING_MAX = 2f
private const val LYRIC_SPACING_STEP = 0.1f

/**
 * 歌词行**点击框**相对文字边缘的横向留白。
 *
 * 原来行项是 `fillMaxWidth()`，点击框横跨整个屏幕宽度 —— 点歌词左右两侧的空白处
 * 也会 seek 到该行，手感「框比字大得多」。改成让点击框只包住文字本身（+ 这点留白）。
 */
private val LYRIC_HIT_PADDING_H = 10.dp

/**
 * 歌词行横向「换行宽度」约束（不参与点击，见 [LyricLineItem] 的 modifier 顺序）。
 *
 * `LYRIC_HIT_PADDING_H + 此值` 等于改动前的横向 padding（32dp / 40dp），
 * 因此长句的**换行位置与改动前完全一致**，只是点击框收窄到文字附近。
 */
private val LYRIC_WRAP_PADDING_H = 30.dp
private val LYRIC_WRAP_PADDING_H_COMPACT = 22.dp

/* ---------------- 每行字号自适应 ---------------- */

/** 自适应时允许占用的最大行数：**1 行** —— 让每行都力争一行放下，长句自动缩小而不是折行 */
private const val LYRIC_AUTOSIZE_MAX_LINES = 1

/**
 * 相对主题字号的**放大上限**（短句最多放大到几倍）。
 *
 * ⚠️ 这个值本质上就是「整首歌的字号」：本机 438dp 视口下基础 16sp 一行已能放下约 21 个
 * 汉字，实测 17 行真实歌词里有 13 行不受行长约束、直接顶到本上限。设 1f = 只缩不放。
 * 所以它是一次性的整体观感选择，而不是「自适应强度」。
 */
private const val LYRIC_AUTOSIZE_MAX_GROWTH = 1.25f

/**
 * 相对主题字号的**缩小下限**（超长句最多缩到几倍）。
 *
 * 实测 27 字的超长行会缩到 ~0.80×（落在下限附近）；再长的行不再继续缩，改为折行 ——
 * 否则字号会小到读不清，得不偿失。
 */
private const val LYRIC_AUTOSIZE_MIN_SHRINK = 0.80f

/**
 * 测量宽度安全系数。
 *
 * 两个来源：当前行有 1.04 的高亮缩放（视觉上更宽），以及逐字路径用 FlowRow
 * 换行、与 Text 的原生断行略有差异。留 4% 余量，避免「刚好放满」的行被挤出去。
 */
private const val LYRIC_AUTOSIZE_WIDTH_SAFETY = 0.96f

/** 二分搜索步数（8 步把区间细分到 1/256，精度远高于肉眼可辨） */
private const val LYRIC_AUTOSIZE_SEARCH_STEPS = 8

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
