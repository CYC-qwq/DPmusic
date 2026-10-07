package com.dpmusic.app.core.net

import com.dpmusic.app.core.util.AppLogger

/** 签到 / 领 VIP 结果 */
data class KgLiteClaimResult(
    val ok: Boolean,
    val message: String,
    /** 今日已领过（幂等，非失败） */
    val alreadyDone: Boolean = false,
    /** 累计已领免费 VIP 天数（服务端返回时填充） */
    val vipDays: String = "",
)

/**
 * 酷狗概念版「每日领 VIP」。
 *
 * 依据工作区逆向成果（逆向报告 §15.5 / `kglite_login.py`），四个接口同属一套
 * Android 签名封套（配置盐 `LnT6…`），**均需已登录状态**（匿名返 `20001/20002`）：
 *
 * ```
 * 听歌领 VIP  POST /youth/v2/report/listen_song   body {mixsongid}
 *              → status:1 成功；error_code:130012 今日已领
 * 看广告领 VIP POST /youth/v1/ad/play_report       body {ad_id, play_start, play_end}
 *              → status:1 成功；error_code:30002 次数用光
 * 任务查询    /youth/v1/free_package/get_vip_task
 *              → vip_signin / vip_morning / daily_vip / total_freemod_vip_days_received
 * ```
 *
 * ⚠️ **合规与风险提示**：本功能按用户明确要求实现，属**自动化账号操作**，
 * 可能违反酷狗用户协议并触发风控（限制登录 / 封号）。仅在用户已登录且主动开启时执行。
 */
class KgLiteClaimApi(
    private val deviceProvider: () -> KgLiteDevice,
    private val appIdProvider: () -> String = { KgSign.APPID },
) {

    /** 是否具备领取条件（已登录且拿到 token） */
    fun canClaim(): Boolean = deviceProvider().let { it.token.isNotBlank() }

    /** 查询 VIP 任务状态（签到 / 晨间 / 日更 / 累计天数） */
    suspend fun fetchVipTasks(): KgLiteClaimResult {
        val device = deviceProvider()
        if (device.token.isBlank()) return notLoggedIn()

        val clientTime = System.currentTimeMillis() / 1000
        val params = buildParams(device, clientTime, clientVer = KgSign.CLIENTVER)
        val body = ""
        params["signature"] = KgSign.sign(params, body, KgSign.SALT_TRACKER)
        val query = buildQuery(params)

        val raw = runCatching {
            Http.get(
                url = "$URL_GATEWAY$PATH_VIP_TASK?$query",
                referer = "https://www.kugou.com/",
                headers = buildHeaders(device, clientTime),
            )
        }.getOrElse {
            AppLogger.w(TAG, "查询 VIP 任务失败：${it.message}")
            return KgLiteClaimResult(false, "网络异常，请稍后重试")
        }
        val json = runCatching { parseJsonPayload(raw) }.getOrNull()
        val status = json.int("status") ?: 0
        if (status != 1) return KgLiteClaimResult(false, msgFor(json.int("error_code") ?: 0))

        val data = json.objOrNull("data") ?: json
        val days = data.str("total_freemod_vip_days_received").orEmpty()
        return KgLiteClaimResult(true, "已累计领取 $days 天", vipDays = days)
    }

    /**
     * 一键领取：听歌领 VIP + 看广告领 VIP（顺序执行，聚合结果）。
     * 幂等：今日已领 / 次数用光均视为成功态，不报错。
     */
    suspend fun claimDaily(): KgLiteClaimResult {
        if (!canClaim()) return notLoggedIn()
        val parts = mutableListOf<String>()
        var anyOk = false

        claimListenSong().let {
            if (it.ok) anyOk = true
            parts += it.message
        }
        claimAdVip().let {
            if (it.ok) anyOk = true
            parts += it.message
        }

        val days = fetchVipTasks().vipDays
        val tail = if (days.isNotBlank()) "（累计 $days 天）" else ""
        return KgLiteClaimResult(anyOk, parts.joinToString("；") + tail, vipDays = days)
    }

    /** 听歌领 VIP */
    suspend fun claimListenSong(): KgLiteClaimResult = claim(
        path = PATH_LISTEN_SONG,
        clientVer = LISTEN_CLIENTVER,
        ua = UA_LISTEN,
        body = """{"mixsongid":$DEFAULT_MIXSONGID}""",
        alreadyCodes = setOf(130012),
        alreadyMsg = "今日听歌奖励已领",
        okMsg = "听歌奖励领取成功",
    )

    /** 看广告领 VIP（每次间隔 30s、每日上限 8 次） */
    suspend fun claimAdVip(): KgLiteClaimResult {
        val now = System.currentTimeMillis()
        val body = """{"ad_id":$DEFAULT_AD_ID,"play_start":${now - 30_000},"play_end":$now}"""
        return claim(
            path = PATH_AD_REPORT,
            clientVer = KgSign.CLIENTVER,
            ua = KgSign.UA,
            body = body,
            alreadyCodes = setOf(30002),
            alreadyMsg = "今日广告奖励次数已用尽",
            okMsg = "广告奖励领取成功",
        )
    }

    /** 通用领取请求 */
    private suspend fun claim(
        path: String,
        clientVer: String,
        ua: String,
        body: String,
        alreadyCodes: Set<Int>,
        alreadyMsg: String,
        okMsg: String,
    ): KgLiteClaimResult {
        val device = deviceProvider()
        if (device.token.isBlank()) return notLoggedIn()

        val clientTime = System.currentTimeMillis() / 1000
        val params = buildParams(device, clientTime, clientVer)
        params["signature"] = KgSign.sign(params, body, KgSign.SALT_TRACKER)
        val query = buildQuery(params)

        val raw = runCatching {
            Http.postJson(
                url = "$URL_GATEWAY$path?$query",
                jsonBody = body,
                headers = buildHeaders(device, clientTime) + mapOf(
                    "User-Agent" to ua,
                    "content-type" to "application/json; charset=utf-8",
                ),
                retryOnFailure = false,
            )
        }.getOrElse {
            AppLogger.w(TAG, "领取请求失败（$path）：${it.message}")
            return KgLiteClaimResult(false, "网络异常，请稍后重试")
        }
        val json = runCatching { parseJsonPayload(raw) }.getOrNull()
        val status = json.int("status") ?: 0
        val code = json.int("error_code") ?: 0
        return when {
            status == 1 -> KgLiteClaimResult(true, okMsg)
            code in alreadyCodes -> KgLiteClaimResult(true, alreadyMsg, alreadyDone = true)
            else -> KgLiteClaimResult(false, msgFor(code))
        }
    }

    private fun buildParams(
        device: KgLiteDevice,
        clientTime: Long,
        clientVer: String,
    ): LinkedHashMap<String, String> = linkedMapOf(
        "dfid" to device.dfid.ifBlank { KgLiteDevice.DEFAULT_DFID },
        "mid" to device.mid.ifBlank { "0" },
        "uuid" to "-",
        "appid" to appIdProvider(),
        "clientver" to clientVer,
        "clienttime" to clientTime.toString(),
        "timestrap" to (System.currentTimeMillis()).toString(),
    ).apply {
        if (device.token.isNotBlank()) put("token", device.token)
        if (device.userId.isNotBlank() && device.userId != "0") put("userid", device.userId)
    }

    private fun buildHeaders(device: KgLiteDevice, clientTime: Long): Map<String, String> =
        KgSign.STD_HEADERS + mapOf(
            "User-Agent" to KgSign.UA,
            "dfid" to device.dfid.ifBlank { KgLiteDevice.DEFAULT_DFID },
            "mid" to device.mid.ifBlank { "0" },
            "clienttime" to clientTime.toString(),
        )

    private fun buildQuery(params: Map<String, String>): String =
        params.entries.sortedBy { it.key }.joinToString("&") { "${urlEnc(it.key)}=${urlEnc(it.value)}" }

    private fun notLoggedIn() = KgLiteClaimResult(false, "请先用手机号登录概念版账号")

    private fun msgFor(errCode: Int): String = when (errCode) {
        20001, 20002 -> "登录态无效或已过期，请重新登录"
        20006 -> "请求签名异常，请稍后重试"
        20028 -> "操作过于频繁，请稍后再试"
        else -> "领取失败（错误码 $errCode）"
    }

    private companion object {
        const val TAG = "KgLiteClaim"
        const val URL_GATEWAY = "https://gateway.kugou.com"
        const val PATH_LISTEN_SONG = "/youth/v2/report/listen_song"
        const val PATH_AD_REPORT = "/youth/v1/ad/play_report"
        const val PATH_VIP_TASK = "/youth/v1/free_package/get_vip_task"

        /** 听歌领 VIP 专用 clientver 与 UA（与其它接口不同，缺失会被判参数错） */
        const val LISTEN_CLIENTVER = "10566"
        const val UA_LISTEN = "Android13-1070-10566-201-0-ReportPlaySongToServerProtocol-wifi"

        const val DEFAULT_MIXSONGID = 666075191L
        const val DEFAULT_AD_ID = 12307537187L
    }
}