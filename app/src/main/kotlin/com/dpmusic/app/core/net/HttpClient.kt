package com.dpmusic.app.core.net

import com.dpmusic.app.core.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** HTTP 层异常（携带状态码便于上层判断 4xx/5xx） */
class HttpException(val code: Int, val url: String, message: String) : Exception(message)

/** HTTP 响应（含响应头） */
data class HttpResult(
    val body: String,
    val headers: Map<String, List<String>>,
)

/**
 * 轻量网络层：
 * - OkHttp 单例，统一 UA / 超时；
 * - 弱网自动重试（3 次指数退避）；
 * - 供三平台 API 与 LX 代理解析复用。
 */
object Http {

    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val BINARY_MEDIA = "application/octet-stream".toMediaType()

    suspend fun get(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        retry {
            val builder = Request.Builder().url(url).header("User-Agent", DEFAULT_UA)
            referer?.let { builder.header("Referer", it) }
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, url, "HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }
    }

    suspend fun postJson(
        url: String,
        jsonBody: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        retry {
            val builder = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(JSON_MEDIA))
                .header("User-Agent", DEFAULT_UA)
            referer?.let { builder.header("Referer", it) }
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, url, "HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }
    }

    /** 表单提交（application/x-www-form-urlencoded）：供听歌识曲 / 网易云 eapi 等接口复用（headers 可覆盖默认 UA） */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        retry {
            val body = FormBody.Builder()
                .apply { form.forEach { (k, v) -> add(k, v) } }
                .build()
            val builder = Request.Builder()
                .url(url)
                .post(body)
                .header("User-Agent", DEFAULT_UA)
            referer?.let { builder.header("Referer", it) }
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, url, "HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }
    }

    /** 二进制提交（application/octet-stream）：供酷狗识曲等接口复用（headers 可覆盖默认 UA） */
    suspend fun postBinary(
        url: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        retry {
            val builder = Request.Builder()
                .url(url)
                .post(body.toRequestBody(BINARY_MEDIA))
                .header("User-Agent", DEFAULT_UA)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, url, "HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }
    }

    /** 指数退避重试：350ms / 700ms */
    private suspend fun <T> retry(times: Int = 3, block: () -> T): T {
        var last: Exception? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (attempt < times - 1) {
                    AppLogger.w("Http", "请求失败，准备重试（${attempt + 1}/$times）：${e.message}")
                    delay(350L * (attempt + 1))
                }
            }
        }
        throw (last ?: IllegalStateException("request failed")).also {
            AppLogger.e("Http", "请求最终失败：${it.message}")
        }
    }
}