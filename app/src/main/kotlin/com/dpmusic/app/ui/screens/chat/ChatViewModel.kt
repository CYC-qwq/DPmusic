package com.dpmusic.app.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.NcmChatRepository
import com.dpmusic.app.core.model.ChatListState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「消息」列表（网易云私信会话）ViewModel。
 *
 * 数据来源为网易云私信系统（`/api/msg/private/users`），
 * 与官方 App 双向互通：对方在官方 App 回复的内容这里也能读到。
 */
class ChatViewModel(
    private val chat: NcmChatRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ChatListState>(ChatListState.Loading)
    val state: StateFlow<ChatListState> = _state.asStateFlow()

    /** 下拉/按钮触发的静默刷新中（不遮挡已有列表） */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notice: SharedFlow<String> = _notice.asSharedFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    /** 刷新会话列表；[silent] = true 时保留当前列表（用于定时 / 手动刷新） */
    fun refresh(silent: Boolean = false) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            if (!chat.loggedIn) {
                _state.value = ChatListState.NotLoggedIn
                return@launch
            }
            val hadContent = _state.value is ChatListState.Ready
            if (!silent && !hadContent) _state.value = ChatListState.Loading
            _refreshing.value = true
            val result = runCatching { chat.conversations() }
            _refreshing.value = false
            result.onSuccess { list ->
                _state.value = ChatListState.Ready(list)
            }.onFailure { e ->
                if (_state.value is ChatListState.Ready) {
                    _notice.tryEmit("刷新失败：${e.message}")
                } else {
                    _state.value = ChatListState.Error(e.message ?: "加载失败")
                }
            }
        }
    }
}