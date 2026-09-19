package com.dpmusic.app.core.lyric

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.dpmusic.app.core.model.LyricLine
import com.dpmusic.app.core.model.LyricWord
import com.dpmusic.app.core.model.SongLyrics
import com.dpmusic.app.core.util.ChineseS2T
import kotlin.math.abs
import kotlin.math.max

/**
 * 桌面歌词悬浮窗内容视图（纯 Canvas 绘制）。
 *
 * 设计取舍：悬浮窗需要长期驻留且每帧刷新，因此不用 Compose（避免在无 Activity
 * 的 WindowManager 宿主里搭建组合树 / 生命周期桥），改为自绘：
 * - 逐字卡拉OK：当前行按字级时间轴左→右渐变填充，正在唱的字带 0.5px 过渡带；
 * - 平滑推进：本地时钟按帧推进（播放位置每 500ms 才推送一次），偏差大时直接对齐；
 * - 样式：背景（无 / 纯色 / 毛玻璃）、圆角、描边、阴影、字距、粗细全部来自 [DesktopLyricStyle]；
 * - 交互：拖动移动、单击显示迷你控制条（上一首 / 播放暂停 / 下一首 / 关闭）、3s 自动收起；
 *   锁定位置后不可拖动，触摸穿透模式由宿主窗口直接放行。
 */
class DesktopLyricView(context: Context) : View(context) {

    /* ---------------- 数据 ---------------- */

    private var style: DesktopLyricStyle = DesktopLyricStyle.DEFAULT
    private var lyrics: SongLyrics = SongLyrics.EMPTY
    private var currentIndex: Int = -1
    private var smoothMs: Float = 0f
    private var isPlaying: Boolean = false
    private var songLabel: String = ""

    /* ---------------- 动画 / 交互状态 ---------------- */

    private var lastFrameMs: Long = 0L
    private var lineAnimStart: Long = 0L
    private var controlsVisible: Boolean = false
    private var pressedControl: Int = -1
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var dragging: Boolean = false

    /* ---------------- 回调（宿主窗口 / 服务） ---------------- */

    /** 拖动位移（px）：宿主负责更新窗口位置 */
    var onDragBy: ((Float, Float) -> Unit)? = null

    /** 拖动结束：宿主负责持久化位置 */
    var onDragEnd: (() -> Unit)? = null
    var onTogglePlay: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    /* ---------------- 画笔 ---------------- */

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 专用测量画笔：按内容测宽时不污染绘制画笔 */
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 字体度量复用缓冲：避免每帧分配 */
    private val fontMetricsBuffer = Paint.FontMetrics()

    /**
     * 基准字体度量（按设置字号 / 字重计算）。
     *
     * 关键：绘制过程中会临时改动 textPaint 的字号（例如缩小以适配宽度），
     * 若测量直接读绘制画笔的字体度量，窗口高度就会在「绘制后」与「绘制前」之间
     * 反复变化 → 窗口尺寸震荡（拖动时表现为强烈抖动）。因此测量一律走独立画笔。
     */
    private fun baseFontMetrics(): Paint.FontMetrics {
        measurePaint.textSize = sp(style.fontSizeSp)
        measurePaint.typeface = currentTypeface()
        measurePaint.letterSpacing = 0f
        measurePaint.getFontMetrics(fontMetricsBuffer)
        return fontMetricsBuffer
    }
    private val iconPath = Path()
    private val rect = RectF()

    private val boldTypeface: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val regularTypeface: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        // 默认字号先行生效：onMeasure 依赖 fontMetrics 计算高度
        textPaint.textSize = sp(style.fontSizeSp)
        textPaint.typeface = regularTypeface
        applyShadow(textPaint)
    }

    /* ---------------- 对外 API ---------------- */

    fun updateStyle(newStyle: DesktopLyricStyle) {
        val layoutChanged = newStyle.fontSizeSp != style.fontSizeSp ||
            newStyle.showNextLine != style.showNextLine ||
            newStyle.showTranslation != style.showTranslation ||
            newStyle.controls != style.controls ||
            newStyle.cornerDp != style.cornerDp
        style = newStyle
        applyTypeface()
        if (layoutChanged) requestLayout()
        invalidate()
    }

    fun updateSong(label: String) {
        if (songLabel == label) return
        songLabel = label
        invalidate()
    }

    fun updateLyrics(newLyrics: SongLyrics, positionMs: Long, playing: Boolean, useS2T: Boolean) {
        lyrics = if (useS2T) convertS2T(newLyrics) else newLyrics
        isPlaying = playing
        smoothMs = positionMs.toFloat()
        currentIndex = findCurrentLine(lyrics.lines, positionMs)
        lineAnimStart = SystemClock.uptimeMillis()
        requestLayout()
        invalidate()
    }

    fun updatePlayback(positionMs: Long, playing: Boolean) {
        val wasPlaying = isPlaying
        isPlaying = playing
        val target = positionMs.toFloat()
        val diff = target - smoothMs
        when {
            !playing || !wasPlaying -> smoothMs = target
            abs(diff) > JUMP_THRESHOLD_MS -> smoothMs = target
            else -> smoothMs += diff * 0.06f
        }
        val index = findCurrentLine(lyrics.lines, positionMs)
        if (index != currentIndex) {
            currentIndex = index
            lineAnimStart = SystemClock.uptimeMillis()
            // 行变化 → 内容长度变化：重新测量，让胶囊宽度跟随歌词
            requestLayout()
        }
        if (playing) postInvalidateOnAnimation() else invalidate()
    }

    /* ---------------- 布局 ---------------- */

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.widthPixels
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        // 自适应宽度：胶囊贴合内容（当前行 / 下一行 / 翻译 / 控制条取最大），
        // 夹在 [MIN_WIDTH_DP, 屏宽 - 两侧边距] 之间 —— 窗口不再横贯整屏
        val maxWidth = (available - dp(SIDE_MARGIN_DP) * 2f).coerceAtLeast(dp(MIN_WIDTH_DP))
        val width = desiredContentWidth().coerceIn(dp(MIN_WIDTH_DP), maxWidth)
        setMeasuredDimension(width.toInt(), contentHeight())
    }

    /** 内容期望宽度（px）：各候选行取最大，再加左右内边距 */
    private fun desiredContentWidth(): Float {
        val candidates = mutableListOf<Float>()
        val line = currentLine()
        if (line != null) {
            candidates += if (style.verbatim && line.words.isNotEmpty()) {
                measureWordsWidth(line.words)
            } else {
                measureTextWidth(line.text, style.fontSizeSp, style.bold)
            }
        } else {
            candidates += measureTextWidth(
                songLabel.ifBlank { "DPmusic 桌面歌词" },
                style.fontSizeSp * 0.72f,
                false,
            )
        }
        if (style.showNextLine) {
            val next = nextLineText()
            if (next.isNotBlank()) {
                candidates += measureTextWidth(next, style.fontSizeSp * 0.62f, false)
            }
        }
        val translation = currentTranslation()
        if (style.showTranslation && translation != null) {
            candidates += measureTextWidth(translation, style.fontSizeSp * 0.62f, false)
        }
        if (controlsVisible && style.controls) {
            val size = controlSize()
            candidates += size * 4f + size * 0.42f * 3f
        }
        return (candidates.maxOrNull() ?: 0f) + paddingH * 2f
    }

    /** 测量整行文本宽度（与绘制一致的字体 / 字号 / 字距） */
    private fun measureTextWidth(text: String, sizeSp: Float, bold: Boolean): Float {
        if (text.isEmpty()) return 0f
        measurePaint.textSize = sp(sizeSp)
        measurePaint.typeface = if (bold) boldTypeface else regularTypeface
        measurePaint.letterSpacing = style.letterSpacing
        return measurePaint.measureText(text)
    }

    /** 测量逐字行宽度（与 drawWords 的排布一致：每字后追加字距） */
    private fun measureWordsWidth(words: List<LyricWord>): Float {
        if (words.isEmpty()) return 0f
        measurePaint.textSize = sp(style.fontSizeSp)
        measurePaint.typeface = currentTypeface()
        measurePaint.letterSpacing = 0f
        val spacing = style.letterSpacing * measurePaint.textSize
        var total = 0f
        for (word in words) {
            total += measurePaint.measureText(word.text) + spacing
        }
        return total
    }

    private fun contentHeight(): Int {
        val fm = baseFontMetrics()
        val lineHeight = fm.descent - fm.ascent
        val gap = lineHeight * 0.10f
        var height = paddingV * 2f + lineHeight
        if (style.showNextLine && hasNextLine()) height += gap + lineHeight * 0.66f
        if (style.showTranslation && currentTranslation() != null) height += gap * 0.7f + lineHeight * 0.55f
        if (controlsVisible && style.controls) height += gap + controlSize() * 2f
        return max(height, lineHeight + paddingV * 2f).toInt()
    }

    /* ---------------- 绘制 ---------------- */

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        if (lastFrameMs == 0L) lastFrameMs = now
        val dt = (now - lastFrameMs).coerceIn(0L, 120L).toFloat()
        lastFrameMs = now
        if (isPlaying) {
            smoothMs += dt
            postInvalidateOnAnimation()
        }

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val layer = if (style.opacity < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, w, h, (style.opacity * 255f).toInt())
        } else {
            -1
        }

        drawBackground(canvas, w, h)

        val fm = baseFontMetrics()
        val lineHeight = fm.descent - fm.ascent
        val gap = lineHeight * 0.10f
        // 首行基线：字体 ascent 在基线上方（负值），descent 在下方（正值）。
        // 用 -ascent 才能让字体框上下各留 paddingV → 文字在胶囊内垂直居中
        // （若用 +descent，上方留白会被 ascent 吃掉，22sp 时几乎贴住上边界）
        var cursor = paddingV - fm.ascent

        val line = currentLine()
        val appear = ((now - lineAnimStart) / LINE_ANIM_MS.toFloat()).coerceIn(0f, 1f)
        val slide = (1f - appear) * dp(6f)

        if (line == null || lyrics.isEmpty) {
            drawPlaceholder(canvas, w, cursor - slide, appear)
        } else {
            drawCurrentLine(canvas, w, cursor - slide, line, appear)
            cursor += lineHeight
            if (style.showNextLine && hasNextLine()) {
                cursor += gap
                drawSecondary(canvas, w, cursor - slide, nextLineText(), 0.55f, appear)
                cursor += lineHeight * 0.66f
            }
            val translation = currentTranslation()
            if (style.showTranslation && translation != null) {
                cursor += gap * 0.7f
                drawSecondary(canvas, w, cursor - slide, translation, 0.42f, appear)
            }
        }

        if (controlsVisible && style.controls) {
            drawControls(canvas, w, h - paddingV - controlSize())
        }

        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        if (!style.drawsBackground) return
        val radius = dp(style.cornerDp)
        rect.set(0f, 0f, w, h)
        fillPaint.shader = null
        fillPaint.color = withAlpha(style.backgroundColor, style.backgroundAlpha)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        if (style.background == DesktopLyricBackground.GLASS) {
            // 玻璃质感：顶部斜向高光 + 极淡描边
            sheenPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0x40FFFFFF, 0x0AFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, sheenPaint)
            sheenPaint.shader = null
        }

        if (style.strokeWidthDp > 0f && style.strokeColor != 0) {
            strokePaint.color = style.strokeColor
            strokePaint.strokeWidth = dp(style.strokeWidthDp)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }
    }

    private fun drawPlaceholder(canvas: Canvas, w: Float, baseline: Float, appear: Float) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = regularTypeface
        textPaint.letterSpacing = style.letterSpacing
        val text = songLabel.ifBlank { "DPmusic 桌面歌词" }
        textPaint.textSize = fitTextSize(text, sp(style.fontSizeSp * 0.72f), w - paddingH * 2f, false)
        applyTextEffect(textPaint, withAlpha(style.textColor, appear))
        canvas.drawText(text, w / 2f, baseline, textPaint)
        textPaint.letterSpacing = 0f
    }

    private fun drawCurrentLine(
        canvas: Canvas,
        w: Float,
        baseline: Float,
        line: LyricLine,
        appear: Float,
    ) {
        val words = line.words
        if (style.verbatim && words.isNotEmpty()) {
            drawWords(canvas, w, baseline, words, appear)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = currentTypeface()
            textPaint.letterSpacing = style.letterSpacing
            textPaint.textSize = fitTextSize(line.text, sp(style.fontSizeSp), w - paddingH * 2f, style.bold)
            drawTextWithStroke(canvas, line.text, w / 2f, baseline, style.highlightColor, appear)
            textPaint.letterSpacing = 0f
        }
    }

    /** 逐字渲染：已唱高亮 / 未唱暗色 / 正在唱渐变扫过（含描边叠层） */
    private fun drawWords(
        canvas: Canvas,
        w: Float,
        baseline: Float,
        words: List<LyricWord>,
        appear: Float,
    ) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = sp(style.fontSizeSp)
        textPaint.typeface = currentTypeface()
        textPaint.letterSpacing = 0f

        val spacing = style.letterSpacing * textPaint.textSize
        val widths = FloatArray(words.size)
        var total = 0f
        for (i in words.indices) {
            widths[i] = textPaint.measureText(words[i].text) + spacing
            total += widths[i]
        }
        val available = w - paddingH * 2f
        if (total > available && total > 0f) {
            val scale = (available / total).coerceAtLeast(0.6f)
            textPaint.textSize *= scale
            val newSpacing = style.letterSpacing * textPaint.textSize
            total = 0f
            for (i in words.indices) {
                widths[i] = textPaint.measureText(words[i].text) + newSpacing
                total += widths[i]
            }
        }

        var x = (w - total) / 2f
        val strokeEnabled = style.strokeWidthDp > 0f && style.strokeColor != 0

        // 第一遍：描边（保证任何底色下都清晰）
        if (strokeEnabled) {
            textPaint.style = Paint.Style.STROKE
            textPaint.strokeWidth = dp(style.strokeWidthDp)
            textPaint.strokeJoin = Paint.Join.ROUND
            textPaint.color = withAlpha(style.strokeColor, appear)
            textPaint.shader = null
            var sx = x
            for (i in words.indices) {
                canvas.drawText(words[i].text, sx, baseline, textPaint)
                sx += widths[i]
            }
            textPaint.style = Paint.Style.FILL
        }

        // 第二遍：填充（卡拉OK 渐变）
        for (i in words.indices) {
            val word = words[i]
            val fraction = wordFillFraction(word, smoothMs)
            val width = widths[i]
            when {
                fraction >= 1f -> textPaint.color = withAlpha(style.highlightColor, appear)
                fraction <= 0f -> textPaint.color = withAlpha(style.textColor, appear)
                else -> {
                    textPaint.color = Color.WHITE
                    textPaint.shader = LinearGradient(
                        x, 0f, x + width, 0f,
                        intArrayOf(style.highlightColor, style.highlightColor, style.textColor, style.textColor),
                        floatArrayOf(
                            0f,
                            (fraction - 0.02f).coerceIn(0f, 1f),
                            (fraction + 0.02f).coerceIn(0f, 1f),
                            1f,
                        ),
                        Shader.TileMode.CLAMP,
                    )
                }
            }
            applyShadow(textPaint)
            canvas.drawText(word.text, x, baseline, textPaint)
            textPaint.shader = null
            x += width
        }
        textPaint.shader = null
    }

    private fun drawSecondary(
        canvas: Canvas,
        w: Float,
        baseline: Float,
        text: String,
        alphaFactor: Float,
        appear: Float,
    ) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = regularTypeface
        textPaint.letterSpacing = style.letterSpacing
        textPaint.shader = null
        textPaint.textSize = fitTextSize(text, sp(style.fontSizeSp * 0.62f), w - paddingH * 2f, false)
        applyTextEffect(textPaint, withAlpha(style.textColor, alphaFactor * appear))
        canvas.drawText(text, w / 2f, baseline, textPaint)
        textPaint.letterSpacing = 0f
    }

    private fun drawTextWithStroke(
        canvas: Canvas,
        text: String,
        cx: Float,
        baseline: Float,
        color: Int,
        appear: Float,
    ) {
        if (style.strokeWidthDp > 0f && style.strokeColor != 0) {
            textPaint.style = Paint.Style.STROKE
            textPaint.strokeWidth = dp(style.strokeWidthDp)
            textPaint.strokeJoin = Paint.Join.ROUND
            textPaint.color = withAlpha(style.strokeColor, appear)
            canvas.drawText(text, cx, baseline, textPaint)
            textPaint.style = Paint.Style.FILL
        }
        applyTextEffect(textPaint, withAlpha(color, appear))
        canvas.drawText(text, cx, baseline, textPaint)
    }

    /* ---------------- 迷你控制条 ---------------- */

    private fun controlSize(): Float = sp(style.fontSizeSp) * 1.05f

    private fun controlCenters(w: Float, centerY: Float): FloatArray {
        val size = controlSize()
        val gap = size * 0.42f
        val totalWidth = size * 4f + gap * 3f
        val startX = (w - totalWidth) / 2f + size / 2f
        return FloatArray(4) { startX + it * (size + gap) }
    }

    private fun drawControls(canvas: Canvas, w: Float, centerY: Float) {
        val size = controlSize()
        val radius = size / 2f
        val centers = controlCenters(w, centerY)
        val iconColor = style.textColor
        val iconHighlight = style.highlightColor

        for (i in 0..3) {
            val cx = centers[i]
            fillPaint.shader = null
            fillPaint.color = withAlpha(style.backgroundColor, 0.34f)
            canvas.drawCircle(cx, centerY, radius, fillPaint)
            strokePaint.color = withAlpha(iconColor, 0.22f)
            strokePaint.strokeWidth = dp(0.8f)
            canvas.drawCircle(cx, centerY, radius, strokePaint)

            val active = pressedControl == i
            val color = if (active) iconHighlight else withAlpha(iconColor, 0.86f)
            val s = size * 0.30f
            when (i) {
                0 -> drawSkipIcon(canvas, cx, centerY, s, color, previous = true)
                1 -> if (isPlaying) drawPauseIcon(canvas, cx, centerY, s, color)
                else drawPlayIcon(canvas, cx, centerY, s, color)

                2 -> drawSkipIcon(canvas, cx, centerY, s, color, previous = false)
                else -> drawCloseIcon(canvas, cx, centerY, s, color)
            }
        }
    }

    private fun drawPlayIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        iconPaint.color = color
        iconPaint.style = Paint.Style.FILL
        iconPath.reset()
        iconPath.moveTo(cx - s * 0.55f, cy - s)
        iconPath.lineTo(cx + s * 0.75f, cy)
        iconPath.lineTo(cx - s * 0.55f, cy + s)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)
    }

    private fun drawPauseIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        iconPaint.color = color
        iconPaint.style = Paint.Style.FILL
        val barW = s * 0.36f
        rect.set(cx - s * 0.62f, cy - s, cx - s * 0.62f + barW, cy + s)
        canvas.drawRoundRect(rect, barW * 0.4f, barW * 0.4f, iconPaint)
        rect.set(cx + s * 0.26f, cy - s, cx + s * 0.26f + barW, cy + s)
        canvas.drawRoundRect(rect, barW * 0.4f, barW * 0.4f, iconPaint)
    }

    private fun drawSkipIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int, previous: Boolean) {
        iconPaint.color = color
        iconPaint.style = Paint.Style.FILL
        val dir = if (previous) -1f else 1f
        iconPath.reset()
        iconPath.moveTo(cx - dir * s * 0.85f, cy - s * 0.9f)
        iconPath.lineTo(cx + dir * s * 0.15f, cy)
        iconPath.lineTo(cx - dir * s * 0.85f, cy + s * 0.9f)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)
        iconStrokePaint.color = color
        iconStrokePaint.strokeWidth = s * 0.34f
        canvas.drawLine(
            cx + dir * s * 0.62f,
            cy - s * 0.9f,
            cx + dir * s * 0.62f,
            cy + s * 0.9f,
            iconStrokePaint,
        )
    }

    private fun drawCloseIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        iconStrokePaint.color = color
        iconStrokePaint.strokeWidth = s * 0.30f
        canvas.drawLine(cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy + s * 0.6f, iconStrokePaint)
        canvas.drawLine(cx + s * 0.6f, cy - s * 0.6f, cx - s * 0.6f, cy + s * 0.6f, iconStrokePaint)
    }

    /* ---------------- 触摸 ---------------- */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (style.touchThrough) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                pressedControl = if (controlsVisible && style.controls) hitControl(event.x, event.y) else -1
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (pressedControl < 0 && !style.locked) {
                    val moved = abs(event.x - downX) + abs(event.y - downY)
                    if (!dragging && moved > touchSlop) dragging = true
                    if (dragging) onDragBy?.invoke(dx, dy)
                }
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                when {
                    dragging -> onDragEnd?.invoke()
                    pressedControl >= 0 -> triggerControl(pressedControl)
                    else -> toggleControls()
                }
                dragging = false
                pressedControl = -1
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                pressedControl = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitControl(x: Float, y: Float): Int {
        val centerY = height - paddingV - controlSize()
        val radius = controlSize() * 0.72f
        val centers = controlCenters(width.toFloat(), centerY)
        for (i in 0..3) {
            if (abs(y - centerY) <= radius && abs(x - centers[i]) <= radius) return i
        }
        return -1
    }

    private fun triggerControl(index: Int) {
        when (index) {
            0 -> onPrevious?.invoke()
            1 -> onTogglePlay?.invoke()
            2 -> onNext?.invoke()
            3 -> onClose?.invoke()
        }
    }

    private fun toggleControls() {
        if (!style.controls) return
        controlsVisible = !controlsVisible
        removeCallbacks(hideControls)
        if (controlsVisible) postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
        requestLayout()
        invalidate()
    }

    private val hideControls = Runnable {
        controlsVisible = false
        requestLayout()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideControls)
        super.onDetachedFromWindow()
    }

    /* ---------------- 工具 ---------------- */

    private fun currentLine(): LyricLine? {
        if (currentIndex !in lyrics.lines.indices) return null
        return lyrics.lines[currentIndex]
    }

    private fun currentTranslation(): String? {
        val line = currentLine() ?: return null
        return line.translation?.takeIf { it.isNotBlank() }
    }

    private fun hasNextLine(): Boolean = nextLineText().isNotBlank()

    private fun nextLineText(): String {
        var index = currentIndex + 1
        while (index < lyrics.lines.size) {
            val text = lyrics.lines[index].text
            if (text.isNotBlank()) return text
            index++
        }
        return ""
    }

    private fun applyTypeface() {
        textPaint.typeface = currentTypeface()
        applyShadow(textPaint)
    }

    private fun applyShadow(paint: Paint) {
        if (style.shadow) {
            paint.setShadowLayer(dp(3.5f), 0f, dp(1.5f), 0x66000000)
        } else {
            paint.clearShadowLayer()
        }
    }

    private fun applyTextEffect(paint: Paint, color: Int) {
        paint.shader = null
        paint.color = color
        applyShadow(paint)
    }

    /**
     * 按可用宽度计算实际字号（过宽时按比例缩小，最小 0.72 倍）。
     *
     * 只返回字号、不改动绘制画笔 —— 避免污染后续测量（窗口尺寸震荡的根因）。
     */
    private fun fitTextSize(text: String, sizePx: Float, available: Float, bold: Boolean): Float {
        if (text.isEmpty() || available <= 0f) return sizePx
        measurePaint.textSize = sizePx
        measurePaint.typeface = if (bold) boldTypeface else regularTypeface
        measurePaint.letterSpacing = style.letterSpacing
        val measured = measurePaint.measureText(text)
        if (measured <= available) return sizePx
        return sizePx * (available / measured).coerceAtLeast(0.72f)
    }

    private fun currentTypeface(): Typeface = if (style.bold) boldTypeface else regularTypeface

    private val paddingH: Float get() = dp(20f)
    private val paddingV: Float get() = dp(14f)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private fun withAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    /** 字填充比例：0 = 未唱，1 = 唱完（时长无效时按开始点二分） */
    private fun wordFillFraction(word: LyricWord, positionMs: Float): Float {
        if (word.durationMs <= 0L) return if (positionMs >= word.startMs) 1f else 0f
        return ((positionMs - word.startMs.toFloat()) / word.durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** 二分查找当前行（带 300ms 提前量，与播放页歌词保持一致的手感） */
    private fun findCurrentLine(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val target = positionMs + 300L
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= target) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 繁体转换（与播放页一致：仅转换有内容的部分） */
    private fun convertS2T(source: SongLyrics): SongLyrics {
        if (source.isEmpty) return source
        return source.copy(
            lines = source.lines.map { line ->
                line.copy(
                    text = ChineseS2T.convert(line.text),
                    translation = line.translation?.let(ChineseS2T::convert),
                    words = line.words.map { word -> word.copy(text = ChineseS2T.convert(word.text)) },
                )
            },
        )
    }

    private companion object {
        /** 播放位置偏差超过该值直接对齐（ms） */
        const val JUMP_THRESHOLD_MS = 450f

        /** 切行动画时长（ms） */
        const val LINE_ANIM_MS = 300L

        /** 控制条自动收起延时（ms） */
        const val CONTROLS_TIMEOUT_MS = 3_000L

        /** 胶囊最小宽度（dp） */
        const val MIN_WIDTH_DP = 168f

        /** 胶囊与屏幕左右两侧的最小留白（dp） */
        const val SIDE_MARGIN_DP = 12f
    }
}