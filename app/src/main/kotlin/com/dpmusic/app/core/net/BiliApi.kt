package com.dpmusic.app.core.net

import com.dpmusic.app.core.model.BiliAccount
import com.dpmusic.app.core.util.AppLogger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 哔哩哔哩接口客户端（纯请求，无状态；匿名可用）。
 *
 * ## 为什么需要 WBI 签名与 buvid 指纹
 * B 站自 2023 起对**搜索 / 详情 / 取流**等接口加了风控：
 * - 不带 `buvid3` Cookie 的请求一律 **HTTP 412**（Precondition Failed）；
 * - `wbi/search` 各接口、`wbi/view` 等还要求 **WBI 签名**（`w_rid` + `wts`），
 *   否则 `code: -403`（访问权限不足）。
 *
 * 因此本客户端维持一份「会话态」（buvid3 + 当日 wbi 密钥），首次使用时初始化，
 * 之后所有请求自动带上。密钥每日轮换、`wts` 有时间窗，故按 TTL 缓存并自动刷新。
 *
 * ## 接口清单（均实测通过，2026-10）
 * - 搜索：`GET /x/web-interface/wbi/search/type?search_type=video&keyword=`（需签名）
 * - 详情：`GET /x/web-interface/wbi/view?bvid=`（需签名）→ 标题 / UP / 时长 / cid / 分P
 * - 取流：`GET /x/player/playurl?avid=&cid=&fnval=16`（**无需签名**）→ `data.dash.audio[]`
 *
 * ## 边界（实测）
 * - **无榜单 / 歌单**：B 站是视频站，没有音乐榜单；收藏夹需登录态。故
 *   [toplists]、[rankSongs] 返回空，`playlistSongs` 走合集（ugc_season）有限支持。
 * - **音频档只有 AAC**：30216(≈64k) / 30232(≈132k) / 30280(≈192k)；会员 FLAC 匿名不可得。
 * - **无歌词**：B 站不提供 LRC；歌词搜索接口未接入。
 */
object BiliApi {

    private const val HOST = "https://api.bilibili.com"
    private const val WWW = "https://www.bilibili.com"

    /** 浏览器 UA：B 站对非浏览器 UA 的 API 请求更易触发风控 */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0 Safari/537.36"

    /* ---------------- 会话态：buvid + WBI 密钥 + 登录态 ---------------- */

    @Volatile private var buvid3: String = ""
    @Volatile private var buvid4: String = ""
    @Volatile private var mixinKey: String = ""
    @Volatile private var keyFetchedAt: Long = 0L

    /**
     * 登录 Cookie 提供者（可选）。设置后所有请求都会带上登录态：
     * - 搜索 / 详情的风控评分更高（更不易 412）；
     * - 取流时若为大会员，`playurl` 才会下发无损 FLAC / 全景声。
     * 未设置或返回空串 = 匿名链路（行为与旧版一致）。
     */
    @Volatile var cookieProvider: (() -> String)? = null

    /** 当前登录 Cookie（去除首尾空白）；无则空串 */
    private fun loginCookie(): String = cookieProvider?.invoke()?.trim().orEmpty()

    /** WBI 密钥与 buvid 的本地缓存时长：密钥每日轮换，保守取 6 小时 */
    private const val KEY_TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * 确保会话态就绪（buvid + WBI 密钥）。
     *
     * 冷启动流程：访问一次 `www.bilibili.com` 拿 `Set-Cookie: buvid3` →
     * `finger/spi` 补 `buvid4` → `nav` 取当日 `wbi_img` 算出 `mixinKey`。
     */
    private suspend fun ensureSession(force: Boolean = false) {
        if (!force && buvid3.isNotBlank() && mixinKey.isNotBlank() &&
            System.currentTimeMillis() - keyFetchedAt < KEY_TTL_MS
        ) return

        runCatching {
            // ① 主页 Set-Cookie：buvid3 / b_nut
            val home = Http.getWithHeaders("$WWW/", headers = mapOf("User-Agent" to UA))
            home.headers["Set-Cookie"].orEmpty().forEach { line ->
                extractCookie(line, "buvid3")?.let { buvid3 = it }
            }
            // ② 指纹接口补 buvid4（部分接口只认 buvid4）
            runCatching {
                val spi = parseJsonPayload(
                    Http.get("$HOST/x/frontend/finger/spi", headers = biliHeaders("$WWW/")),
                )
                spi.objOrNull("data")?.str("b_3")?.takeIf { it.isNotBlank() }?.let { buvid3 = it }
                spi.objOrNull("data")?.str("b_4")?.takeIf { it.isNotBlank() }?.let { buvid4 = it }
            }
            // ③ WBI 密钥
            val nav = parseJsonPayload(Http.get("$HOST/x/web-interface/nav", headers = biliHeaders("$WWW/")))
            val wbi = nav.objOrNull("data")?.objOrNull("wbi_img")
            val imgKey = wbi?.str("img_url").orEmpty().substringAfterLast('/').substringBefore('.')
            val subKey = wbi?.str("sub_url").orEmpty().substringAfterLast('/').substringBefore('.')
            if (imgKey.isNotBlank() && subKey.isNotBlank()) {
                mixinKey = WbiSigner.mixinKeyOf(imgKey, subKey)
                keyFetchedAt = System.currentTimeMillis()
            }
            AppLogger.i(TAG, "会话就绪：buvid3=${buvid3.take(12)}… wbi=${mixinKey.take(8)}…")
        }.onFailure { AppLogger.w(TAG, "会话初始化失败（将退化为无签名请求）：${it.message}") }
    }

    private fun extractCookie(setCookieLine: String, name: String): String? {
        val idx = setCookieLine.indexOf("$name=")
        if (idx < 0) return null
        return setCookieLine.substring(idx + name.length + 1).substringBefore(';').takeIf { it.isNotBlank() }
    }

    /** 请求头：UA + Referer + buvid Cookie +（可选）登录 Cookie。
     *
     * 注意：登录 Cookie 会被**自动附加**，因此匿名接口在有登录态时也会带上，
     * 风控评分更高（更不易 412）。buvid 与登录 Cookie 去重合并。
     */
    private fun biliHeaders(refererUrl: String, extraCookie: String? = null): Map<String, String> = buildMap {
        put("User-Agent", UA)
        put("Referer", refererUrl)
        put("Origin", WWW)
        val cookies = buildList {
            if (buvid3.isNotBlank()) add("buvid3=$buvid3")
            if (buvid4.isNotBlank()) add("buvid4=$buvid4")
            val login = loginCookie()
            if (login.isNotBlank()) add(login)
            if (!extraCookie.isNullOrBlank()) add(extraCookie.trim())
        }
        if (cookies.isNotEmpty()) put("Cookie", cookies.joinToString("; "))
    }

    /**
     * WBI 签名：给参数加上 `wts` 与 `w_rid`（算法见 [WbiSigner]）。
     *
     * @return 已签名并 URL 编码的 query string（按参数名升序）
     */
    private fun wbiSign(params: Map<String, String>, nowSec: Long): String =
        WbiSigner.sign(params, mixinKey, nowSec)

    /* ---------------- 搜索 ---------------- */

    /**
     * 视频搜索（**需 WBI 签名**）。
     *
     * ⚠️ B 站搜索接口不返回「正版音源」标识，返回的是**用户上传的视频**——
     * 因此「搜歌」实际是「搜含该歌的视频」。歌名/歌手由标题启发式拆分（见 BiliPlatformApi）。
     *
     * @return 原始 `data.result` 数组；失败返回空
     */
    suspend fun searchVideos(keyword: String, page: Int, pageSize: Int): JsonElement? {
        if (keyword.isBlank()) return null
        ensureSession()
        val params = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "page" to page.coerceAtLeast(1).toString(),
            "page_size" to pageSize.coerceIn(1, 50).toString(),
        )
        val url = "$HOST/x/web-interface/wbi/search/type?${wbiSign(params, nowSec())}"
        return runCatching {
            val root = parseJsonPayload(Http.get(url, headers = biliHeaders("$WWW/search")))
            val code = root.long("code") ?: -1L
            if (code == -403L) {
                // 签名过期（密钥轮换 / 时间漂移）→ 强刷一次会话重试
                AppLogger.w(TAG, "搜索被拒（-403），刷新会话后重试")
                ensureSession(force = true)
                val retryUrl = "$HOST/x/web-interface/wbi/search/type?${wbiSign(params, nowSec())}"
                parseJsonPayload(Http.get(retryUrl, headers = biliHeaders("$WWW/search")))
            } else root
        }.getOrNull()?.objOrNull("data")?.arrOrNull("result")
    }

    /** 综合搜索（含 `suggest_keyword`，用于搜索联想）；失败返回 null */
    suspend fun suggest(keyword: String): List<String> {
        if (keyword.isBlank()) return emptyList()
        ensureSession()
        val params = mapOf("term" to keyword)
        val url = "$HOST/x/web-interface/search/suggest?${wbiSign(params, nowSec())}"
        return runCatching {
            val root = parseJsonPayload(Http.get(url, headers = biliHeaders("$WWW/")))
            root.objOrNull("data")?.arrOrNull("tag")
                ?.mapNotNull { it.str("value")?.takeIf { v -> v.isNotBlank() } }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /* ---------------- 详情 / 分P ---------------- */

    /** 视频详情（**需 WBI 签名**）：标题 / UP / 时长 / 封面 / aid / cid / 分P */
    suspend fun view(bvid: String): JsonElement? {
        if (bvid.isBlank()) return null
        ensureSession()
        val params = mapOf("bvid" to bvid)
        val url = "$HOST/x/web-interface/wbi/view?${wbiSign(params, nowSec())}"
        val page = "$WWW/video/$bvid/"
        return runCatching {
            val root = parseJsonPayload(Http.get(url, headers = biliHeaders(page)))
            if ((root.long("code") ?: -1L) != 0L) return@runCatching null
            root.objOrNull("data")
        }.getOrNull()
    }

    /** 分P列表（**无需签名**）：`data[] = { cid, part, duration }` */
    suspend fun pagelist(bvid: String): List<JsonElement> {
        if (bvid.isBlank()) return emptyList()
        ensureSession()
        val url = "$HOST/x/player/pagelist?bvid=$bvid"
        return runCatching {
            parseJsonPayload(Http.get(url, headers = biliHeaders("$WWW/video/$bvid/")))
                .arrOrNull("data")?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    /* ---------------- 取流 ---------------- */

    /**
     * DASH 播放信息（**无需签名**，但需 buvid Cookie）。
     *
     * `fnval = 4048` = DASH(16) + HDR(64) + 4K(128) + **Dolby 音频(256)** + Dolby Vision(512)
     * + 8K(1024) + AV1(2048) —— 尽可能把无损 / 全景声档一起要来（与 yt-dlp 取 B 站时的取值一致）。
     * 实际下发哪些音频档由**视频本身**与**账号会员态**共同决定：
     * 非大会员即使带登录态也只能拿到 AAC（30216/30232/30280）。
     *
     * @return `data.dash`：`{ audio: [...], dolby: {audio:...}, flac: {audio:...} }`；失败返回 null
     */
    suspend fun dash(avid: String, cid: String): JsonElement? {
        if (avid.isBlank() || cid.isBlank()) return null
        ensureSession()
        val url = "$HOST/x/player/playurl?avid=$avid&cid=$cid&fnval=$FNVAL_ALL&fourk=1"
        return runCatching {
            val root = parseJsonPayload(Http.get(url, headers = biliHeaders("$WWW/video/av$avid/")))
            if ((root.long("code") ?: -1L) != 0L) return@runCatching null
            root.objOrNull("data")?.objOrNull("dash")
        }.getOrNull()
    }

    /* ---------------- 收藏夹（需登录态 Cookie） ---------------- */

    /**
     * 收藏夹内容（**需登录态 Cookie**）。
     * `GET /x/v3/fav/resource/list?media_id=&pn=&ps=` → `data.medias[]`（含 bvid / title / cover）
     */
    suspend fun favResources(mediaId: String, cookie: String, page: Int = 1, pageSize: Int = 20): List<JsonElement> {
        if (mediaId.isBlank() || cookie.isBlank()) return emptyList()
        val url = "$HOST/x/v3/fav/resource/list?media_id=$mediaId&pn=${page.coerceAtLeast(1)}&ps=$pageSize" +
            "&platform=web&order=mtime&type=0&tid=0"
        return runCatching {
            parseJsonPayload(Http.get(url, headers = biliHeaders("$WWW/", extraCookie = cookie)))
                .objOrNull("data")?.arrOrNull("medias")?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 收藏夹元信息（名称 / 封面 / 数量），需登录态 */
    suspend fun favMeta(mediaId: String, cookie: String): JsonElement? {
        if (mediaId.isBlank() || cookie.isBlank()) return null
        return runCatching {
            parseJsonPayload(
                Http.get("$HOST/x/v3/fav/folder/info?media_id=$mediaId", headers = biliHeaders("$WWW/", extraCookie = cookie)),
            ).objOrNull("data")
        }.getOrNull()
    }

    /** 本人收藏夹列表（用于登录后展示「我的收藏」），需登录态 */
    suspend fun myFavFolders(cookie: String, upMid: String): List<JsonElement> {
        if (cookie.isBlank() || upMid.isBlank()) return emptyList()
        return runCatching {
            parseJsonPayload(
                Http.get("$HOST/x/v3/fav/folder/created/list-all?up_mid=$upMid", headers = biliHeaders("$WWW/", extraCookie = cookie)),
            ).objOrNull("data")?.arrOrNull("list")?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    /* ---------------- 账号 ---------------- */

    /**
     * 读取登录账号资料（**用传入的 cookie 校验**，与全局登录态无关）。
     *
     * @return 登录成功返回 [BiliAccount]；cookie 无效 / 未登录返回 null
     */
    suspend fun account(cookie: String): BiliAccount? {
        if (cookie.isBlank()) return null
        return runCatching {
            val root = parseJsonPayload(Http.get("$HOST/x/web-interface/nav", headers = biliHeaders("$WWW/", extraCookie = cookie)))
            val data = root.objOrNull("data") ?: return@runCatching null
            if ((data.str("isLogin") ?: data.long("isLogin")?.let { if (it != 0L) "true" else "false" }) != "true") {
                return@runCatching null
            }
            val mid = data.str("mid").orEmpty()
            if (mid.isBlank()) return@runCatching null
            val vip = data.objOrNull("vip")
            BiliAccount(
                mid = mid,
                nickname = data.str("uname").orEmpty(),
                avatar = data.str("face").orEmpty(),
                vipStatus = data.int("vipStatus") ?: 0,
                vipType = vip?.int("type") ?: 0,
                vipLabel = vip?.objOrNull("label")?.str("text").orEmpty(),
            )
        }.getOrNull()
    }

    /** 当前登录用户的 mid（登录态校验用）；未登录返回空 */
    suspend fun myMid(cookie: String): String = account(cookie)?.mid.orEmpty()

    /**
     * 音乐区排行（`GET /x/web-interface/ranking/v2?rid=3`）。
     *
     * 走本客户端会话态（buvid + Referer + 可选登录 Cookie）——**必须**如此：
     * 裸请求会被风控拦成 `-352`。
     *
     * 实测（2026-10）：`rid=3`（音乐区）返回 96 条，条目含 `bvid` + `cid`，
     * 拿到即可直接交给 [BiliResolver] 取流播放；其余分区（28/29/30/31…）返回 `-400`
     * （非排行分区），故本应用只接音乐区。
     *
     * @return 原始 `data`；失败返回 null
     */
    suspend fun musicRanking(): JsonElement? {
        ensureSession()
        val url = "$HOST/x/web-interface/ranking/v2?rid=$MUSIC_RID&type=all"
        return runCatching {
            val root = parseJsonPayload(Http.get(url, headers = biliHeaders("$WWW/v/popular/rank/all")))
            if ((root.long("code") ?: -1L) != 0L) return@runCatching null
            root.objOrNull("data")
        }.getOrNull()
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    /** 从收藏夹条目 / 搜索条目里取 bvid（两者字段名不同：`bvid` vs `bvid`，兼容 `aid` 兜底） */
    fun bvidOf(item: JsonElement): String = item.str("bvid").orEmpty()

    /** JSON 数字/字符串混用时统一取字符串 */
    fun idOf(item: JsonElement, key: String): String {
        val el = (item as? kotlinx.serialization.json.JsonObject)?.get(key) ?: return ""
        return (el as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    private const val TAG = "BiliApi"

    /** 音乐区（`rid=3`）—— 实测唯一可用的排行分区 */
    private const val MUSIC_RID = 3

    /**
     * 一次性请求全部 DASH 档位：DASH(16) | HDR(64) | 4K(128) | Dolby 音频(256)
     * | Dolby Vision(512) | 8K(1024) | AV1(2048) = 4048。
     * 无损 FLAC / 全景声只对**大会员 + 视频本身有该档**时下发。
     */
    private const val FNVAL_ALL = 4048
}
