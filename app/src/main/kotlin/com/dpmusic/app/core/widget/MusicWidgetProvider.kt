package com.dpmusic.app.core.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.dpmusic.app.AppContainer
import com.dpmusic.app.MainActivity
import com.dpmusic.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 小组件展示快照（仅含界面关心的字段：用于去重渲染与持久化恢复） */
data class WidgetSnapshot(
    val title: String = "DPmusic",
    val artist: String = "未在播放",
    val isPlaying: Boolean = false,
    val coverUrl: String = "",
)

/**
 * 桌面播放控件（AppWidget）：
 * - 展示当前歌曲（标题 / 歌手 / 封面）与播放状态；
 * - 上一首 / 播放暂停 / 下一首；点击信息区打开应用；
 * - 状态由 [WidgetUpdater] 进程内直接推送；进程冷启动时用持久化快照还原界面。
 */
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 渲染持久化快照（进程可能刚被拉起）
        renderAll(context, readSnapshot(context))
        // 恢复封面（内存无缓存时异步拉取）
        runCatching { AppContainer.widgetUpdater.refreshCoverIfNeeded() }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        when (action) {
            ACTION_TOGGLE, ACTION_PREV, ACTION_NEXT -> dispatchAction(action)
        }
    }

    /**
     * 分发按钮动作：
     * - 冷启动时进程刚拉起、控制器尚未连接 → 先发起连接并等待就绪（最长 3 秒）；
     * - 执行动作（播放 / 暂停 / 切歌）。
     */
    private fun dispatchAction(action: String) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val player = AppContainer.player
                player.connect()
                withTimeoutOrNull(CONNECT_WAIT_MS) { player.connected.first { it } }
                when (action) {
                    ACTION_TOGGLE -> player.togglePlayPause()
                    ACTION_PREV -> player.previous()
                    ACTION_NEXT -> player.next()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        const val ACTION_TOGGLE = "com.dpmusic.app.widget.ACTION_TOGGLE"
        const val ACTION_PREV = "com.dpmusic.app.widget.ACTION_PREV"
        const val ACTION_NEXT = "com.dpmusic.app.widget.ACTION_NEXT"

        private const val CONNECT_WAIT_MS = 3000L
        private const val PREFS_NAME = "dpmusic_widget"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_IS_PLAYING = "is_playing"
        private const val KEY_COVER_URL = "cover_url"

        /** 封面缓存（仅保留最近一张，供渲染复用） */
        private var cachedCoverUrl: String? = null
        private var cachedCoverBitmap: Bitmap? = null

        fun cacheCover(url: String, bitmap: Bitmap) {
            cachedCoverUrl = url
            cachedCoverBitmap = bitmap
        }

        fun cachedCover(url: String): Bitmap? = cachedCoverBitmap?.takeIf { cachedCoverUrl == url }

        /** 持久化快照（进程被杀后 onUpdate 仍能还原界面） */
        fun persist(context: Context, snapshot: WidgetSnapshot) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_TITLE, snapshot.title)
                .putString(KEY_ARTIST, snapshot.artist)
                .putBoolean(KEY_IS_PLAYING, snapshot.isPlaying)
                .putString(KEY_COVER_URL, snapshot.coverUrl)
                .apply()
        }

        fun readSnapshot(context: Context): WidgetSnapshot {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return WidgetSnapshot(
                title = prefs.getString(KEY_TITLE, null) ?: "DPmusic",
                artist = prefs.getString(KEY_ARTIST, null) ?: "未在播放",
                isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false),
                coverUrl = prefs.getString(KEY_COVER_URL, null).orEmpty(),
            )
        }

        /** 刷新全部小组件实例（状态变化时由 [WidgetUpdater] 调用） */
        fun renderAll(context: Context, snapshot: WidgetSnapshot) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            if (ids.isEmpty()) return
            ids.forEach { id -> manager.updateAppWidget(id, buildViews(context, snapshot)) }
        }

        /** 仅更新封面（异步加载完成后部分刷新，避免整组件重建闪烁） */
        fun updateCover(context: Context, bitmap: Bitmap) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_music)
            views.setImageViewBitmap(R.id.widget_album_art, bitmap)
            manager.partiallyUpdateAppWidget(ids, views)
        }

        private fun buildViews(context: Context, snapshot: WidgetSnapshot): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_music)
            views.setTextViewText(R.id.widget_song_title, snapshot.title)
            views.setTextViewText(R.id.widget_song_artist, snapshot.artist)
            views.setImageViewResource(
                R.id.widget_btn_play,
                if (snapshot.isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
            )
            views.setOnClickPendingIntent(R.id.widget_btn_prev, broadcast(context, ACTION_PREV, 11))
            views.setOnClickPendingIntent(R.id.widget_btn_play, broadcast(context, ACTION_TOGGLE, 12))
            views.setOnClickPendingIntent(R.id.widget_btn_next, broadcast(context, ACTION_NEXT, 13))
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
            val cover = cachedCover(snapshot.coverUrl)
            if (cover != null) {
                views.setImageViewBitmap(R.id.widget_album_art, cover)
            } else {
                views.setImageViewResource(R.id.widget_album_art, R.mipmap.ic_launcher)
            }
            return views
        }

        private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MusicWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openApp(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(
                context,
                20,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
