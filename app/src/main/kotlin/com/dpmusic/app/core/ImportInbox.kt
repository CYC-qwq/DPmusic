package com.dpmusic.app.core

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 外部文件导入收件箱：
 * - MainActivity 在「用其他应用打开 / 分享到 DPmusic」（QQ 下载 JSON → 打开）时写入文件 URI；
 * - 歌单页观察到后消费 URI 并自动导入，导入结果走既有 Snackbar 提示。
 */
object ImportInbox {

    private val _pendingUri = MutableStateFlow<Uri?>(null)
    val pendingUri = _pendingUri.asStateFlow()

    fun offer(uri: Uri) {
        _pendingUri.value = uri
    }

    fun consume(): Uri? = _pendingUri.value?.also { _pendingUri.value = null }
}