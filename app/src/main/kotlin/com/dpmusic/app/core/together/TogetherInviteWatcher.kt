package com.dpmusic.app.core.together

import com.dpmusic.app.core.data.NcmRepository
import com.dpmusic.app.core.net.NcmApi
import com.dpmusic.app.core.net.arrOrNull
import com.dpmusic.app.core.net.long
import com.dpmusic.app.core.net.objOrNull
import com.dpmusic.app.core.net.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 一起听邀请守望（官方私信卡片识别）：
 * - 已登录且空闲（未在房间）时，定期轮询私信会话列表；发现好友从官方 App 发来的
 *   「一起听邀请卡片」（resType23 / bizChannel=listen_together_private）后，弹出「加入」询问；
 * - 识别链路：/api/msg/private/users（列表，判断会话有新活动）→ /api/msg/private/history
 *   （取完整卡片，解析 roomId + inviterId + 邀请人昵称）；
 * - 去重：按消息 id（跨重启持久化最后一条已处理消息）；仅提示最近 15 分钟内到达的邀请；
 * - 只读：不做任何写操作，不改变账号消息已读状态。
 */
class TogetherInviteWatcher(
    private val api: NcmApi,
    private val ncm: NcmRepository,
    private val session: TogetherSession,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _pending = MutableStateFlow<TogetherInviteCard?>(null)

    /** 当前待询问的邀请（界面消费；consume 后清空） */
    val pending: StateFlow<TogetherInviteCard?> = _pending.asStateFlow()

    private var watchJob: Job? = null
    private var lastCheckAt = 0L
    private var checking = false

    /** 会话（对方 uid）→ 已处理到的最新消息时间（毫秒） */
    private val convSeenAt = mutableMapOf<Long, Long>()

    /** 已扫描过的消息 id（防止重复解析） */
    private val scannedMsgIds = LinkedHashSet<Long>()

    /** 待询问候选（多个好友先后邀请时逐个询问） */
    private val candidates = mutableListOf<TogetherInviteCard>()

    /** 启动守望（幂等；进程级） */
    fun start() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            delay(FIRST_DELAY_MS)
            while (isActive) {
                checkOnce()
                delay(LOOP_INTERVAL_MS)
            }
        }
    }

    /** 立即检查一次（内部节流；供应用获得焦点等场景调用） */
    fun checkNow() {
        scope.launch {
            if (System.currentTimeMillis() - lastCheckAt < MIN_CHECK_GAP_MS) return@launch
            checkOnce()
        }
    }

    /** 消费当前邀请（加入或忽略后调用） */
    fun consume() {
        _pending.value = null
    }

    private suspend fun checkOnce() {
        if (checking) return
        checking = true
        try {
            doCheck()
        } finally {
            checking = false
        }
    }

    private suspend fun doCheck() {
        lastCheckAt = System.currentTimeMillis()
        val cookie = ncm.cookie.value
        if (cookie.isBlank()) return
        if (session.state.value !is TogetherUiState.Idle) return
        val myUid = ncm.profile.value?.userId ?: 0L
        if (myUid <= 0L) return
        val now = System.currentTimeMillis()

        val list = runCatching { api.msgPrivateUsers(cookie, 20) }.getOrNull() ?: return
        val items = list.arrOrNull("msgs") ?: return
        for (item in items) {
            val convTime = item.long("lastMsgTime") ?: continue
            val u = item.objOrNull("user")
            val fromUid = u?.long("fromUserId") ?: 0L
            val toUid = u?.long("toUserId") ?: 0L
            val otherUid = when {
                fromUid > 0L && fromUid != myUid -> fromUid
                toUid > 0L && toUid != myUid -> toUid
                else -> continue
            }
            // 会话无新活动：跳过
            val seenAt = convSeenAt[otherUid] ?: 0L
            if (convTime <= seenAt) continue
            // 过于陈旧：记基线跳过
            if (now - convTime > STALE_CONV_MS) {
                convSeenAt[otherUid] = convTime
                continue
            }
            // 有新活动：拉历史扫描邀请卡片
            val hist = runCatching { api.msgPrivateHistory(cookie, otherUid, 20) }.getOrNull()
            convSeenAt[otherUid] = convTime
            if (hist == null) continue
            val histMsgs = hist.arrOrNull("msgs") ?: continue
            for (m in histMsgs) {
                val msgId = m.long("id") ?: 0L
                if (msgId <= 0L) continue
                if (!scannedMsgIds.add(msgId)) continue
                val msgTime = m.long("time") ?: 0L
                val msgText = m.str("msg") ?: continue
                if (msgText.length > MAX_SCAN_LENGTH) continue
                val card = TogetherInviteCardParser.parse(msgText, msgId, msgTime) ?: continue
                if (msgTime < now - OFFER_WINDOW_MS) continue
                if (msgId == ncm.lastTogetherInviteMsgId.value) continue
                candidates += card
            }
            trimScanned()
        }

        // 清理过期候选；若当前无弹窗且有候选：取最新一条询问
        candidates.removeAll { it.msgTime < now - OFFER_WINDOW_MS }
        if (_pending.value == null && candidates.isNotEmpty()) {
            val best = candidates.maxByOrNull { it.msgTime } ?: return
            _pending.value = best
            candidates.removeAll { it.msgId == best.msgId }
            ncm.setLastTogetherInviteMsgId(best.msgId)
        }
    }

    private fun trimScanned() {
        while (scannedMsgIds.size > MAX_SCANNED_IDS) {
            val oldest = scannedMsgIds.firstOrNull() ?: return
            scannedMsgIds.remove(oldest)
        }
    }

    private companion object {
        /** 启动后首查延迟（毫秒；避开冷启动高峰） */
        const val FIRST_DELAY_MS = 8_000L

        /** 轮询间隔（毫秒） */
        const val LOOP_INTERVAL_MS = 45_000L

        /** checkNow 最小间隔（毫秒） */
        const val MIN_CHECK_GAP_MS = 20_000L

        /** 会话超过该时长无活动则跳过（毫秒） */
        const val STALE_CONV_MS = 60 * 60_000L

        /** 邀请可提示窗口（毫秒；收到后 15 分钟内提示） */
        const val OFFER_WINDOW_MS = 15 * 60_000L

        /** 单条消息扫描长度上限 */
        const val MAX_SCAN_LENGTH = 20_000

        /** 已扫描消息 id 保留上限 */
        const val MAX_SCANNED_IDS = 400
    }
}
