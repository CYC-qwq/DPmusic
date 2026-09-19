package com.dpmusic.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 搜索顶栏（横屏矮窗口专用）：
 * 把搜索框直接嵌入顶栏，消除独立搜索行——顶部区域省出约 60dp 纵向空间。
 * 仅在横屏手机等"矮窗口"（高度尺寸类为 Compact）形态下由搜索页使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "搜索…",
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            SearchField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                compact = true,
                onSearch = onSearch,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        modifier = modifier,
        actions = actions,
        expandedHeight = 56.dp,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
        ),
    )
}