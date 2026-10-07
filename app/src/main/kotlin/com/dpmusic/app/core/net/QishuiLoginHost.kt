package com.dpmusic.app.core.net

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 汽水音乐**登录宿主**（Android WebView 承载官方 JS SDK）。
 *
 * 背景：汽水登录需要官方 SDK 的运行时签名（`web_protect`），**无法用静态 HTTP 重放**。
 * 方案：把 3.8.0 的官方 SDK（`assets/qishui_host/`）跑在 WebView 里，用真实 SDK 发码/登录/MFA。
 *
 * 已验证的关键点（见 工作区/…/汽水/登录实现/研究总报告.md）：
 * - SDK 可当 ESM `import`；宿主页须**极简**（否则 `mfa.js` 挂不上 `window.ucWebSecondVerify`）
 * - `$$secondVerificationOptions` 必须带 `host:'https://api.qishui.com'`（否则 MFA 请求发到本地）
 * - 2046 时 SDK 会**自动渲染 MFA** 到本页 `#uc-second-verify`，验证成功后 SDK 自动收尾
 * - `api.qishui.com` **无 CORS 头**，但实测 WebView 直连不被拦（无需 `--disable-web-security`）
 *
 * 页面通过 `AndroidBridge.onResult(cbId, json)` 回传结果；Kotlin 侧挂起等待。
 *
 * 合规：仅"用户本人账号登录"；不含任何权益/会员写接口。
 */
class QishuiLoginHost(private val context: Context) {

    interface Listener {
        /** 登录成功，已取到完整 Cookie 串 */
        fun onLoggedIn(cookie: String)
        /** 需要在界面上展示 MFA 卡片 */
        fun onMfaRequired(ways: List<String>)
        /** 提示性事件（如"已发送验证码"） */
        fun onNotice(text: String)
    }

    var listener: Listener? = null

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val counter = AtomicInteger()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 供 Compose 用 `AndroidView` 挂载 */
    val webView: WebView by lazy { buildWebView() }

    /** 初始化是否完成（SDK 是否就绪） */
    private val initDeferred = CompletableDeferred<JsonObject>()

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false)
            // 第三方 Cookie：登录流程可能跨 api.qishui.com / auth.zijieapi.com
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(Bridge(), "AndroidBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.d(TAG, "web: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? =
                    interceptLocal(req)
            }
            // ★ 页面跑在 https://api.qishui.com 源上 → 与服务端同源，避免 CORS
            loadUrl(HOST_URL)
        }
    }

    /** 把 api.qishui.com 域下属于「宿主页/资源」的请求映射到本地 assets，其余返回 null 走网络 */
    private fun interceptLocal(req: WebResourceRequest): WebResourceResponse? {
        val url = req.url
        if (url.host != "api.qishui.com") return null
        val p = url.path ?: return null
        if (!p.startsWith(HOST_PREFIX)) return null          // 只接管 /__host__/ 前缀
        val rel = p.removePrefix(HOST_PREFIX).ifEmpty { "index.html" }
        val mime = when {
            rel.endsWith(".html") -> "text/html"
            rel.endsWith(".js") -> "text/javascript"
            rel.endsWith(".css") -> "text/css"
            rel.endsWith(".wasm") -> "application/wasm"
            rel.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
        return try {
            val stream = context.assets.open("qishui_host/$rel")
            WebResourceResponse(mime, "utf-8", stream)
        } catch (e: Exception) {
            Log.w(TAG, "asset miss: $rel")
            WebResourceResponse(mime, "utf-8", 404, "Not Found", emptyMap(), null)
        }
    }

    // ------------------------------------------------------------------ Bridge

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(cbId: String, payload: String) {
            Log.d(TAG, "bridge[$cbId] " + payload.take(200))
            val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
            pending.remove(cbId)?.complete(obj)
            if (cbId == CB_INIT) initDeferred.complete(obj)
        }
    }

    // ------------------------------------------------------------------ 调用封装

    private suspend fun js(expr: String): JsonObject = callJs { "window.__qs.$expr" }

    /** 通用调用：执行 `callJs("window.__qs.xxx('cbId', ...)")`，等待 Bridge 回调 */
    private suspend fun callJs(makeCall: (cbId: String) -> String): JsonObject = withContext(Dispatchers.Main) {
        val cbId = "cb" + counter.incrementAndGet()
        val def = CompletableDeferred<JsonObject>()
        pending[cbId] = def
        webView.evaluateJavascript(makeCall(cbId), null)
        try {
            withTimeout(TIMEOUT_MS) { def.await() }
        } finally {
            pending.remove(cbId)
        }
    }

    /** 等待 SDK 就绪 */
    suspend fun awaitReady(timeoutMs: Long = 40_000): Boolean =
        runCatching { withTimeout(timeoutMs) { initDeferred.await() }.get("ok")?.jsonPrimitive?.booleanOrNull == true }
            .getOrDefault(false)

    /** 是否已就绪（不阻塞） */
    fun isReady(): Boolean = initDeferred.isCompleted

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    /** ① 发送验证码 */
    suspend fun sendCode(mobile: String): JsonObject =
        callJs { "window.__qs.sendCode('$it','${esc(mobile)}')" }

    /** ② 登录（可能触发 2046 → MFA 自动渲染到本页） */
    suspend fun login(mobile: String, code: String): JsonObject =
        callJs { "window.__qs.login('$it','${esc(mobile)}','${esc(code)}')" }

    /** 登录状态（含 MFA 是否已渲染） */
    suspend fun loginState(): JsonObject = js("loginState")

    /** MFA 可用方式（界面文案） */
    suspend fun mfaWays(): List<String> =
        js("ways").get("ways")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    /** 选择 MFA 方式（按文案，如「登录密码验证」） */
    suspend fun pickWay(label: String): Boolean =
        runCatching { callJs { "window.__qs.pick('$it','${esc(label)}')" }.get("ok")?.jsonPrimitive?.booleanOrNull == true }
            .getOrDefault(false)

    /** MFA 子页信息 */
    suspend fun mfaInfo(): JsonObject = js("mfaInfo")

    /** 提交 MFA（填值 + 点「验证」） */
    suspend fun submitMfa(value: String): JsonObject =
        runCatching { callJs { "window.__qs.submit('$it','${esc(value)}')" } }
            .getOrElse { JsonObject(emptyMap()) }

    /** SDK 收尾结果（`raw` 非空即成功） */
    suspend fun finishState(): JsonObject = js("finishState")

    /**
     * 轮询等待登录成功。
     *
     * **判定标准不是"看起来已登录"，而是"带 cookie 请求 `/luna/pc/me` 真的通"**——
     * 因为汽水登录后下发的是 7 个 Cookie（且无 `sessionid`），只挑单个必然 1000016。
     *
     * @param timeoutMs 超时
     * @return 成功时返回完整 Cookie 串；否则 null
     */
    suspend fun awaitSessionCookie(timeoutMs: Long = 60_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(1000)
            val cookie = readQishuiCookies()
            if (cookie.isNullOrBlank()) continue
            // 用完整 Cookie 真实校验；只有服务端认了才算成功
            val ok = runCatching { QishuiApi.isOk(QishuiApi.meByCookie(cookie)) }.getOrDefault(false)
            if (ok) return cookie
        }
        return null
    }

    /**
     * 读取 WebView 中 **qishui.com 域下的全部 Cookie**（登录凭证是整套，不是单个）。
     *
     * 实测下发的 7 个：
     * `ttwid` / `passport_csrf_token` / `passport_auth_status_ss` / `uid_tt_ss` /
     * `sessionid_ss` / `session_tlb_tag` / `ssid_ucp_v1`（**无 `sessionid`**）。
     * 从 `sessionid_ss` 里再析出待校验值供后续使用。
     */
    fun readQishuiCookies(): String? {
        val cm = android.webkit.CookieManager.getInstance()
        cm.flush()
        // 同时取 api.qishui.com 与 .qishui.com：CookieManager 的域名匹配在某些实现下需要显式补齐
        val merged = linkedMapOf<String, String>()
        for (url in listOf("https://api.qishui.com/", "https://qishui.com/")) {
            val raw = cm.getCookie(url) ?: continue
            raw.split(";").forEach { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) merged.putIfAbsent(kv[0].trim(), kv[1].trim())
            }
        }
        if (merged.isEmpty()) return null
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 从 Cookie 串里提取 `sessionid` / `sessionid_ss`（仅作展示，不作为请求凭证） */
    fun readSessionIdCookie(): String? {
        val c = readQishuiCookies() ?: return null
        c.split(";").forEach { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && (kv[0] == "sessionid" || kv[0] == "sessionid_ss")) return kv[1]
        }
        return null
    }

    /** 销毁 */
    fun destroy() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        runCatching { webView.destroy() }
    }

    private companion object {
        const val TAG = "QishuiLoginHost"
        // 页面跑在服务端同源域下（避免 CORS），本地资源由 shouldInterceptRequest 接管
        const val HOST_URL = "https://api.qishui.com/__host__/index.html"
        const val HOST_PREFIX = "/__host__/"
        const val TIMEOUT_MS = 60_000L
        const val CB_INIT = "init"
    }
}
