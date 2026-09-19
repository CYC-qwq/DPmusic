package com.dpmusic.app.core.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 eapi 协议底层（本机实测验证通过）：
 * - 请求体 = AES-ECB(密钥 e82ckenh8dichen8, PKCS7, 输出大写 Hex)；
 * - 签名 = MD5("nobody{uri}use{text}md5forencrypt")（小写 hex）；
 * - 待加密串 = {uri}-36cd479b6b5-{text}-36cd479b6b5-{签名}；
 * - Cookie 请求头由 header 对象（含 MUSIC_U）以 encodeURIComponent 重建。
 */
object NcmEapi {

    /** eapi 客户端 UA（与实测环境一致） */
    const val EAPI_UA = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"

    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val SEP = "-36cd479b6b5-"
    private const val DEFAULT_OSVER = "Microsoft-Windows-10-Professional-build-19045-64bit"
    private const val DEFAULT_APPVER = "3.1.17.204416"

    /** MD5 → 小写 hex（与服务端校验格式一致） */
    fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** AES-ECB/PKCS7 → 大写 hex */
    fun aesEcbHex(text: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
    }

    /** JS encodeURIComponent 等价实现（Cookie 值编码用） */
    fun encodeUriComponent(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.!~*'()") {
                sb.append(c)
            } else {
                sb.append('%').append("%02X".format(b))
            }
        }
        return sb.toString()
    }

    /**
     * 解析用户粘贴的 Cookie 字符串。
     * 兼容两种形态：原生 `k=v; k=v` 与整串被 URL 编码（含 %3B / %3D）的形态（解码一次）。
     */
    fun parseCookie(raw: String): Map<String, String> {
        val text = if (raw.contains("%3D", true) || raw.contains("%3B", true)) {
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        } else {
            raw
        }
        val map = LinkedHashMap<String, String>()
        text.split(';').forEach { part ->
            val p = part.trim()
            val i = p.indexOf('=')
            if (i > 0) map[p.substring(0, i).trim()] = p.substring(i + 1).trim()
        }
        return map
    }

    /** 构建 eapi 请求 header 对象（参与加密；Cookie 请求头也由它重建） */
    fun buildHeader(cookies: Map<String, String>, deviceId: String): JsonObject = buildJsonObject {
        put("osver", cookies["osver"] ?: DEFAULT_OSVER)
        put("deviceId", cookies["deviceId"] ?: deviceId)
        put("os", cookies["os"] ?: "pc")
        put("appver", cookies["appver"] ?: DEFAULT_APPVER)
        put("versioncode", "140")
        put("mobilename", "")
        put("buildver", (System.currentTimeMillis() / 1000).toString())
        put("resolution", "1920x1080")
        put("__csrf", cookies["__csrf"].orEmpty())
        put("channel", cookies["channel"] ?: "netease")
        put("requestId", requestId())
        cookies["MUSIC_U"]?.let { put("MUSIC_U", it) }
        cookies["NMTID"]?.let { put("NMTID", it) }
    }

    /** 由 header 对象重建 Cookie 请求头（encodeURIComponent 拼接） */
    fun cookieHeader(header: JsonObject): String =
        header.entries.joinToString("; ") { (k, v) ->
            "${encodeUriComponent(k)}=${encodeUriComponent((v as? JsonPrimitive)?.contentOrNull.orEmpty())}"
        }

    /** 生成 eapi params 密文（对完整 payload 签名并加密） */
    fun buildParams(uri: String, payload: JsonObject): String {
        val text = payload.toString()
        val digest = md5Hex("nobody" + uri + "use" + text + "md5forencrypt")
        return aesEcbHex(uri + SEP + text + SEP + digest)
    }

    private fun requestId(): String =
        "${System.currentTimeMillis()}_${(0..9999).random().toString().padStart(4, '0')}"
}