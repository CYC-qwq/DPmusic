package com.dpmusic.app.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpmusic.app.AppContainer
import com.dpmusic.app.core.net.QishuiApi
import com.dpmusic.app.core.net.QishuiPlatformApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 轮询间隔（毫秒） */
private const val POLL_INTERVAL_MS = 2000L

/** 二维码有效期兜底（毫秒） */
private const val QR_TTL_MS = 5 * 60 * 1000L

/**
 * 汽水音乐**扫码登录**对话框（备用方式）。
 *
 * ⚠️ **实测提示**：汽水 PC 端扫码登录的后端（`bff-pc.qishui.com/ucenter_web/...`）
 * 当前返回 **404**，抖音扫码会打开「无法访问页面」，故本方式**暂时不可用**。
 * 首选请用 [QishuiAccountCard] 的「填写登录凭证」（粘贴本人 `sessionid`）。
 * 保留此实现以备服务端恢复。
 */
@Composable
fun QishuiLoginDialog(
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit = {},
) {
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var statusText by remember { mutableStateOf("正在获取二维码…") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // 登录成功 → 稍作停留再关闭
    LaunchedEffect(success) {
        if (success) {
            delay(600)
            onLoggedIn()
            onDismiss()
        }
    }

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        success = false
        qrBitmap = null
        statusText = "正在获取二维码…"
        val qr = runCatching { AppContainer.qishuiLoginApi.getQrcode() }.getOrNull()
        if (qr == null || qr.token.isBlank()) {
            error = "获取二维码失败，请稍后重试"
            loading = false
            return@LaunchedEffect
        }
        qrBitmap = decodeDataUrl(qr.qrcodeDataUrl)
        statusText = "请使用已登录的「抖音 APP」扫码确认"
        loading = false

        val deadline = System.currentTimeMillis() + QR_TTL_MS
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            if (System.currentTimeMillis() > deadline) {
                statusText = "二维码已过期，正在刷新…"
                refreshKey++
                return@LaunchedEffect
            }
            val st = runCatching { AppContainer.qishuiLoginApi.checkQr(qr.token, qr.csrf) }.getOrNull() ?: continue
            if (st.sessionid.isNotBlank()) {
                statusText = "登录成功"
                // 扫码通道实测已下线（后端 404），此处仅为兼容而保留：
                // 扫码只回单个 sessionid，仍按「整套 Cookie」语义保存，校验走 meByCookie。
                val cookie = "sessionid=${st.sessionid}"
                val me = runCatching { QishuiApi.meByCookie(cookie) }.getOrNull()
                if (me != null && QishuiApi.isOk(me)) {
                    runCatching { AppContainer.qishui.saveLogin(cookie, st.sessionid, QishuiApi.profileOf(me)) }
                    success = true
                    return@LaunchedEffect
                }
                statusText = "扫码返回的凭证校验失败（该通道已下线，请用官方 SDK 登录）"
                return@LaunchedEffect
            }
            statusText = when (st.status) {
                "2", "confirmed", "scanned" -> "已扫码，请在手机上确认…"
                "3", "expired", "canceled" -> "二维码已失效，正在刷新…"
                else -> "请使用已登录的「抖音 APP」扫码确认"
            }
            if (st.status == "3" || st.status == "expired" || st.status == "canceled") {
                refreshKey++
                return@LaunchedEffect
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("扫码登录（PC 端扫码服务当前 404，建议改用填写凭证）", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "使用已登录的「抖音 APP」扫码验证",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier.size(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loading -> CircularProgressIndicator()
                        qrBitmap != null -> Image(
                            bitmap = qrBitmap!!,
                            contentDescription = "登录二维码",
                            modifier = Modifier.size(220.dp),
                        )
                        else -> Text(
                            "二维码加载失败",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val e = error
                    if (e != null) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    } else if (success) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = e ?: statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (e != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { refreshKey++ }) { Text("刷新") }
                    Button(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/**
 * 设置页「汽水音乐」卡片：展示登录状态 + 扫码登录 / 退出登录。
 *
 * 自包含：内部持有登录对话框开关，调用方只需在 Sources 分区放一个 `QishuiAccountCard()`。
 */
@Composable
fun QishuiAccountCard(
    onOpenPlaylist: (id: String, title: String) -> Unit = { _, _ -> },
) {
    val loggedIn by AppContainer.qishui.loggedIn.collectAsStateWithLifecycle()
    val profile by AppContainer.qishui.profile.collectAsStateWithLifecycle()
    val settings by AppContainer.settings.settings.collectAsStateWithLifecycle()
    var showQr by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showWeb by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 启动时校验一次本地凭证：cookie 过期则自动登出（网络异常不误判）
    LaunchedEffect(Unit) {
        val c = AppContainer.qishui.cookie.value
        if (c.isBlank()) return@LaunchedEffect
        val me = runCatching { QishuiApi.meByCookie(c) }.getOrNull() ?: return@LaunchedEffect
        if (QishuiApi.isOk(me)) {
            AppContainer.qishui.saveLogin(c, AppContainer.qishui.sessionid.value, QishuiApi.profileOf(me))
        } else {
            AppContainer.qishui.clearLogin()
        }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("汽水音乐", style = MaterialTheme.typography.titleMedium)
                    Text(
                        // 开关已统一收到「音源开关」卡片，这里只反映状态，不再放第二个开关
                        if (settings.qishuiEnabled) {
                            "音源已开启 · 登录以同步歌单 / 推荐"
                        } else {
                            "音源已关闭（到上方「音源开关」开启）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (loggedIn) {
                Text(
                    "已登录：" + (profile?.nickname?.takeIf { it.isNotBlank() } ?: "汽水用户"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { showPlaylists = true }) { Text("汽水歌单 / 推荐") }
                    OutlinedButton(onClick = { scope.launch { AppContainer.qishui.clearLogin() } }) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("退出登录")
                    }
                }
            } else {
                Text(
                    "未登录：仅可解析免费歌全曲，VIP 曲目 30 秒试听",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { showWeb = true }) { Text("登录汽水账号") }
                    OutlinedButton(onClick = { showManual = true }) { Text("填写凭证") }
                }
            }
        }
    }

    if (showQr) {
        QishuiLoginDialog(onDismiss = { showQr = false })
    }

    if (showWeb) {
        QishuiWebLoginDialog(onDismiss = { showWeb = false })
    }

    if (showManual) {
        QishuiManualLoginDialog(onDismiss = { showManual = false })
    }

    if (showPlaylists) {
        QishuiPlaylistsDialog(
            onDismiss = { showPlaylists = false },
            onOpenPlaylist = { id, title ->
                showPlaylists = false
                onOpenPlaylist(id, title)
            },
        )
    }
}

/**
 * 「我的汽水歌单」：列出当前登录账号的汽水歌单（需登录态）。
 *
 * 走 PC 端 `/luna/pc/me/playlist`，实测可拿到「我喜欢的音乐」「抖音收藏的音乐」等。
 * 点击歌单 → 交给调用方跳转到汽水的歌单详情页。
 */
@Composable
fun QishuiPlaylistsDialog(
    onDismiss: () -> Unit,
    onOpenPlaylist: (id: String, title: String) -> Unit = { _, _ -> },
) {
    val cookie by AppContainer.qishui.cookie.collectAsStateWithLifecycle()
    // 汽水音源开关：关闭时弹窗不该有任何后台请求（本弹窗的接口全部直连汽水，不受平台层过滤）
    val qishuiEnabled by AppContainer.settings.settings
        .map { it.qishuiEnabled }
        .collectAsStateWithLifecycle(initialValue = true)
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var mine by remember { mutableStateOf<List<com.dpmusic.app.core.model.PlaylistSummary>>(emptyList()) }
    var reco by remember { mutableStateOf<List<com.dpmusic.app.core.model.PlaylistSummary>>(emptyList()) }
    var scenes by remember { mutableStateOf<List<QishuiPlatformApi.QsScene>>(emptyList()) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var vipMsg by remember { mutableStateOf<String?>(null) }
    var vipBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cookie, qishuiEnabled) {
        if (!qishuiEnabled) {
            loading = false
            error = "汽水音源已在设置中关闭"
            return@LaunchedEffect
        }
        loading = true
        error = null
        val api = AppContainer.qishuiPlatformApi
        // 场景电台（= 汽水「一进 App 就播」的推荐流）
        scenes = runCatching { api.sceneRadios() }.getOrDefault(emptyList())
        // 推荐歌单（匿名）
        reco = runCatching { api.recommendedPlaylists() }.getOrDefault(emptyList())
        // 我的歌单（需登录）
        if (cookie.isNotBlank()) {
            val r = runCatching { api.myPlaylists(cookie) }
            mine = r.getOrDefault(emptyList())
        }
        if (mine.isEmpty() && reco.isEmpty() && scenes.isEmpty()) error = "暂无内容（网络异常）"
        loading = false
    }

    fun playScene(scene: QishuiPlatformApi.QsScene) {
        if (playingId != null) return
        playingId = scene.id
        // 交给电台控制器：它会在队列将尽时自动续杯（接口每次只回 6 首）
        AppContainer.qishuiRadio.start(scene.id, scene.name)
        onDismiss()
        playingId = null
    }

    fun claimVip() {
        if (vipBusy || cookie.isBlank()) return
        vipBusy = true
        scope.launch {
            val api = AppContainer.qishuiPlatformApi
            val r = runCatching { api.applyFreeVip(cookie) }.getOrNull()
            val obj = r as? kotlinx.serialization.json.JsonObject
            val st = (obj?.get("apply_status") as? kotlinx.serialization.json.JsonPrimitive)?.content
            val msg = ((obj?.get("status_info") as? kotlinx.serialization.json.JsonObject)
                ?.get("status_msg") as? kotlinx.serialization.json.JsonPrimitive)?.content
            vipMsg = when {
                r == null -> "领取失败：网络异常"
                st != null -> "已提交（apply_status=$st）· 该活动为服务端动态下发，能否到账以汽水 App 为准"
                else -> "已提交 · " + (msg ?: "")
            }
            vipBusy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("汽水歌单 / 电台") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在加载…", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        if (scenes.isNotEmpty()) {
                            SectionLabel("场景电台（点即播）")
                            scenes.forEach { sc ->
                                TextButton(
                                    onClick = { playScene(sc) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(sc.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                        if (playingId == sc.id) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text("▶", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        if (mine.isNotEmpty()) {
                            SectionLabel("我的歌单")
                            mine.forEach { pl -> PlaylistRowItem(pl, onOpenPlaylist) }
                            Spacer(Modifier.height(10.dp))
                        }
                        if (reco.isNotEmpty()) {
                            SectionLabel("为你推荐")
                            reco.forEach { pl -> PlaylistRowItem(pl, onOpenPlaylist) }
                        }
                        // 每日领 VIP（诚实标注：本通道不改变取址门控）
                        Spacer(Modifier.height(10.dp))
                        SectionLabel("每日领 VIP（实验）")
                        Text(
                            "⚠️ 汽水的领 VIP 是服务端下发的活动页（App 内「领金币」），Web 侧**领不到**。\n" +
                                "此按钮只是尽力尝试，不保证到账；且即使到账，本应用的取址通道也**不会**因此解锁会员曲。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { claimVip() },
                            enabled = !vipBusy && cookie.isNotBlank(),
                        ) { Text(if (vipBusy) "提交中…" else "尝试领取") }
                        vipMsg?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun PlaylistRowItem(
    pl: com.dpmusic.app.core.model.PlaylistSummary,
    onOpenPlaylist: (id: String, title: String) -> Unit,
) {
    TextButton(
        onClick = { onOpenPlaylist(pl.id, pl.name) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(pl.name, style = MaterialTheme.typography.bodyLarge)
            val sub = listOfNotNull(
                pl.creator.takeIf { it.isNotBlank() },
                if (pl.trackCount > 0) "${pl.trackCount} 首" else null,
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 手动填写登录凭证对话框。
 *
 * 背景：汽水 **无 web 登录入口**（官网为营销下载页），PC 扫码后端域名 `bff-pc.qishui.com`
 * 已 **404 下线**——两条自动取票路径都不通。唯一可行且合规的做法：由用户从**自己**
 * 已登录的官方客户端导出 `sessionid`（本人会话票据），粘贴进来校验后本地保存。
 */
@Composable
private fun QishuiManualLoginDialog(onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("填写登录凭证") },
        text = {
            Column {
                Text(
                    "从你已登录的汽水音乐官方客户端 / 浏览器导出**整套 Cookie**（含 sessionid_ss、ttwid、passport_* 等），整串粘贴到下方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "⚠️ 仅用于你本人账号，保存在本机，不上传第三方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Cookie（整串）") },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !busy && !ok,
                    modifier = Modifier.fillMaxWidth(),
                )
                val msg = message
                if (msg != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !ok && input.isNotBlank(),
                onClick = {
                    val cookie = normalizeCookie(input)
                    if (cookie.isBlank()) {
                        message = "未能识别有效 Cookie"
                        return@TextButton
                    }
                    scope.launch {
                        busy = true
                        message = "正在校验登录态…"
                        // ★ 用整串 Cookie 校验（单个 sessionid 必然 1000016）
                        val me = runCatching { QishuiApi.meByCookie(cookie) }.getOrNull()
                        if (me != null && QishuiApi.isOk(me)) {
                            runCatching {
                                AppContainer.qishui.saveLogin(cookie, QishuiApi.extractSessionid(cookie), QishuiApi.profileOf(me))
                            }
                            ok = true
                            message = "登录成功：" + QishuiApi.profileOf(me).nickname
                            delay(600)
                            onDismiss()
                        } else {
                            message = "登录态无效或已过期，请重新获取"
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) "校验中…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/**
 * 规整用户粘贴的 Cookie：
 * - 支持整串 `a=1; b=2`；
 * - 支持从浏览器 DevTools 复制的**多行**格式（自动拆行、去空白）；
 * - 支持裸 `sessionid` 值（极少见，但保留兼容）。
 */
private fun normalizeCookie(raw: String): String {
    val text = raw.trim()
    if (text.isBlank()) return ""
    // 多行 → 单行
    val flat = text.replace(Regex("[\\r\\n]+"), "; ")
    val pairs = flat.split(";")
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains("=") }
        .mapNotNull { seg ->
            val i = seg.indexOf('=')
            val k = seg.substring(0, i).trim()
            val v = seg.substring(i + 1).trim()
            if (k.isBlank() || v.isBlank()) null else "$k=$v"
        }
    if (pairs.isEmpty()) {
        // 裸 sessionid 兜底
        return text.takeIf { s -> s.none { it == ';' || it.isWhitespace() || it == '"' || it == '\'' } }.orEmpty()
    }
    return pairs.joinToString("; ")
}

/** `data:image/png;base64,...` → ImageBitmap */
private fun decodeDataUrl(dataUrl: String): ImageBitmap? {
    if (dataUrl.isBlank()) return null
    val comma = dataUrl.indexOf(',')
    val b64 = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
    val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
    val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
    return bmp.asImageBitmap()
}