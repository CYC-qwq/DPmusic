package com.dpmusic.app.core.net

import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 酷狗概念版登录封套加密原语（移植自工作区 `kglite_login.py`，已用抓包样本复算验证）。
 *
 * - `params`：`AES-256-CBC` 加密，种子随机 16 位（字符池 `1234567890A-Z`，取小写）；
 *   `key = md5(seed)[0:32]`、`iv = md5(seed)[16:32]`（均为 **ASCII 字面量**，非 hex 解码）→ 输出 **大写 hex**。
 * - `pk`：`RSA/ECB/NoPadding`，明文左对齐零填充至模长后加密 → 输出 **大写 hex**。
 *
 * 服务端用 RSA 私钥解 `pk` 得到本次种子，再用它解 `params`。
 */
object KgLiteCrypto {

    /** 与参考实现同字符池（数字 + 大写字母），生成后整体转小写 */
    private const val SEED_POOL = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /**
     * 登录专用 RSA 公钥（1024-bit，APP 内嵌 `publicLiteRasKey`，来自 APK `gconfig`；
     * 与开源 lite 实现逐字节一致 → 排除密钥轮换）。
     */
    private const val LOGIN_PUBKEY_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"

    fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** 随机 16 位种子（大写字符池生成后转小写） */
    fun randomSeed(): String =
        buildString { repeat(16) { append(SEED_POOL.random()) } }.lowercase()

    /**
     * AES-256-CBC 加密（种子派生 key/iv）。
     * @return `hex 密文（大写）` 与本次 `seed`
     */
    fun aesEncryptSeeded(plaintext: String, seed: String = randomSeed()): Pair<String, String> {
        val h = md5Hex(seed)
        val key = SecretKeySpec(h.substring(0, 32).toByteArray(Charsets.US_ASCII), "AES")
        val iv = IvParameterSpec(h.substring(16, 32).toByteArray(Charsets.US_ASCII))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        val hex = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        return hex to seed
    }

    /** AES-256-CBC 解密（`secu_params` → token 信息）；失败返回 null */
    fun aesDecryptSeeded(cipherHex: String, seed: String): String? = runCatching {
        val h = md5Hex(seed)
        val key = SecretKeySpec(h.substring(0, 32).toByteArray(Charsets.US_ASCII), "AES")
        val iv = IvParameterSpec(h.substring(16, 32).toByteArray(Charsets.US_ASCII))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        val raw = cipher.doFinal(cipherHex.hexToBytes())
        raw.toString(Charsets.UTF_8)
    }.getOrNull()

    /** RSA/ECB/NoPadding 裸加密：明文左对齐零填充至模长 → 大写 hex */
    fun rsaRawEncrypt(plaintext: String): String {
        val keyFactory = KeyFactory.getInstance("RSA")
        val pub = keyFactory.generatePublic(
            X509EncodedKeySpec(Base64.decode(LOGIN_PUBKEY_B64, Base64.DEFAULT)),
        ) as RSAPublicKey
        val blockSize = (pub.modulus.bitLength() + 7) / 8
        val data = plaintext.toByteArray(Charsets.UTF_8)
        require(data.size <= blockSize) { "RSA 明文超出模长" }
        val padded = ByteArray(blockSize)
        System.arraycopy(data, 0, padded, 0, data.size)
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, pub)
        return cipher.doFinal(padded).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
