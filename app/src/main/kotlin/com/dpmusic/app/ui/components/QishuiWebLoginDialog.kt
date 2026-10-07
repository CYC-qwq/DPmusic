package com.dpmusic.app.ui.components

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dpmusic.app.AppContainer
import com.dpmusic.app.BuildConfig
import com.dpmusic.app.core.net.QishuiApi
import com.dpmusic.app.core.net.QishuiLoginHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 汽水音乐「官方 SDK 登录」对话框。
 *
 * 用内嵌 WebView 跑官方 3.8.0 SDK：发验证码 → 登录 → （若触发二次验证）在 WebView 内渲染官方 MFA
 * → 本对话框驱动 MFA（密码 / 上行短信 / 扫码）→ SDK 自动收尾 → 取 `sessionid` 存入仓储。
 *
 * 前置：`assets/qishui_host/` 内含官方 SDK 与 MFA 运行时（见 工作区…/研究总报告.md）。
 */
private enum class WebLoginStep { PHONE, CODE, MFA, DONE }

@Composable
fun QishuiWebLoginDialog(
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit = {},
) {
    // 精简版（nosdk）不含官方 SDK 资源：直接给出「缺失」提示，不创建 WebView / 宿主。
    if (!BuildConfig.QISHUI_SDK) {
        QishuiSdkMissingDialog(onDismiss)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember { QishuiLoginHost(context) }

    var step by remember { mutableStateOf(WebLoginStep.PHONE) }
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var mfaValue by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("正在初始化官方 SDK…") }
    var busy by remember { mutableStateOf(false) }

    // 初始化：等 SDK 就绪
    remember(Unit) {
        scope.launch {
            val ok = host.awaitReady()
            status = if (ok) "SDK 就绪，请输入手机号" else "SDK 初始化失败，请检查网络后重试"
        }
    }

    fun finish(cookie: String, sid: String) {
        scope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching { QishuiApi.profileOf(QishuiApi.meByCookie(cookie)) }
                    .getOrNull() ?: com.dpmusic.app.core.model.QishuiProfile()
            }
            runCatching { AppContainer.qishui.saveLogin(cookie, sid, profile) }
            step = WebLoginStep.DONE
            status = "登录成功" + (profile.nickname.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
            delay(500)
            onLoggedIn()
            onDismiss()
        }
    }

    fun doSend() {
        busy = true
        scope.launch {
            status = "正在发送验证码…"
            val r = withContext(Dispatchers.IO) { runCatching { host.sendCode(mobile.trim()) }.getOrNull() }
            val ok = r?.get("ok")?.jsonPrimitive?.booleanOrNull == true
            status = if (ok) "验证码已发送，请查收（4 位）" else "发送失败：" + (r?.get("description")?.jsonPrimitive?.contentOrNull ?: "请稍后重试")
            if (ok) step = WebLoginStep.CODE
            busy = false
        }
    }

    fun doPick(label: String, auto: Boolean = false) {
        busy = true
        scope.launch {
            status = if (auto) "正在进入密码验证…" else "正在切换验证方式…"
            withContext(Dispatchers.IO) { runCatching { host.pickWay(label) } }
            delay(2500)
            status = "请输入登录密码完成验证"
            busy = false
        }
    }

    fun afterLoginCheck() {
        scope.launch {
            delay(2500)
            val st = withContext(Dispatchers.IO) { runCatching { host.loginState() }.getOrNull() }
            val mfaRendered = st?.get("mfaRendered")?.jsonPrimitive?.booleanOrNull == true
            if (mfaRendered) {
                // 仅保留「登录密码验证」，其余方式（扫码 / 上行短信）一律隐藏并自动选中
                val all = withContext(Dispatchers.IO) { runCatching { host.mfaWays() }.getOrNull() } ?: emptyList()
                val pwd = all.firstOrNull { it.contains("登录密码") || it.contains("密码验证") }
                step = WebLoginStep.MFA
                if (pwd != null) {
                    doPick(pwd, auto = true)
                } else {
                    status = "二次验证缺少「登录密码验证」方式，无法继续"
                }
            } else {
                status = "正在确认登录…"
                val cookie = withContext(Dispatchers.IO) { runCatching { host.awaitSessionCookie(30_000) }.getOrNull() }
                if (!cookie.isNullOrBlank()) finish(cookie, host.readSessionIdCookie().orEmpty()) else status = "未取到登录态，请重试"
            }
            busy = false
        }
    }

    fun doLogin() {
        busy = true
        scope.launch {
            status = "正在登录…"
            withContext(Dispatchers.IO) { runCatching { host.login(mobile.trim(), code.trim()) } }
            afterLoginCheck()
        }
    }

    fun doSubmit() {
        busy = true
        scope.launch {
            status = "正在提交验证…"
            withContext(Dispatchers.IO) { runCatching { host.submitMfa(mfaValue) } }
            val cookie = withContext(Dispatchers.IO) { runCatching { host.awaitSessionCookie(45_000) }.getOrNull() }
            if (!cookie.isNullOrBlank()) finish(cookie, host.readSessionIdCookie().orEmpty()) else status = "验证未通过或超时，请重试"
            busy = false
        }
    }

    Dialog(onDismissRequest = { host.destroy(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("汽水音乐 · 官方 SDK 登录", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "仅在登录你本人账号时使用；密码/验证码只在本机处理，不上传第三方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))

                // 手机号
                OutlinedTextField(
                    value = mobile,
                    onValueChange = { mobile = it.filter { c -> c.isDigit() } },
                    label = { Text("手机号") },
                    singleLine = true,
                    enabled = !busy && step == WebLoginStep.PHONE,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (step == WebLoginStep.PHONE) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { doSend() }, enabled = !busy && mobile.length >= 6, modifier = Modifier.fillMaxWidth()) {
                        Text("① 发送验证码")
                    }
                }

                // 验证码
                if (step != WebLoginStep.PHONE) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { c -> c.isDigit() } },
                        label = { Text("短信验证码（4 位）") },
                        singleLine = true,
                        enabled = !busy && step == WebLoginStep.CODE,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (step == WebLoginStep.CODE) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { doLogin() }, enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("② 登录")
                        }
                    }
                }

                // MFA：仅「登录密码验证」（自动选中）
                if (step == WebLoginStep.MFA) {
                    Spacer(Modifier.height(14.dp))
                    Text("二次验证 · 登录密码", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = mfaValue,
                        onValueChange = { mfaValue = it },
                        label = { Text("登录密码") },
                        placeholder = { Text("请输入登录密码") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { doSubmit() }, enabled = !busy && mfaValue.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text("提交验证")
                    }
                }

                // 官方 SDK 运行环境：**始终挂载**（保证页面尽早加载、init 回调能回来）；
                // 仅在验证码 / 二次验证阶段放大显示，便于观察与扫码。
                val showWeb = step == WebLoginStep.CODE || step == WebLoginStep.MFA
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(if (showWeb) 320.dp else 1.dp),
                ) {
                    AndroidView(
                        factory = { host.webView.also { it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)) }
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { host.destroy(); onDismiss() }, enabled = !busy) { Text("关闭") }
                }
            }
        }
    }
}

/**
 * 「登录 SDK 缺失」提示框（仅精简版 nosdk 出现）。
 *
 * 该变体为了体积**不含** `assets/qishui_host/`（官方 JS SDK，约 8.4MB 压缩后），
 * 因此手机号验证码 / MFA 这类依赖 SDK 运行时的登录方式不可用。
 *
 * 这里明确告知**缺的是什么**、以及**还能用什么**，而不是留一个点了没反应的按钮。
 */
@Composable
private fun QishuiSdkMissingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录 SDK 缺失") },
        text = {
            Column {
                Text(
                    "当前是精简版（不含汽水官方登录 SDK），无法使用「手机号验证码 / 二次验证」登录。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text("仍可用的方式：", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("· 「填写凭证」：粘贴本人登录凭证（推荐）", style = MaterialTheme.typography.bodySmall)
                Text("· 汽水音源的解析与播放不受影响", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Text(
                    "如需 SDK 登录，请改用完整版安装包。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}