package com.dpmusic.app.ui.screens.together

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dpmusic.app.core.data.NcmRepository
import com.dpmusic.app.core.model.NcmProfile
import com.dpmusic.app.core.model.TogetherChatMessage
import com.dpmusic.app.core.together.TogetherSession
import com.dpmusic.app.core.together.TogetherUiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 一起听页面 ViewModel：薄壳，代理全局 [TogetherSession] 与账号状态。
 */
class TogetherViewModel(
    private val session: TogetherSession,
    private val ncm: NcmRepository,
) : ViewModel() {

    val state: StateFlow<TogetherUiState> = session.state
    val notice: StateFlow<String?> = session.notice
    val profile: StateFlow<NcmProfile?> = ncm.profile
    val lastRoomId: StateFlow<String> = ncm.lastRoomId
    val autoAdvance: StateFlow<Boolean> = ncm.togetherAutoAdvance
    val chatMessages: StateFlow<List<TogetherChatMessage>> = session.chatMessages

    init {
        // 页面打开：若在房间中则立即刷新一次状态
        session.onPageOpen()
    }

    fun join(input: String) = session.join(input)
    fun createRoom() = session.createRoom()
    fun retryJoin() = session.retryJoin()
    fun cleanupAndJoin() = session.retryCleanupAndJoin()
    fun resumeLast() = session.resumeLastRoom()
    fun endRoom() = session.endRoom()
    fun exitRoom() = session.exitRoom()
    fun shareLink(): String? = session.shareLink()
    fun sendInviteMessage(acceptorId: Long) = session.sendInviteMessage(acceptorId)

    /** 发送一起听邀请并返回结果（供「选择好友」弹窗做成功/失败反馈） */
    suspend fun inviteFriend(acceptorId: Long): Boolean = session.inviteNow(acceptorId)
    fun syncQueueToRoom() = session.syncQueueToRoom()
    fun importPlaylist(playlistId: String) = session.importPlaylistToRoom(playlistId)
    fun openChat(): Boolean = session.openChat()
    fun closeChat() = session.closeChat()
    fun sendChatText(text: String) = session.sendChatText(text)
    fun sendChatSong() = session.sendChatSong()
    fun chatPartner(): Pair<Long, String>? = session.chatPartner()
    fun playPause() = session.playPause()
    fun next() = session.nextSong()
    fun previous() = session.previousSong()
    fun seekTo(positionMs: Long) = session.seekTo(positionMs)
    fun jumpTo(index: Int) = session.jumpTo(index)
    fun refresh() = session.refreshNow()
    fun acknowledgeEnd() = session.acknowledgeEnd()
    fun dismissError() = session.dismissError()
    fun consumeNotice() = session.consumeNotice()

    /** 自动切歌开关（持久化；歌曲结束后由本端发起切换，可完整播放 VIP 歌曲） */
    fun setAutoAdvance(enabled: Boolean) {
        viewModelScope.launch { ncm.setTogetherAutoAdvance(enabled) }
    }
}