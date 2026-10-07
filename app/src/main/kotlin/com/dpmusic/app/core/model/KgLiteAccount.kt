package com.dpmusic.app.core.model

import kotlinx.serialization.Serializable

/**
 * 酷狗概念版账号资料（仅本地保存）。
 *
 * 登录方式：从官方概念版 App / 网页抓包取得 `token` + `userid` 后填入（应用不代持密码）。
 * 未登录（`userid` 为空）= 匿名模式，仍可解析「免费歌全曲」，但付费歌仅 60s 试听。
 */
@Serializable
data class KgLiteProfile(
    val userId: String = "",
    val nickname: String = "",
    /** VIP 类型位（0 = 非会员） */
    val vipType: Int = 0,
    /** 设备标识（数字串；匿名也会持久化一个随机值复用） */
    val mid: String = "",
)