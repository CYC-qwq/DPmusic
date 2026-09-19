package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 不喜欢（屏蔽）规则仓库：
 *
 * 行格式（存储为多行文本，每行一条规则）：
 * - `歌名@歌手` —— 精确屏蔽某首歌；
 * - `歌名` —— 屏蔽同名的所有歌曲；
 * - `@歌手` —— 屏蔽该歌手的全部歌曲。
 *
 * 匹配时忽略大小写；文本内的 `@` 以 `#` 转义（与 LX Music 规则格式一致）。
 */
class DislikeRepository(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 原始规则行（保序；供管理与展示） */
    val rules: StateFlow<List<String>> = dataStore.data
        .map { prefs -> prefs[KEY_RULES].orEmpty().split('\n').filter { it.isNotBlank() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // ---------------- 匹配（带缓存：规则列表未变时不重复解析） ----------------

    private var cachedSource: List<String>? = null
    private var cachedNames: Set<String> = emptySet()
    private var cachedMusicNames: Set<String> = emptySet()
    private var cachedSingerNames: Set<String> = emptySet()

    /** 当前歌曲是否命中屏蔽规则（同步判定，供播放链路高频调用） */
    fun matches(song: Song): Boolean {
        val list = rules.value
        if (list !== cachedSource) {
            val names = mutableSetOf<String>()
            val musicNames = mutableSetOf<String>()
            val singerNames = mutableSetOf<String>()
            list.forEach { line ->
                val sep = line.indexOf(RULE_SEPARATOR)
                when {
                    sep < 0 -> musicNames += normalize(line)
                    sep == 0 -> singerNames += normalize(line.substring(1))
                    else -> {
                        val name = normalize(line.substring(0, sep))
                        val singer = normalize(line.substring(sep + 1))
                        names += "$name$RULE_SEPARATOR$singer"
                    }
                }
            }
            cachedNames = names
            cachedMusicNames = musicNames
            cachedSingerNames = singerNames
            cachedSource = list
        }
        val name = normalize(song.title)
        val singer = normalize(song.artist)
        return cachedMusicNames.contains(name) ||
            cachedSingerNames.contains(singer) ||
            cachedNames.contains("$name$RULE_SEPARATOR$singer")
    }

    /** 添加屏蔽规则（歌名 + 歌手；已存在等价规则时不重复添加） */
    suspend fun add(name: String, singer: String) {
        val cleanName = name.trim()
        val cleanSinger = singer.trim()
        if (cleanName.isEmpty() && cleanSinger.isEmpty()) return
        val rule = when {
            cleanSinger.isEmpty() -> cleanName
            cleanName.isEmpty() -> "$RULE_SEPARATOR$cleanSinger"
            else -> "$cleanName$RULE_SEPARATOR$cleanSinger"
        }
        dataStore.edit { prefs ->
            val current = prefs[KEY_RULES].orEmpty().split('\n').filter { it.isNotBlank() }.toMutableList()
            if (current.none { normalizeRule(it) == normalizeRule(rule) }) {
                current.add(rule)
                prefs[KEY_RULES] = current.joinToString("\n")
            }
        }
    }

    /** 移除单条规则（原始行文本） */
    suspend fun remove(raw: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_RULES].orEmpty().split('\n').filter { it.isNotBlank() }
            prefs[KEY_RULES] = current.filterNot { it == raw }.joinToString("\n")
        }
    }

    /** 清空全部规则 */
    suspend fun clear() {
        dataStore.edit { it[KEY_RULES] = "" }
    }

    private fun normalize(text: String): String =
        text.replace(RULE_SEPARATOR, ESCAPE_CHAR).lowercase().trim()

    private fun normalizeRule(raw: String): String {
        val sep = raw.indexOf(RULE_SEPARATOR)
        return when {
            sep < 0 -> normalize(raw)
            sep == 0 -> "$RULE_SEPARATOR" + normalize(raw.substring(1))
            else -> normalize(raw.substring(0, sep)) + "$RULE_SEPARATOR" + normalize(raw.substring(sep + 1))
        }
    }

    private companion object {
        val KEY_RULES = stringPreferencesKey("dislike_rules")

        /** 规则分隔符（歌名与歌手之间） */
        const val RULE_SEPARATOR = '@'

        /** 文本内分隔符的转义字符 */
        const val ESCAPE_CHAR = '#'
    }
}
