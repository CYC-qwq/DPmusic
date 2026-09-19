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
import com.dpmusic.app.core.model.MusicPlatform

/** 平台徽标：品牌色 16% 透明度底 + 品牌色文字 */
@Composable
fun PlatformBadge(
    platform: MusicPlatform,
    modifier: Modifier = Modifier,
) {
    val brand = Color(platform.brandColor)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(brand.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = platform.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = brand,
            fontSize = 10.sp,
        )
    }
}