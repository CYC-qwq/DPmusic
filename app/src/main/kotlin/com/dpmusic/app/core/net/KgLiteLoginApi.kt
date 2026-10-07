package com.dpmusic.app.core.net

import com.dpmusic.app.core.util.AppLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 短信下发结果 */
data class KgLiteSmsResult(val ok: Boolean, val message: String)

/** 验证码登录结果（成功时携带登录态） */
data class KgLiteLoginResult(
    val ok: Boolean,
    val message: String,
    val token: String = "",
    val userId: String = "",
    val nickname: String = "",
    val vipType: Int = 0,
)

/**
 * 酷狗概念版**手机号 + 验证码登录**（无需人机验证）。
 *
 * 依据工作区逆向成果（`kglite_login.py` / 逆向报告 §15）与本轮 DPmusic 侧实测：
 * ```
 * ① POST http://login.user.kugou.com/v7/send_mobile_code
 *      body = {"businessid":5,"mobile":<11位>,"plat":3}
 * ② POST https://loginserviceretry.kugou.com/v7/login_by_verifycode
 *      query = {dfid, mid, uuid=-, appid, clientver, clienttime} + signature
 *      body  = {plat, support_multi, t1, t2, clienttime_ms, mobile(打码), key, pk, params, dfid, dev, gitversion}
 *      params = AES({mobile, code})；pk = RSA({clienttime_ms, key: 种子})
 *    → status=1 时用同一「种子」解密 data.secu_params 取登录 token
 * ```
 *
 * **关键实测坑（本轮定位）**：query 里 `mid` 必须**非空**——为空字符串时服务端返回
 * `20006 err signature`（误导为签名错，实为封套缺 mid）。mid 由
 * [com.dpmusic.app.core.data.KgLiteRepository] 首次启动随机生成并持久化。
 *
 * 实测返回码：`20020` = 验证码过期（协议通）、`20006` = mid 为空、
 * `20028` 人机验证**仅密码登录会出现**，验证码登录不触发。
 *
 * 合规说明：仅代发一次短信并校验用户主动输入的验证码；不代持密码、不做自动签到/领会员。
 */
class KgLiteLoginApi(
    private val deviceProvider: () -> KgLiteDevice,
    private val appIdProvider: () -> String = { KgSign.APPID },
) {

    /** 下发短信验证码（真实发送；调用方应做频率限制与用户确认） */
    suspend fun sendSmsCode(mobile: String): KgLiteSmsResult {
        val phone = mobile.trim()
        if (!PHONE_REGEX.matches(phone)) return KgLiteSmsResult(false, "请输入 11 位手机号")
        val device = deviceProvider()
        val mid = device.mid.ifBlank { return KgLiteSmsResult(false, "设备标识未就绪，请稍后重试") }

        val params = loginParams(device, mid)
        val body = """{"businessid":5,"mobile":"$phone","plat":3}"""
        val signature = KgSign.sign(params, body, KgSign.SALT_TRACKER)
        val query = buildQuery(params + ("signature" to signature))

        val raw = runCatching {
            Http.postJson(
                url = "$URL_SEND_CODE?$query",
                jsonBody = body,
                headers = loginHeaders(device, mid),
                retryOnFailure = false,
            )
        }.getOrElse {
            AppLogger.w(TAG, "下发验证码失败：${it.message}")
            return KgLiteSmsResult(false, "网络异常，请稍后重试")
        }
        val json = runCatching { parseJsonPayload(raw) }.getOrNull()
        val status = json.int("status") ?: 0
        val errCode = json.int("error_code") ?: 0
        return if (status == 1) {
            KgLiteSmsResult(true, "验证码已发送")
        } else {
            KgLiteSmsResult(false, msgForSendCode(errCode))
        }
    }

    /**
     * 验证码登录：成功后返回登录态（token / userid / 昵称 / VIP 位）。
     * 上层应再用 [KgLiteApi.loginStatus] 复核 token 有效。
     */
    suspend fun loginByCode(mobile: String, code: String): KgLiteLoginResult {
        val phone = mobile.trim()
        val smsCode = code.trim()
        if (!PHONE_REGEX.matches(phone)) return KgLiteLoginResult(false, "请输入 11 位手机号")
        if (smsCode.isBlank()) return KgLiteLoginResult(false, "请输入验证码")
        val device = deviceProvider()
        val mid = device.mid.ifBlank { return KgLiteLoginResult(false, "设备标识未就绪，请稍后重试") }

        val clientTimeMs = System.currentTimeMillis()
        val (encParams, seed) = KgLiteCrypto.aesEncryptSeeded(
            """{"mobile":"$phone","code":"$smsCode"}""",
        )
        val pk = KgLiteCrypto.rsaRawEncrypt(
            """{"clienttime_ms":$clientTimeMs,"key":"$seed"}""",
        )
        val body = buildString {
            append("""{"plat":1,"support_multi":1,"t1":""").append(quote(T1_DEFAULT))
            append(""","t2":""").append(quote(T2_DEFAULT))
            append(""","clienttime_ms":$clientTimeMs""")
            append(""","mobile":""").append(quote(maskPhone(phone)))
            append(""","key":""").append(quote(KgSign.paramsKey(clientTimeMs.toString())))
            append(""","pk":""").append(quote(pk))
            append(""","params":""").append(quote(encParams))
            append(""","dfid":""").append(quote(device.dfid.ifBlank { KgLiteDevice.DEFAULT_DFID }))
            append(""","dev":""").append(quote(deviceModel()))
            append(""","gitversion":""").append(quote(GIT_VERSION))
            append(""","busi_type":"concept","opt_product_types":"dvip,qvip,wvip,evip"""")
            append("}")
        }

        val params = loginParams(device, mid)
        val signature = KgSign.sign(params, body, KgSign.SALT_TRACKER)
        val query = buildQuery(params + ("signature" to signature))

        val raw = runCatching {
            Http.postJson(
                url = "$URL_LOGIN_BY_CODE?$query",
                jsonBody = body,
                headers = loginHeaders(device, mid),
            )
        }.getOrElse {
            AppLogger.w(TAG, "验证码登录失败：${it.message}")
            return KgLiteLoginResult(false, "网络异常，请稍后重试")
        }
        val json = runCatching { parseJsonPayload(raw) }.getOrNull()
        val status = json.int("status") ?: 0
        val errCode = json.int("error_code") ?: 0
        if (status != 1) return KgLiteLoginResult(false, msgForLogin(errCode))

        val data = json.objOrNull("data") ?: json
        val userId = data.str("userid").orEmpty()
        val nickname = data.str("nickname") ?: data.str("username").orEmpty()
        val vipType = data.int("vip_type") ?: data.int("is_vip") ?: 0

        // token 优先级：secu_params 解密结果 → 明文 token → t1
        val token = decryptToken(data.str("secu_params"), seed)
            ?: data.str("token")?.takeIf { it.isNotBlank() }
            ?: data.str("t1")?.takeIf { it.isNotBlank() }
            ?: return KgLiteLoginResult(false, "登录成功但未取到 token，请反馈")

        return KgLiteLoginResult(
            ok = true,
            message = "登录成功",
            token = token,
            userId = userId,
            nickname = nickname.ifBlank { "酷狗用户" },
            vipType = vipType,
        )
    }

    /** 解密 `secu_params`：可能直接是 token 字符串，也可能是含 token/userid 的 JSON */
    private fun decryptToken(secuParams: String?, seed: String): String? {
        val hex = secuParams?.takeIf { it.isNotBlank() } ?: return null
        val plain = KgLiteCrypto.aesDecryptSeeded(hex, seed) ?: return null
        val trimmed = plain.trim()
        if (!trimmed.startsWith("{")) return trimmed.takeIf { it.isNotBlank() }
        val obj = runCatching { parseJsonPayload(trimmed) }.getOrNull() as? JsonObject
        return (obj?.get("token") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("t") as? JsonPrimitive)?.contentOrNull
    }

    private fun loginParams(device: KgLiteDevice, mid: String): LinkedHashMap<String, String> = linkedMapOf(
        "dfid" to device.dfid.ifBlank { KgLiteDevice.DEFAULT_DFID },
        "mid" to mid,
        "uuid" to "-",
        "appid" to appIdProvider(),
        "clientver" to KgSign.CLIENTVER,
        "clienttime" to (System.currentTimeMillis() / 1000).toString(),
    )

    private fun loginHeaders(device: KgLiteDevice, mid: String): Map<String, String> =
        KgSign.STD_HEADERS + mapOf(
            "User-Agent" to UA_LOGIN,
            "support-calm" to "1",
            "dfid" to device.dfid.ifBlank { KgLiteDevice.DEFAULT_DFID },
            "mid" to mid,
            "clienttime" to (System.currentTimeMillis() / 1000).toString(),
        )

    private fun buildQuery(params: Map<String, String>): String =
        params.entries.sortedBy { it.key }.joinToString("&") { "${urlEnc(it.key)}=${urlEnc(it.value)}" }

    private fun quote(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""

    private fun maskPhone(phone: String): String =
        if (phone.length >= 11) phone.take(3) + "*****" + phone.takeLast(3) else phone

    private fun deviceModel(): String = android.os.Build.MODEL.orEmpty().ifBlank { "DPI" }

    private fun msgForSendCode(errCode: Int): String = when (errCode) {
        20006 -> "设备标识异常，请稍后重试"
        20028 -> "操作过于频繁，请稍后再试"
        else -> "验证码下发失败（错误码 $errCode）"
    }

    private fun msgForLogin(errCode: Int): String = when (errCode) {
        20006 -> "设备标识异常，请稍后重试"
        20020 -> "验证码已过期，请重新获取"
        20028 -> "需要人机验证，请稍后再试"
        34175 -> "该手机号绑定多个账号，暂不支持"
        else -> "登录失败（错误码 $errCode）"
    }

    private companion object {
        const val TAG = "KgLiteLogin"
        const val URL_SEND_CODE = "http://login.user.kugou.com/v7/send_mobile_code"
        const val URL_LOGIN_BY_CODE = "https://loginserviceretry.kugou.com/v7/login_by_verifycode"
        const val UA_LOGIN = "Android16-1070-11440-130-0-LOGIN-wifi"

        /** 抓包样本中的固定设备绑定串（t1/t2）；服务端不校验其时效，实测 20020 可达 */
        const val T1_DEFAULT = "5088a6ba5bf17d6bab5734f35b0f039e"
        const val T2_DEFAULT =
            "7ff17f60e257cdaf93aa11b36097680df64b0a629ac51aba682ae379bd14b12c23" +
                "652ce4975db1dce2a2fd417c900fbaf158ab9e511eb5ac7b6cf7b6cd0607e92826f" +
                "574c15bdf5e1af3d49a856870c4667382c56938a128d93c2a86edf0e88c510849928" +
                "35668c2d7413ee11ec69eeb"
        const val GIT_VERSION = "5f0b7c4"

        val PHONE_REGEX = Regex("^1\\d{10}$")
    }
}