package com.dpmusic.app.core.data

import com.dpmusic.app.core.net.KgLiteClaimApi
import com.dpmusic.app.core.net.KgLiteClaimResult
import com.dpmusic.app.core.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 酷狗概念版「每日自动签到领 VIP」。
 *
 * 触发时机：进程启动后延迟执行一次 + 每次回到前台时检查（同日仅一次）。
 * 前置条件：① 用户已手机号登录（有 token）② 设置里「每日自动签到」已开启。
 *
 * 幂等保证：以 `yyyy-MM-dd` 记录当天是否已执行，同日不重复请求。
 *
 * ⚠️ 自动化账号操作可能触发平台风控，本服务仅在用户主动开启后运行。
 */
class KgLiteClaimService(
    private val api: KgLiteClaimApi,
    private val settings: SettingsRepository,
    private val repository: KgLiteRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lastResult = MutableStateFlow<KgLiteClaimResult?>(null)

    /** 最近一次签到结果（供设置页提示） */
    val lastResult: StateFlow<KgLiteClaimResult?> = _lastResult.asStateFlow()

    /** 进程启动调用一次：满足条件时执行当日签到 */
    fun scheduleStartupClaim() {
        scope.launch {
            delay(STARTUP_DELAY_MS)
            runClaimIfNeeded()
        }
    }

    /** 手动触发签到（设置页按钮；忽略同日去重，用于补领） */
    fun claimNow() {
        scope.launch { doClaim() }
    }

    /** 满足「已开启 + 已登录 + 今日未执行」时才签到 */
    private suspend fun runClaimIfNeeded() {
        val autoClaim = settings.settings.first().kgLiteAutoClaim
        if (!autoClaim) return
        if (!api.canClaim()) return
        if (repository.lastClaimDate() == today()) return
        doClaim()
    }

    private suspend fun doClaim() {
        val result = runCatching { api.claimDaily() }.getOrElse {
            AppLogger.w(TAG, "自动签到异常：${it.message}")
            KgLiteClaimResult(false, "自动签到失败：${it.message ?: "网络异常"}")
        }
        _lastResult.value = result
        // 无论成功与否都记录日期：避免失败时反复重试触发风控
        repository.markClaimed(today())
        settings.setKgLiteLastClaimDate(today())
        AppLogger.d(TAG, "签到结果：ok=${result.ok} ${result.message}")
    }

    fun consumeResult() {
        _lastResult.value = null
    }

    private fun today(): String = DATE_FORMAT.format(Date())

    private companion object {
        const val TAG = "KgLiteClaim"
        const val STARTUP_DELAY_MS = 8_000L
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}