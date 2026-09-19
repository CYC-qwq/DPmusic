package com.dpmusic.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 剪贴板链接事件类型 */
sealed interface ClipboardLinkEvent {

    /** 歌曲链接（raw = 原始剪贴板文本） */
    data class Song(val raw: String) : ClipboardLinkEvent

    /** 歌单链接（raw = 原始剪贴板文本） */
    data class Playlist(val raw: String) : ClipboardLinkEvent

    /** 一起听邀请链接（raw = 原始剪贴板文本） */
    data class Together(val raw: String) : ClipboardLinkEvent
}

/**
 * 剪贴板链接收件箱：
 * - MainActivity 在「剪切板自动读取」开启且窗口获得焦点（从其他应用切回）时读取剪贴板，
 *   命中歌曲 / 歌单 / 一起听邀请链接后写入事件；
 * - 顶层弹窗消费事件询问用户；确认后分别写入「待搜索文本」/「待导入链接」/「待加入一起听链接」，
 *   由对应页面消费执行。
 */
object ClipboardLinkInbox {

    /* ---------- 待询问事件（顶层弹窗消费） ---------- */

    private val _event = MutableStateFlow<ClipboardLinkEvent?>(null)
    val event = _event.asStateFlow()

    fun offer(link: ClipboardLinkEvent) {
        _event.value = link
    }

    fun consumeEvent(): ClipboardLinkEvent? = _event.value?.also { _event.value = null }

    /* ---------- 待搜索文本（搜索页消费） ---------- */

    private val _pendingSearch = MutableStateFlow<String?>(null)
    val pendingSearch = _pendingSearch.asStateFlow()

    fun requestSearch(text: String) {
        _pendingSearch.value = text
    }

    fun consumeSearch(): String? = _pendingSearch.value?.also { _pendingSearch.value = null }

    /* ---------- 待导入歌单链接（歌单页消费） ---------- */

    private val _pendingPlaylist = MutableStateFlow<String?>(null)
    val pendingPlaylist = _pendingPlaylist.asStateFlow()

    fun requestPlaylistImport(text: String) {
        _pendingPlaylist.value = text
    }

    fun consumePlaylistImport(): String? = _pendingPlaylist.value?.also { _pendingPlaylist.value = null }

    /* ---------- 待加入一起听链接（一起听页消费） ---------- */

    private val _pendingTogether = MutableStateFlow<String?>(null)
    val pendingTogether = _pendingTogether.asStateFlow()

    fun requestTogetherJoin(text: String) {
        _pendingTogether.value = text
    }

    fun consumeTogetherJoin(): String? = _pendingTogether.value?.also { _pendingTogether.value = null }
}