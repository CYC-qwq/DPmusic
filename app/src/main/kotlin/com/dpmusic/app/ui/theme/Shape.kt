package com.dpmusic.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 形态体系：大圆角设计语言。
 * extraLarge 32dp 用于播放页大卡片 / 抽屉面板；
 * large 24dp 用于歌曲卡片、对话框；
 * medium 16dp 用于列表封面；small 12dp 用于徽标。
 */
val DPShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)