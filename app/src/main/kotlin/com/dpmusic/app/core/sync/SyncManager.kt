package com.dpmusic.app.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.dpmusic.app.core.data.DislikeRepository
import com.dpmusic.app.core.data.FavoritesRepository
import com.dpmusic.app.core.data.SettingsRepository
import com.dpmusic.app.core.data.UserPlaylistRepository
import com.dpmusic.app.core.download.DownloadTask
import com.dpmusic.app.core.download.DownloadTaskStore
import com.dpmusic.app.core.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 单个偏好项（含类型标签，恢复时按类型还原键值） */
@Serializable
data class PrefEntry(
    val type: String,
    val value: String,
)

/** 「设置与音源」同步文件 */
@Serializable
data class SettingsSyncPayload(
    val version: Int = 1,
    val lastModified: Long = 0L,
    val data: Map<String, PrefEntry> = emptyMap(),
)

/** 「歌单与数据」同步文件（含下载任务记录） */
@Serializable
data class ListsSyncPayload(
    val version: Int = 1,
    val lastModified: Long = 0L,
    val data: Map<String, PrefEntry> = emptyMap(),
    val downloadTasks: List<DownloadTask> = emptyList(),
)

/**
 * WebDAV 数据同步（进程级单例，由 AppContainer 装配）：
 * - 云端文件：settings.json（设置与音源）/ lists.json（歌单与数据 + 下载记录）；
 * - 手动上传 / 恢复为「覆盖」语义；自动同步 = 收藏 / 歌单 / 屏蔽规则变更后节流上传；
 * - 敏感键（账号 Cookie / API Key / WebDAV 密码等）不在同步范围内。
 */
class SyncManager(
    private val dataStore: DataStore<Preferences>,
    private val settings: SettingsRepository,
    private val downloadTasks: DownloadTaskStore,
    favorites: FavoritesRepository,
    playlists: UserPlaylistRepository,
    dislike: DislikeRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 变更节流触发器（自动同步） */
    private val changeTick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        // 收藏 / 歌单 / 屏蔽规则变更 → 触发自动同步（节流）
        combine(
            favorites.favorites,
            playlists.playlists,
            dislike.rules,
        ) { _, _, _ -> Unit }
            .drop(1)
            .onEach { changeTick.tryEmit(Unit) }
            .launchIn(scope)
        scope.launch {
            changeTick.collectLatest {
                delay(AUTO_SYNC_DELAY_MS)
                autoUploadLists()
            }
        }
    }

    /** 是否已启用并配置（可执行同步操作） */
    fun isConfigured(): Boolean {
        val s = settings.settings.value
        return s.webdavEnabled && s.webdavUrl.isNotBlank()
    }

    /** 测试连接（含同步目录创建） */
    suspend fun testConnection() {
        WebDavClient.testConnection(requireConfig())
    }

    /** 上传「设置与音源」，返回时间戳 */
    suspend fun uploadSettings(): Long {
        val config = requireConfig()
        val now = System.currentTimeMillis()
        val payload = SettingsSyncPayload(
            lastModified = now,
            data = snapshot(SyncScopes::matchesSettings),
        )
        WebDavClient.upload(config, FILE_SETTINGS, AppJson.encodeToString(payload))
        settings.setWebdavLastSyncTime(now)
        return now
    }

    /** 从云端恢复「设置与音源」；云端无文件返回 false */
    suspend fun downloadSettings(): Boolean {
        val config = requireConfig()
        val text = WebDavClient.download(config, FILE_SETTINGS) ?: return false
        val payload = AppJson.decodeFromString<SettingsSyncPayload>(text)
        restore(SyncScopes::matchesSettings, payload.data)
        settings.setWebdavLastSyncTime(payload.lastModified)
        return true
    }

    /** 上传「歌单与数据」（含下载记录），返回时间戳 */
    suspend fun uploadLists(): Long {
        val config = requireConfig()
        val now = System.currentTimeMillis()
        val payload = ListsSyncPayload(
            lastModified = now,
            data = snapshot(SyncScopes::matchesLists),
            downloadTasks = downloadTasks.snapshotForSync(),
        )
        WebDavClient.upload(config, FILE_LISTS, AppJson.encodeToString(payload))
        settings.setWebdavLastSyncTime(now)
        return now
    }

    /** 从云端恢复「歌单与数据」；云端无文件返回 false */
    suspend fun downloadLists(): Boolean {
        val config = requireConfig()
        val text = WebDavClient.download(config, FILE_LISTS) ?: return false
        val payload = AppJson.decodeFromString<ListsSyncPayload>(text)
        restore(SyncScopes::matchesLists, payload.data)
        downloadTasks.importFromSync(payload.downloadTasks)
        settings.setWebdavLastSyncTime(payload.lastModified)
        return true
    }

    /* ---------------- 内部 ---------------- */

    private fun requireConfig(): WebDavConfig {
        val s = settings.settings.value
        if (!s.webdavEnabled) throw WebDavException("请先启用 WebDAV 同步")
        if (s.webdavUrl.isBlank()) throw WebDavException("请先填写服务器地址")
        return WebDavConfig(
            baseUrl = s.webdavUrl,
            username = s.webdavUsername,
            password = s.webdavPassword,
            dirPath = s.webdavPath.ifBlank { DEFAULT_DIR },
        )
    }

    /** 自动同步（静默：未启用 / 未配置 / 失败均不打扰用户） */
    private suspend fun autoUploadLists() {
        val s = settings.settings.value
        if (!s.webdavEnabled || !s.webdavAutoSync || s.webdavUrl.isBlank()) return
        runCatching { uploadLists() }
    }

    private suspend fun snapshot(matcher: (String) -> Boolean): Map<String, PrefEntry> {
        val prefs = dataStore.data.first()
        val out = LinkedHashMap<String, PrefEntry>()
        for ((key, value) in prefs.asMap()) {
            if (matcher(key.name)) out[key.name] = encodeEntry(value)
        }
        return out
    }

    /** 恢复：先清空桶内旧键（远程没有的本地键也删除 → 覆盖语义），再写入远程键 */
    private suspend fun restore(matcher: (String) -> Boolean, entries: Map<String, PrefEntry>) {
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { matcher(it.name) }
                .forEach { prefs.remove(it) }
            entries.forEach { (name, entry) ->
                if (matcher(name)) prefs.putEntry(name, entry)
            }
        }
    }

    private fun encodeEntry(value: Any?): PrefEntry = when (value) {
        is Boolean -> PrefEntry("boolean", value.toString())
        is Int -> PrefEntry("int", value.toString())
        is Long -> PrefEntry("long", value.toString())
        is Float -> PrefEntry("float", value.toString())
        is Double -> PrefEntry("double", value.toString())
        is String -> PrefEntry("string", value)
        is Set<*> -> PrefEntry("string_set", AppJson.encodeToString(value.map { it.toString() }))
        else -> PrefEntry("string", value?.toString().orEmpty())
    }

    private fun MutablePreferences.putEntry(name: String, entry: PrefEntry) {
        when (entry.type) {
            "boolean" -> this[booleanPreferencesKey(name)] = entry.value.toBooleanStrictOrNull() ?: false
            "int" -> this[intPreferencesKey(name)] = entry.value.toIntOrNull() ?: 0
            "long" -> this[longPreferencesKey(name)] = entry.value.toLongOrNull() ?: 0L
            "float" -> this[floatPreferencesKey(name)] = entry.value.toFloatOrNull() ?: 0f
            "double" -> this[doublePreferencesKey(name)] = entry.value.toDoubleOrNull() ?: 0.0
            "string" -> this[stringPreferencesKey(name)] = entry.value
            "string_set" -> this[stringSetPreferencesKey(name)] = decodeStringSet(entry.value)
        }
    }

    private fun decodeStringSet(raw: String): Set<String> =
        runCatching { AppJson.decodeFromString<List<String>>(raw).toSet() }.getOrElse { emptySet() }

    private companion object {
        const val FILE_SETTINGS = "settings.json"
        const val FILE_LISTS = "lists.json"
        const val DEFAULT_DIR = "/DPmusic/"
        const val AUTO_SYNC_DELAY_MS = 4000L
    }
}
