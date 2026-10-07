package com.dpmusic.app.core.miisland

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.dpmusic.app.core.model.Song
import com.dpmusic.app.core.playback.MusicService
import com.google.common.collect.ImmutableList
import com.xzakota.hyper.notification.focus.FocusNotification
import com.xzakota.hyper.notification.island.model.BigIslandArea
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 媒体通知包装器：委托 Media3 原生 provider 构建通知，仅在返回前注入超级岛载荷。
 *
 * 为什么用「委托 + 包装」而不是自己从零构建通知：
 * 媒体通知牵扯 MediaStyle、media session token、ActionFactory 动作、封面异步加载、
 * 前景服务类型等一堆细节，自己重写等于把这些全部复制一遍并长期跟随 Media3 升级维护。
 * 这里只做加法：原生通知原样保留，额外挂上 `miui.focus.*` extras —— HyperOS 据此
 * 把**同一条通知**渲染成超级岛，其他系统 / 未开启时完全无感。
 *
 * 注意 `MediaNotification.Provider.createNotification` 在 Media3 1.11 起签名为
 * `(MediaSession, ImmutableList<CommandButton>, ActionFactory, Callback)`，
 * 与 1.5.x 的 `(Callback)` 不同 —— 这里以当前依赖版本为准。
 */
@OptIn(UnstableApi::class)
class MiIslandNotificationProvider(
    private val context: Context,
    /** 委托的原生 provider（负责 channel、媒体按钮、MediaStyle 等全部原生行为） */
    private val delegate: MediaNotification.Provider,
    /**
     * 通知构建完成后的回调（每次构建都会调用，含 Media3 自身触发的重建）。
     *
     * 用于「魔法重发」：Media3 自己投递的通知没走断网魔法，会被系统渲染成普通媒体岛
     * 并覆盖掉我们魔法发送的超级岛，因此每次构建后都要补发一次。
     */
    private val onBuilt: (Notification) -> Unit = {},
) : MediaNotification.Provider {

    /**
     * 最近一次构建入参。
     *
     * 保存它是为了让超级岛刷新能**复用 Media3 原生构建路径**重建通知：
     * 周期刷新发生在回调之外，拿不到 `mediaButtonPreferences` / `actionFactory`，
     * 而这些又必须与本次通知完全一致（否则媒体按钮、session token 会失效）。
     */
    private data class BuildArgs(
        val session: MediaSession,
        val mediaButtonPreferences: ImmutableList<androidx.media3.session.CommandButton>,
        val actionFactory: MediaNotification.ActionFactory,
        val callback: MediaNotification.Provider.Callback,
        /**
         * session 的 applicationLooper，在 [createNotification] 时捕获。
         *
         * 刻意在此处捕获而不是在 [rebuild] 里现取：`mediaSession.player` 与
         * `player.applicationLooper` 这类调用**本身就是线程受限的**，从刷新线程调用
         * 会抛「wrong thread」异常 —— 这正是重建一直失败的原因。
         * [createNotification] 由 Media3 在正确线程回调，此处捕获必然有效。
         */
        val looper: Looper,
    )

    @Volatile
    private var lastArgs: BuildArgs? = null

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<androidx.media3.session.CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        lastArgs = BuildArgs(
            mediaSession,
            mediaButtonPreferences,
            actionFactory,
            callback,
            Looper.myLooper() ?: Looper.getMainLooper(),
        )
        val built = delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory, callback)
        val result = inject(built, mediaSession)
        // 补一次魔法重发：Media3 自己投递的这条通知未经断网魔法，会覆盖超级岛
        runCatching { onBuilt(result.notification) }.onFailure {
            Log.w(TAG, "onBuilt 回调失败: ${it.message}")
        }
        return result
    }

    /**
     * 用最近一次入参重建通知（供超级岛歌词逐行刷新复用）。
     *
     * **必须在 [MediaSession] 的 applicationLooper 线程调用**：Media3 会校验
     * 「Player / MediaSession 相关调用只能来自其 applicationLooper 所在线程」，
     * 从 `Dispatchers.IO` 直接调用会抛
     * `Player callback method is called from a wrong thread`，重建必然失败。
     * 这里自行切线程，调用方无需关心线程模型；阻塞等待是为了让调用方能拿到结果。
     *
     * @return 重建后的通知；尚未构建过一次 / 构建失败时返回 null（调用方跳过本次刷新即可）。
     *   注意返回 null 时**不应**推进内容去重键，否则该歌词行将永远不会再被尝试发送。
     */
    fun rebuild(): Notification? {
        val args = lastArgs ?: run {
            Log.i(TAG, "跳过重建：尚未构建过一次原生通知")
            return null
        }
        val looper = args.looper
        // 已在正确线程（如 Media3 回调内）则直接执行，避免无谓的线程切换与 Handler 开销
        if (Looper.myLooper() === looper) return rebuildOn(args)

        val latch = CountDownLatch(1)
        var result: Notification? = null
        val posted = Handler(looper).post {
            try {
                result = rebuildOn(args)
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            Log.w(TAG, "跳过重建：无法投递到 session 线程")
            return null
        }
        // 最多等 1s：刷新周期本身是 1s，超时即放弃本轮，绝不无限阻塞发布协程
        val ok = latch.await(1, TimeUnit.SECONDS)
        if (!ok) Log.w(TAG, "重建超时（session 线程未响应）")
        return result
    }

    /** 真正执行重建（调用线程必须是 session 的 applicationLooper） */
    private fun rebuildOn(args: BuildArgs): Notification? = try {
        val built = delegate.createNotification(
            args.session,
            args.mediaButtonPreferences,
            args.actionFactory,
            args.callback,
        )
        inject(built, args.session)?.notification
    } catch (e: Exception) {
        Log.w(TAG, "重建超级岛通知失败: ${e.message}")
        null
    }

    /** 在原生通知上追加超级岛载荷；注入失败原样返回，绝不影响通知本身。 */
    private fun inject(built: MediaNotification, mediaSession: MediaSession): MediaNotification {
        return try {
            val extras = try {
                buildIslandExtras(mediaSession)
            } catch (e: Exception) {
                Log.w(TAG, "构建超级岛载荷失败: ${e.message}")
                null
            } ?: return built
            injectExtras(built, extras)
        } catch (e: Exception) {
            Log.w(TAG, "注入超级岛载荷失败: ${e.message}")
            built
        }
    }

    /**
     * 把 `miui.focus.*` 载荷写进通知的 extras。
     *
     * 直接写 [Notification.extras] 而不是 `NotificationCompat.Builder.recoverBuilder()`：
     * 后者自 androidx.core 1.17 起已不是公开 API（转为内部的 `Api24Impl`），
     * 而这里本来也只需要加键值，无需重建 Builder（重建反而可能丢失原生 RemoteViews）。
     *
     * [Notification.extras] 是平台公开字段，且 builder.build() 产出的通知其 extras 必非空；
     * 仍保留一次判空，兼容经 Parcel 反序列化而来的旧对象。
     */
    private fun injectExtras(built: MediaNotification, extras: Bundle): MediaNotification {
        val notification = built.notification
        val target = notification.extras ?: Bundle().also { notification.extras = it }
        target.putAll(extras)
        return MediaNotification(built.notificationId, notification)
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        delegate.notificationChannelInfo

    // ---------------- 超级岛载荷 ----------------

    /** 依据当前曲目与播放状态构建载荷；未启用 / 无曲目时返回 null。 */
    private fun buildIslandExtras(mediaSession: MediaSession): Bundle? {
        val mode = MiIslandPrefs.getMode()
        if (!mode.usesLyricIsland) return null
        val song = MiIslandController.currentSong() ?: return null
        val player = mediaSession.player
        val position = player.currentPosition
        val coverBitmap = MiIslandController.currentCover()
        // 配色在这里解析而不是在载荷层：只有此处能拿到封面位图。
        // 「跟随封面」从已下载的位图直接取主色，不产生二次网络请求。
        val colors = MiIslandPalette.resolve(
            palette = MiIslandPrefs.getPalette(),
            coverSeed = CoverSeedExtractor.extract(coverBitmap),
            customSeed = MiIslandPrefs.getCustomColor(),
        )
        return MiIslandNotification.build(
            context = context,
            song = song,
            lyric = MiIslandController.currentLyricText(position),
            coverBitmap = coverBitmap,
            isPlaying = player.isPlaying,
            positionMs = position,
            durationMs = player.duration.takeIf { it > 0L } ?: song.durationMs,
            useOuterGlow = mode.usesOuterGlow,
            layout = MiIslandPrefs.getLayout(),
            colors = colors,
        )
    }

    private companion object {
        const val TAG = "MiIslandProvider"
    }
}

/**
 * 构建小米 HyperOS 超级岛的通知 extras。
 *
 * 只负责「载荷」：返回的 [Bundle] 挂到媒体通知上，因此超级岛与普通媒体通知
 * **共用同一条通知**（不额外发通知、不产生两条卡片）。
 *
 * 全部字段都是可选的：任何字段缺失只会让对应 UI 元素不显示，不会导致整岛失败。
 */
object MiIslandNotification {

    private const val TAG = "MiIslandNotification"

    /** 外发光效果标识（SystemUI 内置效果名），摘要态另需 `miui.bigIsland.effect.src`。 */
    private const val OUTER_GLOW = "outer_glow"

    /** 封面位图在 `miui.focus.pics` 里的键名 */
    private const val PIC_ALBUM_ART = "album_art"
    private const val PIC_APP_ICON = "app_icon"

    /**
     * 构建超级岛 extras。任何异常都吞掉并返回 null —— 宁可没有超级岛，不可没有通知。
     */
    @JvmStatic
    fun build(
        context: Context,
        song: Song,
        lyric: String,
        coverBitmap: Bitmap?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        useOuterGlow: Boolean,
        layout: MiIslandLayout,
        colors: MiIslandColors,
    ): Bundle? = try {
        buildLyricExtras(
            context, song, lyric, coverBitmap, isPlaying,
            positionMs, durationMs, useOuterGlow, layout, colors,
        )
    } catch (e: Exception) {
        Log.w(TAG, "构建超级岛 extras 失败: ${e.message}")
        null
    }

    private fun buildLyricExtras(
        context: Context,
        song: Song,
        lyric: String,
        coverBitmap: Bitmap?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        useOuterGlow: Boolean,
        layout: MiIslandLayout,
        colors: MiIslandColors,
    ): Bundle {
        val appIcon = Icon.createWithResource(context, android.R.drawable.ic_media_play)
        val progress = progressPercent(positionMs, durationMs)
        val timeText = timeText(positionMs, durationMs)
        val lyricText = lyric.ifBlank { "正在播放" }
        val subtitle = if (isPlaying) "播放中 · $timeText" else "已暂停 · $timeText"
        val playPauseTitle = if (isPlaying) "暂停" else "播放"

        // 展开态按钮的落点：指向 MusicService，由其 onStartCommand 分发到 PlayerConnection。
        // requestCode 用固定值 + FLAG_UPDATE_CURRENT：同一按钮反复重建时复用同一条 PendingIntent，
        // 否则每 1s 的歌词刷新都会堆积一条永不回收的 PendingIntent。
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val playPauseIntent = PendingIntent.getService(
            context,
            REQ_CODE_PLAY_PAUSE,
            Intent(context, MusicService::class.java).setAction(MusicService.ACTION_TOGGLE_PLAY),
            piFlags,
        )
        val nextIntent = PendingIntent.getService(
            context,
            REQ_CODE_NEXT,
            Intent(context, MusicService::class.java).setAction(MusicService.ACTION_NEXT),
            piFlags,
        )

        val extras = FocusNotification.buildV3 {
            val appIconKey = createPicture(PIC_APP_ICON, appIcon)
            val cover = coverBitmap
                ?.let { createPicture(PIC_ALBUM_ART, Icon.createWithBitmap(it)) }
                ?: appIconKey

            // updatable：允许同一条通知被反复更新（歌词逐行刷新依赖它）
            updatable = true
            // 是否自动展开大岛：显式设置（系统默认 true，会在每次内容更新时自动弹出大岛）
            islandFirstFloat = MiIslandPrefs.isAutoExpandOnStart()
            ticker = islandTicker(song, lyric)
            tickerPic = cover
            if (useOuterGlow) outEffectSrc = OUTER_GLOW

            bgInfo {
                type = BG_TYPE_PIC
                picBg = cover
                colorBg = colors.bgHex
            }

            baseInfo {
                type = 2
                title = lyricText
                subTitle = song.title
                extraTitle = timeText
                specialTitle = if (isPlaying) "LIVE" else "PAUSE"
                showDivider = true
                showContentDivider = true
                content = islandContent(song, lyric, layout)
                subContent = islandSubContent(song, isPlaying)
                colorSpecialBg = if (isPlaying) colors.accentHex else COLOR_PAUSE
                colorTitle = colors.textPrimaryHex
                colorSubTitle = colors.textSecondaryHex
                colorExtraTitle = colors.textSecondaryHex
                colorContent = colors.textPrimaryHex
                colorSubContent = colors.textSecondaryHex
            }

            multiProgressInfo {
                title = timeText
                this.progress = progress
                color = colors.accentHex
                points = 4
            }

            highlightInfo {
                title = lyricText
                subTitle = song.title
                content = subtitle
                picFunction = cover
                type = 1
            }

            // ★ 展开态底部的按钮行：不填时系统会渲染出字面量占位符（focusTitle）与空底色条，
            //   这正是此前"卡片很丑"的直接原因。两个按钮分别挂 PendingIntent 指向 MusicService。
            textButton {
                addActionInfo {
                    val nativeAction = Notification.Action.Builder(
                        Icon.createWithResource(
                            context,
                            if (isPlaying) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play,
                        ),
                        playPauseTitle,
                        playPauseIntent,
                    ).build()
                    action = createAction(ACTION_ID_PLAY_PAUSE, nativeAction)
                    actionTitle = playPauseTitle
                    clickWithCollapse = false
                    actionTitleColor = colors.textPrimaryHex
                    actionTitleColorDark = colors.textPrimaryHex
                }
                addActionInfo {
                    val nativeAction = Notification.Action.Builder(
                        Icon.createWithResource(context, android.R.drawable.ic_media_next),
                        "下一首",
                        nextIntent,
                    ).build()
                    action = createAction(ACTION_ID_NEXT, nativeAction)
                    actionTitle = "下一首"
                    clickWithCollapse = false
                    actionTitleColor = colors.textPrimaryHex
                    actionTitleColorDark = colors.textPrimaryHex
                }
            }

            island {
                islandProperty = 1
                // 高亮文字（展开态右上角状态词）的强调色
                highlightColor = colors.accentHex

                // 展开态大岛：插槽组合由所选布局决定
                bigIslandArea {
                    applyBigIslandLayout(
                        area = this,
                        layout = layout,
                        song = song,
                        lyric = lyric,
                        timeText = timeText,
                        progress = progress,
                        isPlaying = isPlaying,
                        cover = cover,
                        colors = colors,
                    )
                }

                // 折叠态小胶囊：所有布局统一用「封面 + 环形进度」——胶囊只有一个插槽位，
                // 文字类内容（歌名/歌词）由系统从通知本身取，无需在此重复给定。
                smallIslandArea {
                    combinePicInfo {
                        picInfo {
                            type = 1
                            pic = cover
                        }
                        progressInfo {
                            this.progress = progress
                            colorReach = colors.accentHex
                            colorUnReach = COLOR_PROGRESS_UNREACH
                            isCCW = false
                        }
                    }
                }
            }

            iconTextInfo {
                title = islandTitle(song, lyric)
                // 与 baseInfo 同一套互补规则：标题已是歌词时不再重复
                content = islandContent(song, lyric, layout)
                subContent = timeText
                animIconInfo {
                    type = 0
                    src = cover
                }
            }

            picInfo {
                type = 1
                pic = cover
            }
        }

        // 摘要态（小岛 / 大岛）外发光：与外层 outEffectSrc 是两套独立字段，必须同时设置
        if (useOuterGlow) extras.putString(KEY_BIG_ISLAND_EFFECT, OUTER_GLOW)
        return extras
    }
    // ---------------- 大岛插槽装配 ----------------

    /**
     * 按所选布局装配展开态大岛。
     *
     * 每个布局填充 [BigIslandArea] 的**不同**插槽组合 —— 这是 HyperOS 唯一认可的
     * 「换样式」方式：系统按填了哪些插槽决定摆放，没有额外的样式枚举。插槽全部来自
     * focus-api 1.4 的实际 API（`textInfo` / `picInfo` / `imageTextInfoLeft` /
     * `imageTextInfoRight` / `progressTextInfo` / `fixedWidthDigitInfo` / `sameWidthDigitInfo`）。
     *
     * 注意 `textInfo` 与 `picInfo` 在 BigIslandArea 上**没有** DSL 方法，只能直接 setter；
     * 其余五个都有 `Function1` 扩展，用 `apply` 调用即可。
     *
     * @param focus 外层 `buildV3` 的接收者，图片句柄（`createPicture` 的返回值）由它分配
     * @param cover `miui.focus.pics` 里的封面键
     */
    private fun applyBigIslandLayout(
        area: BigIslandArea,
        layout: MiIslandLayout,
        song: Song,
        lyric: String,
        timeText: String,
        progress: Int,
        isPlaying: Boolean,
        cover: String,
        colors: MiIslandColors,
    ) {
        // 歌词位只放歌词本身：歌名由 baseInfo.title 承载，重复会挤压插槽
        val lyricText = lyric.ifBlank { song.title }

        // ★ 只用 imageTextInfoLeft / imageTextInfoRight 两个插槽，且遵守系统硬约束：
        //   imageTextInfoLeft.type 只能为 1 或 5（HyperOS 会显式校验，非法值抛
        //   IslandParamsException 导致整个大岛渲染失败、岛一闪一闪）。
        //   系统内置模板（MIUI SystemUI 插件 classes5.dex）的权威用法即 Left=1 + Right=2/3。
        //   其余插槽（textInfo / picInfo / progressTextInfo / digitInfo）不在此使用：
        //   它们缺少可验证的合法取值，贸然组合正是之前岛消失的原因。
        when (layout) {
            // 左：封面 + 歌名/进度；右：当前歌词（信息量最均衡）
            MiIslandLayout.LYRIC_SPLIT -> {
                area.imageTextInfoLeft {
                    type = TYPE_IMAGE_TEXT_LEFT
                    picInfo { type = PIC_ALBUM; pic = cover }
                    textInfo {
                        title = song.title
                        content = timeText
                        showHighlightColor = true
                    }
                }
                area.imageTextInfoRight {
                    type = TYPE_IMAGE_TEXT_RIGHT
                    textInfo {
                        title = lyricText
                        // 刻意留空：胶囊态会把 title 与 content 拼成一行，
                        // 填歌手就会变成「歌词 + 歌手」（真机上表现为歌词后面粘着 h3R3）。
                        // 歌手由 baseInfo.subContent 承载，此处不重复。
                        content = ""
                        showHighlightColor = isPlaying
                    }
                }
            }

            // 歌词占据左侧大字位，歌名退到右侧 —— 歌词存在感最强
            MiIslandLayout.LYRIC_HERO -> {
                area.imageTextInfoLeft {
                    type = TYPE_IMAGE_TEXT_LEFT
                    picInfo { type = PIC_ALBUM; pic = cover }
                    textInfo {
                        title = lyricText
                        content = timeText
                        showHighlightColor = isPlaying
                        narrowFont = false
                    }
                }
                area.imageTextInfoRight {
                    type = TYPE_IMAGE_TEXT_RIGHT
                    textInfo {
                        title = song.title
                        // 留空：胶囊态会把它拼在 title 后面（歌词/歌名后粘一串歌手）
                        content = ""
                    }
                }
            }

            // 左侧封面叠环形播放进度，右侧歌名 + 歌词
            MiIslandLayout.PROGRESS_RING -> {
                area.imageTextInfoLeft {
                    type = TYPE_IMAGE_TEXT_LEFT
                    picInfo { type = PIC_ALBUM; pic = cover }
                    progressInfo {
                        this.progress = progress
                        colorReach = colors.accentHex
                        colorUnReach = COLOR_PROGRESS_UNREACH
                        isCCW = false
                    }
                }
                area.imageTextInfoRight {
                    type = TYPE_IMAGE_TEXT_RIGHT
                    textInfo {
                        title = song.title
                        content = lyricText
                        showHighlightColor = isPlaying
                    }
                }
            }

            // 封面 + 歌名/歌手；右侧只放进度与播放态，不含歌词
            MiIslandLayout.COVER_TITLE -> {
                area.imageTextInfoLeft {
                    type = TYPE_IMAGE_TEXT_LEFT
                    picInfo { type = PIC_ALBUM; pic = cover }
                    textInfo {
                        title = song.title
                        content = song.artist
                        showHighlightColor = true
                    }
                }
                area.imageTextInfoRight {
                    type = TYPE_IMAGE_TEXT_RIGHT
                    textInfo {
                        title = timeText
                        // 留空：拼在进度后面会变成「1:35 / 3:59已暂停」
                        content = ""
                    }
                }
            }

            // 极简：左封面 + 右歌名，不出现第二行信息
            MiIslandLayout.MINIMAL -> {
                area.imageTextInfoLeft {
                    type = TYPE_IMAGE_TEXT_LEFT
                    picInfo { type = PIC_ALBUM; pic = cover }
                }
                area.imageTextInfoRight {
                    type = TYPE_IMAGE_TEXT_RIGHT
                    textInfo {
                        title = song.title
                        content = ""
                    }
                }
            }
        }
    }

    // ---------------- 上岛文案 ----------------


    /** 通知 ticker（锁屏顶部一行提示词） */
    private fun islandTicker(song: Song, lyric: String): String = when {
        lyric.isBlank() -> song.title
        MiIslandPrefs.isSongTitlePersistent() -> "${song.title} $lyric"
        else -> lyric
    }

    /**
     * 大岛右侧 / 小岛标题。
     * 「标题固定为歌曲名」开启时优先用自定义标题，其次歌曲名；关闭时显示当前歌词。
     */
    private fun islandTitle(song: Song, lyric: String): String {
        if (!MiIslandPrefs.isSongTitlePersistent()) return truncate(lyric).ifBlank { song.title }
        return MiIslandPrefs.getCustomTitle().ifBlank { song.title }
    }

    /**
     * 折叠态正文（大岛中部一行）。
     *
     * 与标题**互补**而非重复：标题已经是歌词（[islandTitle]）时这里留空，
     * 否则展开态会把同一行歌词上下各显示一遍。这正对齐 XCmusic 的
     * `islandContentField`：非持久标题且歌词非空时返回空串。
     * 「标题固定为歌曲名」开启时歌词腾出来了，这里才轮到它上场。
     */
    private fun islandContent(song: Song, lyric: String, layout: MiIslandLayout): String = when {
        !layout.showsLyric -> song.title
        lyric.isBlank() -> song.title
        MiIslandPrefs.isSongTitlePersistent() -> truncate(lyric)
        else -> ""
    }

    /** 折叠态副行：有歌手就显示歌手，缺失时退化为播放态（避免空行） */
    private fun islandSubContent(song: Song, isPlaying: Boolean): String =
        song.artist.ifBlank { if (isPlaying) "播放中" else "已暂停" }

    /** 按设置裁切歌词行（大岛右侧空间有限） */
    private fun truncate(text: String): String {
        if (!MiIslandPrefs.isLyricLengthLimitEnabled()) return text
        val max = MiIslandPrefs.getMaxLyricLength()
        return if (text.length > max) text.take(max) else text
    }

    // ---------------- 数值格式化 ----------------

    private fun progressPercent(positionMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return ((positionMs.coerceAtLeast(0L) * 100L) / durationMs).coerceIn(0L, 100L).toInt()
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0L)
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun timeText(positionMs: Long, durationMs: Long): String =
        if (durationMs > 0L) "${formatMs(positionMs)} / ${formatMs(durationMs)}" else "DPmusic"

    // ---------------- 常量 ----------------

    private const val KEY_BIG_ISLAND_EFFECT = "miui.bigIsland.effect.src"

    private const val BG_TYPE_PIC = 2

    /**
     * `bigIslandArea.imageTextInfoLeft.type` 的合法值 = 1。
     *
     * HyperOS 会**显式校验**该字段：`IslandParamsException: bigIslandArea.imageTextInfoLeft.type
     * is null or not 1 or 5`。取非法值时整个大岛模板构建抛异常 → 大岛不渲染（表现为岛一闪一闪
     * 或干脆不出现），因此这里收敛成常量，禁止在布局代码里硬编码数字。
     * 值 5 为另一种左侧形态（系统同样接受），本项目的五种布局统一用带封面的形态 1。
     */
    private const val TYPE_IMAGE_TEXT_LEFT = 1

    /**
     * `bigIslandArea.imageTextInfoRight.type`。
     *
     * 系统内置模板（MIUI SystemUI 插件 `classes5.dex`）用 2；本项目此前已在真机验证
     * 通过右值为 3 的组合（左侧 1 + 右侧 3），故沿用 3。
     */
    private const val TYPE_IMAGE_TEXT_RIGHT = 3

    /** `picInfo.type`：图片取自 `miui.focus.pics` 里的键名 */
    private const val PIC_ALBUM = 1

    /** `textButton` 按钮的 action 句柄（`createAction` 的键名，仅载荷内部使用） */
    private const val ACTION_ID_PLAY_PAUSE = "dpmusic_island_play_pause"
    private const val ACTION_ID_NEXT = "dpmusic_island_next"

    /** 按钮 PendingIntent 的 requestCode（固定值，保证反复重建时复用同一条） */
    private const val REQ_CODE_PLAY_PAUSE = 21
    private const val REQ_CODE_NEXT = 22
    private const val COLOR_BG = "#191B22"
    private const val COLOR_LIVE = "#31C27C"
    private const val COLOR_PAUSE = "#8A8F99"
    private const val COLOR_TEXT_PRIMARY = "#FFFFFF"
    private const val COLOR_PROGRESS_UNREACH = "#33FFFFFF"
}