package com.dpmusic.app.core.script

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.net.AppJson
import com.dpmusic.app.core.net.Http
import com.dpmusic.app.core.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 已导入的 MusicFree 音源插件（元信息；插件原文与用户变量单独存储） */
@Serializable
data class MusicFreePlugin(
    val id: String,
    val name: String,
    val platform: String = "",
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val addedAt: Long = 0L,
    /** 插件声明的用户自定义输入项（挂载后由引擎回填） */
    val variableKeys: List<String> = emptyList(),
)

/** 插件导入结果 */
sealed interface PluginImportResult {
    data class Success(val plugin: MusicFreePlugin) : PluginImportResult
    data class Error(val message: String) : PluginImportResult
}

/**
 * MusicFree 插件仓库（DataStore 持久化）：
 * - 插件列表（元信息 JSON）+ 插件原文（独立 key）+ 用户变量 + 当前激活插件 id；
 * - 导入：从插件源码里解析 platform / version / author（MusicFree 插件无注释头，元信息在导出对象里）；
 * - 激活：把插件挂载进 [MusicFreeEngine]；启动时自动恢复激活插件。
 */
class MusicFreePluginRepository(
    private val dataStore: DataStore<Preferences>,
    private val engine: MusicFreeEngine,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已导入插件列表 */
    val plugins: StateFlow<List<MusicFreePlugin>> = dataStore.data
        .map { decodeList(it[KEY_PLUGIN_LIST]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 当前激活插件 id（null = 未启用） */
    val activeId: StateFlow<String?> = dataStore.data
        .map { it[KEY_ACTIVE_ID] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            val prefs = dataStore.data.first()
            val id = prefs[KEY_ACTIVE_ID] ?: return@launch
            val plugin = decodeList(prefs[KEY_PLUGIN_LIST]).find { it.id == id } ?: return@launch
            val content = prefs[contentKey(id)].orEmpty()
            if (content.isNotBlank()) {
                engine.load(plugin.id, content, decodeVars(prefs[varsKey(id)]))
            }
        }
    }

    /** 从文本导入插件（sourceName 用于兜底命名） */
    suspend fun importPlugin(content: String, sourceName: String = ""): PluginImportResult {
        val source = content.trim()
        if (source.isBlank()) return PluginImportResult.Error("插件内容为空")
        if (source.length > MAX_PLUGIN_SIZE) return PluginImportResult.Error("插件过大（超过 512KB）")
        if (!source.contains("module.exports") && !source.contains("exports.")) {
            return PluginImportResult.Error("未找到 module.exports，可能不是 MusicFree 插件")
        }
        val now = System.currentTimeMillis()
        val platform = matchString(source, "platform")
        val version = matchString(source, "version")
        val author = matchString(source, "author")
        val description = matchString(source, "description")
        val meta = MusicFreePlugin(
            id = "mf_plugin_${now}_${(100..999).random()}",
            name = platform.ifBlank { sourceName.ifBlank { "mf_plugin_$now" } },
            platform = platform,
            version = version,
            author = author,
            description = description,
            addedAt = now,
        )
        dataStore.edit { prefs ->
            prefs[KEY_PLUGIN_LIST] = encodeList(decodeList(prefs[KEY_PLUGIN_LIST]) + meta)
            prefs[contentKey(meta.id)] = source
        }
        AppLogger.i(TAG, "已导入插件：${meta.name}（${meta.platform}）")
        return PluginImportResult.Success(meta)
    }

    /** 从 URL 下载并导入 */
    suspend fun importFromUrl(url: String): PluginImportResult {
        val target = url.trim()
        if (target.isBlank()) return PluginImportResult.Error("请输入插件链接")
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return PluginImportResult.Error("链接需以 http(s):// 开头")
        }
        return try {
            val fileName = target.substringAfterLast('/').substringBefore('?').removeSuffix(".js")
            importPlugin(Http.get(target), fileName)
        } catch (e: Exception) {
            PluginImportResult.Error("下载失败：${e.message ?: "网络错误"}")
        }
    }

    /** 激活插件（挂载进引擎） */
    suspend fun setActive(id: String) {
        dataStore.edit { it[KEY_ACTIVE_ID] = id }
        val prefs = dataStore.data.first()
        val plugin = decodeList(prefs[KEY_PLUGIN_LIST]).find { it.id == id } ?: return
        val content = prefs[contentKey(id)].orEmpty()
        if (content.isBlank()) {
            AppLogger.w(TAG, "插件内容缺失，无法挂载：${plugin.name}")
            return
        }
        engine.load(plugin.id, content, decodeVars(prefs[varsKey(id)]))
    }

    /** 停用当前插件 */
    suspend fun deactivate() {
        dataStore.edit { it.remove(KEY_ACTIVE_ID) }
        engine.destroy()
    }

    /** 删除插件 */
    suspend fun remove(id: String) {
        var removedActive = false
        dataStore.edit { prefs ->
            prefs[KEY_PLUGIN_LIST] = encodeList(decodeList(prefs[KEY_PLUGIN_LIST]).filterNot { it.id == id })
            prefs.remove(contentKey(id))
            prefs.remove(varsKey(id))
            if (prefs[KEY_ACTIVE_ID] == id) {
                prefs.remove(KEY_ACTIVE_ID)
                removedActive = true
            }
        }
        if (removedActive) engine.destroy()
        AppLogger.i(TAG, "已删除插件：$id")
    }

    /** 保存插件的用户变量（如 Cookie），若为激活插件则立即重新挂载 */
    suspend fun saveUserVariables(id: String, variables: Map<String, String>) {
        val payload = AppJson.encodeToString(variables)
        dataStore.edit { it[varsKey(id)] = payload }
        if (activeId.value == id) setActive(id)
    }

    /** 读取插件的用户变量 */
    suspend fun userVariables(id: String): Map<String, String> {
        val prefs = dataStore.data.first()
        return decodeVars(prefs[varsKey(id)])
    }

    /** 读取插件原文（调试 / 查看用） */
    suspend fun sourceOf(id: String): String {
        val prefs = dataStore.data.first()
        return prefs[contentKey(id)].orEmpty()
    }

    private fun contentKey(id: String) = stringPreferencesKey("mf_plugin_source_$id")

    private fun varsKey(id: String) = stringPreferencesKey("mf_plugin_vars_$id")

    private fun decodeList(raw: String?): List<MusicFreePlugin> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { AppJson.decodeFromString<List<MusicFreePlugin>>(raw) }.getOrDefault(emptyList())

    private fun encodeList(list: List<MusicFreePlugin>): String = AppJson.encodeToString(list)

    private fun decodeVars(raw: String?): Map<String, String> =
        if (raw.isNullOrBlank()) emptyMap()
        else runCatching { AppJson.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())

    private companion object {
        const val TAG = "MusicFreePlugin"
        const val MAX_PLUGIN_SIZE = 512 * 1024

        val KEY_PLUGIN_LIST = stringPreferencesKey("mf_plugin_list_json")
        val KEY_ACTIVE_ID = stringPreferencesKey("mf_plugin_active_id")

        /** 从插件源码中提取字符串字段（MusicFree 插件元信息写在导出对象里） */
        fun matchString(source: String, field: String): String =
            Regex("""\b$field\s*:\s*['"`]([^'"`]{0,80})['"`]""")
                .find(source)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
                .trim()
    }
}