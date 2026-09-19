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
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 自定义音源脚本引擎状态 */
sealed interface ScriptEngineStatus {
    /** 未加载任何脚本 */
    data object Idle : ScriptEngineStatus

    /** 正在加载脚本 */
    data class Loading(val scriptId: String) : ScriptEngineStatus

    /** 脚本已就绪（sources：平台标识 → 支持的 action 列表） */
    data class Ready(
        val scriptId: String,
        val sources: Map<String, List<String>>,
    ) : ScriptEngineStatus

    /** 加载或初始化失败 */
    data class Failed(val scriptId: String, val message: String) : ScriptEngineStatus
}

/**
 * LX Music 自定义音源 JS 引擎（QuickJS）：
 *
 * - 在独立工作线程（HandlerThread）上运行 QuickJS 上下文；用户脚本与内置协议脚本
 *   assets/script/user-api-preload.js（LX 官方协议层）在隔离环境中执行；
 * - 通过 __lx_native_call__ 系列桥接宿主能力（Base64 / MD5 / AES / RSA / 定时器）；
 * - 脚本以 lx.send('inited', ...) 上报能力（sources），以 lx.on('request', ...)
 *   响应宿主下发的解析请求（音乐地址 / 歌词 / 封面）；
 * - 脚本发起的网络请求（lx.request）由本引擎用 OkHttp 代执行并回传响应。
 *
 * 线程模型：
 * - 工作线程：QuickJS 上下文的一切操作（加载 / 求值 / 回调）；
 * - 网络线程池：脚本请求的 HTTP 执行，完成后投递回工作线程回传；
 * - 状态流：任意线程可安全读取。
 */
class UserApiEngine(private val context: Context) {

    private val networkExecutor = Executors.newCachedThreadPool()

    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var jsContext: QuickJSContext? = null
    private var key: String = ""
    private var currentScriptId: String = ""

    private val requestCalls = ConcurrentHashMap<String, Call>()

    private val _status = MutableStateFlow<ScriptEngineStatus>(ScriptEngineStatus.Idle)
    val status: StateFlow<ScriptEngineStatus> = _status.asStateFlow()

    private val pendingScriptRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @Synchronized
    private fun ensureWorker(): Handler {
        var handler = worker
        if (handler == null) {
            val t = HandlerThread("DpmUserApiEngine")
            t.start()
            thread = t
            handler = Handler(t.looper)
            worker = handler
        }
        return handler
    }

    /** 加载并运行脚本（自动替换现有脚本） */
    fun load(
        scriptId: String,
        name: String,
        description: String,
        version: String,
        author: String,
        homepage: String,
        script: String,
    ) {
        val handler = ensureWorker()
        _status.value = ScriptEngineStatus.Loading(scriptId)
        handler.post { doLoad(scriptId, name, description, version, author, homepage, script) }
    }

    /** 向脚本下发动作（宿主 → 脚本；request / response 通信） */
    fun sendAction(action: String, data: String): Boolean {
        val handler = worker ?: return false
        handler.post { callScript(action, data) }
        return true
    }

    /** 向脚本发起一次请求并等待响应（宿主 → 脚本 → 宿主）；返回脚本响应的 JSON 字符串 */
    suspend fun requestScript(data: JSONObject, timeoutMs: Long = SCRIPT_REQUEST_TIMEOUT_MS): JSONObject {
        val requestKey = "req_" + UUID.randomUUID().toString().replace("-", "")
        val deferred = CompletableDeferred<String>()
        pendingScriptRequests[requestKey] = deferred
        val payload = JSONObject().apply {
            put("requestKey", requestKey)
            put("data", data)
        }
        if (!sendAction("request", payload.toString())) {
            pendingScriptRequests.remove(requestKey)
            throw IllegalStateException("音源脚本未就绪")
        }
        val raw = try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingScriptRequests.remove(requestKey)
        }
        return JSONObject(raw)
    }

    /** 销毁当前脚本（保留引擎线程，供下次加载复用） */
    fun destroy() {
        val handler = worker
        if (handler == null) {
            _status.value = ScriptEngineStatus.Idle
            return
        }
        handler.post { doDestroy() }
    }

    /* ---------------- 工作线程内部实现 ---------------- */

    private fun doLoad(
        scriptId: String,
        name: String,
        description: String,
        version: String,
        author: String,
        homepage: String,
        script: String,
    ) {
        try {
            if (!quickJsLoaded) {
                QuickJSLoader.init()
                quickJsLoaded = true
            }
            jsContext?.let { runCatching { it.destroy() } }
            jsContext = null
            key = UUID.randomUUID().toString()
            currentScriptId = scriptId
            val ctx = QuickJSContext.create()
            jsContext = ctx
            ctx.setConsole(EngineConsole())

            val preload = readAsset(PRELOAD_ASSET)
                ?: throw IllegalStateException("缺少内置协议脚本：$PRELOAD_ASSET")
            createEnvObject(ctx)
            ctx.evaluate(preload)
            ctx.getGlobalObject().getJSFunction("lx_setup")
                .call(key, scriptId, name, description, version, author, homepage, script)

            try {
                ctx.evaluate(script)
            } catch (e: Exception) {
                runCatching { callScript("__run_error__") }
                throw e
            }

            AppLogger.i(TAG, "脚本已加载：$name（等待初始化上报）")
            worker?.postDelayed({
                val s = _status.value
                if (s is ScriptEngineStatus.Loading && s.scriptId == scriptId) {
                    _status.value = ScriptEngineStatus.Failed(scriptId, "脚本未上报初始化信息，可能不是有效的音源脚本")
                }
            }, INITED_TIMEOUT_MS)
        } catch (e: Exception) {
            AppLogger.e(TAG, "脚本加载失败：${e.message}", e)
            _status.value = ScriptEngineStatus.Failed(scriptId, e.message ?: "脚本加载失败")
        }
    }

    private fun doDestroy() {
        requestCalls.values.forEach { runCatching { it.cancel() } }
        requestCalls.clear()
        pendingScriptRequests.values.forEach {
            it.completeExceptionally(IllegalStateException("音源脚本已销毁"))
        }
        pendingScriptRequests.clear()
        jsContext?.let { runCatching { it.destroy() } }
        jsContext = null
        currentScriptId = ""
        _status.value = ScriptEngineStatus.Idle
        AppLogger.i(TAG, "脚本引擎已销毁")
    }

    private fun readAsset(path: String): String? = try {
        context.assets.open(path).use { input ->
            input.readBytes().toString(StandardCharsets.UTF_8)
        }
    } catch (e: Exception) {
        AppLogger.e(TAG, "读取内置脚本失败：${e.message}")
        null
    }

    /** 将宿主能力注册到 QuickJS 全局对象 */
    private fun createEnvObject(ctx: QuickJSContext) {
        val global = ctx.getGlobalObject()

        global.setProperty("__lx_native_call__", nativeFunction { args ->
            try {
                val k = args.getOrNull(0) as? String
                val action = args.getOrNull(1) as? String
                val data = args.getOrNull(2) as? String
                if (k == key && action != null) handleScriptAction(action, data)
            } catch (e: Exception) {
                AppLogger.e(TAG, "脚本回调处理失败：${e.message}")
            }
            null
        })

        global.setProperty("__lx_native_call__utils_str2b64", nativeFunction { args ->
            try {
                val text = args.getOrNull(0) as? String ?: return@nativeFunction ""
                String(Base64.encode(text.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP))
            } catch (e: Exception) {
                ""
            }
        })

        global.setProperty("__lx_native_call__utils_b642buf", nativeFunction { args ->
            try {
                val text = args.getOrNull(0) as? String ?: return@nativeFunction ""
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
                ""
            }
        })

        global.setProperty("__lx_native_call__utils_str2md5", nativeFunction { args ->
            try {
                val raw = args.getOrNull(0) as? String ?: return@nativeFunction ""
                val text = URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
                val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8))
                digest.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
            } catch (e: Exception) {
                ""
            }
        })

        global.setProperty("__lx_native_call__utils_aes_encrypt", nativeFunction { args ->
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

        global.setProperty("__lx_native_call__utils_rsa_encrypt", nativeFunction { args ->
            try {
                RSA.encryptRSAToString(
                    args.getOrNull(0) as? String ?: return@nativeFunction "",
                    args.getOrNull(1) as? String ?: return@nativeFunction "",
                    args.getOrNull(2) as? String ?: return@nativeFunction "",
                )
            } catch (e: Exception) {
                ""
            }
        })

        global.setProperty("__lx_native_call__set_timeout", nativeFunction { args ->
            try {
                val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@nativeFunction null
                val delay = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
                worker?.postDelayed({ callScript("__set_timeout__", id) }, delay)
            } catch (e: Exception) {
                // 忽略
            }
            null
        })
    }

    /** JSCallFunction 的 Kotlin 包装：统一收参签名 */
    private fun nativeFunction(block: (Array<out Any?>) -> Any?): JSCallFunction =
        object : JSCallFunction {
            override fun call(vararg args: Any?): Any? = block(args)
        }

    /** 处理脚本 → 宿主的事件（工作线程） */
    private fun handleScriptAction(action: String, data: String?) {
        when (action) {
            "init" -> handleInitEvent(data)
            "request" -> handleNetworkRequest(data)
            "cancelRequest" -> {
                val requestKey = runCatching { JSONTokener(data.orEmpty()).nextValue() as? String }.getOrNull()
                if (requestKey != null) requestCalls.remove(requestKey)?.cancel()
            }
            "updateAlert" -> AppLogger.i(TAG, "脚本更新提示：${data.orEmpty().take(200)}")
            "response" -> handleScriptResponse(data)
        }
    }

    /** 分发脚本响应：匹配等待中的请求（未匹配的丢弃） */
    private fun handleScriptResponse(data: String?) {
        val raw = data.orEmpty()
        val requestKey = runCatching { JSONObject(raw).optString("requestKey") }.getOrNull()
        if (!requestKey.isNullOrEmpty()) {
            pendingScriptRequests.remove(requestKey)?.complete(raw)
        }
    }

    private fun handleInitEvent(data: String?) {
        try {
            val json = JSONObject(data.orEmpty())
            if (json.optBoolean("status", false)) {
                val sourcesObj = json.optJSONObject("info")?.optJSONObject("sources")
                val sources = linkedMapOf<String, List<String>>()
                if (sourcesObj != null) {
                    val keys = sourcesObj.keys()
                    while (keys.hasNext()) {
                        val source = keys.next()
                        val item = sourcesObj.optJSONObject(source) ?: continue
                        val actionsArr = item.optJSONArray("actions")
                        val actions = mutableListOf<String>()
                        if (actionsArr != null) {
                            for (i in 0 until actionsArr.length()) {
                                actions.add(actionsArr.optString(i))
                            }
                        }
                        sources[source] = actions
                    }
                }
                _status.value = ScriptEngineStatus.Ready(currentScriptId, sources)
                AppLogger.i(TAG, "脚本已就绪：支持 ${sources.keys.joinToString("/")}")
            } else {
                val message = json.optString("errorMessage", "脚本初始化失败")
                _status.value = ScriptEngineStatus.Failed(currentScriptId, message)
                AppLogger.w(TAG, "脚本初始化失败：$message")
            }
        } catch (e: Exception) {
            _status.value = ScriptEngineStatus.Failed(currentScriptId, "初始化数据解析失败：${e.message}")
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

    /** 执行脚本发起的网络请求（网络线程池） */
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
                postToWorker { callScript("response", payload.toString()) }
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
            postToWorker { callScript("response", payload.toString()) }
        } finally {
            requestCalls.remove(requestKey)
        }
    }

    /** 构造请求体（返回请求体 to Content-Type） */
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
            val boundary = "----DpmBoundary" + UUID.randomUUID().toString().replace("-", "")
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

    /** 调用脚本侧的 __lx_native__ 入口（必须在工作线程调用） */
    private fun callScript(action: String, vararg args: Any?) {
        val ctx = jsContext ?: return
        try {
            val fn = ctx.getGlobalObject().getJSFunction("__lx_native__") ?: return
            val params = arrayOfNulls<Any?>(args.size + 2)
            params[0] = key
            params[1] = action
            System.arraycopy(args, 0, params, 2, args.size)
            fn.call(*params)
        } catch (e: Exception) {
            AppLogger.e(TAG, "调用脚本失败：${e.message}")
        }
    }

    /** 脚本 console 输出 → 应用日志 */
    private inner class EngineConsole : QuickJSContext.Console {
        override fun log(info: String) {
            AppLogger.d(TAG, "[脚本] ${info.take(500)}")
        }

        override fun info(info: String) {
            AppLogger.i(TAG, "[脚本] ${info.take(500)}")
        }

        override fun warn(info: String) {
            AppLogger.w(TAG, "[脚本] ${info.take(500)}")
        }

        override fun error(info: String) {
            AppLogger.e(TAG, "[脚本] ${info.take(500)}")
        }
    }

    private companion object {
        const val TAG = "UserApi"
        const val PRELOAD_ASSET = "script/user-api-preload.js"
        const val DEFAULT_REQUEST_TIMEOUT_MS = 13_000L
        const val INITED_TIMEOUT_MS = 15_000L
        const val SCRIPT_REQUEST_TIMEOUT_MS = 20_000L
        const val FORM_MEDIA = "application/x-www-form-urlencoded"
        const val JSON_MEDIA = "application/json"
        const val SCRIPT_UA =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36"

        @Volatile
        private var quickJsLoaded = false
    }
}
