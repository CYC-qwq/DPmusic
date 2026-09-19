package com.dpmusic.app.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.data.AppSettings
import com.dpmusic.app.core.lyric.DesktopLyricBackground
import com.dpmusic.app.core.lyric.DesktopLyricPreset
import com.dpmusic.app.core.lyric.DesktopLyricService
import com.dpmusic.app.core.lyric.DesktopLyricStyle
import com.dpmusic.app.ui.theme.glassPanelColor
import kotlinx.coroutines.launch

/** 文字色候选（0 = 跟随预设） */
private val TEXT_COLOR_OPTIONS = listOf(
    "跟随" to 0,
    "纯白" to 0xFFFFFFFF.toInt(),
    "浅雾" to 0xFFE6E6EE.toInt(),
    "暖白" to 0xFFF5EFE4.toInt(),
    "墨黑" to 0xFF14141A.toInt(),
)

/** 高亮色候选（0 = 跟随主题色） */
private val HIGHLIGHT_COLOR_OPTIONS = listOf(
    "主题" to 0,
    "星紫" to 0xFF7C5CFF.toInt(),
    "暖金" to 0xFFE8B457.toInt(),
    "亮青" to 0xFF19F0FF.toInt(),
    "亮粉" to 0xFFFF7BC0.toInt(),
    "纯白" to 0xFFFFFFFF.toInt(),
)

/**
 * 桌面歌词设置面板：
 * - 顶部总开关（未授权时引导授予「悬浮窗」权限）；
 * - 实时预览（按当前设置渲染，含背景 / 圆角 / 描边 / 逐字高亮 / 翻译 / 下一行）；
 * - 6 套预设一键应用，随后可逐项微调；
 * - 自定义：字号 / 字距 / 不透明度 / 背景 / 浓度 / 圆角 / 描边 / 文字色 / 高亮色 / 阴影 /
 *   逐字 / 翻译 / 下一行 / 控制条 / 锁定 / 触摸穿透。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopLyricSheet(onDismiss: () -> Unit) {
    val settings by AppContainer.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
        if (overlayGranted && settings.desktopLyricEnabled) {
            DesktopLyricService.sync(context, true)
        }
    }

    val requestOverlay: () -> Unit = {
        runCatching {
            permissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    val setEnabled: (Boolean) -> Unit = { enabled ->
        if (enabled && !Settings.canDrawOverlays(context)) {
            requestOverlay()
        } else {
            scope.launch { AppContainer.settings.setDesktopLyricEnabled(enabled) }
        }
    }

    val applyPreset: (DesktopLyricPreset) -> Unit = { preset ->
        scope.launch {
            val repo = AppContainer.settings
            repo.setDesktopLyricPreset(preset.id)
            repo.setDesktopLyricBackground(preset.background.id)
            repo.setDesktopLyricBackgroundColor(preset.backgroundColor)
            repo.setDesktopLyricBackgroundAlpha(preset.backgroundAlpha)
            repo.setDesktopLyricCorner(preset.corner)
            repo.setDesktopLyricStrokeWidth(preset.strokeWidth)
            repo.setDesktopLyricStrokeColor(preset.strokeColor)
            repo.setDesktopLyricShadow(preset.shadow)
            repo.setDesktopLyricTextColor(preset.textColor)
            repo.setDesktopLyricHighlightColor(preset.highlightColor)
            repo.setDesktopLyricLetterSpacing(preset.letterSpacing)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerLow, strong = true),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                HeaderRow(
                    enabled = settings.desktopLyricEnabled,
                    onToggle = setEnabled,
                )
            }

            if (!overlayGranted) {
                item {
                    PermissionCard(onGrant = requestOverlay)
                }
            }

            item {
                DesktopLyricPreview(
                    settings = settings,
                    highlight = MaterialTheme.colorScheme.primary,
                )
            }

            item {
                SectionTitle(icon = Icons.Outlined.Palette, title = "样式预设")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(DesktopLyricPreset.entries, key = { it.id }) { preset ->
                        PresetCard(
                            preset = preset,
                            selected = settings.desktopLyricPreset == preset.id,
                            onClick = { applyPreset(preset) },
                        )
                    }
                }
            }

            item {
                SectionTitle(icon = Icons.Outlined.Tune, title = "自定义")
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SliderRow(
                        label = "字号",
                        value = settings.desktopLyricFontSize,
                        range = DesktopLyricStyle.MIN_FONT_SP..DesktopLyricStyle.MAX_FONT_SP,
                        valueLabel = { "${it.toInt()} sp" },
                        onCommit = { scope.launch { AppContainer.settings.setDesktopLyricFontSize(it) } },
                    )
                    SliderRow(
                        label = "字距",
                        value = settings.desktopLyricLetterSpacing,
                        range = 0f..0.2f,
                        valueLabel = { String.format("%.2f em", it) },
                        onCommit = { scope.launch { AppContainer.settings.setDesktopLyricLetterSpacing(it) } },
                    )
                    SliderRow(
                        label = "不透明度",
                        value = settings.desktopLyricOpacity,
                        range = DesktopLyricStyle.MIN_OPACITY..DesktopLyricStyle.MAX_OPACITY,
                        valueLabel = { "${(it * 100).toInt()}%" },
                        onCommit = { scope.launch { AppContainer.settings.setDesktopLyricOpacity(it) } },
                    )

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "背景样式",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DesktopLyricBackground.entries.forEach { background ->
                            FilterChip(
                                selected = settings.desktopLyricBackground == background.id,
                                onClick = {
                                    scope.launch {
                                        AppContainer.settings.setDesktopLyricBackground(background.id)
                                    }
                                },
                                label = { Text(background.label) },
                            )
                        }
                    }

                    if (settings.desktopLyricBackground != DesktopLyricBackground.NONE.id) {
                        SliderRow(
                            label = "背景浓度",
                            value = settings.desktopLyricBackgroundAlpha,
                            range = 0f..1f,
                            valueLabel = { "${(it * 100).toInt()}%" },
                            onCommit = { scope.launch { AppContainer.settings.setDesktopLyricBackgroundAlpha(it) } },
                        )
                        SliderRow(
                            label = "圆角",
                            value = settings.desktopLyricCorner,
                            range = 0f..DesktopLyricStyle.MAX_CORNER_DP,
                            valueLabel = { "${it.toInt()} dp" },
                            onCommit = { scope.launch { AppContainer.settings.setDesktopLyricCorner(it) } },
                        )
                    }

                    SliderRow(
                        label = "描边",
                        value = settings.desktopLyricStrokeWidth,
                        range = 0f..DesktopLyricStyle.MAX_STROKE_DP,
                        valueLabel = { if (it <= 0.01f) "关闭" else String.format("%.1f dp", it) },
                        onCommit = { scope.launch { AppContainer.settings.setDesktopLyricStrokeWidth(it) } },
                    )

                    Spacer(Modifier.height(10.dp))
                    SwatchRow(
                        label = "文字色",
                        value = settings.desktopLyricTextColor,
                        options = TEXT_COLOR_OPTIONS,
                        onSelect = { scope.launch { AppContainer.settings.setDesktopLyricTextColor(it) } },
                    )
                    Spacer(Modifier.height(12.dp))
                    SwatchRow(
                        label = "高亮色",
                        value = settings.desktopLyricHighlightColor,
                        options = HIGHLIGHT_COLOR_OPTIONS,
                        onSelect = { scope.launch { AppContainer.settings.setDesktopLyricHighlightColor(it) } },
                    )
                    Spacer(Modifier.height(14.dp))

                    SwitchRow(
                        title = "逐字卡拉OK",
                        subtitle = "按字级时间轴左→右渐变高亮",
                        checked = settings.desktopLyricVerbatim,
                        onCheckedChange = { scope.launch { AppContainer.settings.setDesktopLyricVerbatim(it) } },
                    )
                    SwitchRow(
                        title = "文字阴影",
                        subtitle = "提升复杂壁纸下的可读性",
                        checked = settings.desktopLyricShadow,
                        onCheckedChange = { scope.launch { AppContainer.settings.setDesktopLyricShadow(it) } },
                    )
                    SwitchRow(
                        title = "显示翻译",
                        subtitle = "有翻译歌词时显示在当前行下方",
                        checked = settings.desktopLyricShowTranslation,
                        onCheckedChange = { scope.launch { AppContainer.settings.setDesktopLyricShowTranslation(it) } },
                    )
                    SwitchRow(
                        title = "显示下一行",
                        subtitle = "预读下一句，演唱更从容",
                        checked = settings.desktopLyricShowNextLine,
                        onCheckedChange = { scope.launch { AppContainer.settings.setDesktopLyricShowNextLine(it) } },
                    )
                    SwitchRow(
                        title = "迷你控制条",
                        subtitle = "单击歌词显示（上一首 / 播放暂停 / 下一首 / 关闭）",
                        checked = settings.desktopLyricControls,
                        onCheckedChange = { scope.launch { AppContainer.settings.setDesktopLyricControls(it) } },
                    )
                    SwitchRow(
                        title = "锁定位置",
                        subtitle = "锁定后无法拖动，避免误触",
                        checked = settings.desktopLyricLocked,
                        onCheckedChange = { scope.launch { AppContainer.settings.setDesktopLyricLocked(it) } },
                    )
                    SwitchRow(
                        title = "触摸穿透",
                        subtitle = "点击 / 拖动直接作用于下层应用（控制条将不可用）",
                        checked = settings.desktopLyricTouchThrough,
                        onCheckedChange = { scope.launch { AppContainer.settings.setDesktopLyricTouchThrough(it) } },
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "提示：拖动歌词可移动位置（自动记忆）；单击歌词显示控制条；" +
                            "关闭控制条中的「✕」或关闭上方开关都会隐藏桌面歌词。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                AppContainer.settings.setDesktopLyricOffsetX(-1f)
                                AppContainer.settings.setDesktopLyricOffsetY(-1f)
                            }
                        },
                    ) {
                        Text("恢复默认位置")
                    }
                }
            }
        }
    }
}

/* ---------------- 头部 / 权限 / 分区标题 ---------------- */

@Composable
private fun HeaderRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lyrics,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "桌面歌词", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "悬浮于其他应用之上 · 逐字高亮",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "需要「悬浮窗」权限",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "授权后返回即可开启桌面歌词",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                )
            }
            TextButton(onClick = onGrant) {
                Text("去授权")
            }
        }
    }
}

@Composable
private fun SectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ---------------- 预设卡片 ---------------- */

@Composable
private fun PresetCard(
    preset: DesktopLyricPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(132.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(preset.swatch)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = preset.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/* ---------------- 实时预览 ---------------- */

@Composable
private fun DesktopLyricPreview(settings: AppSettings, highlight: Color) {
    val style = remember(settings, highlight) {
        DesktopLyricStyle.from(settings, highlight.toArgb())
    }
    val textColor = Color(style.textColor)
    val highlightColor = Color(style.highlightColor)
    val bgColor = if (style.drawsBackground) {
        Color(style.backgroundColor).copy(alpha = style.backgroundAlpha)
    } else {
        Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF23232C), Color(0xFF0F0F14)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(style.cornerDp.dp),
                color = bgColor,
                border = if (style.strokeWidthDp > 0f && style.strokeColor != 0) {
                    BorderStroke(style.strokeWidthDp.dp, Color(style.strokeColor))
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "夜色温柔地流过城市",
                        style = TextStyle(
                            fontSize = style.fontSizeSp.sp,
                            fontWeight = if (style.bold) FontWeight.SemiBold else FontWeight.Normal,
                            letterSpacing = style.letterSpacing.em,
                            brush = Brush.horizontalGradient(
                                listOf(
                                    highlightColor,
                                    highlightColor,
                                    textColor,
                                    textColor,
                                ),
                            ),
                        ),
                        maxLines = 1,
                    )
                    if (style.showTranslation) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "示例翻译 · translation",
                            style = TextStyle(
                                fontSize = (style.fontSizeSp * 0.62f).sp,
                                color = textColor.copy(alpha = 0.42f),
                            ),
                            maxLines = 1,
                        )
                    }
                    if (style.showNextLine) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "下一行歌词预览",
                            style = TextStyle(
                                fontSize = (style.fontSizeSp * 0.62f).sp,
                                color = textColor.copy(alpha = 0.55f),
                            ),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (style.controls) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) {
                        Box(
                            modifier = Modifier
                                .size((style.fontSizeSp * 1.05f).dp)
                                .clip(CircleShape)
                                .background(Color(style.backgroundColor).copy(alpha = 0.34f))
                                .border(
                                    0.8.dp,
                                    textColor.copy(alpha = 0.22f),
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/* ---------------- 通用行组件 ---------------- */

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    // 拖动期间只更新本地状态，松手才落盘（避免高频写 DataStore）
    var local by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel(local),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
        )
    }
}

@Composable
private fun SwatchRow(
    label: String,
    value: Int,
    options: List<Pair<String, Int>>,
    onSelect: (Int) -> Unit,
) {
    val currentLabel = options.firstOrNull { it.second == value }?.first ?: "自定义"
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (name, argb) ->
                val selected = argb == value
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            if (argb == 0) {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            } else {
                                Color(argb)
                            },
                        )
                        .border(
                            width = if (selected) 2.dp else 0.8.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            },
                            shape = CircleShape,
                        )
                        .clickable { onSelect(argb) },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        argb == 0 -> Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = name,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )

                        selected -> Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = name,
                            tint = if (Color(argb).luminance() > 0.6f) {
                                Color.Black
                            } else {
                                Color.White
                            },
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    )
}