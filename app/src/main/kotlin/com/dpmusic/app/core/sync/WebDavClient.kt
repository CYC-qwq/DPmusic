package com.dpmusic.app.core.sync

import com.dpmusic.app.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** WebDAV 连接配置 */
data class WebDavConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    val dirPath: String,
)

/** WebDAV 操作异常（message 为用户可读文案） */
class WebDavException(message: String) : Exception(message)

/**
 * 极简 WebDAV 客户端（OkHttp）：
 * - 同步目录自动逐级创建（MKCOL，兼容已存在 / 不支持递归创建的服务器）；
 * - 文本文件上传（PUT）/ 下载（GET，404 视为不存在）；
 * - Basic 认证；复用全局 Http.client。
 */
object WebDavClient {

    private val TEXT = "text/plain; charset=utf-8".toMediaType()

    /** 测试连接：确保同步目录可创建 / 可访问（同时验证认证与写入权限） */
    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        ensureDirectory(config)
    }

    /** 上传文本（自动创建父目录） */
    suspend fun upload(config: WebDavConfig, fileName: String, content: String) = withContext(Dispatchers.IO) {
        ensureDirectory(config)
        val request = Request.Builder()
            .url(resolveUrl(config, listOf(fileName)))
            .put(content.toRequestBody(TEXT))
            .applyAuth(config)
            .build()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw WebDavException("上传失败：HTTP ${resp.code}")
        }
    }

    /** 下载文本；文件不存在返回 null */
    suspend fun download(config: WebDavConfig, fileName: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(resolveUrl(config, listOf(fileName)))
            .get()
            .applyAuth(config)
            .build()
        Http.client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) throw WebDavException("下载失败：HTTP ${resp.code}")
            resp.body?.string()
        }
    }

    /* ---------------- 内部 ---------------- */

    private fun ensureDirectory(config: WebDavConfig) {
        val segments = config.dirPath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return
        val builder = baseBuilder(config)
        for (seg in segments) {
            builder.addPathSegment(seg)
            val request = Request.Builder()
                .url(builder.build())
                .method("MKCOL", null)
                .applyAuth(config)
                .build()
            Http.client.newCall(request).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 ->
                        throw WebDavException("认证失败或没有写入权限（HTTP ${resp.code}）")
                    resp.isSuccessful -> Unit // 201 / 200 / 204
                    resp.code == 301 || resp.code == 302 || resp.code == 405 -> Unit // 目录已存在
                    else -> throw WebDavException("创建目录失败：HTTP ${resp.code}")
                }
            }
        }
    }

    private fun resolveUrl(config: WebDavConfig, extra: List<String>): HttpUrl {
        val builder = baseBuilder(config)
        (config.dirPath.split('/').filter { it.isNotBlank() } + extra)
            .forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    private fun baseBuilder(config: WebDavConfig): HttpUrl.Builder {
        val raw = config.baseUrl.trim()
        if (raw.isEmpty()) throw WebDavException("未配置服务器地址")
        val normalized = if ("://" in raw) raw else "https://$raw"
        val url = normalized.toHttpUrlOrNull() ?: throw WebDavException("服务器地址格式不正确")
        return url.newBuilder()
    }

    private fun Request.Builder.applyAuth(config: WebDavConfig): Request.Builder = apply {
        if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
            header("Authorization", Credentials.basic(config.username, config.password))
        }
    }
}
