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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** 已导入的自定义音源脚本（元信息；脚本原文单独存储） */
@Serializable
data class UserScript(
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val homepage: String = "",
    val version: String = "",
    val addedAt: Long = 0L,
    /** 内容指纹（SHA-256 前 16 字节 hex），用于导入去重；老数据为空串（读取时自动补算） */
    val fingerprint: String = "",
)

/** 脚本导入结果 */
sealed interface ScriptImportResult {
    data class Success(val script: UserScript) : ScriptImportResult

    /** 内容与已存在的脚本重复（[existing] 为命中的那条，用于提示文案） */
    data class Duplicate(val existing: UserScript) : ScriptImportResult

    data class Error(val message: String) : ScriptImportResult
}

/**
 * 批量导入汇总结果（多选导入用）。
 *
 * @param imported 成功导入的条目
 * @param duplicates 因内容重复被跳过的条目
 * @param failures 失败项（错误信息 + 来源名）
 */
data class BatchImportResult<T>(
    val imported: List<T> = emptyList(),
    val duplicates: List<T> = emptyList(),
    val failures: List<Pair<String, String>> = emptyList(),
) {
    val total: Int get() = imported.size + duplicates.size + failures.size

    /** 生成一句可直接展示的汇总文案 */
    fun summary(noun: String): String = buildString {
        append("已导入 ${imported.size} 个$noun")
        if (duplicates.isNotEmpty()) append("，跳过重复 ${duplicates.size} 个")
        if (failures.isNotEmpty()) append("，失败 ${failures.size} 个")
        if (total == 0) append("（没有可导入的文件）")
        // 单条失败时把原因直接说出来，省得用户再去翻日志
        failures.singleOrNull()?.let { (src, msg) -> append("：$msg") }
    }
}

/**
 * 自定义音源脚本仓库（DataStore 持久化）：
 * - 脚本列表（元信息 JSON）+ 每脚本内容（独立 key）；
 * - 导入：解析脚本头部注释块的 @name / @description / @author / @homepage / @version
 *   （解析规则与长度截断对齐 LX Music）；
 * - **驻留管理**：把 [ScriptEnginePool] 与「被任一平台启用的脚本」对齐
 *   （见 [ScriptOrder.residentScriptIds]），脚本一导入即可用。
 *
 * ## 与旧版的区别
 * 旧版是「单选激活」（`active_id`，激活 B 会自动停用 A）。现在顺序与启停都在
 * [ScriptOrder] 里（**逐平台**），可以同时启用多个脚本、并各自排序。
 * 因此本仓库不再持有 `activeId`；`user_script_active_id` 这个旧键**保留不删**
 * （回滚安全），仅在首次生成默认顺序时作为「把哪个脚本排在前面」的参考。
 */
class UserApiRepository(
    private val dataStore: DataStore<Preferences>,
    private val pool: ScriptEnginePool,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已导入脚本列表 */
    val scripts: StateFlow<List<UserScript>> = dataStore.data
        .map { decodeList(it[KEY_SCRIPT_LIST]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 每个脚本的**实时**内存占用（字节）。由 [refreshMemoryUsage] 刷新。 */
    val memoryUsage: StateFlow<Map<String, Long>> = pool.memoryUsage

    init {
        // 启动时把池与「启用列表」对齐：用户上次启用的脚本会自动重新驻留
        scope.launch {
            dataStore.data.collect { prefs ->
                syncPool(prefs)
            }
        }
    }

    /**
     * 把池与「被任一平台启用的脚本」对齐。
     *
     * 只卸载「不再被任何平台启用」的脚本 —— 用户在某平台临时关掉、别的平台仍启用时，
     * 无需重新跑一遍 JS（重新加载明显更慢）。
     */
    private suspend fun syncPool(prefs: Preferences) {
        val all = decodeList(prefs[KEY_SCRIPT_LIST])
        val orders = decodeOrders(prefs[KEY_SCRIPT_ORDERS])
        val readyOrders = ScriptOrder.sanitize(orders, mapOf(ScriptKind.SCRIPT to all.map { it.id }))
        val wantedIds = readyOrders.residentIds(ScriptKind.SCRIPT)

        val requests = all.filter { it.id in wantedIds }.mapNotNull { meta ->
            val content = prefs[contentKey(meta.id)].orEmpty()
            if (content.isBlank()) {
                AppLogger.w(TAG, "脚本内容缺失，跳过驻留：${meta.name}")
                null
            } else {
                ScriptLoadRequest(
                    id = meta.id,
                    name = meta.name,
                    description = meta.description,
                    version = meta.version,
                    author = meta.author,
                    homepage = meta.homepage,
                    content = content,
                )
            }
        }
        pool.reconcile(requests)
    }

    /** 某个脚本当前的加载状态（UI 用来显示「未加载 / 加载中 / 就绪 / 失败」） */
    fun statusOf(scriptId: String) = pool.statusOf(scriptId)

    /** 刷新内存占用（UI 按需调用，不必每帧刷） */
    fun refreshMemoryUsage() = pool.refreshMemoryUsage()

    /** 从文本导入脚本（自动跳过内容重复的脚本） */
    suspend fun importScript(content: String): ScriptImportResult {
        val script = content.trim()
        if (script.isBlank()) return ScriptImportResult.Error("脚本内容为空")
        if (script.length > MAX_SCRIPT_SIZE) return ScriptImportResult.Error("脚本过大（超过 512KB）")
        val commentBlock = extractCommentBlock(script)
            ?: return ScriptImportResult.Error("未找到脚本信息注释块（脚本需以块注释开头）")
        val info = matchScriptInfo(commentBlock)

        val fingerprint = SourceFingerprint.of(script)
        val prefs = dataStore.data.first()
        val existing = decodeList(prefs[KEY_SCRIPT_LIST])
        // 去重：内容指纹相同 → 直接跳过，避免同一条音源在列表里堆多份
        existing.firstOrNull { fingerprintOf(it, prefs) == fingerprint }?.let {
            AppLogger.i(TAG, "跳过重复音源脚本：${it.name}")
            return ScriptImportResult.Duplicate(it)
        }

        val now = System.currentTimeMillis()
        val meta = UserScript(
            id = "user_script_${now}_${(100..999).random()}",
            name = info["name"].orEmpty().ifBlank { "user_script_$now" },
            description = info["description"].orEmpty(),
            author = info["author"].orEmpty(),
            homepage = info["homepage"].orEmpty(),
            version = info["version"].orEmpty(),
            addedAt = now,
            fingerprint = fingerprint,
        )
        dataStore.edit { store ->
            store[KEY_SCRIPT_LIST] = encodeList(decodeList(store[KEY_SCRIPT_LIST]) + meta)
            store[contentKey(meta.id)] = script
        }
        AppLogger.i(TAG, "已导入音源脚本：${meta.name}")
        return ScriptImportResult.Success(meta)
    }

    /**
     * 批量导入脚本（多选文件用）。
     *
     * 逐条走 [importScript]，因此**同一批次内**的两个重复文件也会被正确识别（第二条起命中重复）。
     */
    suspend fun importScriptsBatch(contents: List<String>): BatchImportResult<UserScript> {
        val imported = mutableListOf<UserScript>()
        val duplicates = mutableListOf<UserScript>()
        val failures = mutableListOf<Pair<String, String>>()
        contents.forEachIndexed { index, text ->
            when (val r = importScript(text)) {
                is ScriptImportResult.Success -> imported += r.script
                is ScriptImportResult.Duplicate -> duplicates += r.existing
                is ScriptImportResult.Error -> failures += "#${index + 1}" to r.message
            }
        }
        return BatchImportResult(imported, duplicates, failures)
    }

    /**
     * 取某条脚本的指纹：优先用已存字段；老数据（字段为空）现场按内容补算，
     * 保证「升级前导入的脚本」也能被新导入的文件识别为重复。
     */
    private fun fingerprintOf(script: UserScript, prefs: Preferences): String {
        if (script.fingerprint.isNotBlank()) return script.fingerprint
        val content = prefs[contentKey(script.id)] ?: return ""
        return SourceFingerprint.of(content)
    }

    /** 从 URL 下载并导入 */
    suspend fun importFromUrl(url: String): ScriptImportResult {
        val target = url.trim()
        if (target.isBlank()) return ScriptImportResult.Error("请输入脚本链接")
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return ScriptImportResult.Error("链接需以 http(s):// 开头")
        }
        return try {
            importScript(Http.get(target))
        } catch (e: Exception) {
            ScriptImportResult.Error("下载失败：${e.message ?: "网络错误"}")
        }
    }

    /* ---------------- 顺序与启停（逐平台，由 SettingsRepository 持久化） ---------------- */
    // 旧版的 setActive / deactivate 已删除：
    //   「激活一个」→ 现在表达为「在顺序里把其余关掉」；
    //   「停用当前」→ 现在表达为「把所有平台上的它都关掉」。
    // 两者都是 ScriptOrder 上的普通编辑，不需要仓库提供专门入口。

    /** 删除脚本（顺带从各平台的顺序里移除；池会在下次对齐时卸载它） */
    suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            prefs[KEY_SCRIPT_LIST] = encodeList(decodeList(prefs[KEY_SCRIPT_LIST]).filterNot { it.id == id })
            prefs.remove(contentKey(id))
            // 旧键里若正指向它，一并清掉（键本身保留，只是不再指向已删除的脚本）
            if (prefs[KEY_ACTIVE_ID] == id) prefs.remove(KEY_ACTIVE_ID)
        }
        AppLogger.i(TAG, "已删除音源脚本：$id")
    }

    private fun decodeOrders(raw: String?): List<ScriptOrder> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { AppJson.decodeFromString<List<ScriptOrder>>(raw) }.getOrDefault(emptyList())

    private fun contentKey(id: String) = stringPreferencesKey("user_script_content_$id")

    private fun decodeList(raw: String?): List<UserScript> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { AppJson.decodeFromString<List<UserScript>>(raw) }.getOrDefault(emptyList())

    private fun encodeList(list: List<UserScript>): String = AppJson.encodeToString(list)

    companion object {
        private const val TAG = "UserApi"
        private const val MAX_SCRIPT_SIZE = 512 * 1024

        private val KEY_SCRIPT_LIST = stringPreferencesKey("user_script_list_json")
        private val KEY_ACTIVE_ID = stringPreferencesKey("user_script_active_id")

        /** JS 顺序与启停（逐平台）；与 [MusicFreePluginRepository] 共读同一个键 */
        private val KEY_SCRIPT_ORDERS = stringPreferencesKey("script_orders_json")

        /** 元信息字段长度上限（对齐 LX Music：超长截断加 ...） */
        private val INFO_LIMITS = linkedMapOf(
            "name" to 24,
            "description" to 36,
            "author" to 56,
            "homepage" to 1024,
            "version" to 36,
        )

        /** 提取脚本头部注释块（首个块注释） */
        private fun extractCommentBlock(script: String): String? =
            Regex("""^/\*[\S\s]+?\*/""").find(script)?.value

        /** 解析注释块中的 @字段（对齐 LX Music matchInfo 实现） */
        private fun matchScriptInfo(commentBlock: String): Map<String, String> {
            val infos = mutableMapOf<String, String>()
            val pattern = Regex("""^\s?\*\s?@(\w+)\s(.+)${'$'}""")
            commentBlock.split(Regex("""\r?\n""")).forEach { line ->
                val result = pattern.find(line) ?: return@forEach
                val key = result.groupValues[1]
                if (!INFO_LIMITS.containsKey(key)) return@forEach
                infos[key] = result.groupValues[2].trim()
            }
            INFO_LIMITS.forEach { (key, limit) ->
                val value = infos[key].orEmpty()
                infos[key] = if (value.length > limit) value.take(limit) + "..." else value
            }
            return infos
        }
    }
}
