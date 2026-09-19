package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 播放会话仓库：持久化「上一次播放队列 + 当前曲目索引 + 播放进度」，
 * 供主页「继续收听」在冷启动后恢复完整队列与进度条。
 */
class PlaybackSessionStore(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val session: StateFlow<PlaybackSession?> = dataStore.data
        .map { decode(it[KEY_SESSION]) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun save(session: PlaybackSession) {
        dataStore.edit { prefs ->
            prefs[KEY_SESSION] = AppJson.encodeToString(
                session.copy(updatedAt = System.currentTimeMillis()),
            )
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY_SESSION) }
    }

    private fun decode(raw: String?): PlaybackSession? =
        if (raw.isNullOrBlank()) null
        else runCatching { AppJson.decodeFromString<PlaybackSession>(raw) }.getOrNull()

    private companion object {
        val KEY_SESSION = stringPreferencesKey("playback_session_json")
    }
}

/** 播放会话快照（持久化）：队列 + 当前索引 + 进度 */
@Serializable
data class PlaybackSession(
    val songs: List<Song> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val updatedAt: Long = 0L,
)