package com.dpmusic.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.dpmusic.app.ui.theme.glassPanelColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.audio.AudioFileDecoder
import com.dpmusic.app.core.audio.AudioFingerprintEngine
import com.dpmusic.app.core.audio.AudioPlaybackCapturer
import com.dpmusic.app.core.audio.AudioRecognizer
import com.dpmusic.app.core.audio.AudioSampler
import com.dpmusic.app.core.audio.CaptureForegroundService
import com.dpmusic.app.core.audio.KgRecognizer
import com.dpmusic.app.core.model.MusicPlatform
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 拾音方式：麦克风 / 系统播放捕获 / 音频文件 */
enum class AudioPickSource { MIC, SYSTEM, FILE }

/** 听歌识曲状态机 */
sealed interface RecognizeState {
    /** 待开始 */
    object Idle : RecognizeState

    /** 采集中（progress 0..1，level 音量 0..1） */
    data class Recording(
        val progress: Float,
        val level: Float,
        val source: AudioPickSource = AudioPickSource.MIC,
    ) : RecognizeState

    /** 指纹编码 + 接口匹配中 */
    object Working : RecognizeState

    /** 双引擎识别结果（网易云 + 酷狗 并行） */
    data class Results(
        val wy: EngineState,
        val kg: EngineState,
    ) : RecognizeState

    /** 失败（录音 / 权限等整体失败） */
    data class Failed(val message: String) : RecognizeState
}

/** 单引擎识别状态 */
sealed interface EngineState {
    /** 识别中 */
    object Loading : EngineState

    /** 完成（items 为空 = 无匹配） */
    data class Done(val items: List<RecognizeItem>) : EngineState

    /** 失败 */
    data class Failed(val message: String) : EngineState
}

/** 统一识别结果条目（双引擎共用） */
data class RecognizeItem(
    val song: Song,
    val tag: String?,
)

/** 「找原唱」搜索视图状态（null = 不显示） */
sealed interface SearchViewState {
    val keyword: String
    val platform: MusicPlatform

    /** 搜索中 */
    data class Loading(
        override val keyword: String,
        override val platform: MusicPlatform,
    ) : SearchViewState

    /** 搜索结果 */
    data class Loaded(
        override val keyword: String,
        override val platform: MusicPlatform,
        val songs: List<AudioRecognizer.VersionSong>,
    ) : SearchViewState

    /** 搜索失败 */
    data class Failed(
        override val keyword: String,
        override val platform: MusicPlatform,
        val message: String,
    ) : SearchViewState
}

/**
 * 听歌识曲 Sheet：
 * - 三种拾音方式：麦克风（环境音）/ 系统播放捕获（内部音频，Android 10+）/ 音频文件（SAF）；
 * - 采集 10 秒 → 双引擎并行匹配（酷狗 PCM 直传 / 网易云 afp 指纹取前 6 秒窗口）；
 * - 候选列表支持播放 / 收藏 / 长按加歌单；
 * - 引擎随 Sheet 创建与销毁，音频仅内存处理不落盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionSheet(
    onDismiss: () -> Unit,
    onPlay: (Song) -> Unit,
) {
    val context = LocalContext.current
    val vm: RecognitionViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val searchView by vm.searchView.collectAsStateWithLifecycle()
    val favorites by AppContainer.favorites.favorites.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val addHost = rememberAddToPlaylistHost()

    // 指纹引擎：随 Sheet 创建 / 销毁（WebView 承载官方 afp.wasm）
    val engine = remember { AudioFingerprintEngine(context) }
    DisposableEffect(Unit) {
        onDispose {
            engine.destroy()
            vm.reset()
        }
    }

    // 拾音方式（记忆最近一次，用于「再试一次」）
    var lastSource by remember { mutableStateOf(AudioPickSource.MIC) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) action?.invoke() else vm.onPermissionDenied()
    }

    /** 确保麦克风权限（系统播放捕获同样需要） */
    fun ensureMicPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 系统播放捕获授权（MediaProjection，Android 10+）
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            vm.startSystem(engine, result.resultCode, data)
        } else {
            vm.showError("未授予系统音频捕获权限，已取消")
        }
    }
    val launchProjection: () -> Unit = {
        if (!AudioPlaybackCapturer.isSupported) {
            vm.showError("系统播放捕获需要 Android 10 及以上系统")
        } else {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (manager != null) {
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            } else {
                vm.showError("当前设备不支持系统音频捕获")
            }
        }
    }

    // 音频文件选择（SAF）
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.startFile(engine, uri)
    }

    // 三种拾音入口
    val onPickMic: () -> Unit = {
        lastSource = AudioPickSource.MIC
        ensureMicPermission { vm.startMic(engine) }
    }
    val onPickSystem: () -> Unit = {
        lastSource = AudioPickSource.SYSTEM
        ensureMicPermission(launchProjection)
    }
    val onPickFile: () -> Unit = {
        lastSource = AudioPickSource.FILE
        fileLauncher.launch(arrayOf("audio/*"))
    }

    // 「再试一次」：沿用最近一次的拾音方式
    val retry: () -> Unit = {
        when (lastSource) {
            AudioPickSource.MIC -> ensureMicPermission { vm.startMic(engine) }
            AudioPickSource.SYSTEM -> ensureMicPermission(launchProjection)
            AudioPickSource.FILE -> fileLauncher.launch(arrayOf("audio/*"))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerLow, strong = true),
    ) {
        Box {
            // 隐藏的 WebView（1dp）：仅提供 JS / WASM 运行环境
            AndroidView(
                factory = { engine.webView },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 标题行：搜索模式下左置返回键、标题切换为「找原唱」
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (searchView != null) {
                        IconButton(
                            onClick = vm::closeSearch,
                            modifier = Modifier.align(Alignment.CenterStart),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回识别结果")
                        }
                    }
                    Text(
                        text = if (searchView != null) "找原唱" else "听歌识曲",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                Spacer(Modifier.height(20.dp))
                val sv = searchView
                if (sv != null) {
                    SearchVersionsContent(
                        view = sv,
                        favorites = favorites,
                        onSearch = vm::searchWith,
                        onPlatformChange = vm::switchPlatform,
                        onPlay = onPlay,
                        onToggleFavorite = vm::toggleFavorite,
                        onLongPress = { addHost.show(listOf(it)) },
                    )
                } else {
                    when (val s = state) {
                        is RecognizeState.Idle -> IdleContent(
                            onMic = onPickMic,
                            onSystem = onPickSystem,
                            onFile = onPickFile,
                        )
                        is RecognizeState.Recording -> RecordingContent(state = s, onCancel = vm::cancel)
                        is RecognizeState.Working -> WorkingContent()
                        is RecognizeState.Results -> ResultsContent(
                            results = s,
                            favorites = favorites,
                            onPlay = onPlay,
                            onToggleFavorite = vm::toggleFavorite,
                            onLongPress = { addHost.show(listOf(it)) },
                            onRetry = retry,
                            onSearchVersion = vm::openSearch,
                        )
                        is RecognizeState.Failed -> FailedContent(
                            message = s.message,
                            onRetry = retry,
                        )
                    }
                }
            }
        }
        AddToPlaylistHost(addHost)
    }
}

/* ---------------- 待开始（拾音方式选择） ---------------- */

@Composable
private fun IdleContent(
    onMic: () -> Unit,
    onSystem: () -> Unit,
    onFile: () -> Unit,
) {
    Text(
        text = "选择拾音方式",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "识别周围声音 / 手机内部播放 / 本地音频文件",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        PickSourceCard(
            icon = Icons.Outlined.Mic,
            title = "麦克风",
            desc = "周围环境音",
            onClick = onMic,
            modifier = Modifier.weight(1f),
        )
        PickSourceCard(
            icon = Icons.Outlined.PhoneAndroid,
            title = "系统播放",
            desc = "手机内部音频",
            onClick = onSystem,
            modifier = Modifier.weight(1f),
        )
        PickSourceCard(
            icon = Icons.Outlined.FolderOpen,
            title = "音频文件",
            desc = "本地音频文件",
            onClick = onFile,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        text = "识别约需 10 秒；系统播放捕获需 Android 10+ 授权",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** 拾音方式卡片 */
@Composable
private fun PickSourceCard(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = glassPanelColor(MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/* ---------------- 录音中 ---------------- */

@Composable
private fun RecordingContent(state: RecognizeState.Recording, onCancel: () -> Unit) {
    val remaining = ((1f - state.progress) * 10f).coerceAtLeast(0f)
    val isSystem = state.source == AudioPickSource.SYSTEM
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
        CircularProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 4.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Icon(
            imageVector = if (isSystem) Icons.Outlined.PhoneAndroid else Icons.Outlined.Mic,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .scale(1f + state.level * 0.4f),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = (if (isSystem) "正在捕获系统播放… %.1fs" else "正在聆听… %.1fs").format(remaining),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = if (isSystem) "请保持音乐继续播放" else "保持环境安静，靠近音源效果更佳",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onCancel) {
        Text("取消")
    }
}

/* ---------------- 识别中 ---------------- */

@Composable
private fun WorkingContent() {
    CircularProgressIndicator(modifier = Modifier.size(48.dp))
    Spacer(Modifier.height(16.dp))
    Text(
        text = "正在识别…",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "正在比对音乐指纹",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/* ---------------- 结果列表 ---------------- */

@Composable
private fun ResultsContent(
    results: RecognizeState.Results,
    favorites: List<Song>,
    onPlay: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onLongPress: (Song) -> Unit,
    onRetry: () -> Unit,
    onSearchVersion: (Song) -> Unit,
) {
    val wyCount = (results.wy as? EngineState.Done)?.items?.size ?: 0
    val kgCount = (results.kg as? EngineState.Done)?.items?.size ?: 0
    val total = wyCount + kgCount
    val bothEmpty = (results.kg as? EngineState.Done)?.items?.isEmpty() == true &&
        (results.wy as? EngineState.Done)?.items?.isEmpty() == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (total > 0) "识别到 $total 个结果" else "识别结果",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onRetry) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("再试一次")
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = "双引擎并行 · 点击播放 · 长按加歌单 · 🔍找原唱",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp),
    ) {
        // 酷狗为主力引擎，优先展示
        engineItems(
            state = results.kg,
            keyPrefix = "kg",
            platform = MusicPlatform.KG,
            favorites = favorites,
            onPlay = onPlay,
            onToggleFavorite = onToggleFavorite,
            onLongPress = onLongPress,
            onSearchVersion = onSearchVersion,
        )
        engineItems(
            state = results.wy,
            keyPrefix = "wy",
            platform = MusicPlatform.WY,
            favorites = favorites,
            onPlay = onPlay,
            onToggleFavorite = onToggleFavorite,
            onLongPress = onLongPress,
            onSearchVersion = onSearchVersion,
        )
        // 双引擎均无匹配：给出提升命中率的引导提示
        if (bothEmpty) {
            item(key = "empty_hint") {
                Text(
                    text = "没识别到？试试调整音量或靠近音源，也可换个拾音方式再试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            }
        }
    }
}

/* ---------------- 单引擎分组（结果列表内） ---------------- */

private fun LazyListScope.engineItems(
    state: EngineState,
    keyPrefix: String,
    platform: MusicPlatform,
    favorites: List<Song>,
    onPlay: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onLongPress: (Song) -> Unit,
    onSearchVersion: (Song) -> Unit,
) {
    item(key = "${keyPrefix}_header") {
        EngineHeader(platform = platform, state = state)
    }
    when (state) {
        is EngineState.Loading -> item(key = "${keyPrefix}_loading") {
            EngineStatusText("识别中…")
        }
        is EngineState.Done -> if (state.items.isEmpty()) {
            item(key = "${keyPrefix}_empty") {
                EngineStatusText("未匹配到歌曲")
            }
        } else {
            items(state.items, key = { "${keyPrefix}_${it.song.stableKey}" }) { item ->
                SongRow(
                    song = item.song,
                    onClick = { onPlay(item.song) },
                    onLongClick = { onLongPress(item.song) },
                    subtitleOverride = item.tag?.let { "$it · ${item.song.artist}" },
                    trailing = {
                        IconButton(onClick = { onSearchVersion(item.song) }) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "找原唱",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FavoriteButton(
                            song = item.song,
                            favorites = favorites,
                            onToggle = onToggleFavorite,
                        )
                    },
                )
            }
        }
        is EngineState.Failed -> item(key = "${keyPrefix}_failed") {
            EngineStatusText(state.message, isError = true)
        }
    }
}

/** 引擎分组标题：平台名 + 状态 */
@Composable
private fun EngineHeader(platform: MusicPlatform, state: EngineState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        Text(
            text = platform.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        when (state) {
            is EngineState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
            )
            is EngineState.Done -> Text(
                text = if (state.items.isEmpty()) "未匹配" else "${state.items.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is EngineState.Failed -> Text(
                text = "识别失败",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 引擎状态行文本 */
@Composable
private fun EngineStatusText(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** 收藏按钮（识别结果 / 搜索结果共用） */
@Composable
private fun FavoriteButton(
    song: Song,
    favorites: List<Song>,
    onToggle: (Song) -> Unit,
) {
    val isFavorite = favorites.any { it.stableKey == song.stableKey }
    IconButton(onClick = { onToggle(song) }) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (isFavorite) "取消收藏" else "收藏",
            tint = if (isFavorite) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/* ---------------- 找原唱（搜索结果） ---------------- */

@Composable
private fun SearchVersionsContent(
    view: SearchViewState,
    favorites: List<Song>,
    onSearch: (String) -> Unit,
    onPlatformChange: (MusicPlatform) -> Unit,
    onPlay: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onLongPress: (Song) -> Unit,
) {
    var text by remember(view.keyword) { mutableStateOf(view.keyword) }
    SearchField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = "输入歌名，搜索更多版本…",
        onSearch = { onSearch(text) },
    )
    Spacer(Modifier.height(8.dp))
    PlatformChips(
        selected = view.platform,
        onSelect = onPlatformChange,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
    )
    Spacer(Modifier.height(4.dp))
    when (view) {
        is SearchViewState.Loading -> {
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = "正在${view.platform.label}搜索「${view.keyword}」…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is SearchViewState.Loaded -> {
            if (view.songs.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "没有找到「${view.keyword}」的相关版本",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = if (view.platform == MusicPlatform.WY) {
                        "共 ${view.songs.size} 个版本 · 优先选「原唱」"
                    } else {
                        "共 ${view.songs.size} 个版本"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                ) {
                    items(view.songs, key = { it.song.stableKey }) { version ->
                        SongRow(
                            song = version.song,
                            onClick = { onPlay(version.song) },
                            onLongClick = { onLongPress(version.song) },
                            subtitleOverride = version.label?.let { "$it · ${version.song.artist}" },
                            trailing = {
                                FavoriteButton(
                                    song = version.song,
                                    favorites = favorites,
                                    onToggle = onToggleFavorite,
                                )
                            },
                        )
                    }
                }
            }
        }
        is SearchViewState.Failed -> {
            Spacer(Modifier.height(24.dp))
            Text(
                text = view.message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onSearch(view.keyword) }) {
                Text("重试")
            }
        }
    }
}

/* ---------------- 失败 ---------------- */

@Composable
private fun FailedContent(message: String, onRetry: () -> Unit) {
    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = null,
        modifier = Modifier.size(40.dp),
        tint = MaterialTheme.colorScheme.error,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRetry) {
        Text("重新识别")
    }
}

/* ---------------- ViewModel ---------------- */

/** 听歌识曲 ViewModel：录音 → 指纹 → 匹配 全流程编排 */
class RecognitionViewModel : ViewModel() {

    private val _state = MutableStateFlow<RecognizeState>(RecognizeState.Idle)
    val state: StateFlow<RecognizeState> = _state.asStateFlow()

    private var job: Job? = null

    private val _searchView = MutableStateFlow<SearchViewState?>(null)
    val searchView: StateFlow<SearchViewState?> = _searchView.asStateFlow()
    private var searchJob: Job? = null

    /** 麦克风识别：录 10 秒 → 双引擎并行匹配 */
    fun startMic(engine: AudioFingerprintEngine) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                _state.value = RecognizeState.Recording(0f, 0f)
                val pcm = AudioSampler.record(10_000) { progress, level ->
                    _state.value = RecognizeState.Recording(progress, level)
                }
                runEngines(engine, pcm)
            } catch (e: TimeoutCancellationException) {
                _state.value = RecognizeState.Failed("识别超时，请重试")
            } catch (e: CancellationException) {
                _state.value = RecognizeState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = RecognizeState.Failed(friendlyMessage(e))
            }
        }
    }

    /** 系统播放识别：捕获手机内部音频 10 秒 → 双引擎并行匹配（Android 10+） */
    fun startSystem(engine: AudioFingerprintEngine, resultCode: Int, data: Intent) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            if (!AudioPlaybackCapturer.isSupported) {
                _state.value = RecognizeState.Failed("系统播放捕获需要 Android 10 及以上系统")
                return@launch
            }
            val context = AppContainer.appContext
            var projection: MediaProjection? = null
            try {
                _state.value = RecognizeState.Recording(0f, 0f, AudioPickSource.SYSTEM)
                // Android 14+：getMediaProjection 前必须先运行 mediaProjection 前台服务
                if (!CaptureForegroundService.startAndAwait(context)) {
                    throw IllegalStateException("捕获服务启动失败，请重试")
                }
                val proj = AudioPlaybackCapturer.obtainProjection(resultCode, data)
                    ?: throw IllegalStateException("无法获取系统音频捕获权限")
                projection = proj
                val pcm = AudioPlaybackCapturer.capture(proj, 10_000) { progress, level ->
                    _state.value = RecognizeState.Recording(progress, level, AudioPickSource.SYSTEM)
                }
                runEngines(engine, pcm)
            } catch (e: TimeoutCancellationException) {
                _state.value = RecognizeState.Failed("识别超时，请重试")
            } catch (e: CancellationException) {
                _state.value = RecognizeState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = RecognizeState.Failed(friendlyMessage(e))
            } finally {
                runCatching { projection?.stop() }
                CaptureForegroundService.stop(context)
            }
        }
    }

    /** 音频文件识别：解码本地文件 → 双引擎并行匹配 */
    fun startFile(engine: AudioFingerprintEngine, uri: Uri) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                _state.value = RecognizeState.Results(EngineState.Loading, EngineState.Loading)
                val pcm = AudioFileDecoder.decodeForRecognize(AppContainer.appContext, uri)
                    ?: throw IllegalStateException("无法解析该音频文件（格式不支持或内容为空）")
                runEngines(engine, pcm)
            } catch (e: TimeoutCancellationException) {
                _state.value = RecognizeState.Failed("识别超时，请重试")
            } catch (e: CancellationException) {
                _state.value = RecognizeState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = RecognizeState.Failed(friendlyMessage(e))
            }
        }
    }

    /** 直接展示错误（设备不支持 / 取消授权等） */
    fun showError(message: String) {
        _state.value = RecognizeState.Failed(message)
    }

    /** 双引擎并行识别（酷狗 PCM 直传 + 网易云指纹），等待全部完成 */
    private suspend fun runEngines(engine: AudioFingerprintEngine, pcm: FloatArray) = coroutineScope {
        _state.value = RecognizeState.Results(EngineState.Loading, EngineState.Loading)
        // 双引擎并行：酷狗（主力，PCM 直传）+ 网易云（指纹匹配）
        launch { runKgEngine(pcm) }
        launch { runWyEngine(engine, pcm) }
    }

    /** 网易云引擎：指纹编码 → 匹配接口（官方管线为 6 秒窗口，取录音前 6 秒） */
    private suspend fun runWyEngine(engine: AudioFingerprintEngine, pcm: FloatArray) {
        try {
            val window = if (pcm.size > 48_000) pcm.copyOfRange(0, 48_000) else pcm
            val fingerprint = engine.encode(window)
            val candidates = AudioRecognizer.recognize(fingerprint)
            updateResult { it.copy(wy = EngineState.Done(candidates.map { c -> RecognizeItem(c.song, c.versionLabel) })) }
        } catch (e: TimeoutCancellationException) {
            updateResult { it.copy(wy = EngineState.Failed("识别超时")) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateResult { it.copy(wy = EngineState.Failed(friendlyMessage(e))) }
        }
    }

    /** 酷狗引擎（主力）：PCM 直传 + MD5 签名 */
    private suspend fun runKgEngine(pcm: FloatArray) {
        try {
            val bytes = AudioSampler.floatToInt16Le(pcm)
            val candidates = KgRecognizer.recognize(bytes)
            updateResult { it.copy(kg = EngineState.Done(candidates.map { c -> RecognizeItem(c.song, c.versionLabel) })) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateResult { it.copy(kg = EngineState.Failed(friendlyMessage(e))) }
        }
    }

    /** 更新双引擎结果（仅当处于 Results 状态时） */
    private fun updateResult(transform: (RecognizeState.Results) -> RecognizeState.Results) {
        _state.update { current -> if (current is RecognizeState.Results) transform(current) else current }
    }

    /** 取消当前识别 */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** 重置（Sheet 关闭时） */
    fun reset() {
        job?.cancel()
        job = null
        _state.value = RecognizeState.Idle
        searchJob?.cancel()
        searchJob = null
        _searchView.value = null
    }

    /* ---------- 找原唱：按关键词搜索更多版本 ---------- */

    /** 打开「找原唱」：以清洗后的歌名为关键词搜索（跟随歌曲来源平台） */
    fun openSearch(song: Song) {
        startSearch(song.platform, cleanTitle(song.title))
    }

    /** 按关键词搜索更多版本（沿用当前平台） */
    fun searchWith(keyword: String) {
        val platform = _searchView.value?.platform ?: MusicPlatform.WY
        startSearch(platform, keyword)
    }

    /** 切换平台并重新搜索（保持当前关键词） */
    fun switchPlatform(platform: MusicPlatform) {
        val current = _searchView.value ?: return
        if (current.platform == platform) return
        startSearch(platform, current.keyword)
    }

    /** 执行搜索：网易云带原唱标记；QQ / 酷狗走统一搜索接口 */
    private fun startSearch(platform: MusicPlatform, keyword: String) {
        val kw = keyword.trim()
        if (kw.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchView.value = SearchViewState.Loading(kw, platform)
            try {
                val songs = if (platform == MusicPlatform.WY) {
                    AudioRecognizer.searchVersions(kw)
                } else {
                    AppContainer.musicRepository.searchSongs(platform, kw)
                        .map { AudioRecognizer.VersionSong(it, null) }
                }
                _searchView.value = SearchViewState.Loaded(kw, platform, songs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _searchView.value = SearchViewState.Failed(kw, platform, friendlyMessage(e))
            }
        }
    }

    /** 关闭搜索视图，回到识别结果 */
    fun closeSearch() {
        searchJob?.cancel()
        searchJob = null
        _searchView.value = null
    }

    /** 清洗歌名：去掉 (Live) / （原唱周杰伦）/ [伴奏] 等括号内容，保留纯歌名 */
    private fun cleanTitle(title: String): String =
        title.replace(Regex("[(（\\[【][^)）\\]】]*[)）\\]】]"), "").trim().ifBlank { title }

    /** 权限被拒 */
    fun onPermissionDenied() {
        _state.value = RecognizeState.Failed("需要麦克风权限才能听歌识曲，请在系统设置中开启")
    }

    /** 收藏 / 取消收藏 */
    fun toggleFavorite(song: Song) {
        viewModelScope.launch { AppContainer.favorites.toggle(song) }
    }

    private fun friendlyMessage(e: Exception): String = when (e) {
        is HttpException -> "网络异常（HTTP ${e.code}），请稍后重试"
        else -> e.message ?: "识别失败，请重试"
    }
}