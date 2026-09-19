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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 音效均衡器设置持久化 */
class EqualizerRepository(private val dataStore: DataStore<Preferences>) {

    @Serializable
    data class Stored(
        val enabled: Boolean = false,
        val presetId: String = "flat",
        /** 手动调节的频段电平（毫贝）；空 = 由预设推导 */
        val bandLevelsMb: List<Float> = emptyList(),
        val bassStrength: Int = 0,
        val surroundStrength: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: StateFlow<Stored> = dataStore.data
        .map { prefs -> decode(prefs[KEY]) }
        .stateIn(scope, SharingStarted.Eagerly, Stored())

    suspend fun save(value: Stored) {
        dataStore.edit { prefs -> prefs[KEY] = AppJson.encodeToString(value) }
    }

    private fun decode(raw: String?): Stored =
        if (raw.isNullOrBlank()) Stored()
        else runCatching { AppJson.decodeFromString<Stored>(raw) }.getOrDefault(Stored())

    private companion object {
        val KEY = stringPreferencesKey("equalizer_settings_json")
    }
}