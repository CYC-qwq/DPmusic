package com.dpmusic.app.core.util

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256（十六进制小写）。
 *
 * 用于汽水 relay 的设备签名：`HMAC_SHA256(DEVICE_SECRET, "device." + ts + "." + path)`。
 * 该算法**与实现无关、与平台无关** —— 只要密钥 / 时间戳 / 路径相同，输出恒定且唯一。
 * 因此单测里可以用 RFC 4231 的官方测试向量钉住它，服务端也能独立复算。
 *
 * 故意不依赖 `android.util.Base64` 等 Android 类，保持**纯 JVM**，便于 JVM 单测直接覆盖。
 */
object HmacUtil {

    private const val ALGORITHM = "HmacSHA256"
    private const val HEX = "0123456789abcdef"

    /** 计算 HMAC-SHA256，返回 **64 位十六进制小写**字符串。 */
    fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHexLower()
    }

    /** 生成设备签名：`device.<ts>.<path>` */
    fun deviceSignature(secret: String, ts: Long, path: String): String =
        hmacSha256Hex(secret, "device.$ts.$path")

    private fun ByteArray.toHexLower(): String {
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}