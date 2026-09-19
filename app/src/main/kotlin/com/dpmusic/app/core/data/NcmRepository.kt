package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.model.NcmProfile
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
 * 网易云账号仓储（「一起听」功能）：
 * - Cookie 仅保存在本地（用于 eapi 直连），不写入日志、不上传第三方；
 * - 资料缓存（昵称 / 头像 / uid）供设置页展示；
 * - 上次房间 id 供「恢复连接」入口使用。
 */
class NcmRepository(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val cookie: StateFlow<String> = dataStore.data
        .map { it[KEY_COOKIE].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    val profile: StateFlow<NcmProfile?> = dataStore.data
        .map { prefs ->
            prefs[KEY_PROFILE]?.let { raw ->
                runCatching { AppJson.decodeFromString<NcmProfile>(raw) }.getOrNull()
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val lastRoomId: StateFlow<String> = dataStore.data
        .map { it[KEY_LAST_ROOM].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    /** 上次房间的本地过期时间戳（毫秒；= roomCreateTime + effectiveDurationMs）；0 表示未知 */
    val lastRoomExpireAt: StateFlow<Long> = dataStore.data
        .map { it[KEY_LAST_ROOM_EXPIRE]?.toLongOrNull() ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    /** 上次红心同步时间戳（毫秒）；0 表示尚未同步 */
    val lastLikesSyncAt: StateFlow<Long> = dataStore.data
        .map { it[KEY_LIKES_SYNC_AT]?.toLongOrNull() ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    /** 一起听·自动切歌：歌曲结束后由本端发起切换（用于完整播放 VIP 歌曲） */
    val togetherAutoAdvance: StateFlow<Boolean> = dataStore.data
        .map { it[KEY_TOGETHER_AUTO_ADVANCE] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** 一起听邀请：最后一条已处理（已提示/已忽略）的私信消息 id；0 表示无 */
    val lastTogetherInviteMsgId: StateFlow<Long> = dataStore.data
        .map { it[KEY_TOGETHER_INVITE_MSG_ID]?.toLongOrNull() ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    suspend fun saveCookie(raw: String, profile: NcmProfile) {
        dataStore.edit {
            it[KEY_COOKIE] = raw.trim()
            it[KEY_PROFILE] = AppJson.encodeToString(profile)
        }
    }

    suspend fun clearCookie() {
        dataStore.edit {
            it.remove(KEY_COOKIE)
            it.remove(KEY_PROFILE)
            it.remove(KEY_LAST_ROOM)
            it.remove(KEY_LAST_ROOM_EXPIRE)
            it.remove(KEY_LIKES_SYNC_AT)
            it.remove(KEY_TOGETHER_INVITE_MSG_ID)
        }
    }

    suspend fun setLastRoomId(roomId: String?) {
        dataStore.edit {
            if (roomId.isNullOrBlank()) {
                it.remove(KEY_LAST_ROOM)
                it.remove(KEY_LAST_ROOM_EXPIRE)
            } else {
                it[KEY_LAST_ROOM] = roomId
            }
        }
    }

    /** 写入上次房间的过期时间戳（毫秒）；传 0 清除 */
    suspend fun setLastRoomExpireAt(expireAtMs: Long) {
        dataStore.edit {
            if (expireAtMs <= 0L) it.remove(KEY_LAST_ROOM_EXPIRE) else it[KEY_LAST_ROOM_EXPIRE] = expireAtMs.toString()
        }
    }

    /** 写入上次红心同步时间戳（毫秒）；传 0 清除 */
    suspend fun setLastLikesSyncAt(atMs: Long) {
        dataStore.edit {
            if (atMs <= 0L) it.remove(KEY_LIKES_SYNC_AT) else it[KEY_LIKES_SYNC_AT] = atMs.toString()
        }
    }

    /** 写入一起听·自动切歌开关 */
    suspend fun setTogetherAutoAdvance(enabled: Boolean) {
        dataStore.edit { it[KEY_TOGETHER_AUTO_ADVANCE] = enabled }
    }

    /** 写入一起听邀请·最后处理的私信消息 id（传 0 清除） */
    suspend fun setLastTogetherInviteMsgId(msgId: Long) {
        dataStore.edit {
            if (msgId <= 0L) it.remove(KEY_TOGETHER_INVITE_MSG_ID) else it[KEY_TOGETHER_INVITE_MSG_ID] = msgId.toString()
        }
    }

    private companion object {
        val KEY_COOKIE = stringPreferencesKey("ncm_cookie")
        val KEY_PROFILE = stringPreferencesKey("ncm_profile_json")
        val KEY_LAST_ROOM = stringPreferencesKey("ncm_last_room_id")
        val KEY_LAST_ROOM_EXPIRE = stringPreferencesKey("ncm_last_room_expire_at")
        val KEY_LIKES_SYNC_AT = stringPreferencesKey("ncm_likes_sync_at")
        val KEY_TOGETHER_AUTO_ADVANCE = booleanPreferencesKey("ncm_together_auto_advance")
        val KEY_TOGETHER_INVITE_MSG_ID = stringPreferencesKey("ncm_together_invite_msg_id")
    }
}