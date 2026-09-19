package com.dpmusic.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dpmusic.app.core.data.ListeningStatsStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 听歌统计卡片：
 * - 本次播放时长（自本次打开 App 后首次播放起，秒级实时刷新）；
 * - 近 7 日每日听歌时长柱状图（今日高亮渐变，入场生长动画）。
 */
@Composable
fun ListeningStatsCard(
    days: List<ListeningStatsStore.DayStat>,
    sessionSeconds: Long,
    sessionStartMs: Long?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val todaySeconds = days.lastOrNull()?.seconds ?: 0L

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            // ---- 标题行 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "听歌统计",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "近 7 日 ${shortDuration(days.sumOf { it.seconds })}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))

            // ---- 本次播放时长 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "本次播放时长",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (sessionStartMs == null) "--:--" else clockDuration(sessionSeconds),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Light,
                        color = if (sessionStartMs == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (sessionStartMs == null) {
                            "本次打开后尚未播放"
                        } else {
                            "自 ${timeOfDay(sessionStartMs)} 起 · 今日已听 ${shortDuration(todaySeconds)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(isPlaying = isPlaying, started = sessionStartMs != null)
            }

            Spacer(Modifier.height(18.dp))

            // ---- 近 7 日柱状图 ----
            WeekBarChart(days = days)
        }
    }
}

/** 播放状态胶囊（播放中呼吸点 / 已暂停 / 待播放） */
@Composable
private fun StatusChip(isPlaying: Boolean, started: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    val dotColor = when {
        isPlaying -> MaterialTheme.colorScheme.primary
        started -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        shape = CircleShape,
        color = if (isPlaying) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .alpha(if (isPlaying) pulse else 0.55f)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = when {
                    isPlaying -> "播放中"
                    started -> "已暂停"
                    else -> "待播放"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 近 7 日柱状图（今日高亮渐变，逐条生长动画） */
@Composable
private fun WeekBarChart(days: List<ListeningStatsStore.DayStat>) {
    val maxSeconds = (days.maxOfOrNull { it.seconds } ?: 0L).coerceAtLeast(1L)

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEachIndexed { index, day ->
            val target = when {
                day.seconds <= 0L -> 0.02f
                else -> (day.seconds.toFloat() / maxSeconds).coerceIn(0.08f, 1f)
            }
            val fraction by animateFloatAsState(
                targetValue = if (entered) target else 0f,
                animationSpec = tween(
                    durationMillis = 560,
                    delayMillis = index * 45,
                    easing = FastOutSlowInEasing,
                ),
                label = "bar_$index",
            )

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = barLabel(day.seconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day.isToday) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(88.dp * fraction)
                        .clip(
                            RoundedCornerShape(
                                topStart = 8.dp,
                                topEnd = 8.dp,
                                bottomStart = 3.dp,
                                bottomEnd = 3.dp,
                            ),
                        )
                        .background(
                            if (day.isToday) {
                                Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary,
                                    ),
                                )
                            } else {
                                Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.40f),
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    ),
                                )
                            },
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = weekdayLabel(day),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day.isToday) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (day.isToday) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/* ---------------- 格式化工具 ---------------- */

/** 秒 → 时钟格式：h:mm:ss 或 m:ss */
private fun clockDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 秒 → 紧凑中文：42分 / 1时23分 */
private fun shortDuration(seconds: Long): String = when {
    seconds <= 0L -> "0分"
    seconds < 60L -> "<1分"
    seconds < 3600L -> "${seconds / 60}分"
    else -> {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        if (m == 0L) "${h}小时" else "${h}时${m}分"
    }
}

/** 柱顶标签：0 → —；其余同紧凑格式 */
private fun barLabel(seconds: Long): String =
    if (seconds <= 0L) "—" else shortDuration(seconds)

private fun weekdayLabel(day: ListeningStatsStore.DayStat): String = when (day.dayOfWeek) {
    Calendar.MONDAY -> "周一"
    Calendar.TUESDAY -> "周二"
    Calendar.WEDNESDAY -> "周三"
    Calendar.THURSDAY -> "周四"
    Calendar.FRIDAY -> "周五"
    Calendar.SATURDAY -> "周六"
    else -> "周日"
}

private fun timeOfDay(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))