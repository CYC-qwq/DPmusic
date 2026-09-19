package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

/**
 * 搜索历史仓库：最近搜索关键词（去重置顶，最多 [MAX] 条）。
 */
class SearchHistoryRepository(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val history: StateFlow<List<String>> = dataStore.data
        .map { decode(it[KEY_HISTORY]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 记录一次搜索：去重（忽略大小写）后置顶 */
    suspend fun record(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_HISTORY]).toMutableList()
            current.removeAll { it.equals(k, ignoreCase = true) }
            current.add(0, k)
            prefs[KEY_HISTORY] = encode(current.take(MAX))
        }
    }

    /** 删除单条 */
    suspend fun remove(keyword: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_HISTORY]).toMutableList()
            current.removeAll { it == keyword }
            prefs[KEY_HISTORY] = encode(current)
        }
    }

    /** 清空全部 */
    suspend fun clear() {
        dataStore.edit { it.remove(KEY_HISTORY) }
    }

    private fun decode(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { AppJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    private fun encode(list: List<String>): String = AppJson.encodeToString(list)

    private companion object {
        val KEY_HISTORY = stringPreferencesKey("search_history_json")
        const val MAX = 20
    }
}
