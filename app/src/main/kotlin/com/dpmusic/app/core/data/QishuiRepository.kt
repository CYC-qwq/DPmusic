package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.model.QishuiProfile
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
 * 汽水音乐账号仓储：
 * - **登录凭证是「整套 Cookie」而非单个 sessionid**（实测见 工作区…/汽水/登录实现/★★★成功-登录跑通与会话真相.md）；
 * - `sessionid` 仅作展示/兼容，真正用于请求的是 [cookie]；
 * - 全部仅保存在本地，不写日志、不上传第三方。
 *
 * 背景：汽水 PC 端登录后下发 7 个 Cookie
 * （`ttwid` / `passport_csrf_token` / `passport_auth_status_ss` / `uid_tt_ss` /
 * `sessionid_ss` / `session_tlb_tag` / `ssid_ucp_v1`），**且不含 `sessionid`**。
 * 只带 `sessionid_ss` 请求会返回 `1000016 登录状态已失效`。
 */
class QishuiRepository(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 完整 Cookie 串（登录凭证）；空串表示未登录 */
    val cookie: StateFlow<String> = dataStore.data
        .map { it[KEY_COOKIE].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    /** `sessionid` / `sessionid_ss` 的值；汽水 PC 端通常**为空**，仅作展示 */
    val sessionid: StateFlow<String> = dataStore.data
        .map { it[KEY_SESSION].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    /** 是否已登录（以 [cookie] 为准） */
    val loggedIn: StateFlow<Boolean> = dataStore.data
        .map { !it[KEY_COOKIE].isNullOrBlank() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val profile: StateFlow<QishuiProfile?> = dataStore.data
        .map { prefs ->
            prefs[KEY_PROFILE]?.let { raw ->
                runCatching { AppJson.decodeFromString<QishuiProfile>(raw) }.getOrNull()
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 保存登录态。
     *
     * @param cookie 完整 Cookie 串（**必须**，请求时原样回带）
     * @param sessionid `sessionid` / `sessionid_ss` 的值，可为空
     */
    suspend fun saveLogin(cookie: String, sessionid: String, profile: QishuiProfile) {
        dataStore.edit {
            it[KEY_COOKIE] = cookie.trim()
            it[KEY_SESSION] = sessionid.trim()
            it[KEY_PROFILE] = AppJson.encodeToString(profile)
        }
    }

    suspend fun clearLogin() {
        dataStore.edit {
            it.remove(KEY_COOKIE)
            it.remove(KEY_SESSION)
            it.remove(KEY_PROFILE)
        }
    }

    private companion object {
        val KEY_COOKIE = stringPreferencesKey("qishui_cookie")
        val KEY_SESSION = stringPreferencesKey("qishui_sessionid")
        val KEY_PROFILE = stringPreferencesKey("qishui_profile_json")
    }
}
