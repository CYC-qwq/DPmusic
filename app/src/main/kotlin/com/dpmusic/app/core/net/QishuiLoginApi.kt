package com.dpmusic.app.core.net

import com.dpmusic.app.core.util.AppLogger
import java.net.URLEncoder

/** 扫码登录：二维码信息 */
data class QishuiQr(
    val token: String,
    /** `data:image/png;base64,...`，可直接解码为 Bitmap 展示 */
    val qrcodeDataUrl: String,
    val expireTime: Long,
    val webName: String,
    /** 首次请求下发的 `passport_csrf_token`，轮询时需回带 */
    val csrf: String,
)

/** 扫码登录：轮询状态 */
data class QishuiQrStatus(
    /** new=待扫码；2/confirmed=已确认；3/expired/canceled=失效 */
    val status: String,
    /** 非空即登录成功 */
    val sessionid: String,
    val message: String = "",
)

/**
 * 汽水音乐**扫码登录**客户端（无需短信 / 人机验证）。
 *
 * 实测流程：
 * ```
 * ① GET  https://api.qishui.com/passport/web/get_qrcode/
 *      → data.token / data.qrcode(PNG base64) / data.expire_time；Set-Cookie: passport_csrf_token
 * ② POST https://api.qishui.com/passport/web/check_qrconnect/?…
 *      body = {token, …}，Cookie = passport_csrf_token
 *      → data.status（未扫=new）；成功时 Set-Cookie: sessionid
 * ③ GET  /luna/pc/me（带 sessionid）校验登录态
 * ```
 *
 * 约束：二维码须用**已登录的「抖音 APP」**扫码确认（服务端文案明示）。
 * 合规：仅实现"登录用户本人账号"；不含短信通道、不含任何活动/权益写接口。
 */
class QishuiLoginApi {

    /** ① 生成登录二维码 */
    suspend fun getQrcode(): QishuiQr {
        val q = linkedMapOf(
            "passport_jssdk_version" to "2.4.13",
            "passport_jssdk_type" to "normal",
            "is_from_ttaccountsdk" to "1",
            "aid" to QishuiApi.AID,
            "next" to QishuiApi.PC_HOST,
            "need_logo" to "false",
            "need_short_url" to "false",
            "is_new_login" to "1",
        )
        val url = "${QishuiApi.PC_HOST}/passport/web/get_qrcode/?${qs(q)}"
        val res = Http.getWithHeaders(url)
        val json = parseJsonPayload(res.body)
        val data = json.objOrNull("data") ?: json
        val qr = QishuiQr(
            token = data.str("token").orEmpty(),
            qrcodeDataUrl = data.str("qrcode").orEmpty(),
            expireTime = data.long("expire_time") ?: 0L,
            webName = data.str("web_name").orEmpty(),
            csrf = pickSetCookie(res.headers, "passport_csrf_token"),
        )
        if (qr.token.isBlank()) AppLogger.w(TAG, "未取到登录 token：${res.body.take(160)}")
        return qr
    }

    /** ② 轮询扫码状态；返回的 [QishuiQrStatus.sessionid] 非空即成功 */
    suspend fun checkQr(token: String, csrf: String): QishuiQrStatus {
        val q = linkedMapOf(
            "passport_jssdk_version" to "2.4.13",
            "passport_jssdk_type" to "normal",
            "is_from_ttaccountsdk" to "1",
            "aid" to QishuiApi.AID,
            "iid" to QishuiApi.IID,
        )
        val form = linkedMapOf(
            "need_logo" to "false",
            "need_short_url" to "false",
            "is_frontier" to "true",
            "token" to token,
            "is_new_login" to "1",
            "next" to QishuiApi.PC_HOST,
        )
        val url = "${QishuiApi.PC_HOST}/passport/web/check_qrconnect/?${qs(q)}"
        val headers = if (csrf.isNotBlank()) mapOf("Cookie" to csrf) else emptyMap()
        val res = Http.postFormWithHeaders(url, form, headers = headers)
        val json = runCatching { parseJsonPayload(res.body) }.getOrNull()
        val data = json?.objOrNull("data") ?: json
        val status = data?.str("status").orEmpty()
        val sid = pickSetCookie(res.headers, "sessionid").ifBlank { data?.str("sessionid").orEmpty() }
        return QishuiQrStatus(
            status = status,
            sessionid = sid,
            message = data?.str("description").orEmpty(),
        )
    }

    /** 从响应头（`Set-Cookie`）中提取指定 Cookie 值 */
    private fun pickSetCookie(headers: Map<String, List<String>>, name: String): String {
        val all = headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
        val re = Regex("(?:^|[;,]\\s*)$name=([^;,\\s]+)")
        for (c in all) {
            val m = re.find(c)
            if (m != null) return m.groupValues[1]
        }
        return ""
    }

    private fun qs(m: Map<String, String>): String =
        m.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val TAG = "QishuiLogin"
    }
}
