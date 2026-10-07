package com.dpmusic.app.core.net

import java.security.MessageDigest

/**
 * 酷狗概念版（`com.kugou.android.lite`）请求签名与协议常量。
 *
 * 算法来源：逆向 APK 5.2.9 + 两份真实抓包逐字节复算命中（见工作区
 * `工作区/music api大全/酷狗概念版/` 的逆向报告与 `kglite_sign.py`）。
 *
 * ```
 * signature = md5( salt + Σ(按 key 排序的 "k=v" 直接拼接) + body + salt )
 * ```
 * - `body` 必须是**最终发出的原始 JSON 字节串**（字段顺序/空白敏感）；GET 请求传空串。
 * - `signature` 自身不参与计算。
 *
 * **盐有两套（实测确认，不可混用）**：
 * - [SALT_GENERAL]：dex 明文盐，老接口（hot_tab 等）。可复现 `KgApi.kt` 里写死的 `ee44edb9…`。
 * - [SALT_TRACKER]：配置下发盐（`listen.usersdkparam.appkey`），`/v5/url` 等新一代接口。
 *   两套盐均可从配置接口动态拉取，但默认值经线上校验**未轮换**，直接用默认即可。
 *
 * 参数常量（[APPID]/[CLIENTVER]/[PID]/[PIDVERSION]）同样来自 APK `res/aa/gconfig`，
 * 与线上配置一致；酷狗发版后可能变化，故 [KgLiteCreds] 允许用户在设置页覆盖。
 */
object KgSign {

    /** 通用盐（老接口；与 `KgApi.hotSearch()` 写死值同源，已复现验证） */
    const val SALT_GENERAL = "OIlwieks28dk2k092lksi2UIkp"

    /** tracker / 取播放地址盐（配置键 `listen.usersdkparam.appkey`） */
    const val SALT_TRACKER = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"

    /** `/v5/url` 的 `key` 参数专用分片密钥（配置键 `tracker_pidversion_sercret`） */
    const val TRACKER_SECRET = "185672dd44712f60bb1736df5a377e82"

    const val APPID = "3116"

    /** 通用 lite clientver（登录 / 账号类接口；对应参考实现 `default_params`） */
    const val CLIENTVER = "11440"

    /** `/v5/url` 播放地址接口专用 clientver（与 `version` 同源，实测有效值） */
    const val TRACKER_CLIENTVER = "11430"

    /** tracker pid / pidversion（缺失会被服务端判 `illegal pid or pidversion`） */
    const val PID = "411"
    const val PIDVERSION = "3001"

    /** 播放地址接口里的固定页面标识（实测有效值） */
    const val PAGE_ID = "967177915"
    const val PPAGE_ID = "356753938,823673182,967485191"

    /** 客户端 UA（搜索/tracker 等接口缺它会返回 `152 Parameter Error`） */
    const val UA = "Android13-1070-10566-201-0-ReportPlaySongToServerProtocol-wifi"

    /** 酷狗客户端通用签名头，缺了多数接口报错 */
    val STD_HEADERS = mapOf(
        "kg-rc" to "1",
        "kg-thash" to "5d816a0",
        "kg-rec" to "1",
        "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
    )

    fun md5(text: String): String = md5(text.toByteArray(Charsets.UTF_8))

    fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * 标准 Android 签名。
     * @param params 不含 `signature` 自身的参数表（值须为最终编码形态）
     * @param body   最终发出的原始 body 字符串；GET 传空串
     * @param salt   盐（默认 [SALT_TRACKER]；老接口传 [SALT_GENERAL]）
     */
    fun sign(params: Map<String, String>, body: String = "", salt: String = SALT_TRACKER): String {
        val joined = params.entries.sortedBy { it.key }.joinToString("") { "${it.key}=${it.value}" }
        return md5(salt + joined + body + salt)
    }

    /** `key = md5(hash + SECRET + appid + mid + userid)`（`/v5/url` 专用） */
    fun trackerKey(hash: String, mid: String, userId: String = "0", appId: String = APPID): String =
        md5(hash.lowercase() + TRACKER_SECRET + appId + mid + (userId.ifBlank { "0" }))

    /**
     * `key = md5(appid + appkey + clientver + clienttime_ms)`（登录封套 `key` 字段专用）。
     * 注意用**毫秒**时间戳（秒级会算错，逆向报告 §14.2 定论）。
     */
    fun paramsKey(clientTimeMs: String, appId: String = APPID, clientVer: String = CLIENTVER): String =
        md5(appId + SALT_TRACKER + clientVer + clientTimeMs)
}
