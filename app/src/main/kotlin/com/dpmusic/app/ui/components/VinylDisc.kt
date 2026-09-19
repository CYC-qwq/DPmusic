package com.dpmusic.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.dpmusic.app.core.model.Song
import kotlinx.coroutines.isActive

/**
 * 黑胶唱片封面：
 * - 播放时匀速旋转（24s/圈，暂停保持当前角度）；
 * - 「呼吸」微缩放（播放时 1.035 往复）；
 * - 底层封面主色流光光晕 + 唱片纹路。
 */
@Composable
fun VinylDisc(
    song: Song?,
    isPlaying: Boolean,
    glowColors: List<Color>,
    modifier: Modifier = Modifier,
) {
    // 旋转：使用 Animatable 手动循环，暂停时保留角度
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying, song?.stableKey) {
        if (isPlaying) {
            while (isActive) {
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(durationMillis = 24_000, easing = LinearEasing),
                )
                rotation.snapTo(rotation.value % 360f)
            }
        }
    }

    // 呼吸
    val breathTransition = rememberInfiniteTransition(label = "vinylBreath")
    val breath by breathTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPlaying) 1.035f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 流光光晕（封面主色 → 透明）
        if (glowColors.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.18f
                        scaleY = 1.18f
                    }
                    .blur(52.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = glowColors.take(3).map { it.copy(alpha = 0.55f) } +
                                Color.Transparent,
                        ),
                        shape = CircleShape,
                    ),
            )
        }

        // 黑胶盘体
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotation.value
                    scaleX = breath
                    scaleY = breath
                }
                .clip(CircleShape)
                .background(Color(0xFF17141A)),
            contentAlignment = Alignment.Center,
        ) {
            // 唱片纹路
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 1.dp.toPx())
                val maxRadius = size.minDimension / 2 * 0.94f
                var radius = maxRadius
                while (radius > size.minDimension / 2 * 0.42f) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.045f),
                        radius = radius,
                        style = stroke,
                    )
                    radius -= 5.dp.toPx()
                }
            }

            // 光泽高光（左上 → 右下 的斜向柔光，模拟唱片反光）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.08f),
                                Color.Transparent,
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            // 封面标签外环（细亮环，勾勒唱片标签轮廓）
            Box(
                modifier = Modifier
                    .fillMaxSize(0.64f)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.07f)),
            )

            // 封面
            CoverArt(
                url = song?.coverUrl,
                modifier = Modifier.fillMaxSize(0.62f),
                shape = CircleShape,
            )

            // 中心孔
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF17141A)),
            )
        }
    }
}