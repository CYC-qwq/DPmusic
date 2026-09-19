package com.dpmusic.app.core.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 时长格式化：03:45 / 1:02:33 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 大数字格式化：3.2万 / 1.5亿 */
fun formatCount(count: Long): String = when {
    count >= 100_000_000 -> "%.1f亿".format(count / 100_000_000.0)
    count >= 10_000 -> "%.1f万".format(count / 10_000.0)
    else -> count.toString()
}

/** 相对时间：刚刚 / x分钟前 / x小时前 / x天前 / MM-dd */
fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000}天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
    }
}

/** 最近播放页时间轴分组标签 */
fun dayLabel(timestamp: Long): String {
    val todayStart = startOfDay(System.currentTimeMillis())
    return when {
        timestamp >= todayStart -> "今天"
        timestamp >= todayStart - 86_400_000L -> "昨天"
        timestamp >= todayStart - 6 * 86_400_000L -> "本周"
        else -> "更早"
    }
}

private fun startOfDay(time: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = time
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}