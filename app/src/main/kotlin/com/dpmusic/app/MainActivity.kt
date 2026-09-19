package com.dpmusic.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.dpmusic.app.core.ClipboardLinkEvent
import com.dpmusic.app.core.ClipboardLinkInbox
import com.dpmusic.app.core.ImportInbox
import com.dpmusic.app.core.lyric.DesktopLyricService
import com.dpmusic.app.core.net.PlaylistLinkParser
import com.dpmusic.app.core.net.SongLinkParser
import com.dpmusic.app.core.together.TogetherInviteParser
import com.dpmusic.app.ui.components.ListDisplayOptions
import com.dpmusic.app.ui.components.LocalGlassBlur
import com.dpmusic.app.ui.components.LocalListDisplayOptions
import com.dpmusic.app.ui.shell.DPmusicShell
import com.dpmusic.app.ui.theme.DPmusicTheme
import kotlinx.coroutines.launch

/**
 * 应用唯一 Activity：
 *
 * - Edge-to-Edge 沉浸式，全量声明 configChanges，旋转 / 深色切换不重建，
 *   WindowSizeClass 在组合内实时响应断点变化；
 * - 主题跟随设置（动态取色开关 + 深色模式三态）；
 * - 启动即与 MediaSessionService 建立连接（幂等，可安全重入）；
 * - Android 13+ 主动申请媒体通知权限（拒绝不影响前台播放）；
 * - 剪切板自动读取（可选）：从其他应用切回时识别剪贴板中的歌曲 / 歌单 / 一起听链接并询问处理。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 拒绝也不影响前台播放，仅影响通知栏媒体卡片
        }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 与 MediaSessionService 建立连接（幂等）
        AppContainer.player.connect()

        // 外部打开 / 分享的 JSON 备份文件（QQ 下载 → 用其他应用打开 → DPmusic 自动导入）
        handleOpenIntent(intent)

        // Android 13+ 媒体通知需要运行时授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settings by AppContainer.settings.settings.collectAsStateWithLifecycle()

            // 桌面歌词：开关变化时同步前台服务（未授权时不启动，由设置面板引导授权）
            val lyricContext = LocalContext.current
            LaunchedEffect(settings.desktopLyricEnabled) {
                DesktopLyricService.sync(lyricContext, settings.desktopLyricEnabled)
            }

            val darkTheme = when (settings.darkMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

            // 系统栏图标与 App 主题同步（仅主题变化时执行一次，避免每帧调用系统栏 API）
            val view = LocalView.current
            LaunchedEffect(darkTheme, view) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }

            DPmusicTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                themeColor = settings.themeColor,
                glass = settings.glassMode,
            ) {
                val windowSizeClass = calculateWindowSizeClass(this)

                // 毛玻璃：共享背景层（GlassBackdrop 写入 → 玻璃面板采样做真实模糊）
                val glassBlurLayer = rememberGraphicsLayer()
                // 列表显示开关（设置页可关）：歌曲行等列表元素按此渲染
                val listDisplayOptions = ListDisplayOptions(
                    showAlbumName = settings.listShowAlbumName,
                    showDuration = settings.listShowDuration,
                    showCover = settings.listShowCover,
                    showSource = settings.listShowSource,
                )
                CompositionLocalProvider(
                    LocalGlassBlur provides glassBlurLayer,
                    LocalListDisplayOptions provides listDisplayOptions,
                ) {
                    DPmusicShell(windowSizeClass = windowSizeClass)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)
    }

    /** 解析「用其他应用打开 / 分享到 DPmusic」的文件 URI，投入导入收件箱（歌单页消费后自动导入） */
    private fun handleOpenIntent(intent: Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        ImportInbox.offer(uri)
    }

    /* ---------------- 剪切板自动读取 ---------------- */

    /** 窗口获得焦点（从其他应用切回）时检查剪贴板 / 一起听邀请：命中时弹出询问 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkClipboard()
            // 一起听邀请（官方私信卡片）：切回应用时立即检查一次（内部节流）
            AppContainer.togetherInviteWatcher.checkNow()
        }
    }

    private fun checkClipboard() {
        if (!AppContainer.settings.settings.value.clipboardAutoRead) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = manager.primaryClip ?: return
        if (clip.itemCount <= 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_CLIPBOARD_LENGTH) return
        // 仅处理含网页链接的文本（避免纯数字 / 普通文案误触发）
        if (!URL_PATTERN.containsMatchIn(text)) return
        // 去重：同一内容只处理一次（含被用户忽略过的）
        if (text == AppContainer.settings.settings.value.lastClipboardHandled) return
        lifecycleScope.launch {
            AppContainer.settings.setLastClipboardHandled(text.take(512))
        }
        // 一起听邀请优先（时效性最强），其次歌曲链接、歌单链接
        if (TogetherInviteParser.parse(text) != null) {
            ClipboardLinkInbox.offer(ClipboardLinkEvent.Together(text))
            return
        }
        if (SongLinkParser.parse(text) != null) {
            ClipboardLinkInbox.offer(ClipboardLinkEvent.Song(text))
            return
        }
        if (PlaylistLinkParser.parseCandidates(text).isNotEmpty()) {
            ClipboardLinkInbox.offer(ClipboardLinkEvent.Playlist(text))
        }
    }

    private companion object {
        const val MAX_CLIPBOARD_LENGTH = 4000
        val URL_PATTERN = Regex("""https?://\S+""")
    }
}