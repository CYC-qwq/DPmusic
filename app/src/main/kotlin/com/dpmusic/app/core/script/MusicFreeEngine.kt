package com.dpmusic.app.core.script

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import com.dpmusic.app.core.net.Http
import com.dpmusic.app.core.util.AppLogger
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 插件声明的用户自定义输入项（如「Cookie」「音质」等） */
data class PluginUserVariable(
    val key: String,
    val name: String,
    val hint: String,
)

/** MusicFree 插件元信息（挂载后由插件导出对象读取） */
data class MusicFreePluginMeta(
    val platform: String = "",
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val supportedSearchType: List<String> = emptyList(),
    val userVariables: List<PluginUserVariable> = emptyList(),
    /** 插件实现的方法名列表（search / getMediaSource / getLyric / getTopLists ...） */
    val methods: List<String> = emptyList(),
) {
    fun supports(method: String): Boolean = methods.contains(method)
}

/** MusicFree 插件引擎状态 */
sealed interface PluginEngineStatus {
    data object Idle : PluginEngineStatus
    data class Loading(val pluginId: String) : PluginEngineStatus
    data class Ready(val pluginId: String, val meta: MusicFreePluginMeta) : PluginEngineStatus
    data class Failed(val pluginId: String, val message: String) : PluginEngineStatus
}

/**
 * MusicFree 插件引擎（QuickJS）。
 *
 * 与 [UserApiEngine]（LX 脚本）并列的第二套脚本运行时，二者互不干扰：
 * - 独立 QuickJS 上下文与工作线程；
 * - 运行时适配层见 `assets/script/musicfree-preload.js`：把 MusicFree 插件协议
 *   （module.exports = { platform, search, getMediaSource, ... }）映射到宿主能力；
 * - 插件发起的网络请求由本引擎用 OkHttp 代执行（UA / 超时 / 二进制 / 表单均支持）；
 * - 宿主 → 插件调用为「异步桥」：投递到工作线程触发，插件完成后回调响应。
 */
class MusicFreeEngine(private val context: Context) {

    private val networkExecutor = Executors.newCachedThreadPool()

    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var jsContext: QuickJSContext? = null
    private var key: String = ""
    private var currentPluginId: String = ""

    private val requestCalls = ConcurrentHashMap<String, Call>()
    private val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private val _status = MutableStateFlow<PluginEngineStatus>(PluginEngineStatus.Idle)
    val status: StateFlow<PluginEngineStatus> = _status.asStateFlow()

    @Synchronized
    private fun ensureWorker(): Handler {
        var handler = worker
        if (handler == null) {
            val t = HandlerThread("DpmMusicFreeEngine")
            t.start()
            thread = t
            handler = Handler(t.looper)
            worker = handler
        }
        return handler
    }

    /** 挂载插件（自动替换现有插件） */
    fun load(pluginId: String, source: String, userVariables: Map<String, String>) {
        val handler = ensureWorker()
        _status.value = PluginEngineStatus.Loading(pluginId)
        handler.post { doLoad(pluginId, source, userVariables) }
    }

    /**
     * 调用插件方法（宿主 → 插件 → 宿主）。
     *
     * @param method 插件方法名，如 `search` / `getMediaSource` / `getLyric`
     * @param argsJson 参数数组的 JSON 字符串，例如 `["关键词", 1, "music"]`
     * @return 插件返回值包装：`{ ok, result }`
     */
    suspend fun invoke(method: String, argsJson: String, timeoutMs: Long = CALL_TIMEOUT_MS): JSONObject {
        val handler = worker ?: throw IllegalStateException("插件引擎未就绪")
        val requestKey = "call_" + UUID.randomUUID().toString().replace("-", "")
        val deferred = CompletableDeferred<String>()
        pendingCalls[requestKey] = deferred
        val posted = handler.post { callPlugin(requestKey, method, argsJson) }
        if (!posted) {
            pendingCalls.remove(requestKey)
            throw IllegalStateException("插件引擎未就绪")
        }
        val raw = try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingCalls.remove(requestKey)
        }
        val json = JSONObject(raw)
        if (!json.optBoolean("ok", false)) {
            throw IllegalStateException(json.optString("error", "插件调用失败"))
        }
        return json
    }

    /** 便捷调用：直接取 result（可能为 JSONObject / JSONArray / 字符串 / null） */
    suspend fun invokeResult(method: String, argsJson: String, timeoutMs: Long = CALL_TIMEOUT_MS): Any? =
        invoke(method, argsJson, timeoutMs).opt("result")

    fun destroy() {
        val handler = worker
        if (handler == null) {
            _status.value = PluginEngineStatus.Idle
            return
        }
        handler.post { doDestroy() }
    }

    /* ---------------- 工作线程内部实现 ---------------- */

    private fun doLoad(pluginId: String, source: String, userVariables: Map<String, String>) {
        try {
            if (!quickJsLoaded) {
                QuickJSLoader.init()
                quickJsLoaded = true
            }
            jsContext?.let { runCatching { it.destroy() } }
            jsContext = null
            requestCalls.values.forEach { runCatching { it.cancel() } }
            requestCalls.clear()

            key = UUID.randomUUID().toString()
            currentPluginId = pluginId
            val ctx = QuickJSContext.create()
            jsContext = ctx
            ctx.setConsole(EngineConsole())

            val preload = readAsset(PRELOAD_ASSET)
                ?: throw IllegalStateException("缺少内置插件适配层：$PRELOAD_ASSET")
            // cheerio 兼容层（可选）：必须先于适配层求值，适配层会把它注册进 require 表
            readAsset(CHEERIO_ASSET)?.let { lib ->
                runCatching { ctx.evaluate(lib) }
                    .onFailure { AppLogger.w(TAG, "cheerio 兼容层注入失败：${it.message}") }
            }
            createBridge(ctx)
            ctx.evaluate(preload)

            val varsJson = JSONObject().apply {
                userVariables.forEach { (k, v) -> put(k, v) }
            }.toString()
            ctx.getGlobalObject().getJSFunction("mf_setup")
                .call(key, source, varsJson, appVersion())

            AppLogger.i(TAG, "插件已挂载：$pluginId（等待元信息上报）")
            worker?.postDelayed({
                val s = _status.value
                if (s is PluginEngineStatus.Loading && s.pluginId == pluginId) {
                    _status.value = PluginEngineStatus.Failed(
                        pluginId,
                        "插件未上报元信息，可能不是有效的 MusicFree 插件",
                    )
                }
            }, INIT_TIMEOUT_MS)
        } catch (e: Exception) {
            AppLogger.e(TAG, "插件挂载失败：${e.message}", e)
            _status.value = PluginEngineStatus.Failed(pluginId, e.message ?: "插件挂载失败")
        }
    }

    private fun doDestroy() {
        requestCalls.values.forEach { runCatching { it.cancel() } }
        requestCalls.clear()
        pendingCalls.values.forEach {
            it.completeExceptionally(IllegalStateException("插件引擎已销毁"))
        }
        pendingCalls.clear()
        jsContext?.let { runCatching { it.destroy() } }
        jsContext = null
        currentPluginId = ""
        _status.value = PluginEngineStatus.Idle
    }

    private fun readAsset(path: String): String? = try {
        context.assets.open(path).use { input ->
            input.readBytes().toString(StandardCharsets.UTF_8)
        }
    } catch (e: Exception) {
        AppLogger.e(TAG, "读取内置适配层失败：${e.message}")
        null
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** 注册宿主能力到 QuickJS 全局对象（命名与适配层约定一致） */
    private fun createBridge(ctx: QuickJSContext) {
        val global = ctx.getGlobalObject()

        global.setProperty("__mf_native_call__", nativeFunction { args ->
            try {
                val k = args.getOrNull(0) as? String
                val action = args.getOrNull(1) as? String
                val data = args.getOrNull(2) as? String
                if (k == key && action != null) handlePluginAction(action, data)
            } catch (e: Exception) {
                AppLogger.e(TAG, "插件回调处理失败：${e.message}")
            }
            null
        })

        global.setProperty("__mf_native_call__set_timeout", nativeFunction { args ->
            try {
                val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@nativeFunction null
                val delay = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
                worker?.postDelayed({ callPluginGlobal("mf_native", id.toString(), "\"__set_timeout__\"") }, delay)
            } catch (e: Exception) {
                // 忽略
            }
            null
        })

        global.setProperty("__mf_native_call__str2b64", nativeFunction { args ->
            try {
                val text = args.getOrNull(0) as? String ?: return@nativeFunction ""
                Base64.encodeToString(text.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            } catch (e: Exception) {
                ""
            }
        })

        global.setProperty("__mf_native_call__b642buf", nativeFunction { args ->
            try {
                val text = args.getOrNull(0) as? String ?: return@nativeFunction "[]"
                val bytes = Base64.decode(text.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                buildString {
                    append('[')
                    bytes.forEachIndexed { index, b ->
                        if (index > 0) append(',')
                        append(b.toInt())
                    }
                    append(']')
                }
            } catch (e: Exception) {
                "[]"
            }
        })

        global.setProperty("__mf_native_call__md5", digestBridge("MD5"))
        global.setProperty("__mf_native_call__sha1", digestBridge("SHA-1"))
        global.setProperty("__mf_native_call__sha256", digestBridge("SHA-256"))

        global.setProperty("__mf_native_call__aes_encrypt", nativeFunction { args ->
            try {
                AES.encrypt(
                    args.getOrNull(0) as? String ?: return@nativeFunction "",
                    args.getOrNull(1) as? String ?: return@nativeFunction "",
                    args.getOrNull(2) as? String ?: return@nativeFunction "",
                    args.getOrNull(3) as? String ?: return@nativeFunction "",
                )
            } catch (e: Exception) {
                ""
            }
        })
    }

    private fun digestBridge(algo: String): JSCallFunction = nativeFunction { args ->
        try {
            val text = args.getOrNull(0) as? String ?: return@nativeFunction ""
            MessageDigest.getInstance(algo)
                .digest(text.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
        } catch (e: Exception) {
            ""
        }
    }

    private fun nativeFunction(block: (Array<out Any?>) -> Any?): JSCallFunction =
        object : JSCallFunction {
            override fun call(vararg args: Any?): Any? = block(args)
        }

    /** 处理插件 → 宿主的事件（工作线程） */
    private fun handlePluginAction(action: String, data: String?) {
        when (action) {
            "init" -> handleInitEvent(data)
            "request" -> handleNetworkRequest(data)
            "log" -> handleLogEvent(data)
            "response" -> handleCallResponse(data)
        }
    }

    private fun handleInitEvent(data: String?) {
        try {
            val json = JSONObject(data.orEmpty())
            if (!json.optBoolean("status", false)) {
                val message = json.optString("errorMessage", "插件初始化失败")
                _status.value = PluginEngineStatus.Failed(currentPluginId, message)
                AppLogger.w(TAG, "插件初始化失败：$message")
                return
            }
            val meta = json.optJSONObject("meta") ?: JSONObject()
            val methods = mutableListOf<String>()
            meta.optJSONArray("methods")?.let { arr ->
                for (i in 0 until arr.length()) methods.add(arr.optString(i))
            }
            val vars = mutableListOf<PluginUserVariable>()
            meta.optJSONArray("userVariables")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val varKey = item.optString("key")
                    if (varKey.isBlank()) continue
                    vars.add(
                        PluginUserVariable(
                            key = varKey,
                            name = item.optString("name", varKey),
                            hint = item.optString("hint", ""),
                        ),
                    )
                }
            }
            val searchTypes = mutableListOf<String>()
            meta.optJSONArray("supportedSearchType")?.let { arr ->
                for (i in 0 until arr.length()) searchTypes.add(arr.optString(i))
            }
            _status.value = PluginEngineStatus.Ready(
                currentPluginId,
                MusicFreePluginMeta(
                    platform = meta.optString("platform"),
                    version = meta.optString("version"),
                    author = meta.optString("author"),
                    description = meta.optString("description"),
                    supportedSearchType = searchTypes,
                    userVariables = vars,
                    methods = methods,
                ),
            )
            AppLogger.i(TAG, "插件已就绪：${meta.optString("platform")}（能力：${methods.joinToString("/")}）")
        } catch (e: Exception) {
            _status.value = PluginEngineStatus.Failed(currentPluginId, "元信息解析失败：${e.message}")
        }
    }

    private fun handleLogEvent(data: String?) {
        val json = runCatching { JSONObject(data.orEmpty()) }.getOrNull() ?: return
        val message = "[插件] ${json.optString("message").take(500)}"
        when (json.optString("level", "debug")) {
            "info" -> AppLogger.i(TAG, message)
            "warn" -> AppLogger.w(TAG, message)
            "error" -> AppLogger.e(TAG, message)
            else -> AppLogger.d(TAG, message)
        }
    }

    private fun handleCallResponse(data: String?) {
        val raw = data.orEmpty()
        val requestKey = runCatching { JSONObject(raw).optString("requestKey") }.getOrNull()
        if (!requestKey.isNullOrEmpty()) {
            pendingCalls.remove(requestKey)?.complete(raw)
        }
    }

    private fun handleNetworkRequest(data: String?) {
        val json = runCatching { JSONObject(data.orEmpty()) }.getOrNull() ?: return
        val requestKey = json.optString("requestKey")
        if (requestKey.isEmpty()) return
        val url = json.optString("url")
        val options = json.optJSONObject("options") ?: JSONObject()
        networkExecutor.execute { executeRequest(requestKey, url, options) }
    }

    /** 执行插件发起的网络请求（网络线程池），完成后投递回工作线程 */
    private fun executeRequest(requestKey: String, url: String, options: JSONObject) {
        try {
            val method = options.optString("method", "get").lowercase(Locale.ROOT)
            val binary = options.optBoolean("binary", false)
            val timeout = options.optLong("timeout", 0L).coerceIn(0L, 60_000L)
            val headers = options.optJSONObject("headers")
            val form = options.optJSONObject("form")
            val formData = options.optJSONObject("formData")
            val bodyValue = options.opt("body")

            val builder = Request.Builder().url(url)
            builder.header("User-Agent", SCRIPT_UA)
            builder.header("Accept", "application/json")
            var contentType: String? = null
            if (headers != null) {
                val names = headers.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    val value = headers.optString(name)
                    when {
                        name.equals("User-Agent", ignoreCase = true) -> builder.header("User-Agent", value)
                        name.equals("Accept", ignoreCase = true) -> builder.header("Accept", value)
                        name.equals("Content-Type", ignoreCase = true) -> {
                            builder.header("Content-Type", value)
                            contentType = value
                        }
                        else -> runCatching { builder.header(name, value) }
                    }
                }
            }

            val isPostLike = method == "post" || method == "put" || method == "patch" || method == "delete"
            if (isPostLike) {
                val (requestBody, bodyType) = buildRequestBody(contentType, form, formData, bodyValue)
                builder.header("Content-Type", bodyType)
                builder.method(method.uppercase(Locale.ROOT), requestBody)
            } else {
                builder.get()
            }

            val effectiveTimeout = if (timeout > 0) timeout else DEFAULT_REQUEST_TIMEOUT_MS
            val client = Http.client.newBuilder()
                .callTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
                .build()
            val call = client.newCall(builder.build())
            requestCalls[requestKey] = call
            call.execute().use { resp ->
                val headersJson = JSONObject()
                for (name in resp.headers.names()) {
                    headersJson.put(name, JSONArray(resp.headers.values(name)))
                }
                val body: Any = if (binary) {
                    Base64.encodeToString(resp.body?.bytes() ?: ByteArray(0), Base64.NO_WRAP)
                } else {
                    val text = resp.body?.string().orEmpty()
                    runCatching { JSONTokener(text).nextValue() }.getOrElse { text }
                }
                val payload = JSONObject().apply {
                    put("requestKey", requestKey)
                    put("error", JSONObject.NULL)
                    put("response", JSONObject().apply {
                        put("statusCode", resp.code)
                        put("statusMessage", resp.message)
                        put("headers", headersJson)
                        put("body", body)
                    })
                }
                postToWorker { callPluginGlobal("mf_native", "\"response\"", payload.toString()) }
            }
        } catch (e: Exception) {
            val message = when (e) {
                is InterruptedIOException -> "请求超时或被取消"
                else -> e.message ?: "网络请求失败"
            }
            val payload = JSONObject().apply {
                put("requestKey", requestKey)
                put("error", message)
                put("response", JSONObject.NULL)
            }
            postToWorker { callPluginGlobal("mf_native", "\"response\"", payload.toString()) }
        } finally {
            requestCalls.remove(requestKey)
        }
    }

    private fun buildRequestBody(
        contentType: String?,
        form: JSONObject?,
        formData: JSONObject?,
        bodyValue: Any?,
    ): Pair<RequestBody, String> = when {
        form != null -> {
            val encoded = buildString {
                val keys = form.keys()
                var first = true
                while (keys.hasNext()) {
                    val name = keys.next()
                    if (!first) append('&')
                    first = false
                    append(URLEncoder.encode(name, "UTF-8"))
                    append('=')
                    append(URLEncoder.encode(form.optString(name), "UTF-8"))
                }
            }
            val type = contentType ?: FORM_MEDIA
            encoded.toRequestBody(type.toMediaType()) to type
        }
        formData != null -> {
            val boundary = "----DpmMfBoundary" + UUID.randomUUID().toString().replace("-", "")
            val type = "multipart/form-data; boundary=$boundary"
            val text = buildString {
                val keys = formData.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val raw = formData.opt(name)
                    val value = when (raw) {
                        is String -> raw
                        is JSONObject -> raw.optString("text", raw.toString())
                        null -> ""
                        else -> raw.toString()
                    }
                    append("--").append(boundary).append("\r\n")
                    append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                    append(value).append("\r\n")
                }
                append("--").append(boundary).append("--\r\n")
            }
            text.toRequestBody(type.toMediaType()) to type
        }
        bodyValue != null -> {
            val type = contentType ?: JSON_MEDIA
            val text = when (bodyValue) {
                is JSONObject, is JSONArray -> bodyValue.toString()
                is String -> bodyValue
                else -> bodyValue.toString()
            }
            text.toRequestBody(type.toMediaType()) to type
        }
        else -> {
            val type = contentType ?: JSON_MEDIA
            "".toRequestBody(type.toMediaType()) to type
        }
    }

    private fun postToWorker(block: () -> Unit) {
        worker?.post(block)
    }

    /** 调用适配层暴露的入口（必须在工作线程调用） */
    private fun callPlugin(requestKey: String, method: String, argsJson: String) {
        val ctx = jsContext ?: return
        try {
            val fn = ctx.getGlobalObject().getJSFunction("mf_call") ?: return
            fn.call(key, requestKey, method, argsJson)
        } catch (e: Exception) {
            pendingCalls.remove(requestKey)?.complete(
                JSONObject().apply {
                    put("requestKey", requestKey)
                    put("ok", false)
                    put("error", e.message ?: "插件调用异常")
                }.toString(),
            )
        }
    }

    /** 调用适配层上的辅助入口（必须在工作线程调用） */
    private fun callPluginGlobal(function: String, vararg args: Any?) {
        val ctx = jsContext ?: return
        try {
            val fn = ctx.getGlobalObject().getJSFunction(function) ?: return
            fn.call(key, *args)
        } catch (e: Exception) {
            AppLogger.e(TAG, "调用插件入口失败：${e.message}")
        }
    }

    private inner class EngineConsole : QuickJSContext.Console {
        override fun log(info: String) = AppLogger.d(TAG, "[插件] ${info.take(500)}")
        override fun info(info: String) = AppLogger.i(TAG, "[插件] ${info.take(500)}")
        override fun warn(info: String) = AppLogger.w(TAG, "[插件] ${info.take(500)}")
        override fun error(info: String) = AppLogger.e(TAG, "[插件] ${info.take(500)}")
    }

    private companion object {
        const val TAG = "MusicFreePlugin"
        const val PRELOAD_ASSET = "script/musicfree-preload.js"
        const val CHEERIO_ASSET = "script/musicfree-cheerio.js"
        const val DEFAULT_REQUEST_TIMEOUT_MS = 15_000L
        const val INIT_TIMEOUT_MS = 15_000L
        const val CALL_TIMEOUT_MS = 25_000L
        const val FORM_MEDIA = "application/x-www-form-urlencoded"
        const val JSON_MEDIA = "application/json"
        const val SCRIPT_UA =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36"

        @Volatile
        private var quickJsLoaded = false
    }
}