package com.dpmusic.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.download.DownloadTaskStatus

/**
 * 下载悬浮球（Shell 级挂载）：
 * - 有活跃任务时显示圆环进度（多任务取平均）；
 * - 全部完成时保留「完成」态，直到用户点击或出现新任务；
 * - 点击跳转下载管理页。
 */
@Composable
fun DownloadBall(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tasks by AppContainer.downloadTasks.tasks.collectAsStateWithLifecycle()
    val active = tasks.filter { it.isActive }
    val doneTask = tasks.firstOrNull {
        it.status == DownloadTaskStatus.COMPLETED && (it.finishedAt ?: 0L) > 0L
    }

    var dismissed by remember { mutableStateOf(false) }
    // 有新活跃任务 → 重置完成态隐藏标记
    LaunchedEffect(active.isNotEmpty()) {
        if (active.isNotEmpty()) dismissed = false
    }

    val showDone = active.isEmpty() && doneTask != null && !dismissed
    val visible = active.isNotEmpty() || showDone
    val progress = if (active.isEmpty()) {
        1f
    } else {
        active.map { if (it.progress > 0f) it.progress else 0f }.average().toFloat().coerceIn(0f, 1f)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.6f),
        exit = fadeOut() + scaleOut(targetScale = 0.6f),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
                .clickable {
                    dismissed = true
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            val accent = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
                val strokePx = 3.dp.toPx()
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokePx),
                )
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
            Icon(
                imageVector = if (active.isNotEmpty()) Icons.Outlined.Download else Icons.Filled.CheckCircle,
                contentDescription = "下载任务",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
