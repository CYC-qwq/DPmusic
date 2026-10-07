package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.model.BiliAccount
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
 * 哔哩哔哩账号仓储（可选登录）：
 * - 登录 Cookie 仅保存在本地（仅供本应用发请求），不写日志、不上传第三方；
 * - 资料缓存（昵称 / 头像 / 会员态）供设置页展示与「按账号定音质」判定；
 * - 未登录时 [cookie] 为空串 → [com.dpmusic.app.core.net.BiliApi] 走匿名链路。
 */
class BiliRepository(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 登录 Cookie（`SESSDATA=…; bili_jct=…; DedeUserID=…`）；空 = 匿名 */
    val cookie: StateFlow<String> = dataStore.data
        .map { it[KEY_COOKIE].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    /** 账号资料（含会员态）；未登录为 null */
    val account: StateFlow<BiliAccount?> = dataStore.data
        .map { prefs ->
            prefs[KEY_ACCOUNT]?.let { raw ->
                runCatching { AppJson.decodeFromString<BiliAccount>(raw) }.getOrNull()
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** 是否大会员（决定能否解锁无损 / 全景声）。未登录视为非会员 */
    fun isVip(): Boolean = account.value?.isVip == true

    suspend fun saveLogin(cookie: String, account: BiliAccount) {
        dataStore.edit {
            it[KEY_COOKIE] = cookie.trim()
            it[KEY_ACCOUNT] = AppJson.encodeToString(account)
        }
    }

    /** 仅刷新资料（Cookie 不变，会员态可能变化） */
    suspend fun updateAccount(account: BiliAccount) {
        dataStore.edit { it[KEY_ACCOUNT] = AppJson.encodeToString(account) }
    }

    suspend fun clearLogin() {
        dataStore.edit {
            it.remove(KEY_COOKIE)
            it.remove(KEY_ACCOUNT)
        }
    }

    private companion object {
        val KEY_COOKIE = stringPreferencesKey("bili_cookie")
        val KEY_ACCOUNT = stringPreferencesKey("bili_account_json")
    }
}