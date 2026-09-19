package com.dpmusic.app.core.together

import com.dpmusic.app.core.model.TogetherInvite
import com.dpmusic.app.core.net.AppJson
import com.dpmusic.app.core.net.objOrNull
import com.dpmusic.app.core.net.str
import java.net.URLDecoder
import kotlinx.serialization.json.JsonObject

/**
 * 一起听邀请解析：
 * - 官方分享链接：st.music.163.com/listen-together/share|multishare/index.html?...roomId=..&inviterId|inviterUid=..；
 * - 或同时包含 roomId（32 位 hex_时间戳）与邀请人 uid 的任意文本。
 */
object TogetherInviteParser {

    private val PARAM_ROOM = Regex("[?&]roomId=([^&\\s#]+)")
    private val PARAM_INVITER = Regex("[?&](?:inviterId|inviterUid|uid)=([0-9]+)")
    private val ROOM_ID = Regex("([0-9a-fA-F]{32}_\\d+)")
    private val BARE_INVITER = Regex("\\d{5,}")

    fun parse(raw: String): TogetherInvite? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val roomFromParam = PARAM_ROOM.find(text)?.groupValues?.get(1)
        val inviterFromParam = PARAM_INVITER.find(text)?.groupValues?.get(1)
        if (roomFromParam != null && inviterFromParam != null) {
            return TogetherInvite(roomId = decode(roomFromParam), inviterId = inviterFromParam.toLong())
        }

        val roomId = ROOM_ID.find(text)?.value ?: return null
        val afterRoom = text.substringAfter(roomId)
        val inviter = BARE_INVITER.find(afterRoom)?.value
            ?: BARE_INVITER.find(text.replace(roomId, " "))?.value
            ?: return null
        return TogetherInvite(roomId = roomId, inviterId = inviter.toLong())
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

/** 从官方私信「一起听邀请卡片」解析出的邀请信息 */
data class TogetherInviteCard(
    val roomId: String,
    val inviterId: Long,
    val inviterName: String,
    /** 私信消息 id（去重键） */
    val msgId: Long,
    /** 消息时间（毫秒） */
    val msgTime: Long,
) {
    /** 合成标准分享链接（复用现有解析 / 加入链路） */
    val shareLink: String get() = "https://st.music.163.com/listen-together/share/?roomId=$roomId&inviterId=$inviterId"
}

/**
 * 一起听邀请卡片解析（官方私信 resType23 / bizChannel=listen_together_private）：
 * - 卡片根：{"msg":"我的耳机分你一半…","bizChannel":"listen_together_private",
 *   "generalMsg":{"nativeUrl":"orpheus://open?url1=…listenTogether?roomId=..&inviterId=..&inviterName=.."}}；
 * - 部分接口（列表 lastMsg）会包裹一层 {"msgId":..,"msg":"<卡片JSON字符串>"}。
 */
object TogetherInviteCardParser {

    private val NATIVE_ROOM = Regex("roomId=([0-9a-fA-F]{32}_\\d+)")
    private val NATIVE_INVITER = Regex("inviterId=(\\d+)")
    private val NATIVE_NAME = Regex("inviterName=([^&]*)")

    /** 解析单条消息文本（history 的 msg 字段 / list 的 lastMsg）；非邀请卡片返回 null */
    fun parse(msgText: String, msgId: Long, msgTime: Long): TogetherInviteCard? {
        val root = runCatching { AppJson.parseToJsonElement(msgText) }.getOrNull() as? JsonObject ?: return null
        cardFrom(root, msgId, msgTime)?.let { return it }
        val inner = root.str("msg")?.let { runCatching { AppJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
        return cardFrom(inner, msgId, msgTime)
    }

    private fun cardFrom(obj: JsonObject, msgId: Long, msgTime: Long): TogetherInviteCard? {
        val channel = obj.str("bizChannel").orEmpty()
        val nativeUrl = obj.objOrNull("generalMsg")?.str("nativeUrl").orEmpty()
        if (channel != "listen_together_private" && !nativeUrl.contains("listenTogether", ignoreCase = true)) return null
        val decoded = runCatching { URLDecoder.decode(nativeUrl, "UTF-8") }.getOrDefault(nativeUrl)
        val roomId = NATIVE_ROOM.find(decoded)?.groupValues?.get(1) ?: return null
        val inviterId = NATIVE_INVITER.find(decoded)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val name = NATIVE_NAME.find(decoded)?.groupValues?.get(1).orEmpty()
        return TogetherInviteCard(roomId = roomId, inviterId = inviterId, inviterName = name, msgId = msgId, msgTime = msgTime)
    }
}