package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.net.AppJson
import com.dpmusic.app.core.playback.PlayerConnection
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 听歌时长统计：
 * - 按天累计「实际播放秒数」（持久化，保留最近 30 天，供「近 7 日」图表）；
 * - 本次会话计时：自本次打开 App 后首次播放起累计（进程级，暂停冻结，实时刷新）；
 * - 数据源：秒级轮询播放器状态，仅当正在播放时累计。
 */
class ListeningStatsStore(
    private val dataStore: DataStore<Preferences>,
    private val player: PlayerConnection,
) {

    /** 单日统计（近 7 日图表用） */
    data class DayStat(
        val dateKey: String,
        val seconds: Long,
        /** Calendar.MONDAY..Calendar.SUNDAY */
        val dayOfWeek: Int,
        val isToday: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 本次会话开始时间（本次打开 App 后首次播放时刻）；null = 尚未播放 */
    private val _sessionStartMs = MutableStateFlow<Long?>(null)
    val sessionStartMs: StateFlow<Long?> = _sessionStartMs

    /** 本次会话累计播放秒数（仅播放时累计，暂停冻结） */
    private val _sessionSeconds = MutableStateFlow(0L)
    val sessionSeconds: StateFlow<Long> = _sessionSeconds

    /** 今日累计播放秒数（含未落盘增量） */
    private val _todaySeconds = MutableStateFlow(0L)
    val todaySeconds: StateFlow<Long> = _todaySeconds

    /** 近 7 日统计（索引 0 = 6 天前 … 6 = 今天） */
    private val _days = MutableStateFlow<List<DayStat>>(emptyList())
    val days: StateFlow<List<DayStat>> = _days

    @Volatile
    private var persisted: Map<String, Long> = emptyMap()
    private var pendingMs = 0L
    private var sessionMs = 0L
    private var lastTickAt = 0L
    private var wasPlaying = false

    init {
        // 持久化数据 → UI 流
        scope.launch {
            dataStore.data.collect { prefs ->
                persisted = decode(prefs[KEY_DAILY])
                publish()
            }
        }
        // 秒级累计器：仅播放时累计
        scope.launch {
            while (isActive) {
                delay(1000L)
                val playing = player.nowPlaying.value?.isPlaying == true
                val now = System.currentTimeMillis()
                if (playing) {
                    if (_sessionStartMs.value == null) _sessionStartMs.value = now
                    if (lastTickAt > 0L) {
                        val delta = now - lastTickAt
                        if (delta in 1L..10_000L) {
                            pendingMs += delta
                            sessionMs += delta
                            _sessionSeconds.value = sessionMs / 1000L
                        }
                    }
                    lastTickAt = now
                    if (pendingMs >= FLUSH_EVERY_MS) flushNow()
                } else {
                    if (wasPlaying) flushNow()
                    lastTickAt = 0L
                }
                wasPlaying = playing
                publish()
            }
        }
    }

    private fun publish() {
        val todayKey = fmtDay(System.currentTimeMillis())
        _todaySeconds.value = (persisted[todayKey] ?: 0L) + pendingMs / 1000L
        _days.value = buildDays()
    }

    private fun buildDays(): List<DayStat> {
        val base = Calendar.getInstance()
        val result = ArrayList<DayStat>(7)
        for (offset in 6 downTo 0) {
            val c = base.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, -offset)
            val key = fmtDay(c.timeInMillis)
            val seconds = if (offset == 0) {
                (persisted[key] ?: 0L) + pendingMs / 1000L
            } else {
                persisted[key] ?: 0L
            }
            result += DayStat(key, seconds, c.get(Calendar.DAY_OF_WEEK), offset == 0)
        }
        return result
    }

    /** 未落盘增量写入 DataStore（保留最近 30 天） */
    private suspend fun flushNow() {
        val addSeconds = pendingMs / 1000L
        if (addSeconds <= 0L) return
        pendingMs -= addSeconds * 1000L
        val key = fmtDay(System.currentTimeMillis())
        dataStore.edit { prefs ->
            val map = decode(prefs[KEY_DAILY]).toMutableMap()
            map[key] = (map[key] ?: 0L) + addSeconds
            val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -KEEP_DAYS) }
            val cutoffKey = fmtDay(cutoff.timeInMillis)
            map.keys.toList().forEach { k -> if (k < cutoffKey) map.remove(k) }
            prefs[KEY_DAILY] = encode(map)
        }
    }

    private fun fmtDay(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timeMs))

    private fun decode(raw: String?): Map<String, Long> =
        if (raw.isNullOrBlank()) emptyMap()
        else runCatching { AppJson.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())

    private fun encode(map: Map<String, Long>): String = AppJson.encodeToString(map)

    private companion object {
        val KEY_DAILY = stringPreferencesKey("listening_daily_seconds")
        const val FLUSH_EVERY_MS = 20_000L
        const val KEEP_DAYS = 30
    }
}