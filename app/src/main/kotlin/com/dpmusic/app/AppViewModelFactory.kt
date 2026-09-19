package com.dpmusic.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dpmusic.app.ui.player.DownloadViewModel
import com.dpmusic.app.ui.player.PlayerViewModel
import com.dpmusic.app.ui.screens.favorites.FavoritesViewModel
import com.dpmusic.app.ui.screens.playlist.PlaylistDetailViewModel
import com.dpmusic.app.ui.screens.rank.RankDetailViewModel
import com.dpmusic.app.ui.screens.rank.RankViewModel
import com.dpmusic.app.ui.screens.recent.RecentViewModel
import com.dpmusic.app.ui.screens.search.SearchViewModel
import com.dpmusic.app.ui.screens.settings.SettingsViewModel
import com.dpmusic.app.ui.screens.settings.SyncViewModel
import com.dpmusic.app.ui.screens.sources.SourceManagerViewModel
import com.dpmusic.app.ui.screens.together.TogetherViewModel

/** 全局 ViewModel 工厂：集中装配 AppContainer 依赖 */
object AppViewModelFactory : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val container = AppContainer
        return when {
            modelClass.isAssignableFrom(SearchViewModel::class.java) ->
                SearchViewModel(container.musicRepository, container.settings, container.player, container.userPlaylists, container.searchHistory)

            modelClass.isAssignableFrom(RankViewModel::class.java) ->
                RankViewModel(container.musicRepository, container.settings, container.player)

            modelClass.isAssignableFrom(RankDetailViewModel::class.java) ->
                RankDetailViewModel(container.musicRepository, container.player, container.userPlaylists)

            modelClass.isAssignableFrom(PlaylistDetailViewModel::class.java) ->
                PlaylistDetailViewModel(container.musicRepository, container.player, container.userPlaylists)

            modelClass.isAssignableFrom(FavoritesViewModel::class.java) ->
                FavoritesViewModel(container.favorites, container.player)

            modelClass.isAssignableFrom(RecentViewModel::class.java) ->
                RecentViewModel(container.history, container.player, container.listeningStats)

            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(container.settings, container.player, container.appContext, container.ncm, container.ncmApi, container.qq, container.qqApi)

            modelClass.isAssignableFrom(DownloadViewModel::class.java) ->
                DownloadViewModel(container.musicRepository, container.settings, container.appContext)

            modelClass.isAssignableFrom(SyncViewModel::class.java) ->
                SyncViewModel(container.settings, container.syncManager)
            modelClass.isAssignableFrom(PlayerViewModel::class.java) ->
                PlayerViewModel(
                    container.musicRepository,
                    container.player,
                    container.favorites,
                    container.settings,
                    container.appContext,
                )

            modelClass.isAssignableFrom(TogetherViewModel::class.java) ->
                TogetherViewModel(container.togetherSession, container.ncm)
            modelClass.isAssignableFrom(SourceManagerViewModel::class.java) ->
                SourceManagerViewModel(container.userApi, container.userApiEngine, container.settings)

            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        } as T
    }
}