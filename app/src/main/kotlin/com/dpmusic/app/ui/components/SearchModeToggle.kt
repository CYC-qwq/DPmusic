package com.dpmusic.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 搜索模式：歌曲 / 歌单 */
enum class SearchMode(val label: String) {
    Songs("歌曲"),
    Playlists("歌单"),
}

/** 「歌曲 / 歌单」搜索模式切换（紧凑分段按钮，与搜索框同行放置） */
@Composable
fun SearchModeToggle(
    mode: SearchMode,
    onModeChange: (SearchMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        SearchMode.entries.forEachIndexed { index, item ->
            SegmentedButton(
                selected = mode == item,
                onClick = { if (mode != item) onModeChange(item) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = SearchMode.entries.size),
            ) {
                Text(item.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}