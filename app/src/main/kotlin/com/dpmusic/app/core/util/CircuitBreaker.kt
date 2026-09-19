package com.dpmusic.app.core.util

/**
 * 简易熔断器：
 * - 连续失败达到阈值后进入冷却期，期间直接拒绝请求（避免对失效音源持续打点）；
 * - 冷却结束放行单个探测请求，成功则闭合，失败则重新进入冷却。
 */
class CircuitBreaker(
    private val failureThreshold: Int = 4,
    private val openDurationMs: Long = 30_000L,
) {
    private var failures = 0
    private var openedAt = 0L
    private var probing = false

    @Synchronized
    fun allowRequest(now: Long = System.currentTimeMillis()): Boolean {
        if (openedAt == 0L) return true
        if (now - openedAt < openDurationMs) return false
        if (probing) return false
        probing = true
        return true
    }

    @Synchronized
    fun recordSuccess() {
        failures = 0
        openedAt = 0L
        probing = false
    }

    @Synchronized
    fun recordFailure(now: Long = System.currentTimeMillis()) {
        probing = false
        failures++
        if (failures >= failureThreshold) {
            openedAt = now
            failures = 0
        }
    }

    @Synchronized
    fun isOpen(now: Long = System.currentTimeMillis()): Boolean =
        openedAt != 0L && now - openedAt < openDurationMs
}