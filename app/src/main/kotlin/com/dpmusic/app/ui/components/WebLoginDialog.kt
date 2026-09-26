package com.dpmusic.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dpmusic.app.core.util.AppLogger
import kotlinx.coroutines.delay

/**
 * 网页快速登录弹窗（内置 WebView 打开官方登录页，登录成功后**自动抓取 Cookie**）。
 *
 * 思路与 LX Music 移动端一致：不碰密码 / 验证码 / 风控，把官方网页登录页嵌进来，
 * 用户在熟悉的官方页面里完成登录（短信验证码、扫码、第三方登录都可用），
 * 再从系统 Cookie 容器里取出登录态，交给调用方校验并保存。
 *
 * 关键点：
 * - 使用 [CookieManager]（原生 Cookie 容器）而不是 `document.cookie`，
 *   因为登录票据（如 `MUSIC_U`）是 **HttpOnly**，JS 读不到；
 * - 登录成功可能不发生整页跳转（SPA / 弹层），因此除了页面加载完成，
 *   还按 [POLL_INTERVAL_MS] 轮询 Cookie；仅当 Cookie **内容发生变化**时才上报，
 *   避免重复校验打接口；
 * - 关闭弹窗时销毁 WebView，不残留后台页面。
 *
 * @param requiredCookieKeys 命中任意一个即认为「可能已登录」，随后交给调用方校验
 * @param onCookie 抓到疑似登录 Cookie（调用方负责校验 + 保存 + 反馈）
 * @param onFallbackPaste 兜底：改为手动粘贴 Cookie
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginDialog(
    title: String,
    subtitle: String,
    startUrl: String,
    cookieUrl: String,
    requiredCookieKeys: List<String>,
    busy: Boolean,
    message: String?,
    success: Boolean,
    onCookie: (String) -> Unit,
    onFallbackPaste: () -> Unit,
    onDismiss: () -> Unit,
    userAgent: String? = null,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoading by remember { mutableStateOf(true) }
    var captured by remember { mutableStateOf(false) }
    var lastTried by remember { mutableStateOf<String?>(null) }
    val emitCookie by rememberUpdatedState(onCookie)

    // 登录成功 → 稍作停留让用户看到结果，再关闭
    LaunchedEffect(success) {
        if (success) {
            delay(600)
            onDismiss()
        }
    }

    BackHandler { onDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                // 顶栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "官方页面",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 状态条：加载中 / 校验中 / 结果
                when {
                    pageLoading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                    busy -> StatusBar(
                        icon = null,
                        text = "已获取登录信息，正在校验…",
                        color = MaterialTheme.colorScheme.primary,
                        spinning = true,
                    )

                    success -> StatusBar(
                        icon = Icons.Outlined.CheckCircle,
                        text = message ?: "登录成功",
                        color = MaterialTheme.colorScheme.primary,
                    )

                    !message.isNullOrBlank() -> StatusBar(
                        icon = Icons.Outlined.ErrorOutline,
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                    )

                    captured -> StatusBar(
                        icon = null,
                        text = "已检测到登录，正在获取登录态…",
                        color = MaterialTheme.colorScheme.primary,
                        spinning = true,
                    )
                }

                // WebView
                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val view = WebView(ctx)
                            view.settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                // 桌面版登录页（如 QQ 的 OAuth 页）按宽视口缩放铺满，并可双指放大
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                mediaPlaybackRequiresUserGesture = true
                                // 允许 window.open（配合 onCreateWindow 原地承载）
                                setSupportMultipleWindows(true)
                                userAgent?.let { userAgentString = it }
                            }
                            CookieManager.getInstance().apply {
                                setAcceptCookie(true)
                                // 必须接受三方 Cookie：登录页的登录框常是跨域 iframe / 跳转链
                                setAcceptThirdPartyCookies(view, true)
                            }
                            view.webChromeClient = object : WebChromeClient() {
                                /**
                                 * 登录页常用 `window.open` 打开授权框 / 第三方登录。
                                 * 这里把「新窗口」的地址直接在当前 WebView 里加载（原地承载），
                                 * 否则弹窗会被静默丢弃，用户点了没反应。
                                 */
                                override fun onCreateWindow(
                                    view: WebView,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?,
                                ): Boolean {
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                    transport.webView = view
                                    resultMsg.sendToTarget()
                                    return true
                                }
                            }
                            view.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    v: WebView,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    pageLoading = true
                                }

                                override fun onPageFinished(v: WebView, url: String?) {
                                    pageLoading = false
                                }

                                override fun shouldOverrideUrlLoading(
                                    v: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    // 只允许 http(s)：拦截 openapp:// / tel: 等外部 scheme，避免跳走或崩
                                    val url = request.url?.toString().orEmpty()
                                    return !url.startsWith("http")
                                }
                            }
                            view.loadUrl(startUrl)
                            webViewRef = view
                            view
                        },
                    )
                }

                // 底部：抓取状态 + 兜底手动粘贴
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (captured) "登录态已获取" else "登录成功后会自动填充，无需手动复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onFallbackPaste) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("手动粘贴")
                    }
                }
            }
        }
    }

    // Cookie 轮询：登录成功后可能不发生整页跳转，需要主动检查
    LaunchedEffect(startUrl) {
        val cm = CookieManager.getInstance()
        while (true) {
            delay(POLL_INTERVAL_MS)
            val raw = cm.getCookie(cookieUrl).orEmpty()
            if (raw.isBlank()) continue
            if (requiredCookieKeys.none { raw.contains("$it=") }) continue
            if (raw == lastTried) continue
            // 仅当内容变化时上报：避免同一份无效 Cookie 反复打校验接口
            lastTried = raw
            captured = true
            cm.flush()
            AppLogger.d(
                TAG,
                "从 Cookie 容器抓到登录态：长度=${raw.length}，命中=" +
                    requiredCookieKeys.filter { raw.contains("$it=") },
            )
            emitCookie(raw)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { view ->
                runCatching {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
                }
            }
            webViewRef = null
        }
    }
}

@Composable
private fun StatusBar(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    text: String,
    color: androidx.compose.ui.graphics.Color,
    spinning: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinning) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = color,
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Cookie 轮询间隔（毫秒）：登录完成到取到票据通常 < 1s，700ms 足够灵敏又不费电 */
private const val POLL_INTERVAL_MS = 700L

/** 日志标签 */
private const val TAG = "WebLogin"

/** 网易云移动版登录页（与 LX Music 移动端一致） */
const val NCM_LOGIN_URL = "https://music.163.com/m/login"

/** 网易云移动版 UA：官网会据此渲染移动端登录表单（短信验证码 / 密码） */
const val NCM_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

/** 网易云登录票据 Cookie 名（HttpOnly，只能用 CookieManager 取） */
val NCM_LOGIN_COOKIE_KEYS = listOf("MUSIC_U", "MUSIC_A", "S_INFO")

/** 网易云 Cookie 归属域 */
const val NCM_COOKIE_URL = "https://music.163.com"

