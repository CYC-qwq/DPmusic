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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 收藏歌曲仓库（DataStore JSON 持久化） */
class FavoritesRepository(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val favorites: StateFlow<List<Song>> = dataStore.data
        .map { decodeSongs(it[KEY_FAVORITES]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun toggle(song: Song) {
        dataStore.edit { prefs ->
            val current = decodeSongs(prefs[KEY_FAVORITES]).toMutableList()
            val removed = current.removeAll { it.stableKey == song.stableKey }
            if (!removed) current.add(0, song)
            prefs[KEY_FAVORITES] = encodeSongs(current.take(MAX))
        }
    }

    /** 批量添加（红心同步等场景）：按 stableKey 去重、忽略已存在项；返回新增数量 */
    suspend fun addAll(songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        var added = 0
        dataStore.edit { prefs ->
            val current = decodeSongs(prefs[KEY_FAVORITES]).toMutableList()
            val existing = current.mapTo(mutableSetOf()) { it.stableKey }
            for (song in songs) {
                if (current.size >= MAX) break
                if (existing.add(song.stableKey)) {
                    current.add(0, song)
                    added++
                }
            }
            prefs[KEY_FAVORITES] = encodeSongs(current)
        }
        return added
    }

    suspend fun remove(key: String) {
        dataStore.edit { prefs ->
            val current = decodeSongs(prefs[KEY_FAVORITES]).filterNot { it.stableKey == key }
            prefs[KEY_FAVORITES] = encodeSongs(current)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY_FAVORITES) }
    }

    private fun decodeSongs(raw: String?): List<Song> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { AppJson.decodeFromString<List<Song>>(raw) }.getOrDefault(emptyList())

    private fun encodeSongs(list: List<Song>): String = AppJson.encodeToString(list)

    private companion object {
        val KEY_FAVORITES = stringPreferencesKey("favorites_json")
        const val MAX = 2000
    }
}