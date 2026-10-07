package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.model.KgLiteProfile
import com.dpmusic.app.core.net.AppJson
import com.dpmusic.app.core.net.KgLiteDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 酷狗概念版账号仓储：
 * - `token` / `userid` 仅保存在本地（用于取播放地址），不写日志、不上传第三方；
 * - 首次使用时生成并持久化一个随机 `mid`（设备标识），后续复用；
 * - 资料缓存（昵称 / VIP 类型）供设置页展示。
 */
class KgLiteRepository(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val token: StateFlow<String> = dataStore.data
        .map { it[KEY_TOKEN].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    /** 持久化的设备标识（数字串）；为空表示尚未初始化（首次读取时生成） */
    val mid: StateFlow<String> = dataStore.data
        .map { it[KEY_MID].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    val profile: StateFlow<KgLiteProfile?> = dataStore.data
        .map { prefs ->
            prefs[KEY_PROFILE]?.let { raw ->
                runCatching { AppJson.decodeFromString<KgLiteProfile>(raw) }.getOrNull()
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** 组装给 [com.dpmusic.app.core.net.KgLiteApi] 使用的设备/账号凭据 */
    fun device(): KgLiteDevice = KgLiteDevice(
        mid = mid.value.ifBlank { "0" },
        dfid = KgLiteDevice.DEFAULT_DFID,
        userId = profile.value?.userId.orEmpty(),
        token = token.value,
    )

    /** 上次自动签到日期（yyyy-MM-dd）；空表示从未签到。直接从 DataStore 读取以持久生效 */
    suspend fun lastClaimDate(): String = dataStore.data.first()[KEY_LAST_CLAIM].orEmpty()

    /** 记录已签到日期（用于同日去重） */
    suspend fun markClaimed(date: String) {
        dataStore.edit { it[KEY_LAST_CLAIM] = date }
    }

    /** 首次使用时确保已有一个持久化的随机 mid（写回 DataStore） */
    suspend fun ensureMid(seed: () -> String) {
        dataStore.edit {
            val cur = it[KEY_MID].orEmpty()
            if (cur.isBlank()) it[KEY_MID] = seed()
        }
    }

    suspend fun saveLogin(token: String, profile: KgLiteProfile) {
        dataStore.edit {
            it[KEY_TOKEN] = token.trim()
            it[KEY_PROFILE] = AppJson.encodeToString(profile)
        }
    }

    suspend fun clearLogin() {
        dataStore.edit {
            it.remove(KEY_TOKEN)
            it.remove(KEY_PROFILE)
        }
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("kglite_token")
        val KEY_MID = stringPreferencesKey("kglite_mid")
        val KEY_PROFILE = stringPreferencesKey("kglite_profile_json")
        val KEY_LAST_CLAIM = stringPreferencesKey("kglite_last_claim_date")
    }
}