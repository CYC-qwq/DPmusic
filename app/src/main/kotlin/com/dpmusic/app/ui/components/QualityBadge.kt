package com.dpmusic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpmusic.app.core.model.PlayQuality

/**
 * 歌曲「最高可用音质」徽标。
 *
 * 数据来自**列表接口本身**（见 `qualityFromMaxLevel` 注释），不发额外请求，
 * 让用户点歌前就知道这首歌能到什么规格，省掉逐档试错。
 * 上限未知时不渲染（由调用方判空），避免误导成「只能到 320K」。
 */
@Composable
fun QualityBadge(
    quality: PlayQuality,
    modifier: Modifier = Modifier,
) {
    val color = qualityBadgeColor(quality)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = quality.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            fontSize = 9.sp,
        )
    }
}

/**
 * 档位配色：
 * - 有损（128K / 320K）→ 中性灰，视觉退让；
 * - 无损（FLAC / 24bit）→ 绿；
 * - Hi-Res → 金；
 * - 全景声 → 紫；
 * - 母带 → 红（最高规格，最醒目）。
 */
@Composable
internal fun qualityBadgeColor(quality: PlayQuality): Color = when (quality) {
    PlayQuality.STANDARD, PlayQuality.HIGH -> MaterialTheme.colorScheme.onSurfaceVariant
    PlayQuality.LOSSLESS, PlayQuality.FLAC24 -> Color(0xFF3FBF6F)
    PlayQuality.HIRES -> Color(0xFFD9A21B)
    PlayQuality.ATMOS, PlayQuality.ATMOS_PLUS -> Color(0xFF9A6CF0)
    PlayQuality.MASTER -> Color(0xFFE1504C)
}