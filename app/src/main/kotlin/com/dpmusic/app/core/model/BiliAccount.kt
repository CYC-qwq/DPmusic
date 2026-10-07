package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/**
 * 哔哩哔哩账号资料（仅本地保存）。
 *
 * 登录方式：填写浏览器 Cookie（含 `SESSDATA` 等）。应用不代持密码、不存验证码。
 * 未登录（[mid] 为空）= 匿名，仍可搜索 / 播放，但只能取 **AAC** 音频档
 * （30216/30232/30280）；登录后按账号的会员状态决定能否取到 **无损 FLAC / 全景声**。
 *
 * 字段来源：`GET /x/web-interface/nav` 的 `data`
 * （`mid` / `uname` / `face` / `vipStatus` / `vip.type` / `vip.label.text`）。
 */
@Serializable
data class BiliAccount(
    /** 用户 UID；空串表示未登录 */
    val mid: String = "",
    /** 昵称 */
    val nickname: String = "",
    /** 头像 URL */
    val avatar: String = "",
    /** 会员状态：1 = 大会员（`vipStatus`） */
    val vipStatus: Int = 0,
    /** 会员类型位（`vip.type`：0 无 / 1 月度 / 2 年度…） */
    val vipType: Int = 0,
    /** 会员标签文案（`vip.label.text`，如「年度大会员」） */
    val vipLabel: String = "",
) {
    /** 是否已登录 */
    val isLogin: Boolean get() = mid.isNotBlank()

    /** 是否大会员（决定能否解锁 FLAC / 全景声） */
    val isVip: Boolean get() = vipStatus == 1

    /** 账号态简述（设置页展示用） */
    val summary: String
        get() = when {
            !isLogin -> "匿名（仅 AAC 音频）"
            isVip -> nickname.ifBlank { "已登录" } + " · " + vipLabel.ifBlank { "大会员" } + "（可无损）"
            else -> nickname.ifBlank { "已登录" } + "（非大会员，仅 AAC）"
        }
}
