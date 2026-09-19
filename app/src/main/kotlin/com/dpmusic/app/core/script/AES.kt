package com.dpmusic.app.core.script

import android.util.Base64
import com.dpmusic.app.core.util.AppLogger
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES 加解密工具（LX Music 音源脚本 lx.utils.crypto.aesEncrypt 的原生实现）。
 *
 * 与 LX 移动端行为对齐：
 * - data / key / iv 均为 Base64 字符串；
 * - iv 为空串时走 ECB 模式，否则走 CBC 模式（iv 不足 16 字节时右侧补零）；
 * - 任何异常吞掉并返回空串（脚本侧以空串判定失败）。
 */
object AES {

    private const val TAG = "UserApi"

    private fun decodeBase64(data: String): ByteArray = Base64.decode(data, Base64.DEFAULT)

    private fun encodeBase64(data: ByteArray): String =
        String(Base64.encode(data, Base64.NO_WRAP), StandardCharsets.UTF_8)

    private fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray, mode: String): String = try {
        val cipher = Cipher.getInstance(mode)
        val secretKeySpec = SecretKeySpec(key, "AES")
        val finalIv = ByteArray(16)
        System.arraycopy(iv, 0, finalIv, 0, minOf(iv.size, 16))
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, IvParameterSpec(finalIv))
        encodeBase64(cipher.doFinal(data))
    } catch (e: Exception) {
        AppLogger.w(TAG, "aes encrypt error: ${e.message}")
        ""
    }

    private fun encrypt(data: ByteArray, key: ByteArray, mode: String): String = try {
        val cipher = Cipher.getInstance(mode)
        val secretKeySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec)
        encodeBase64(cipher.doFinal(data))
    } catch (e: Exception) {
        AppLogger.w(TAG, "aes encrypt error: ${e.message}")
        ""
    }

    /** 加密（脚本入口；iv 为空走 ECB） */
    fun encrypt(data: String, key: String, iv: String, mode: String): String =
        if (iv.isEmpty()) {
            encrypt(decodeBase64(data), decodeBase64(key), mode)
        } else {
            encrypt(decodeBase64(data), decodeBase64(key), decodeBase64(iv), mode)
        }
}
