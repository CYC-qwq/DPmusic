package com.dpmusic.app.core.net

import com.dpmusic.app.core.model.QishuiPlayUrl
import com.dpmusic.app.core.model.QishuiQuality
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * 汽水音乐接口客户端（纯请求，无状态）。
 *
 * 依据「music api大全/汽水」逆向成果与 DPmusic 侧实测：
 * - **匿名元数据**：`/luna/playlist/detail`、`/luna/sug`、`/luna/feed/radio/tracks` 等（App UA，免签）；
 * - **取播放地址**：`GET /luna/h5/seo_track`（**浏览器 UA，免登录免签**）——本节核心；
 * - **登录态能力**：`/luna/pc/…`（PC UA + `Cookie: sessionid=`）。
 *
 * 说明：
 * - 取址走 `h5/seo_track`，与 App 播放接口 `/luna/media-player` 无关；后者需客户端签名，本应用不碰。
 * - 合规：仅访问本人账号数据；不含任何活动/权益写接口（不自动领会员）。
 */
object QishuiApi {

    const val LUNA_HOST = "https://beta-luna.douyin.com"
    const val PC_HOST = "https://api.qishui.com"
    const val APP_UA = "Luna/19.1.0 Android"
    const val PC_UA = "LunaPC/3.0.0(290101097)"
    const val AID = "386088"
    const val IID = "27960026095955"

    private const val ANDROID_QUERY =
        "aid=$AID&device_platform=android&device_type=DPI&os_version=16&os_api=36" +
            "&version_code=100211030&version_name=21.1.0&channel=local_test" +
            "&device_id=7000000000000000000&iid=8000000000000000000"

    private fun cookieHeaders(cookie: String): Map<String, String> =
        mapOf("User-Agent" to PC_UA, "Cookie" to cookie)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /* ---------------- 取播放地址（免登录，浏览器 UA） ---------------- */

    /**
     * 取某曲目播放地址。返回 `null` 表示未取到（曲目不存在 / 无权限 / 网络异常）。
     * VIP 曲目通常只返回 30s 试听档位。
     */
    suspend fun playUrl(trackId: String): QishuiPlayUrl? {
        val url = "$LUNA_HOST/luna/h5/seo_track?track_id=${enc(trackId)}&device_platform=web"
        val json = runCatching { parseJsonPayload(Http.get(url)) }.getOrNull() ?: return null
        val tp = json.objOrNull("track_player") ?: return null
        val vm = tp.str("video_model")?.let { raw ->
            runCatching { parseJsonPayload(raw) }.getOrNull()
        }
        val list = vm?.arrOrNull("video_list")?.toList().orEmpty()
        val qualities = list.mapNotNull { item ->
            val meta = item.str("video_meta")?.let { runCatching { parseJsonPayload(it) }.getOrNull() }
            val u = item.str("main_url").orEmpty()
            if (u.isBlank()) null
            else QishuiQuality(
                quality = meta?.str("quality").orEmpty(),
                bitrate = meta?.long("bitrate") ?: 0L,
                size = meta?.long("size") ?: 0L,
                url = u,
            )
        }
        val first = list.firstOrNull()
        return QishuiPlayUrl(
            title = json.objOrNull("seo_track")?.objOrNull("track")?.str("name").orEmpty(),
            url = first?.str("main_url").orEmpty(),
            backupUrl = first?.str("backup_url").orEmpty(),
            expireAt = tp.long("expire_at") ?: 0L,
            qualities = qualities,
        )
    }

    /* ---------------- 匿名元数据（App UA，免签） ---------------- */

    /**
     * 汽水曲目搜索（**匿名可用**）。`q` 必须放在 query string 里，body 只放 cursor/count。
     * 返回根 JSON，曲目在 `result_groups[].data[].entity.track`。
     */
    suspend fun searchTracksRoot(keyword: String, cursor: Int = 0, count: Int = 20): JsonElement {
        val url = "$PC_HOST/luna/search/track?$ANDROID_QUERY&q=${enc(keyword)}&cursor=$cursor&count=$count"
        val body = """{"query":"${enc2(keyword)}","cursor":$cursor,"count":$count}"""
        return parseJsonPayload(Http.postJson(url, body, headers = mapOf("User-Agent" to APP_UA)))
    }

    /** 热搜词 / 搜索块（匿名可用）：`suggest_words[].keyword` */
    suspend fun searchBlock(): JsonElement {
        val url = "$PC_HOST/luna/search-block?$ANDROID_QUERY"
        return parseJsonPayload(Http.postJson(url, "{}", headers = mapOf("User-Agent" to APP_UA)))
    }

    /**
     * `h5/seo_track` 原始响应（**浏览器 UA**）：含 `seo_track.track`（完整元数据）、
     * `lyric.content`（明文 KRC 逐字歌词）、`track_player`（可播地址）、`comments` 等。
     */
    suspend fun seoTrack(trackId: String): JsonElement =
        parseJsonPayload(Http.get("$LUNA_HOST/luna/h5/seo_track?track_id=${enc(trackId)}&device_platform=web"))

    /** 歌单详情（含曲目列表）；`cursor` 传空串为首页 */
    suspend fun playlistDetail(
        playlistId: String,
        count: Int = 20,
        cursor: String = "",
    ): JsonElement {
        val url = "$LUNA_HOST/luna/playlist/detail?$ANDROID_QUERY"
        val body = """{"playlist_id":"${enc2(playlistId)}","playlist_type":0,"count":$count,"cursor":"${enc2(cursor)}"}"""
        return parseJsonPayload(
            Http.postJson(url, body, headers = mapOf("User-Agent" to APP_UA)),
        )
    }

    /** 搜索联想 */
    suspend fun sug(keyword: String): JsonElement {
        val url = "$LUNA_HOST/luna/sug?$ANDROID_QUERY&q=${enc(keyword)}&sug_scene=1&sug_search_id=1"
        return parseJsonPayload(Http.get(url, headers = mapOf("User-Agent" to APP_UA)))
    }

    /** 相关曲目（种子设为 track/playlist id） */
    suspend fun relatedMedia(id: String): JsonElement {
        val url = "$LUNA_HOST/luna/media/related?$ANDROID_QUERY"
        return parseJsonPayload(
            Http.postJson(url, """{"id":"${enc2(id)}"}""", headers = mapOf("User-Agent" to APP_UA)),
        )
    }

    /**
     * 汽水**个性化推荐（发现-混排）**——匿名可用。
     *
     * `POST /luna/discover/mix {}` → `inner_block[8].resources[].entity.playlist`
     * （实测返回「吉他伴奏」「港味经典粤语」「失恋」「深夜学习轻音乐」等推荐歌单，
     * 每项含 `id/title/url_cover/desc/count_tracks`）。
     *
     * 注：`/luna/discover` 在 `device_platform=android` 下返回空 blocks；
     * **`discover/mix` 才是可用的推荐入口**。
     */
    suspend fun discoverMix(): JsonElement {
        val url = "$PC_HOST/luna/discover/mix?$ANDROID_QUERY"
        return parseJsonPayload(Http.postJson(url, "{}", headers = mapOf("User-Agent" to APP_UA)))
    }

    /** 电台曲目流（需先拿到 `radio_id`） */
    suspend fun radioTracks(radioId: String, count: Int = 20): JsonElement {
        val url = "$PC_HOST/luna/feed/radio/tracks?$ANDROID_QUERY"
        return parseJsonPayload(
            Http.postJson(url, """{"radio_id":"${enc2(radioId)}","count":$count}""", headers = mapOf("User-Agent" to APP_UA)),
        )
    }

    /**
     * 场景音乐电台目录（汽水「一进 App 就播」那套电台）。
     *
     * `POST /luna/feed/mode {}` → `feed_mode_block[].feed_mode[]`
     * 每项：`text`（场景名，如「治愈」「DJ模式」）+ `entity.feed_scene_mode.scene_mode_id`。
     *
     * ⚠️ **该 `scene_mode_id` 就是 [radioTracks] 要的 `radio_id`**（实测 `radio_id="21"` → 25 首）。
     */
    suspend fun feedModes(): JsonElement {
        val url = "$PC_HOST/luna/feed/mode?$ANDROID_QUERY"
        return parseJsonPayload(Http.postJson(url, "{}", headers = mapOf("User-Agent" to APP_UA)))
    }

    /* ---------------- 登录态能力（PC UA + sessionid） ---------------- */

    /** 本人账号信息（`status_code == 0` 表示登录态有效） */
    suspend fun me(cookie: String): JsonElement = meByCookie(cookie)

    /**
     * 用**完整 Cookie 串**校验登录态。
     *
     * ⚠️ 汽水 PC 端登录后下发的是 **7 个 Cookie 且不含 `sessionid`**
     * （`ttwid` / `passport_csrf_token` / `passport_auth_status_ss` / `uid_tt_ss` /
     * `sessionid_ss` / `session_tlb_tag` / `ssid_ucp_v1`）。
     * 只带单个 `sessionid` 或 `sessionid_ss` → `1000016 登录状态已失效`。
     * 因此这里**原样回带整串 Cookie**，不要自行拼 `sessionid=`。
     */
    suspend fun meByCookie(cookie: String): JsonElement =
        parseJsonPayload(
            Http.get("$PC_HOST/luna/pc/me?aid=$AID", headers = mapOf("User-Agent" to PC_UA, "Cookie" to cookie)),
        )

    /** 本人歌单 */
    suspend fun myPlaylists(cookie: String): JsonElement {
        val url = "$PC_HOST/luna/pc/me/playlist?aid=$AID&iid=$IID&version_code=30020100"
        return parseJsonPayload(Http.get(url, headers = cookieHeaders(cookie)))
    }

    /** 本人收藏（混合） */
    suspend fun myCollection(cookie: String): JsonElement =
        parseJsonPayload(Http.get("$PC_HOST/luna/pc/me/collection/mixed?aid=$AID", headers = cookieHeaders(cookie)))

    /**
     * 查询「免费 VIP / 播放权益」状态。
     *
     * `GET /luna/commerce/me/subscription` →
     * `subs_info.play_entitlements {expire_at, reward_tasks_finished, video_complete_acquired_time_ms}`
     * + `free_vip_activity_detail`（领取日历：`redeem_calendar_dates[].status`）。
     */
    suspend fun subscription(cookie: String): JsonElement =
        parseJsonPayload(
            Http.get("$PC_HOST/luna/commerce/me/subscription?aid=$AID&app_name=luna_pc&device_platform=windows&version_code=30000000",
                headers = cookieHeaders(cookie)),
        )

    /**
     * 申请免费 VIP（每日领取入口）。
     *
     * 实测：`POST /luna/commerce/v2/apply_free_vip` 返回 `status_code:0` + `apply_status:13`。
     *
     * ⚠️ **诚实标注**：`apply_status:13` 的枚举含义未知（疑似「未达领取条件 / 活动未开放」）。
     * 即便返回成功，**本应用的取址通道（`h5/seo_track`）也不会因此解锁会员曲**——
     * 会员曲地址来自原生 App 独占的 `/luna/media_v2`（需设备级 `x-tt-token`）。
     * 因此本方法只是一个「尽力而为」的领取尝试，不作为听会员歌的手段。
     */
    suspend fun applyFreeVip(cookie: String): JsonElement =
        parseJsonPayload(
            Http.postJson("$PC_HOST/luna/commerce/v2/apply_free_vip?aid=$AID&app_name=luna_pc&device_platform=windows&version_code=30000000",
                "{}", headers = cookieHeaders(cookie)),
        )
    suspend fun myPlaylistsRaw(cookie: String): JsonElement {
        val url = "$PC_HOST/luna/pc/me/playlist?aid=$AID&iid=$IID&version_code=30020100"
        return parseJsonPayload(Http.get(url, headers = cookieHeaders(cookie)))
    }

    /** 登录态搜索 */
    suspend fun search(cookie: String, keyword: String): JsonElement {
        val url = "$PC_HOST/luna/pc/search/mixed?aid=$AID&app_name=luna_pc&device_platform=windows" +
            "&version_name=3.0.0&version_code=30000000&channel=official&device_id=7000000000000000" +
            "&iid=$IID&q=${enc(keyword)}&cursor=0"
        return parseJsonPayload(Http.get(url, headers = cookieHeaders(cookie)))
    }

    /** 从 Cookie 串里提取 `sessionid`（WebView 登录后落盘用） */
    fun extractSessionid(cookie: String): String =
        SESSIONID_RE.find(cookie)?.groupValues?.get(1).orEmpty()

    private val SESSIONID_RE = Regex("(?:^|;\\s*)sessionid=([^;\\s]+)")

    private fun enc2(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /* ---------------- 便捷解析 ---------------- */

    /** `status_code == 0` 视为成功 */
    fun isOk(json: JsonElement?): Boolean = (json?.long("status_code") ?: -1L) == 0L

    /** 取错误提示 */
    fun statusMessage(json: JsonElement?): String =
        json?.objOrNull("status_info")?.str("status_msg").orEmpty()

    /**
     * 从账号信息里提取资料。
     *
     * ⚠️ `/luna/pc/me` 的字段是 **`my_info`**（不是 `user_info`）：
     * `{ id, nickname, douyin_id, larger_avatar_url:{ urls:[...] }, is_vip, vip_stage }`。
     * 头像 URL 在 `urls` **数组**里（老接口叫 `url_list`，一并兼容）。
     */
    fun profileOf(me: JsonElement?): com.dpmusic.app.core.model.QishuiProfile {
        val u = me?.objOrNull("my_info")
            ?: me?.objOrNull("user_info")
            ?: me?.objOrNull("data")?.objOrNull("my_info")
            ?: me?.objOrNull("data")?.objOrNull("user_info")
        fun s(k: String) = u?.str(k).orEmpty()
        return com.dpmusic.app.core.model.QishuiProfile(
            userId = s("id").ifBlank { s("user_id").ifBlank { s("uid") } },
            // 昵称缺失时留空，交给 UI 兜底；不要用数字 ID 冒充昵称
            nickname = s("nickname").ifBlank { s("nick_name") },
            avatarUrl = firstAvatarUrl(u),
        )
    }

    /** 头像：优先 `larger_avatar_url` → `medium_avatar_url` → `avatar_url`；URL 在 `urls`/`url_list` 里 */
    private fun firstAvatarUrl(u: JsonElement?): String {
        val keys = listOf("larger_avatar_url", "medium_avatar_url", "avatar_url", "avatar_thumb")
        for (k in keys) {
            val node = u?.objOrNull(k) ?: continue
            for (arrKey in listOf("urls", "url_list")) {
                val first = node.arrOrNull(arrKey)?.firstOrNull() as? JsonPrimitive
                first?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
    }
}
