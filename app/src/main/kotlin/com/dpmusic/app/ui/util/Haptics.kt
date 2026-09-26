package com.dpmusic.app.ui.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 触觉反馈（Haptics）
 *
 * 设计原则（对齐 Apple HIG / Material 的触觉规范）：
 *
 * 1. **克制而有意**：只在「状态真的发生了改变」时触发，不做无意义震动。
 *    列表滚动、悬停、无副作用的点击一律不震。
 * 2. **语义分级**：不同含义用不同强度——
 *    [tick] 极轻（选择变化）< [click] 轻（普通点击）< [confirm] 确认（成功）
 *    < [reject] 警告（失败 / 移除）。
 * 3. **尊重系统设置**：用户关闭系统触觉反馈时，`performHapticFeedback`
 *    会自动静默，无需自己判断。
 * 4. **降级安全**：Android 11 (API 30) 以下没有 CONFIRM / REJECT / GESTURE_END，
 *    自动回退到等价的旧常量；异常一律吞掉，绝不因震动让业务崩溃。
 */
class DpHaptics(private val view: View) {

    /** 极轻：Tab 切换、选项变化、滚动吸附 */
    fun tick() = fire(HapticFeedbackConstants.CLOCK_TICK)

    /** 轻：普通按钮点击、列表项点击 */
    fun click() = fire(HapticFeedbackConstants.VIRTUAL_KEY)

    /** 长按：进入多选、弹出长按菜单 */
    fun longPress() = fire(HapticFeedbackConstants.LONG_PRESS)

    /** 确认：收藏成功、加入歌单、下载完成、操作成功 */
    fun confirm() = fire(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    /** 拒绝 / 警告：操作失败、不可用、移除内容 */
    fun reject() = fire(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS,
    )

    /** 手势结束：拖拽落位、滑块松手、播放器吸附 */
    fun gestureEnd() = fire(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_END
        else HapticFeedbackConstants.CLOCK_TICK,
    )

    private fun fire(constant: Int) {
        runCatching { view.performHapticFeedback(constant) }
    }
}

/** 取当前 View 的触觉反馈入口（全局复用，无额外对象分配） */
@Composable
fun rememberDpHaptics(): DpHaptics {
    val view = LocalView.current
    return remember(view) { DpHaptics(view) }
}