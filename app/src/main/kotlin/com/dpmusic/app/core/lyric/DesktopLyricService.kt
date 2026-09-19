package com.dpmusic.app.core.lyric

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.dpmusic.app.AppContainer
import com.dpmusic.app.MainActivity
import com.dpmusic.app.R
import com.dpmusic.app.core.data.AppSettings
import com.dpmusic.app.core.model.SongLyrics
import com.dpmusic.app.ui.theme.themePaletteById
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 桌面歌词悬浮窗前台服务。
 *
 * 职责：
 * - 用 WindowManager 添加 TYPE_APPLICATION_OVERLAY 悬浮窗（内容为 [DesktopLyricView] 自绘）；
 * - 订阅「播放状态 + 全局歌词中心 + 设置」三路数据流，实时刷新歌词 / 样式；
 * - 拖动结果持久化到设置（下次启动恢复位置）；
 * - 迷你控制条：上一首 / 播放暂停 / 下一首 / 关闭（关闭 = 同时写入设置开关）。
 *
 * 权限：需要 SYSTEM_ALERT_WINDOW（悬浮窗）。未授权时 [sync] 不会启动服务。
 */
class DesktopLyricService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var lyricView: DesktopLyricView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 拖动中标记：拖动期间不响应数据流的位置同步 */
    private var dragging: Boolean = false

    /** 同帧位置更新合并标记 */
    private var pendingWindowUpdate: Boolean = false

    /** 本地位置（px；gravity = BOTTOM 下 y 向上为正），避免每帧取整丢精度 */
    private var posX: Float = 0f
    private var posY: Float = 0f

    /** 最近一次由设置应用到窗口的偏移：未变化时不重复布局 */
    private var lastAppliedOffsetX: Float = Float.NaN
    private var lastAppliedOffsetY: Float = Float.NaN

    private var lastLyricsKey: String = ""
    private var lastLyrics: SongLyrics = SongLyrics.EMPTY
    private var lastS2T: Boolean = false

    /** 是否曾观察到「开关 = 开」：用于区分 DataStore 首帧默认值与真实关闭 */
    private var sawEnabled: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        LyricsHub.attach(AppContainer.player, AppContainer.musicRepository)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        addOverlay()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (lyricView == null) addOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        lyricView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        lyricView = null
        layoutParams = null
        super.onDestroy()
    }

    /* ---------------- 悬浮窗 ---------------- */

    private fun addOverlay() {
        if (lyricView != null) return
        val manager = windowManager ?: return
        if (!Settings.canDrawOverlays(this)) return

        val view = DesktopLyricView(this).apply {
            onDragBy = { dx, dy -> moveBy(dx, dy) }
            onDragEnd = { persistPosition() }
            onPrevious = { AppContainer.player.previous() }
            onNext = { AppContainer.player.next() }
            onTogglePlay = { AppContainer.player.togglePlayPause() }
            onClose = {
                scope.launch { AppContainer.settings.setDesktopLyricEnabled(false) }
                stopSelf()
            }
        }

        val params = WindowManager.LayoutParams(
            // 宽度自适应：由 DesktopLyricView 按内容测量（胶囊贴合文字）
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            overlayFlags(touchThrough = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        val added = runCatching { manager.addView(view, params) }.isSuccess
        if (!added) {
            stopSelf()
            return
        }
        lyricView = view
        layoutParams = params

        // 初始位置：优先恢复设置里记忆的位置；未设置时贴在底部上方（避开手势条）
        val stored = AppContainer.settings.settings.value
        lastAppliedOffsetX = stored.desktopLyricOffsetX
        lastAppliedOffsetY = stored.desktopLyricOffsetY
        posX = if (stored.desktopLyricOffsetX < -0.5f) 0f else stored.desktopLyricOffsetX
        posY = if (stored.desktopLyricOffsetY < -0.5f) {
            defaultBottomPx().toFloat()
        } else {
            stored.desktopLyricOffsetY
        }
        // 屏幕旋转 / 分辨率变化后旧坐标可能越界：先夹紧到屏内
        val maxUpInit = (resources.displayMetrics.heightPixels - topGapPx()).coerceAtLeast(0)
        posY = posY.coerceIn(0f, maxUpInit.toFloat())
        params.x = posX.roundToInt()
        params.y = posY.roundToInt()
        runCatching { manager.updateViewLayout(view, params) }
    }

    @Suppress("DEPRECATION")
    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun overlayFlags(touchThrough: Boolean): Int {
        // 刻意不启用「不限边界」窗口标志：避免窗口被拖出屏幕边界后无法找回
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (touchThrough) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    /**
     * 拖动位移（px）。
     *
     * 注意：窗口 gravity = BOTTOM，`params.y` 表示「窗口底边距屏幕底边的距离」，
     * **向上为正**，因此纵向必须取反（手指上移 dy < 0 → y 增大 → 窗口上移）。
     */
    private fun moveBy(dx: Float, dy: Float) {
        val view = lyricView ?: return
        dragging = true
        posX += dx
        posY -= dy

        val metrics = resources.displayMetrics
        val maxUp = (metrics.heightPixels - view.height - topGapPx()).coerceAtLeast(0).toFloat()
        val halfWidth = horizontalRangePx()
        posX = posX.coerceIn(-halfWidth, halfWidth)
        posY = posY.coerceIn(0f, maxUp)

        // 同一帧内的多次 MOVE 合并为一次窗口更新：跟手且不抖
        scheduleWindowUpdate()
    }

    /** 合并同帧的位置更新：每帧最多调用一次 updateViewLayout */
    private fun scheduleWindowUpdate() {
        if (pendingWindowUpdate) return
        val view = lyricView ?: return
        pendingWindowUpdate = true
        view.postOnAnimation {
            pendingWindowUpdate = false
            applyWindowPosition()
        }
    }

    /** 把本地位置写入窗口参数 */
    private fun applyWindowPosition() {
        val manager = windowManager ?: return
        val view = lyricView ?: return
        val params = layoutParams ?: return
        params.x = posX.roundToInt()
        params.y = posY.roundToInt()
        runCatching { manager.updateViewLayout(view, params) }
    }

    private fun persistPosition() {
        dragging = false
        // 拖动结束：立即落定最终位置（并补一次窗口更新，避免被帧合并吞掉）
        applyWindowPosition()
        scope.launch {
            AppContainer.settings.setDesktopLyricOffsetX(posX)
            AppContainer.settings.setDesktopLyricOffsetY(posY)
        }
    }

    private fun applyPosition(style: DesktopLyricStyle) {
        val manager = windowManager ?: return
        val view = lyricView ?: return
        val params = layoutParams ?: return
        // 拖动中不响应数据流；设置值未变化时不重复布局
        // （播放位置每 500ms 推送一次，无条件应用会把刚拖到的位置拉回原位）
        if (dragging) return
        if (style.offsetX == lastAppliedOffsetX && style.offsetY == lastAppliedOffsetY) return
        lastAppliedOffsetX = style.offsetX
        lastAppliedOffsetY = style.offsetY

        val metrics = resources.displayMetrics
        val maxUp = (metrics.heightPixels - view.height - topGapPx()).coerceAtLeast(0)
        val halfWidth = horizontalRangePx()
        val targetX = (if (style.offsetX < -0.5f) 0f else style.offsetX)
            .coerceIn(-halfWidth, halfWidth)
            .roundToInt()
        val targetY = (if (style.offsetY < -0.5f) defaultBottomPx() else style.offsetY.roundToInt())
            .coerceIn(0, maxUp)

        posX = targetX.toFloat()
        posY = targetY.toFloat()
        if (params.x == targetX && params.y == targetY) return
        params.x = targetX
        params.y = targetY
        runCatching { manager.updateViewLayout(view, params) }
    }

    private fun applyFlags(style: DesktopLyricStyle) {
        val manager = windowManager ?: return
        val view = lyricView ?: return
        val params = layoutParams ?: return
        val flags = overlayFlags(style.touchThrough)
        if (params.flags == flags) return
        params.flags = flags
        runCatching { manager.updateViewLayout(view, params) }
    }

    /** 水平可移动范围（px）：窗口比屏幕窄时，左右各可移动「半差 − 最小可见余量」 */
    private fun horizontalRangePx(): Float {
        val view = lyricView ?: return 0f
        val metrics = resources.displayMetrics
        // 允许左右略微移出屏幕（24dp），方便把胶囊摆到任意一侧
        val slack = (metrics.widthPixels - view.width) / 2f + dp(24f)
        return slack.coerceAtLeast(0f)
    }

    /** 窗口尺寸变化后重新夹紧位置：保证胶囊始终完整留在屏内 */
    private fun clampPosition() {
        // 拖动中不夹紧：否则每 500ms 的数据推送会在手指移动时把窗口拽一下
        if (dragging) return
        val manager = windowManager ?: return
        val view = lyricView ?: return
        val params = layoutParams ?: return
        val metrics = resources.displayMetrics
        val maxUp = (metrics.heightPixels - view.height - topGapPx()).coerceAtLeast(0).toFloat()
        val halfWidth = horizontalRangePx()
        val newX = posX.coerceIn(-halfWidth, halfWidth)
        val newY = posY.coerceIn(0f, maxUp)
        if (newX == posX && newY == posY) return
        posX = newX
        posY = newY
        params.x = posX.roundToInt()
        params.y = posY.roundToInt()
        runCatching { manager.updateViewLayout(view, params) }
    }

    /** 默认贴底位置（px）：留出导航栏 / 手势条空间 */
    private fun defaultBottomPx(): Int = dp(26f)

    /** 顶部最小余量（px）：允许胶囊一直拖到屏幕顶部，只留极小边距 */
    private fun topGapPx(): Int = dp(4f)

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    /* ---------------- 数据订阅 ---------------- */

    private fun observeState() {
        scope.launch {
            combine(
                AppContainer.player.nowPlaying,
                LyricsHub.lyrics,
                LyricsHub.currentKey,
                AppContainer.settings.settings,
            ) { nowPlaying, hubLyrics, hubKey, settings ->
                RenderState(nowPlaying?.song?.stableKey.orEmpty(), nowPlaying?.positionMs ?: 0L, nowPlaying?.isPlaying == true, hubKey, hubLyrics, settings)
            }.collect { state -> render(state) }
        }
    }

    private fun render(state: RenderState) {
        val view = lyricView ?: return
        val settings = state.settings

        if (settings.desktopLyricEnabled) {
            sawEnabled = true
        } else if (sawEnabled) {
            stopSelf()
            return
        }

        val style = DesktopLyricStyle.from(settings, themeHighlight(settings))
        view.updateStyle(style)
        applyFlags(style)
        applyPosition(style)

        val song = AppContainer.player.nowPlaying.value?.song
        view.updateSong(song?.let { "${it.title} · ${it.artist}" }.orEmpty())

        val lyrics = if (state.hubKey.isNotBlank() && state.hubKey == state.songKey) {
            state.hubLyrics
        } else {
            SongLyrics.EMPTY
        }
        val lyricsChanged = state.hubKey != lastLyricsKey ||
            state.hubLyrics !== lastLyrics ||
            settings.lyricS2T != lastS2T
        if (lyricsChanged) {
            lastLyricsKey = state.hubKey
            lastLyrics = state.hubLyrics
            lastS2T = settings.lyricS2T
            view.updateLyrics(lyrics, state.positionMs, state.isPlaying, settings.lyricS2T)
        } else {
            view.updatePlayback(state.positionMs, state.isPlaying)
        }
        // 歌词行长度变化会改变胶囊宽度 → 重新夹紧位置，避免窗口越界
        clampPosition()
    }

    /** 主题高亮色（动态取色开启时用调色板近似，保证「跟随主题」在悬浮窗同样生效） */
    private fun themeHighlight(settings: AppSettings): Int =
        themePaletteById(settings.themeColor).dark.primary.toArgb()

    /* ---------------- 前台通知 ---------------- */

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "桌面歌词", NotificationManager.IMPORTANCE_MIN),
                )
            }
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("桌面歌词")
            .setContentText("歌词将显示在其他应用之上")
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    /** 渲染输入快照 */
    private data class RenderState(
        val songKey: String,
        val positionMs: Long,
        val isPlaying: Boolean,
        val hubKey: String,
        val hubLyrics: SongLyrics,
        val settings: AppSettings,
    )

    companion object {
        private const val CHANNEL_ID = "desktop_lyric"
        private const val NOTIFICATION_ID = 0x5EA2

        /** 同步开关：开（且已授权）→ 启动前台服务；关 → 停止服务 */
        fun sync(context: Context, enabled: Boolean) {
            if (!enabled) {
                stop(context)
                return
            }
            if (!Settings.canDrawOverlays(context)) return
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DesktopLyricService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DesktopLyricService::class.java)) }
        }
    }
}