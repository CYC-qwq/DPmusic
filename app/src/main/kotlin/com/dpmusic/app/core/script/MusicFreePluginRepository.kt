package com.dpmusic.app.core.script

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.model.ScriptKind
import com.dpmusic.app.core.model.ScriptOrder
import com.dpmusic.app.core.model.residentIds
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
    /** 内容指纹（SHA-256 前 16 字节 hex），用于导入去重；老数据为空串（读取时自动补算） */
    val fingerprint: String = "",
)

/** 插件导入结果 */
sealed interface PluginImportResult {
    data class Success(val plugin: MusicFreePlugin) : PluginImportResult

    /** 内容与已存在的插件重复（[existing] 为命中的那条，用于提示文案） */
    data class Duplicate(val existing: MusicFreePlugin) : PluginImportResult

    data class Error(val message: String) : PluginImportResult
}

/**
 * MusicFree 插件仓库（DataStore 持久化）：
 * - 插件列表（元信息 JSON）+ 插件原文（独立 key）+ 用户变量；
 * - 导入：从插件源码里解析 platform / version / author（MusicFree 插件无注释头，元信息在导出对象里）；
 * - **驻留管理**：把 [PluginEnginePool] 与「被任一平台启用的插件」对齐，导入即可用。
 *
 * 与旧版的区别同 [UserApiRepository]：不再是「单选激活」，顺序与启停都在
 * [ScriptOrder] 里（逐平台）。旧键 `musicfree_plugin_active_id` 保留不删。
 */
class MusicFreePluginRepository(
    private val dataStore: DataStore<Preferences>,
    private val pool: PluginEnginePool,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已导入插件列表 */
    val plugins: StateFlow<List<MusicFreePlugin>> = dataStore.data
        .map { decodeList(it[KEY_PLUGIN_LIST]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 每个插件的**实时**内存占用（字节）。由 [refreshMemoryUsage] 刷新。 */
    val memoryUsage: StateFlow<Map<String, Long>> = pool.memoryUsage

    init {
        // 启动时把池与「启用列表」对齐：用户上次启用的插件会自动重新挂载
        scope.launch {
            dataStore.data.collect { prefs -> syncPool(prefs) }
        }
    }

    /** 把池与「被任一平台启用的插件」对齐（只卸载不再被任何平台启用的） */
    private suspend fun syncPool(prefs: Preferences) {
        val all = decodeList(prefs[KEY_PLUGIN_LIST])
        val orders = decodeOrders(prefs[KEY_SCRIPT_ORDERS])
        val readyOrders = ScriptOrder.sanitize(orders, mapOf(ScriptKind.PLUGIN to all.map { it.id }))
        val wantedIds = readyOrders.residentIds(ScriptKind.PLUGIN)

        val requests = all.filter { it.id in wantedIds }.mapNotNull { meta ->
            val content = prefs[contentKey(meta.id)].orEmpty()
            if (content.isBlank()) {
                AppLogger.w(TAG, "插件内容缺失，跳过挂载：${meta.name}")
                null
            } else {
                PluginLoadRequest(meta.id, content, decodeVars(prefs[varsKey(meta.id)]))
            }
        }
        pool.reconcile(requests)
    }

    fun statusOf(pluginId: String) = pool.statusOf(pluginId)

    fun refreshMemoryUsage() = pool.refreshMemoryUsage()

    /** 从文本导入插件（sourceName 用于兜底命名；自动跳过内容重复的插件） */
    suspend fun importPlugin(content: String, sourceName: String = ""): PluginImportResult {
        val source = content.trim()
        if (source.isBlank()) return PluginImportResult.Error("插件内容为空")
        if (source.length > MAX_PLUGIN_SIZE) return PluginImportResult.Error("插件过大（超过 512KB）")
        if (!source.contains("module.exports") && !source.contains("exports.")) {
            return PluginImportResult.Error("未找到 module.exports，可能不是 MusicFree 插件")
        }

        val fingerprint = SourceFingerprint.of(source)
        val prefs = dataStore.data.first()
        val existing = decodeList(prefs[KEY_PLUGIN_LIST])
        // 去重：内容指纹相同 → 直接跳过，避免同一个插件在列表里堆多份
        existing.firstOrNull { fingerprintOf(it, prefs) == fingerprint }?.let {
            AppLogger.i(TAG, "跳过重复插件：${it.name}")
            return PluginImportResult.Duplicate(it)
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
            fingerprint = fingerprint,
        )
        dataStore.edit { store ->
            store[KEY_PLUGIN_LIST] = encodeList(decodeList(store[KEY_PLUGIN_LIST]) + meta)
            store[contentKey(meta.id)] = source
        }
        AppLogger.i(TAG, "已导入插件：${meta.name}（${meta.platform}）")
        return PluginImportResult.Success(meta)
    }

    /**
     * 批量导入插件（多选文件用）。
     *
     * [items] 为「内容 to 来源名」列表；逐条走 [importPlugin]，
     * 因此**同一批次内**的重复文件也会被正确识别（第二条起命中重复）。
     */
    suspend fun importPluginsBatch(items: List<Pair<String, String>>): BatchImportResult<MusicFreePlugin> {
        val imported = mutableListOf<MusicFreePlugin>()
        val duplicates = mutableListOf<MusicFreePlugin>()
        val failures = mutableListOf<Pair<String, String>>()
        items.forEachIndexed { index, (text, name) ->
            when (val r = importPlugin(text, name)) {
                is PluginImportResult.Success -> imported += r.plugin
                is PluginImportResult.Duplicate -> duplicates += r.existing
                is PluginImportResult.Error -> failures += name.ifBlank { "#${index + 1}" } to r.message
            }
        }
        return BatchImportResult(imported, duplicates, failures)
    }

    /**
     * 取某条插件的指纹：优先用已存字段；老数据（字段为空）现场按内容补算，
     * 保证「升级前导入的插件」也能被新导入的文件识别为重复。
     */
    private fun fingerprintOf(plugin: MusicFreePlugin, prefs: Preferences): String {
        if (plugin.fingerprint.isNotBlank()) return plugin.fingerprint
        val content = prefs[contentKey(plugin.id)] ?: return ""
        return SourceFingerprint.of(content)
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

    /* ---------------- 顺序与启停（逐平台，由 SettingsRepository 持久化） ---------------- */
    // 旧版的 setActive / deactivate 已删除，理由同 UserApiRepository：
    // 「激活一个」= 在顺序里把其余关掉；「停用当前」= 把所有平台上的它都关掉。

    /** 删除插件（顺带从各平台的顺序里移除；池会在下次对齐时卸载它） */
    suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            prefs[KEY_PLUGIN_LIST] = encodeList(decodeList(prefs[KEY_PLUGIN_LIST]).filterNot { it.id == id })
            prefs.remove(contentKey(id))
            prefs.remove(varsKey(id))
            // 旧键里若正指向它，一并清掉（键本身保留）
            if (prefs[KEY_ACTIVE_ID] == id) prefs.remove(KEY_ACTIVE_ID)
        }
        AppLogger.i(TAG, "已删除插件：$id")
    }

    private fun decodeOrders(raw: String?): List<ScriptOrder> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { AppJson.decodeFromString<List<ScriptOrder>>(raw) }.getOrDefault(emptyList())

    /**
     * 保存插件的用户变量（如 Cookie）。
     *
     * 改完变量必须**重新挂载**才会生效（插件在 `load` 时读取变量），
     * 所以这里让池重新对齐一次：先把该插件从池里摘掉，再触发一次 [syncPool]。
     */
    suspend fun saveUserVariables(id: String, variables: Map<String, String>) {
        val payload = AppJson.encodeToString(variables)
        dataStore.edit { it[varsKey(id)] = payload }
        // 先摘掉旧实例：下次 dataStore 变更触发的 syncPool 会把它重新挂载（带上新变量）
        pool.unload(id)
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

        /** JS 顺序与启停（逐平台）；与 [UserApiRepository] 共读同一个键 */
        val KEY_SCRIPT_ORDERS = stringPreferencesKey("script_orders_json")

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