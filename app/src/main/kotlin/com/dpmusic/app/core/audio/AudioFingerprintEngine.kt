package com.dpmusic.app.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebView 指纹引擎：承载官方 afp.wasm（内联于 assets/audiofp/index.html，与网易云服务端 bit 级一致）。
 *
 * 页面加载后暴露 `window.__fpEncode(reqId, pcmB64)`：
 * - 输入：预降采样的 8kHz Float32 PCM（Base64，小端）；
 * - 输出：6 秒窗口的 Base64 指纹（经 AndroidBridge.onResult 回传）。
 *
 * 注意：必须在主线程构造（Compose 组合内 remember 即可）；用完后调用 [destroy]。
 */
@SuppressLint("SetJavaScriptEnabled")
class AudioFingerprintEngine(context: Context) {

    private val ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val counter = AtomicInteger()

    /** 供 Compose AndroidView 挂载的 WebView（1dp 不可见，仅提供 JS 运行环境） */
    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        addJavascriptInterface(Bridge(), "AndroidBridge")
        loadUrl("file:///android_asset/audiofp/index.html")
    }

    /** 等待引擎就绪（页面加载 + wasm 实例化完成） */
    suspend fun awaitReady(timeoutMs: Long = 20_000) {
        withTimeout(timeoutMs) { ready.await() }
    }

    /**
     * 编码指纹：8kHz Float32 PCM → Base64 指纹（6 秒窗口）。
     * @throws IllegalStateException 引擎内部错误 / 超时。
     */
    suspend fun encode(pcm8k: FloatArray): String {
        awaitReady()
        val reqId = "r" + counter.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[reqId] = deferred
        val b64 = floatToBase64(pcm8k)
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript("window.__fpEncode('$reqId','$b64')", null)
        }
        return try {
            withTimeout(30_000) { deferred.await() }
        } finally {
            pending.remove(reqId)
        }
    }

    /** 销毁引擎：未完成的请求全部失败，WebView 释放 */
    fun destroy() {
        pending.values.forEach { it.completeExceptionally(IllegalStateException("engine destroyed")) }
        pending.clear()
        runCatching { webView.destroy() }
    }

    /** JS 回调桥（回调发生在 WebView 的 JS 线程，非主线程） */
    private inner class Bridge {
        @JavascriptInterface
        fun onReady() {
            ready.complete(Unit)
        }

        @JavascriptInterface
        fun onResult(reqId: String, encoded: String, error: String) {
            val deferred = pending.remove(reqId) ?: return
            if (error.isNotEmpty()) {
                deferred.completeExceptionally(IllegalStateException(error))
            } else {
                deferred.complete(encoded)
            }
        }
    }

    private companion object {
        /** Float32 小端数组 → Base64（与 JS `new Float32Array(buffer)` 字节序一致） */
        fun floatToBase64(data: FloatArray): String {
            val bytes = ByteArray(data.size * 4)
            var bi = 0
            data.forEach { f ->
                val bits = f.toRawBits()
                bytes[bi++] = (bits and 0xFF).toByte()
                bytes[bi++] = ((bits ushr 8) and 0xFF).toByte()
                bytes[bi++] = ((bits ushr 16) and 0xFF).toByte()
                bytes[bi++] = ((bits ushr 24) and 0xFF).toByte()
            }
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }
}