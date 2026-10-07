package com.dpmusic.app.core.net

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * B 站 **WBI 签名**（独立抽出，便于用「已实测可用的参考值」交叉校验）。
 *
 * 算法（官方 `mixinKeyEncTab`）：
 * 1. `mixinKey = (imgKey + subKey)` 按 [MIXIN_TAB] 重排后取前 32 字符；
 * 2. 请求参数加入 `wts`（秒级时间戳），按键名**升序**拼接为 query 串
 *    （值需过滤 `!'()*` 五个字符）；
 * 3. `w_rid = md5(query + mixinKey)`。
 *
 * ⚠️ 这里每个细节都不能想当然：过滤字符、排序方向、拼接后取前 32，任一处写错都会
 * 得到 `code: -403`（权限不足），而**错误信息完全不提示**是哪一步错了。
 * 因此 [WbiSignerTest] 用固定输入 + 固定 `wts` 钉住输出。
 */
internal object WbiSigner {

    /** 官方混入表（64 位；重排后取前 32 字符即为 mixinKey） */
    private val MIXIN_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    /** `imgKey + subKey` → `mixinKey`（按 [MIXIN_TAB] 重排，取前 32 字符） */
    fun mixinKeyOf(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        val sb = StringBuilder()
        MIXIN_TAB.forEach { i -> if (i < raw.length) sb.append(raw[i]) }
        return sb.toString().take(32)
    }

    /**
     * 生成已签名的 query string（含 `wts` 与 `w_rid`，按键名升序）。
     *
     * @param params 业务参数（不含 `wts` / `w_rid`）
     * @param wts 秒级时间戳（**显式传入**，便于测试确定性）
     */
    fun sign(params: Map<String, String>, mixinKey: String, wts: Long): String {
        val all = params.toMutableMap().apply { put("wts", wts.toString()) }
        val query = all.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "$k=${urlEncode(v.filterNot { it in FILTER_CHARS })}" }
        val rid = md5(query + mixinKey)
        all["w_rid"] = rid
        return all.entries.sortedBy { it.key }.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
    }

    /** 需要从参数值里过滤掉的字符（官方要求） */
    private const val FILTER_CHARS = "!'()*"

    fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}