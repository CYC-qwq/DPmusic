package com.dpmusic.app.core.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 应用日志级别 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** 一条日志记录 */
data class LogEntry(
    val time: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val stackTrace: String? = null,
)

/**
 * 轻量应用日志：
 * - 内存环形缓冲（最多 500 条）+ Logcat 同步输出；
 * - 全局未捕获异常捕获（应用启动时安装）；
 * - 支持导出为文本（日志页分享）。
 */
object AppLogger {

    private const val MAX_ENTRIES = 500

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun d(tag: String, message: String) = append(LogLevel.DEBUG, tag, message)

    fun i(tag: String, message: String) = append(LogLevel.INFO, tag, message)

    fun w(tag: String, message: String) = append(LogLevel.WARN, tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        append(LogLevel.ERROR, tag, message, throwable)

    /** 安装全局未捕获异常处理器（应用启动时调用一次） */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e(
                "Crash",
                "未捕获异常（${thread.name}）：${throwable.javaClass.simpleName}: ${throwable.message}",
                throwable,
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun formatTime(time: Long): String = timeFormat.format(Date(time))

    /** 导出为文本（分享 / 保存） */
    fun exportText(): String {
        val entries = _logs.value
        return buildString {
            appendLine("DPmusic 运行日志")
            appendLine("导出时间：${fullFormat.format(Date())}")
            appendLine("共 ${entries.size} 条")
            appendLine("=".repeat(48))
            entries.sortedBy { it.time }.forEach { entry ->
                appendLine("[${fullFormat.format(Date(entry.time))}] [${entry.level}] [${entry.tag}] ${entry.message}")
                entry.stackTrace?.let { appendLine(it) }
            }
        }
    }

    private fun append(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }
        val stack = throwable?.stackTraceToString()?.lines()?.take(20)?.joinToString("\n")
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message, stack)
        _logs.value = (_logs.value + entry).takeLast(MAX_ENTRIES)
    }
}
