package com.dpmusic.app.core.script

import android.util.Base64
import com.dpmusic.app.core.util.AppLogger
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * RSA 加密工具（LX Music 音源脚本 lx.utils.crypto.rsaEncrypt 的原生实现）。
 *
 * - 待加密数据与 X.509 公钥均为 Base64 字符串；输出为 Base64（NO_WRAP）；
 * - 填充模式由脚本传入（常用 RSA/ECB/NoPadding、RSA/ECB/OAEPWithSHA1AndMGF1Padding）；
 * - 任何异常吞掉并返回空串（脚本侧以空串判定失败）。
 */
object RSA {

    private const val TAG = "UserApi"

    /** 使用公钥加密（decryptedBase64：待加密数据；publicKey：公钥 Base64；padding：填充模式） */
    fun encryptRSAToString(decryptedBase64: String, publicKey: String, padding: String): String = try {
        val keyFactory = KeyFactory.getInstance("RSA")
        val keySpec = X509EncodedKeySpec(Base64.decode(publicKey.trim().toByteArray(), Base64.DEFAULT))
        val key = keyFactory.generatePublic(keySpec)
        val cipher = Cipher.getInstance(padding)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encryptedBytes = cipher.doFinal(Base64.decode(decryptedBase64, Base64.DEFAULT))
        String(Base64.encode(encryptedBytes, Base64.NO_WRAP))
    } catch (e: Exception) {
        AppLogger.w(TAG, "rsa encrypt error: ${e.message}")
        ""
    }
}
