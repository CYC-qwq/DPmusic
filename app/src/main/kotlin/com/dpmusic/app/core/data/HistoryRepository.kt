package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.model.RecentPlay
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 最近播放仓库（含播放进度，供历史进度胶囊展示） */
class HistoryRepository(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val recent: StateFlow<List<RecentPlay>> = dataStore.data
        .map { decode(it[KEY_RECENT]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 播放开始：置顶该曲目 */
    suspend fun recordStart(song: Song) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_RECENT]).toMutableList()
            current.removeAll { it.song.stableKey == song.stableKey }
            current.add(0, RecentPlay(song, System.currentTimeMillis(), 0L, song.durationMs))
            prefs[KEY_RECENT] = encode(current.take(MAX))
        }
    }

    /** 暂停 / 切歌时回写进度 */
    suspend fun updateProgress(songKey: String, positionMs: Long, durationMs: Long) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_RECENT]).toMutableList()
            val index = current.indexOfFirst { it.song.stableKey == songKey }
            if (index >= 0) {
                current[index] = current[index].copy(
                    progressMs = positionMs,
                    durationMs = if (durationMs > 0) durationMs else current[index].durationMs,
                    playedAt = System.currentTimeMillis(),
                )
                prefs[KEY_RECENT] = encode(current)
            }
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY_RECENT) }
    }

    private fun decode(raw: String?): List<RecentPlay> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { AppJson.decodeFromString<List<RecentPlay>>(raw) }.getOrDefault(emptyList())

    private fun encode(list: List<RecentPlay>): String = AppJson.encodeToString(list)

    private companion object {
        val KEY_RECENT = stringPreferencesKey("recent_json")
        const val MAX = 200
    }
}