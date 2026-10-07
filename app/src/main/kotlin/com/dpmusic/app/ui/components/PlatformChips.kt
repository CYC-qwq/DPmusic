package com.dpmusic.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.data.enabledPlatforms
import com.dpmusic.app.core.model.MusicPlatform

/**
 * 已启用平台的单选 FilterChips（搜索页 / 榜单页 / 歌单页共用）。
 *
 * 展示范围默认取 [AppSettings.enabledPlatforms]——用户在设置里关掉的音源（汽水 / B 站）
 * 不会出现在这里；调用方也可用 [platforms] 显式覆盖（用于只提供部分平台的场景）。
 */
@Composable
fun PlatformChips(
    selected: MusicPlatform,
    onSelect: (MusicPlatform) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    platforms: List<MusicPlatform>? = null,
) {
    val settings by AppContainer.settings.settings.collectAsStateWithLifecycle()
    val shown = platforms ?: settings.enabledPlatforms()
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shown.forEach { platform ->
            val isSelected = platform == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(platform) },
                label = { Text(platform.label) },
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else null,
            )
        }
    }
}