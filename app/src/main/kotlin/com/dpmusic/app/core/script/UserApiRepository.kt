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
)

/** 脚本导入结果 */
sealed interface ScriptImportResult {
    data class Success(val script: UserScript) : ScriptImportResult
    data class Error(val message: String) : ScriptImportResult
}

/**
 * 自定义音源脚本仓库（DataStore 持久化）：
 * - 脚本列表（元信息 JSON）+ 每脚本内容（独立 key）+ 当前激活脚本 id；
 * - 导入：解析脚本头部注释块的 @name / @description / @author / @homepage / @version
 *   （解析规则与长度截断对齐 LX Music）；
 * - 激活：把脚本加载进 UserApiEngine；启动时自动恢复激活脚本。
 */
class UserApiRepository(
    private val dataStore: DataStore<Preferences>,
    private val engine: UserApiEngine,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已导入脚本列表 */
    val scripts: StateFlow<List<UserScript>> = dataStore.data
        .map { decodeList(it[KEY_SCRIPT_LIST]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 当前激活脚本 id（null = 未启用） */
    val activeId: StateFlow<String?> = dataStore.data
        .map { it[KEY_ACTIVE_ID] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // 启动时自动加载激活脚本
        scope.launch {
            val prefs = dataStore.data.first()
            val id = prefs[KEY_ACTIVE_ID] ?: return@launch
            val script = decodeList(prefs[KEY_SCRIPT_LIST]).find { it.id == id } ?: return@launch
            val content = prefs[contentKey(id)].orEmpty()
            if (content.isNotBlank()) {
                engine.load(
                    scriptId = script.id,
                    name = script.name,
                    description = script.description,
                    version = script.version,
                    author = script.author,
                    homepage = script.homepage,
                    script = content,
                )
            }
        }
    }

    /** 从文本导入脚本 */
    suspend fun importScript(content: String): ScriptImportResult {
        val script = content.trim()
        if (script.isBlank()) return ScriptImportResult.Error("脚本内容为空")
        if (script.length > MAX_SCRIPT_SIZE) return ScriptImportResult.Error("脚本过大（超过 512KB）")
        val commentBlock = extractCommentBlock(script)
            ?: return ScriptImportResult.Error("未找到脚本信息注释块（脚本需以块注释开头）")
        val info = matchScriptInfo(commentBlock)
        val now = System.currentTimeMillis()
        val meta = UserScript(
            id = "user_script_${now}_${(100..999).random()}",
            name = info["name"].orEmpty().ifBlank { "user_script_$now" },
            description = info["description"].orEmpty(),
            author = info["author"].orEmpty(),
            homepage = info["homepage"].orEmpty(),
            version = info["version"].orEmpty(),
            addedAt = now,
        )
        dataStore.edit { prefs ->
            prefs[KEY_SCRIPT_LIST] = encodeList(decodeList(prefs[KEY_SCRIPT_LIST]) + meta)
            prefs[contentKey(meta.id)] = script
        }
        AppLogger.i(TAG, "已导入音源脚本：${meta.name}")
        return ScriptImportResult.Success(meta)
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

    /** 激活脚本（加载进引擎） */
    suspend fun setActive(id: String) {
        dataStore.edit { it[KEY_ACTIVE_ID] = id }
        val prefs = dataStore.data.first()
        val script = decodeList(prefs[KEY_SCRIPT_LIST]).find { it.id == id } ?: return
        val content = prefs[contentKey(id)].orEmpty()
        if (content.isBlank()) {
            AppLogger.w(TAG, "脚本内容缺失，无法加载：${script.name}")
            return
        }
        engine.load(
            scriptId = script.id,
            name = script.name,
            description = script.description,
            version = script.version,
            author = script.author,
            homepage = script.homepage,
            script = content,
        )
    }

    /** 停用当前脚本 */
    suspend fun deactivate() {
        dataStore.edit { it.remove(KEY_ACTIVE_ID) }
        engine.destroy()
    }

    /** 删除脚本 */
    suspend fun remove(id: String) {
        var removedActive = false
        dataStore.edit { prefs ->
            prefs[KEY_SCRIPT_LIST] = encodeList(decodeList(prefs[KEY_SCRIPT_LIST]).filterNot { it.id == id })
            prefs.remove(contentKey(id))
            if (prefs[KEY_ACTIVE_ID] == id) {
                prefs.remove(KEY_ACTIVE_ID)
                removedActive = true
            }
        }
        if (removedActive) engine.destroy()
        AppLogger.i(TAG, "已删除音源脚本：$id")
    }

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
