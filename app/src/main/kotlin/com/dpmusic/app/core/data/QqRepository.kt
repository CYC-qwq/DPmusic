package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.model.QqProfile
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
 * QQ 音乐账号仓储：
 * - Cookie 仅保存在本地（用于接口直连），不写入日志、不上传第三方；
 * - 资料缓存（昵称 / 头像 / uin）供设置页展示；
 * - 上次红心同步时间供节流与展示。
 */
class QqRepository(private val dataStore: DataStore<Preferences>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val cookie: StateFlow<String> = dataStore.data
        .map { it[KEY_COOKIE].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    val profile: StateFlow<QqProfile?> = dataStore.data
        .map { prefs ->
            prefs[KEY_PROFILE]?.let { raw ->
                runCatching { AppJson.decodeFromString<QqProfile>(raw) }.getOrNull()
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** 上次红心同步时间戳（毫秒）；0 表示尚未同步 */
    val lastLikesSyncAt: StateFlow<Long> = dataStore.data
        .map { it[KEY_LIKES_SYNC_AT]?.toLongOrNull() ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    suspend fun saveCookie(raw: String, profile: QqProfile) {
        dataStore.edit {
            it[KEY_COOKIE] = raw.trim()
            it[KEY_PROFILE] = AppJson.encodeToString(profile)
        }
    }

    suspend fun clearCookie() {
        dataStore.edit {
            it.remove(KEY_COOKIE)
            it.remove(KEY_PROFILE)
            it.remove(KEY_LIKES_SYNC_AT)
        }
    }

    /** 写入上次红心同步时间戳（毫秒）；传 0 清除 */
    suspend fun setLastLikesSyncAt(atMs: Long) {
        dataStore.edit {
            if (atMs <= 0L) it.remove(KEY_LIKES_SYNC_AT) else it[KEY_LIKES_SYNC_AT] = atMs.toString()
        }
    }

    private companion object {
        val KEY_COOKIE = stringPreferencesKey("qq_cookie")
        val KEY_PROFILE = stringPreferencesKey("qq_profile_json")
        val KEY_LIKES_SYNC_AT = stringPreferencesKey("qq_likes_sync_at")
    }
}
