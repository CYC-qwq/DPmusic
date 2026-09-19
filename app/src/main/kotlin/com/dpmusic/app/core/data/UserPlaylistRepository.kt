package com.dpmusic.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dpmusic.app.core.model.AutoUpdateMode
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.model.UserPlaylist
import com.dpmusic.app.core.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * 本地歌单仓库（DataStore JSON 持久化）：
 * - 创建 / 重命名 / 删除 / 添加歌曲 / 移除歌曲；
 * - 导出：全部歌单 → 备份 JSON 文本（可写入文件分享）；
 * - 导入：解析备份 JSON → 追加合并（重名自动加后缀，ID 重新生成防冲突）。
 */
class UserPlaylistRepository(private val dataStore: DataStore<Preferences>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val playlists: StateFlow<List<UserPlaylist>> = dataStore.data
        .map { decode(it[KEY_PLAYLISTS]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_PLAYLISTS])
            prefs[KEY_PLAYLISTS] = encode(
                current + UserPlaylist(id = id, name = name.trim(), createdAt = System.currentTimeMillis()),
            )
        }
        return id
    }

    /** 创建带歌曲的歌单（链接导入场景）：重名自动加后缀，返回新歌单 ID */
    suspend fun createWithSongs(
        name: String,
        songs: List<Song>,
        sourceLink: String? = null,
        sourcePlatformId: String? = null,
        sourcePlaylistId: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_PLAYLISTS])
            val finalName = uniqueName(name.trim().ifBlank { "未命名歌单" }, current.map { it.name })
            prefs[KEY_PLAYLISTS] = encode(
                current + UserPlaylist(
                    id = id,
                    name = finalName,
                    songs = songs,
                    createdAt = now,
                    sourceLink = sourceLink,
                    sourcePlatformId = sourcePlatformId,
                    sourcePlaylistId = sourcePlaylistId,
                    lastUpdatedAt = if (sourceLink.isNullOrBlank()) 0L else now,
                ),
            )
        }
        return id
    }

    suspend fun rename(id: String, name: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_PLAYLISTS])
            prefs[KEY_PLAYLISTS] = encode(
                current.map { if (it.id == id) it.copy(name = name.trim()) else it },
            )
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_PLAYLISTS])
            prefs[KEY_PLAYLISTS] = encode(current.filterNot { it.id == id })
        }
    }

    /** 添加歌曲（同曲去重）。返回 true 表示新增成功，false 表示已存在。 */
    suspend fun addSong(id: String, song: Song): Boolean = addSongs(id, listOf(song)) > 0

    /** 批量添加（同曲去重），返回实际新增数量 */
    suspend fun addSongs(id: String, songs: List<Song>): Int {
        var added = 0
        val incoming = songs.distinctBy { it.stableKey }
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_PLAYLISTS])
            prefs[KEY_PLAYLISTS] = encode(
                current.map { pl ->
                    if (pl.id != id) {
                        pl
                    } else {
                        val existing = pl.songs.map { it.stableKey }.toSet()
                        val newOnes = incoming.filter { it.stableKey !in existing }
                        added = newOnes.size
                        pl.copy(songs = pl.songs + newOnes)
                    }
                },
            )
        }
        return added
    }

    suspend fun removeSong(id: String, songKey: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_PLAYLISTS])
            prefs[KEY_PLAYLISTS] = encode(
                current.map { pl ->
                    if (pl.id == id) pl.copy(songs = pl.songs.filterNot { it.stableKey == songKey }) else pl
                },
            )
        }
    }

    /** 批量移除（单次落盘），返回实际移除数量 */
    suspend fun removeSongs(id: String, songKeys: Set<String>): Int {
        if (songKeys.isEmpty()) return 0
        var removed = 0
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_PLAYLISTS])
            prefs[KEY_PLAYLISTS] = encode(
                current.map { pl ->
                    if (pl.id != id) {
                        pl
                    } else {
                        removed = pl.songs.count { it.stableKey in songKeys }
                        pl.copy(songs = pl.songs.filterNot { it.stableKey in songKeys })
                    }
                },
            )
        }
        return removed
    }

    /** 链接更新：整体替换歌曲列表并记录更新时间 */
    suspend fun replaceSongs(id: String, songs: List<Song>) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_PLAYLISTS])
            prefs[KEY_PLAYLISTS] = encode(
                current.map { pl ->
                    if (pl.id == id) {
                        pl.copy(songs = songs, lastUpdatedAt = System.currentTimeMillis())
                    } else {
                        pl
                    }
                },
            )
        }
    }

    /** 设置定时更新频率 */
    suspend fun setAutoUpdate(id: String, mode: AutoUpdateMode) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_PLAYLISTS])
            prefs[KEY_PLAYLISTS] = encode(
                current.map { if (it.id == id) it.copy(autoUpdate = mode) else it },
            )
        }
    }

    /** 导出全部歌单为备份 JSON 文本 */
    fun exportJson(): String = AppJson.encodeToString(
        PlaylistBackup(playlists = playlists.value, exportedAt = System.currentTimeMillis()),
    )

    /** 导出单个歌单为备份 JSON 文本（分享 / 单卡导出，可被 importJson 再导入） */
    fun exportSingleJson(playlist: UserPlaylist): String = AppJson.encodeToString(
        PlaylistBackup(playlists = listOf(playlist), exportedAt = System.currentTimeMillis()),
    )

    /** 导入备份 JSON（追加合并）。返回导入的歌单数量。 */
    suspend fun importJson(raw: String): Result<Int> = runCatching {
        val backup = runCatching { AppJson.decodeFromString<PlaylistBackup>(raw) }
            .getOrElse { error("不是有效的歌单备份文件") }
        require(backup.playlists.isNotEmpty()) { "文件中没有歌单" }
        var count = 0
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_PLAYLISTS]).toMutableList()
            backup.playlists.forEach { incoming ->
                val name = uniqueName(incoming.name.ifBlank { "未命名歌单" }, current.map { it.name })
                current += incoming.copy(
                    id = UUID.randomUUID().toString(),
                    name = name,
                )
                count++
            }
            prefs[KEY_PLAYLISTS] = encode(current)
        }
        count
    }

    private fun uniqueName(base: String, existing: List<String>): String {
        if (base !in existing) return base
        var i = 1
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    private fun decode(raw: String?): List<UserPlaylist> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { AppJson.decodeFromString<List<UserPlaylist>>(raw) }.getOrDefault(emptyList())

    private fun encode(list: List<UserPlaylist>): String = AppJson.encodeToString(list)

    /** 备份文件结构（含版本号，向前兼容） */
    @Serializable
    data class PlaylistBackup(
        val version: Int = 1,
        val exportedAt: Long = 0L,
        val playlists: List<UserPlaylist> = emptyList(),
    )

    private companion object {
        val KEY_PLAYLISTS = stringPreferencesKey("user_playlists_json")
    }
}