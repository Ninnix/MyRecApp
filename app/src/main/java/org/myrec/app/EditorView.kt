package org.myrec.app

import android.content.Context
import android.graphics.*
import android.view.*
import kotlin.math.*

class EditorView(
    context: Context,
    val editor: Editor,
    private val index: MediaIndex,
    val action: (String) -> Unit,
    val seek: (Double) -> Unit,
) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val hits = mutableListOf<Pair<RectF, String>>()
    private val hitLabels = mutableMapOf<String, String>()
    private val accessible = EditorAccessibility(this, ::accessibilityItems)
    private var unit = 1f
    private var ox = 0f
    private var oy = 0f
    private var bw = 640f
    private var bh = 294f
    private var landscape = true
    var bitmap: Bitmap? = null
        set(value) {
            val old = field
            field = value
            invalidate()
            old?.takeIf { it !== value }?.recycle()
        }

    var preparing: Float? = null
    var playing = false
    var fullscreen = false
    val inspection = PreviewViewport()
    var onInspection: (Float) -> Unit = {}
    private var previewGesture = false
    private var panAnchored = false
    private var panX = 0f
    private var panY = 0f
    private val previewPinch = InspectionGesture { factor, x, y, oldX, oldY ->
        inspection.scale(factor, x / unit - ox - previewBounds.left, y / unit - oy - previewBounds.top,
            oldX / unit - ox - previewBounds.left, oldY / unit - oy - previewBounds.top)
        onInspection(inspection.zoom)
        moved = true
        invalidate()
    }
    val moving get() = editor.move != null
    private var pickingMoveBoundary = false
    var boundaryChoices = listOf<Double>()
        private set
    private var laneScroll = 0f
    private var laneScrollMax = 0f
    private var downLaneScroll = 0f
    private var longPressed = false
    private val longPress = Runnable {
        val clip = gestureClip
        if (!moved && !moving && timeline.contains(downX,downY) && downY<timeline.top+16) {
            longPressed=true;performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);action("bookmarks")
        } else if (!moved && !moving && clip != null && editor.range == null && editor.mark == null) {
            longPressed = true
            editor.toggleSelection(clip)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
    var showPaste = false
    var previousPosition: Double? = null
    var previewBounds = RectF()
        private set

    private var timeline = RectF()
    private var start = 0.0
    private var span = 10.0
    private var lanes = listOf<TimelineLane>()
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var downCursor = 0.0
    private var downSpan = 10.0
    private var scrubbing = false
    private var downMoveOffset = 0.0
    private var chipScroll = 0f
    private var chipLimit = 0f
    private var downChipScroll = 0f
    private var chipBounds = RectF()
    private var downGain = 0f
    private var gainChanged = false
    private var audioGesture = false
    private var rangeHandle = 0
    private var lastPlayTap = 0L
    private var gestureClip: Clip? = null
    private val red = Color.rgb(255, 55, 74)
    private val grey = Color.rgb(130, 130, 134)
    private val cyan = Color.rgb(5, 191, 179)
    private val yellow = Color.rgb(255, 211, 0)
    private val pinch =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    finishScrub()
                    moved = true
                    panAnchored = false
                    return true
                }
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    editor.wholeProject = false
                    editor.scale = (editor.scale / detector.scaleFactor).coerceIn(
                        1.0, max(1.0, editor.project.duration))
                    invalidate()
                    return true
                }
            },
        )

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Video editor. Scroll timeline to scrub; pinch to zoom."
    }

    private fun rect(c: Canvas, r: RectF, color: Int, radius: Float = 0f) {
        p.color = color
        p.style = Paint.Style.FILL
        c.drawRoundRect(r, radius, radius, p)
    }

    private fun text(
        c: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int = Color.WHITE,
        align: Paint.Align = Paint.Align.LEFT,
        bold: Boolean = false,
    ) {
        p.color = color
        p.textSize = size
        p.style = Paint.Style.FILL
        p.textAlign = align
        p.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        c.drawText(text, x, y, p)
    }

    private fun hit(r: RectF, id: String) {
        hits.add(r to id)
    }

    private fun activate(id: String) {
        when {
            id == "resetPreviewZoom" -> { inspection.reset(); onInspection(1f) }
            id == "preview" -> action("play")
            id == "moveChoose" -> pickingMoveBoundary = true
            id == "moveBack" -> pickingMoveBoundary = false
            else -> action(id)
        }
        invalidate()
    }

    internal fun controlBounds(id: String): RectF? = hits.lastOrNull { it.second == id }?.first?.let {
        RectF((it.left + ox) * unit, (it.top + oy) * unit,
            (it.right + ox) * unit, (it.bottom + oy) * unit)
    }

    internal fun clipBounds(id: String, audio: Boolean = false): RectF? {
        val lane = lanes.firstOrNull { it.audio == audio && id in it.ids } ?: return null
        val clip = editor.project.clips.firstOrNull { it.id == id } ?: return null
        val interval = TimelineLayout.interval(editor.project, clip, !moving)
        val rect = RectF(max(timeline.left, xFor(interval.start)), max(timeline.top + 16, timeline.top + lane.top),
            min(timeline.right, xFor(interval.endInclusive)), min(timeline.bottom, timeline.top + lane.bottom))
        if (rect.isEmpty) return null
        return RectF((rect.left + ox) * unit, (rect.top + oy) * unit,
            (rect.right + ox) * unit, (rect.bottom + oy) * unit)
    }

    internal fun boundaryPosition(time: Double): Float = (xFor(time) + ox) * unit

    val pointsPerSecond get() = timeline.width() / span

    fun wavePeak(clip: Clip, sourceTime: Double): Float? {
        val peaks = index.waves[clip.path] ?: return null
        if (peaks.isEmpty()) return null
        val duration = indexedDuration(clip.path)
        if (sourceTime < 0 || sourceTime >= duration) return 0f
        return peaks[(sourceTime / duration * peaks.size).toInt().coerceIn(0, peaks.lastIndex)]
    }

    private fun scrubTime(x: Float): Double = if (editor.wholeProject)
        (x - timeline.left) / timeline.width() * downSpan
        else downCursor - (x - downX) / timeline.width() * downSpan

    private fun finishScrub() {
        if (!scrubbing) return
        scrubbing = false
        seek(editor.snapTime(editor.cursor, timeline.width() / downSpan))
        invalidate()
    }

    private fun button(
        c: Canvas,
        r: RectF,
        id: String,
        title: String,
        symbol: String,
        enabled: Boolean = true,
        active: Boolean = false,
    ) {
        rect(
            c,
            r,
            if (id == "export" && enabled) Color.rgb(34, 84, 60)
            else if (active) Color.rgb(50, 45, 19) else Color.rgb(29, 29, 31),
            7f,
        )
        icon(
            c,
            symbol,
            r.centerX(),
            r.top + if (landscape || r.height() < 38f) r.height() * 0.31f else r.height() * 0.35f,
            if (symbol == "volume") 12f else 8f,
            if (active) yellow else if (enabled) Color.WHITE else Color.rgb(60, 60, 63),
        )
        text(
            c,
            title,
            r.centerX(),
            r.bottom - if (landscape) 6f else if (r.height() < 38f) 8f else 12f,
            if (landscape || r.height() < 38f) 8f else 9f,
            if (enabled) Color.WHITE else Color.rgb(65, 65, 68),
            Paint.Align.CENTER,
        )
        if (enabled) { hit(r, id); hitLabels[id] = title }
    }

    fun icon(
        c: Canvas,
        name: String,
        x: Float,
        y: Float,
        size: Float = 10f,
        color: Int = Color.WHITE,
    ) {
        p.color = color
        p.strokeWidth = size * 0.11f
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val s = size / 2
        fun line(a: Float, b: Float, d: Float, e: Float) =
            c.drawLine(x + a * s, y + b * s, x + d * s, y + e * s, p)
        fun path(vararg points: Float) {
            val path = Path()
            path.moveTo(x + points[0] * s, y + points[1] * s)
            for (i in 2 until points.size step 2) path.lineTo(
                x + points[i] * s,
                y + points[i + 1] * s,
            )
            c.drawPath(path, p)
        }
        when (name) {
            "play",
            "next",
            "previous" -> {
                val path = Path()
                val sign = if (name == "previous") -1 else 1
                path.moveTo(x - s * 0.65f * sign, y - s)
                path.lineTo(x + s * 0.95f * sign, y)
                path.lineTo(x - s * 0.65f * sign, y + s)
                path.close()
                if (name == "play") p.style = Paint.Style.FILL
                c.drawPath(path, p)
                p.style = Paint.Style.STROKE
                if (name != "play")
                    line(
                        if (name == "previous") -1.15f else 1.15f,
                        -1f,
                        if (name == "previous") -1.15f else 1.15f,
                        1f,
                    )
            }
            "pause",
            "split" -> {
                c.drawRoundRect(x - s, y - s, x - s * 0.15f, y + s, 0.3f, 0.3f, p)
                c.drawRoundRect(x + s * 0.15f, y - s, x + s, y + s, 0.3f, 0.3f, p)
            }
            "transition" -> {
                p.strokeWidth = size * .065f
                c.drawRoundRect(x - s, y - s, x + s, y + s, s * .35f, s * .35f, p)
                p.style = Paint.Style.FILL
                for (side in floatArrayOf(-1f, 1f)) {
                    val triangle = Path()
                    triangle.moveTo(x + side * s * .62f, y - s * .53f)
                    triangle.lineTo(x + side * s * .1f, y)
                    triangle.lineTo(x + side * s * .62f, y + s * .53f)
                    triangle.close()
                    c.drawPath(triangle, p)
                }
            }
            "back" -> path(0.5f, -1f, -0.5f, 0f, 0.5f, 1f)
            "text" -> text(c, "Aa", x, y + size * .35f, size, color, Paint.Align.CENTER)
            "undo",
            "redo" -> {
                val sign = if (name == "redo") -1 else 1
                val curve = Path().apply {
                    moveTo(x - s * sign, y)
                    lineTo(x + s * .35f * sign, y)
                    cubicTo(x + s * 1.6f * sign, y, x + s * 1.6f * sign, y + s * 1.6f,
                        x + s * .35f * sign, y + s * 1.6f)
                    lineTo(x, y + s * 1.6f)
                }
                c.drawPath(curve, p)
                path(-.4f * sign, -.7f, -1f * sign, 0f, -.4f * sign, .7f)
            }
            "snap" -> {
                p.strokeWidth = size * 0.23f
                val path = Path()
                path.moveTo(x - s, y - s * 0.35f)
                path.lineTo(x - s, y + s * 0.2f)
                path.cubicTo(x - s, y + s * 1.5f, x + s, y + s * 1.5f, x + s, y + s * 0.2f)
                path.lineTo(x + s, y - s * 0.35f)
                c.drawPath(path, p)
                p.color = if (color == yellow) Color.WHITE else color
                line(-1f, -.85f, -1f, -1f)
                line(1f, -.85f, 1f, -1f)
            }
            "volume" -> {
                path(
                    -1f,
                    -0.4f,
                    -0.5f,
                    -0.4f,
                    0.2f,
                    -1f,
                    0.2f,
                    1f,
                    -0.5f,
                    0.4f,
                    -1f,
                    0.4f,
                    -1f,
                    -0.4f,
                )
                c.drawArc(
                    x + s * .05f,
                    y - s * .65f,
                    x + s * 1.1f,
                    y + s * .65f,
                    -60f,
                    120f,
                    false,
                    p,
                )
                c.drawArc(
                    x + s * .05f,
                    y - s * 1.05f,
                    x + s * 1.8f,
                    y + s * 1.05f,
                    -60f,
                    120f,
                    false,
                    p,
                )
            }
            "tools" ->
                for (i in 0..1) for (j in 0..1) c.drawRoundRect(
                    x - s + i * s * 1.2f,
                    y - s + j * s * 1.2f,
                    x - s + i * s * 1.2f + s * 0.7f,
                    y - s + j * s * 1.2f + s * 0.7f,
                    0.5f,
                    0.5f,
                    p,
                )
            "export" -> {
                path(-1f, 0f, -1f, 1f, 1f, 1f, 1f, 0f)
                line(0f, 0.5f, 0f, -1.2f)
                path(-0.6f, -0.6f, 0f, -1.2f, 0.6f, -0.6f)
            }
            "mark" -> {
                p.pathEffect = DashPathEffect(floatArrayOf(1.5f, 1.5f), 0f)
                c.drawRoundRect(x - s, y - s * 0.75f, x + s, y + s * 0.75f, 1f, 1f, p)
                p.pathEffect = null
            }
            "link" -> {
                c.save()
                c.rotate(-35f, x, y)
                c.drawRoundRect(x - s * 1.2f, y - s * 0.7f, x + s * 0.1f, y + s * 0.7f, 2f, 2f, p)
                c.drawRoundRect(x - s * 0.1f, y - s * 0.7f, x + s * 1.2f, y + s * 0.7f, 2f, 2f, p)
                c.restore()
            }
            "add" -> {
                line(-1f, 0f, 1f, 0f)
                line(0f, -1f, 0f, 1f)
            }
            "move", "scale" -> {
                line(-1f, 0f, 1f, 0f)
                path(-0.5f, -0.5f, -1f, 0f, -0.5f, 0.5f)
                path(0.5f, -0.5f, 1f, 0f, 0.5f, 0.5f)
            }
            "fullscreen" -> {
                path(-1f, -0.2f, -1f, -1f, -0.2f, -1f)
                path(0.2f, 1f, 1f, 1f, 1f, 0.2f)
                line(-1f, -1f, -0.2f, -0.2f)
                line(1f, 1f, 0.2f, 0.2f)
            }
            "delete" -> {
                path(-0.7f, -0.5f, -0.6f, 1f, 0.6f, 1f, 0.7f, -0.5f)
                line(-1f, -0.7f, 1f, -0.7f)
                line(-0.4f, -1f, 0.4f, -1f)
            }
            "copy" -> {
                c.drawRoundRect(x - s * 0.6f, y - s * 0.5f, x + s, y + s, 1f, 1f, p)
                path(-0.8f, 0.6f, -1f, 0.6f, -1f, -1f, 0.5f, -1f, 0.5f, -0.7f)
            }
            "close" -> {
                line(-0.6f, -0.6f, 0.6f, 0.6f)
                line(-0.6f, 0.6f, 0.6f, -0.6f)
            }
            "bookmark" -> path(-0.55f, 1f, -0.55f, -1f, 0.55f, -1f, 0.55f, 1f, 0f, 0.4f, -0.55f, 1f)
            "camera" -> {
                c.drawRoundRect(x - s, y - s * 0.65f, x + s, y + s * 0.65f, 1f, 1f, p)
                c.drawCircle(x, y, s * 0.4f, p)
            }
            else -> {
                c.drawCircle(x, y, s, p)
                line(0f, -0.5f, 0f, 0.5f)
                line(-0.5f, 0f, 0.5f, 0f)
            }
        }
        p.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        landscape = width > height
        bw = if (landscape) 640f else 294f
        bh = if (landscape) 294f else 640f
        unit = min(width / bw, height / bh)
        ox = (width / unit - bw) / 2
        oy = (height / unit - bh) / 2
        if (!landscape && !fullscreen) {
            val safe = SafeArea.insets(this)
            unit = min((width-safe.left-safe.right)/294f,(height-safe.top-safe.bottom)/600f)
            ox = safe.left/unit + ((width-safe.left-safe.right)/unit-294f)/2
            oy = safe.top/unit-40f
        }
        canvas.save()
        canvas.scale(unit, unit)
        canvas.translate(ox, oy)
        hits.clear()
        hitLabels.clear()
        if (fullscreen) {
            previewBounds = RectF(0f, 0f, bw, bh - 38)
            drawPreview(canvas, previewBounds)
            rect(canvas, RectF(0f, bh - 38, bw, bh), Color.rgb(15, 15, 16))
            transport(canvas, RectF(20f, bh - 36, bw - 20, bh - 6))
            text(canvas, "Done", bw - 10, 20f, 10f, Color.WHITE, Paint.Align.RIGHT)
            hit(RectF(bw - 60, 0f, bw, 35f), "fullscreen")
            canvas.restore()
            return
        }
        if (landscape) {
            previewBounds = RectF(46f, 0f, 309f, 127f)
            drawPreview(canvas, previewBounds)
            rect(canvas, RectF(309f, 11f, 594f, 118f), Color.rgb(15, 15, 16))
            transport(canvas, RectF(317f, 12f, 586f, 43f))
            controls(canvas, 318f, 47f, 268f)
            timeline = RectF(46f, 127f, 594f, 236f)
        } else {
            text(canvas, "‹", 12f, 66f, 23f)
            hit(RectF(0f, 40f, 34f, 80f), "projects")
            text(canvas, editor.project.name, 38f, 64f, 10f)
            previewBounds = RectF(0f, 74f, 294f, 350f)
            drawPreview(canvas, previewBounds)
            rect(canvas, RectF(0f, 350f, 294f, 383f), Color.rgb(15, 15, 16))
            transport(canvas, RectF(8f, 350f, 286f, 380f))
            controls(canvas, 8f, 536f, 278f)
            timeline = RectF(36f, 383f, 294f, 492f)
        }
        val addX = timeline.left - 18f
        val addY = timeline.top + 52f
        p.color = Color.rgb(60, 60, 62)
        p.style = Paint.Style.FILL
        canvas.drawCircle(addX, addY, 9f, p)
        p.color = grey
        p.style = Paint.Style.STROKE
        p.strokeWidth = .8f
        canvas.drawCircle(addX, addY, 9f, p)
        icon(canvas, "add", addX, addY, 5f)
        if (!moving) hit(RectF(addX - 14, addY - 18, addX + 14, addY + 18), "addMedia")
        drawTimeline(canvas)
        val fxY = timeline.bottom + 22f
        val hasChips = drawSelection(canvas, fxY)
        if (editor.selected.isEmpty() && editor.range == null && !hasChips) {
            text(
                canvas,
                "Scroll to scrub · pinch to zoom",
                timeline.left + 5,
                if (landscape) 261f else 518f,
                8f,
                grey,
            )
        }
        if (landscape) {
            icon(canvas, "back", 60f, 16f, 10f)
            hit(RectF(46f, 0f, 80f, 32f), "projects")
        }
        val fullX = if (landscape) 286f else 272f
        val fullY = if (landscape) 14f else 96f
        icon(canvas, "fullscreen", fullX, fullY, 11f)
        hit(RectF(fullX - 17, fullY - 17, fullX + 17, fullY + 17), "fullscreen")
        val speed =
            RectF(
                previewBounds.left + 5,
                previewBounds.bottom - 38,
                previewBounds.left + 39,
                previewBounds.bottom - 5,
            )
        rect(canvas, speed, Color.argb(170, 15, 15, 16), 18f)
        text(
            canvas,
            "${editor.playbackRate.let { if(it%1f==0f)it.toInt().toString() else it.toString() }}×",
            speed.centerX(),
            speed.centerY() + 3f,
            10f,
            Color.WHITE,
            Paint.Align.CENTER,
        )
        hit(speed, "playbackSpeed")
        canvas.restore()
    }

    private fun drawPreview(c: Canvas, r: RectF) {
        rect(c, r, Color.BLACK)
        hit(RectF(r), "preview")
        bitmap?.let { b ->
            val before = inspection.zoom
            inspection.configure(r.width(), r.height(), editor.project.width.toFloat(), editor.project.height.toFloat())
            if (inspection.zoom != before) onInspection(inspection.zoom)
            val ratio = min(r.width() / b.width, r.height() / b.height) * inspection.zoom
            val dest =
                RectF(
                    r.centerX() + inspection.x - b.width * ratio / 2,
                    r.centerY() + inspection.y - b.height * ratio / 2,
                    r.centerX() + inspection.x + b.width * ratio / 2,
                    r.centerY() + inspection.y + b.height * ratio / 2,
                )
            c.save()
            c.clipRect(r)
            p.alpha = 255
            c.drawBitmap(b, null, dest, p)
            c.restore()
        }
        if (inspection.zoom > 1.001f) {
            val reset = RectF(r.right - 64, r.bottom - 29, r.right - 5, r.bottom - 5)
            rect(c, reset, Color.argb(210, 15, 15, 15), 12f)
            text(c, "%.1f× · Reset".format(java.util.Locale.US, inspection.zoom), reset.centerX(), reset.centerY() + 3,
                7f, Color.WHITE, Paint.Align.CENTER)
            hit(reset, "resetPreviewZoom")
        }
        preparing?.let { progress ->
            val box = RectF(r.centerX() - 60, r.centerY() - 16, r.centerX() + 60, r.centerY() + 16)
            rect(c, box, Color.argb(230, 55, 55, 58), 9f)
            text(
                c,
                "Preparing preview · ${(progress*100).toInt()}%",
                box.centerX(),
                box.centerY() + 3,
                8f,
                Color.WHITE,
                Paint.Align.CENTER,
            )
        }
        if (editor.project.clips.isEmpty()) {
            icon(c, "camera", r.centerX(), r.centerY() - 18, 25f, grey)
            text(
                c,
                "Record or add a video",
                r.centerX(),
                r.centerY() + 12,
                11f,
                grey,
                Paint.Align.CENTER,
            )
            hit(RectF(r.left, r.centerY() - 45, r.right, r.centerY() + 40), "empty")
        }
    }

    private fun transport(c: Canvas, r: RectF) {
        text(c, timeLabel(editor.cursor), r.left, r.centerY() + 2, 8f)
        val x = r.left + r.width() * 0.24f
        val gap = r.width() * 0.12f
        val names =
            listOf("bookmark", "previous", if (playing) "pause" else "play", "next", "bookmark")
        val ids = listOf("previousMark", "previous", "play", "next", "forwardPoint")
        for (i in names.indices) {
            icon(c, names[i], x + i * gap, r.centerY(), if (i == 2) 12f else 9f)
            hit(RectF(x + i * gap - 13, r.top, x + i * gap + 13, r.bottom), ids[i])
        }
        text(
            c,
            timeLabel(editor.project.duration),
            r.right,
            r.centerY() + 2,
            8f,
            grey,
            Paint.Align.RIGHT,
        )
    }

    private fun controls(c: Canvas, x: Float, y: Float, w: Float) {
        val gap = 5f
        val cell = (w - gap * 3) / 4
        val first = if (landscape) 31f else 41f
        if (moving) {
            val draft = editor.move!!
            val ids = listOf("moveSelected", "moveLeft", "moveRight", "moveBoth")
            for ((i, scope) in MoveScope.entries.withIndex())
                button(c, RectF(x + i * (cell + gap), y, x + i * (cell + gap) + cell, y + first),
                    ids[i], scope.title, "move", draft.available(scope), draft.scope == scope)
        } else {
            button(
                c,
                RectF(x + cell + gap, y, x + 2 * cell + gap, y + first),
                "split",
                "Split",
                "split",
                editor.project.duration > 0 && !moving,
            )
            button(
                c,
                RectF(x, y, x + cell, y + first),
                "mark",
                if (editor.range != null) "Move end"
                else if (editor.mark != null) "End"
                else "Start",
                "mark",
                editor.project.duration > 0 && !moving,
            )
            button(
                c,
                RectF(x + 2 * (cell + gap), y, x + 3 * cell + 2 * gap, y + first),
                "tools",
                "Tools",
                "tools",
                !moving,
            )
            button(
                c,
                RectF(x + 3 * (cell + gap), y, x + w, y + first),
                "export",
                "Export",
                "export",
                editor.project.duration > 0 && !moving,
            )
        }
        val sy = y + first + 6f
        val h = if (landscape) 32f else 35f
        val u = w * 0.11f
        var left = x
        for ((name, enabled) in listOf("undo" to (editor.canUndo && !moving), "redo" to (editor.canRedo && !moving))) {
            icon(c, name, left + u / 2, sy + h / 2, 11f, if (enabled) Color.WHITE else grey)
            if (enabled) hit(RectF(left, sy, left + u, sy + h), name)
            left += u
        }
        val snapW = w * 0.12f
        rect(
            c,
            RectF(left, sy, left + snapW, sy + h),
            if (editor.snap) Color.rgb(51, 45, 22) else Color.rgb(29, 29, 31),
            7f,
        )
        c.save()
        c.rotate(-18f, left + snapW / 2, sy + h / 2)
        icon(c, "snap", left + snapW / 2, sy + h / 2, 15f, if (editor.snap) yellow else grey)
        c.restore()
        hit(RectF(left, sy, left + snapW, sy + h), "snap")
        left += snapW + 4
        val rest = (x + w - left - 8) / 3
        button(
            c,
            RectF(left, sy, left + rest, sy + h),
            "link",
            if (editor.selectionLinked) "Unlink" else "Link",
            "link",
            !moving,
        )
        left += rest + 4
        button(
            c,
            RectF(left, sy, left + rest, sy + h),
            "move",
            "Move",
            "move",
            !moving && (editor.selected.isNotEmpty() || editor.range != null),
            moving,
        )
        left += rest + 4
        button(
            c,
            RectF(left, sy, x + w, sy + h),
            "volume",
            "Volume",
            "volume",
            editor.project.duration > 0 && !moving,
        )
    }

    private fun drawTimeline(c: Canvas) {
        span = max(1.0, if (editor.wholeProject) editor.move?.base?.duration ?: editor.project.duration else editor.scale)
        start = TimelineLayout.start(editor.cursor, span, editor.wholeProject)
        val draft = editor.move
        if (draft == null) pickingMoveBoundary = false
        val rows = TimelineLayout.lanes(draft?.base ?: editor.project, timeline.height(), draft?.members ?: emptySet())
        laneScrollMax = max(0f, (rows.lastOrNull()?.bottom ?: 0f) + 1 - timeline.height())
        laneScroll = laneScroll.coerceIn(0f, laneScrollMax)
        lanes = rows.map { it.copy(top = it.top - laneScroll, bottom = it.bottom - laneScroll) }
        rect(c, timeline, Color.rgb(13, 13, 14))
        c.save()
        c.clipRect(timeline)
        val tick = listOf(.1, .2, .5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0)
            .firstOrNull { it * timeline.width() / span >= 30 } ?: 3600.0
        var t = max(0.0, ceil(start / tick) * tick)
        while (t <= min(editor.project.duration, start + span)) {
            val x = xFor(t)
            text(
                c,
                if (span <= 2) "%.1f".format(java.util.Locale.US, t)
                else if (span < 60) t.toInt().toString() else timeLabel(t, false),
                max(x, timeline.left + 3.5f),
                timeline.top + 9,
                7f,
                grey,
                Paint.Align.CENTER,
            )
            t += tick
        }
        var videoNumber = 0
        var audioNumber = 0
        for (clip in editor.project.clips) {
            if (clip.video) videoNumber++
            if (clip.audio) audioNumber++
            val interval = TimelineLayout.interval(editor.project, clip, !moving)
            if (interval.endInclusive - interval.start < .000001) continue
            if (interval.endInclusive < start || interval.start > start + span) continue
            index.request(clip)
            val left = xFor(interval.start)
            val right = xFor(interval.endInclusive)
            for (lane in
                lanes.filter {
                    clip.id in it.ids && if (it.audio) clip.audio else clip.video
                }) {
                val top = timeline.top + lane.top
                val bottom = timeline.top + lane.bottom
                val clipR = RectF(left, top, right, bottom)
                c.save()
                c.clipPath(Path().apply { addRoundRect(clipR, 3f, 3f, Path.Direction.CW) })
                rect(c, clipR, Color.rgb(28, 28, 30))
                if (!lane.audio) {
                    val list = index.thumbnails[clip.path]
                    if (!list.isNullOrEmpty()) {
                        val tw = max(1f, (bottom - top) * list.first().width / list.first().height)
                        var x = TimelineLayout.thumbnailStart(left, timeline.left, tw)
                        while (x < min(right, timeline.right)) {
                            val at =
                                clip.sourceTime(start + (x - timeline.left) / timeline.width() * span)
                            val total = max(clip.sourceOut, indexedDuration(clip.path))
                            val image =
                                list[(at / total * list.size).toInt().coerceIn(0, list.size - 1)]
                            c.save()
                            c.clipRect(clipR)
                            p.color = Color.WHITE
                            p.alpha = 255
                            c.drawBitmap(image, null, RectF(x, top, x + tw, bottom), p)
                            c.restore()
                            x += tw
                        }
                    } else rect(c, clipR, Color.rgb(31, 35, 37))
                    rect(
                        c,
                        RectF(
                            max(left, timeline.left) + 1,
                            top + 1,
                            min(max(left, timeline.left) + 31, right),
                            min(bottom, top + 12),
                        ),
                        Color.argb(75, 0, 0, 0),
                    )
                    text(c, "V$videoNumber", max(left, timeline.left) + 3, min(bottom - 1, top + 9), 7f)
                    if (clip.linked) icon(c, "link", max(left, timeline.left) + 23, top + 5, 4f)
                } else {
                    val audioTop = top + 1
                    val mid = (audioTop + bottom) / 2
                    val wave = index.waves[clip.path]
                    p.color = cyan
                    p.strokeWidth = 0.8f
                    val gain = 10f.pow(clip.gainDb / 20)
                    var x = max(left, timeline.left)
                    while (x < min(right, timeline.right)) {
                        val source =
                            clip.sourceTime(start + (x - timeline.left) / timeline.width() * span)
                        val i =
                            if (wave != null && wave.isNotEmpty())
                                (source / indexedDuration(clip.path) * wave.size)
                                    .toInt()
                                    .coerceIn(0, wave.size - 1)
                            else -1
                        val amplitude =
                            if (i >= 0)
                                min(1f, sqrt(wave!![i]) * gain) * (clipR.bottom - audioTop) / 2
                            else 0.6f
                        c.drawLine(x, mid - amplitude, x, mid + amplitude, p)
                        x += 1f
                    }
                    val labelLeft = max(left, timeline.left)
                    rect(c, RectF(labelLeft + 1, max(top, bottom - 13),
                        min(labelLeft + 31, right), bottom - 1), Color.argb(125, 0, 0, 0), 2f)
                    text(c, "♪$audioNumber", labelLeft + 3, clipR.bottom - 3, 7f)
                    if (clip.linked) icon(c, "link", labelLeft + 23, bottom - 7, 4f)
                }
                p.color = Color.rgb(80, 80, 83)
                p.strokeWidth = 0.7f
                c.drawLine(right, top, right, clipR.bottom, p)
                for (fx in
                    clip.effects.filter {
                        it.enabled && (it.kind in Effects.audio) == lane.audio
                    }) {
                    val from = xFor(clip.start + fx.from)
                    val to = xFor(clip.start + min(clip.duration, fx.to))
                    p.color = Effects.color(fx.kind)
                    p.strokeWidth = 2f
                    c.drawLine(from, bottom - 1, to, bottom - 1, p)
                }
                if (clip.id in editor.selected) {
                    rect(c, clipR, Color.argb(55, 255, 212, 0))
                    p.style = Paint.Style.STROKE
                    p.color = yellow
                    p.strokeWidth = 1.2f
                    c.drawRect(clipR, p)
                    p.style = Paint.Style.FILL
                }
                c.restore()
            }
        }
        if (pickingMoveBoundary && draft != null) {
            p.color = cyan
            p.strokeWidth = 1f
            p.pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
            for (time in draft.boundaries) {
                val x = xFor(time)
                c.drawLine(x, timeline.top + 16, x, timeline.bottom, p)
            }
            p.pathEffect = null
        }
        draft?.snap?.let {
            p.color = cyan
            p.strokeWidth = 1f
            c.drawLine(xFor(it.target), timeline.top, xFor(it.target), timeline.bottom, p)
        }
        for (time in editor.project.markers()) {
            val x = xFor(time)
            p.color = yellow
            p.strokeWidth = .7f
            c.drawLine(x, timeline.top + 15, x, timeline.bottom, p)
            icon(c, "bookmark", x, timeline.top + 10, 4f, yellow)
        }
        editor.range?.let { r ->
            val rr = RectF(xFor(r.start), timeline.top + 16, xFor(r.endInclusive), timeline.bottom)
            rect(c, rr, Color.argb(55, 255, 212, 0))
            p.style = Paint.Style.STROKE
            p.color = yellow
            p.strokeWidth = 1.2f
            c.drawRect(rr, p)
            p.style = Paint.Style.FILL
        }
        editor.mark?.let {
            val x = xFor(it)
            p.color = yellow
            p.strokeWidth = 1.3f
            c.drawLine(x, timeline.top, x, timeline.bottom, p)
        }
        for (tr in editor.project.transitions) {
            val x = xFor(tr.cut)
            val y = timeline.top + 49
            rect(c, RectF(x - 8.5f, y - 8.5f, x + 8.5f, y + 8.5f), Color.argb(220, 18, 18, 20), 3f)
            icon(c, "transition", x, y, 17f, Color.rgb(255, 149, 32))
        }
        if (laneScrollMax > 0) {
            val barHeight = max(10f, timeline.height() * timeline.height() / (timeline.height() + laneScrollMax))
            val barTop = timeline.top + (timeline.height() - barHeight) * laneScroll / laneScrollMax
            rect(c, RectF(timeline.right - 2, barTop, timeline.right, barTop + barHeight), grey, 1f)
        }
        val cursor = xFor(editor.cursor)
        p.color = Color.WHITE
        p.strokeWidth = 0.8f
        c.drawLine(cursor, timeline.top, cursor, timeline.bottom, p)
        rect(
            c,
            RectF(timeline.right - 30, timeline.top + 17, timeline.right - 3, timeline.top + 31),
            Color.argb(220, 25, 25, 27),
            3f,
        )
        icon(c, "scale", timeline.right - 16, timeline.top + 24, 7f)
        hit(
            RectF(timeline.right - 32, timeline.top + 13, timeline.right, timeline.top + 45),
            "scale",
        )
        if (gainChanged) {
            val gain = gestureClip?.gainDb ?: 0f
            val r =
                RectF(
                    timeline.centerX() - 30,
                    timeline.top + 41,
                    timeline.centerX() + 30,
                    timeline.top + 60,
                )
            rect(c, r, Color.argb(230, 24, 24, 26), 9f)
            text(
                c,
                "%+.1f dB".format(gain),
                r.centerX(),
                r.centerY() + 3,
                9f,
                Color.WHITE,
                Paint.Align.CENTER,
            )
        }
        c.restore()
    }

    private fun indexedDuration(path: String) =
        max(
            0.001,
            index.durations[path]
                ?: editor.project.clips.filter { it.path == path }.maxOfOrNull { it.sourceOut }
                ?: 1.0,
        )

    private fun xFor(t: Double) = timeline.left + ((t - start) / span * timeline.width()).toFloat()

    private data class MoveControl(val id: String, val label: String, val left: Float,
                                   val width: Float, val enabled: Boolean)

    private fun drawSelection(c: Canvas, y: Float): Boolean {
        var x = timeline.left + 4
        chipBounds.setEmpty()
        if (moving) {
            val draft = editor.move!!
            if (pickingMoveBoundary) {
                text(c, "Tap a dashed cyan line", x, y, 8f, cyan)
                text(c, "Back", timeline.right - 35, y, 8f, red)
                hit(RectF(timeline.right - 45, y - 13, timeline.right, y + 10), "moveBack")
                return true
            }
            text(c, timeLabel(draft.position), x, y, 8f)
            val right = timeline.right
            for ((id, label, left, width, enabled) in listOf(
                MoveControl("movePrevious", "‹|", right - 192, 25f, draft.boundaries.any { it < draft.position }),
                MoveControl("moveNext", "|›", right - 165, 25f, draft.boundaries.any { it > draft.position }),
                MoveControl("moveChoose", "Choose", right - 136, 45f, draft.boundaries.size > 1),
                MoveControl("moveCancel", "Cancel", right - 85, 43f, true),
                MoveControl("moveDone", "Done", right - 38, 38f, true))) {
                text(c, label, left + width / 2, y, 8f, if (enabled) red else grey, Paint.Align.CENTER)
                if (enabled) hit(RectF(left, y - 15, left + width, y + 12), id)
            }
            return true
        }
        if (
            showPaste &&
                editor.selected.isEmpty() &&
                editor.range == null &&
                editor.clipboard.isNotEmpty()
        ) {
            icon(c, "copy", x + 5, y - 2, 7f, red)
            text(c, "Paste", x + 16, y, 8f, red)
            text(c, if (editor.clipboardInsertsTime) "Inserts time" else "Adds a layer", x + 45, y, 7f, grey)
            icon(c, "close", x + 104, y - 3, 6f, red)
            hit(RectF(x, y - 13, x + 92, y + 10), "paste")
            hit(RectF(x + 94, y - 13, x + 118, y + 10), "dismissPaste")
            return true
        }
        if (editor.selected.isEmpty() && editor.range == null && TimelineRules.gap(editor.project, editor.cursor) != null) {
            text(c, "Close gap", x + 8, y, 9f, red)
            hit(RectF(x, y - 15, x + 70, y + 12), "closeGap")
            return true
        }
        if (editor.selected.isNotEmpty() || editor.range != null) {
            val targets = editor.targets()
            text(
                c,
                if (editor.range != null)
                    "Video + audio · ${timeLabel(editor.range!!.start)}–${timeLabel(editor.range!!.endInclusive)}"
                else
                    "${editor.targets().joinToString(" + ") { clip ->
                        (if (clip.video) "V" else "♪") + (editor.project.clips.takeWhile { it.id != clip.id }
                            .count { if (clip.video) it.video else it.audio } + 1)
                    }}",
                x,
                y - 4,
                7f,
                red,
            )
            val video = targets.count { it.video }
            val audio = targets.count { it.audio }
            text(c, "$video video + $audio audio part${if (targets.size == 1) "" else "s"}",
                x, y + 5, 6f, Color.rgb(125, 47, 53))
            x += if (landscape) 95f else 82f
            for ((symbol, id) in
                listOf("copy" to "copy", "split" to "cut", "delete" to "delete", "close" to "clear")) {
                icon(c, symbol, x, y - 2, 8f, if (id == "clear") grey else red)
                hit(RectF(x - 12, y - 13, x + 12, y + 10), id)
                x += 25
            }
            editor.syncOffset()?.takeIf { abs(it) > .000001 }?.let {
                text(c, "Sync", x + 6, y + 1, 8f, red)
                hit(RectF(x - 8, y - 15, x + 40, y + 12), "restoreSync")
            }
        } else {
            val active =
                editor.project.clips
                    .filter { it.contains(editor.cursor) }
                    .flatMap { clip ->
                        clip.effects.filter { it.active(editor.cursor - clip.start) }
                    }
                    .distinctBy { it.kind }
            val left = if (landscape) timeline.left + 4 else 8f
            val right = if (landscape) timeline.right else bw - 8f
            chipScroll = chipScroll.coerceIn(0f, chipLimit)
            x = left - chipScroll
            c.save()
            c.clipRect(left, y - 17, right, y + 17)
            fun chip(title: String, color: Int, id: String, symbol: String? = null) {
                p.textSize = 11f
                val width = p.measureText(title) + if (symbol == null) 24 else 39
                val bounds = RectF(x, y - 17, x + width, y + 17)
                rect(
                    c,
                    bounds,
                    Color.argb(35, Color.red(color), Color.green(color), Color.blue(color)),
                    17f,
                )
                if (symbol != null) icon(c, symbol, x + 13, y, 7f, color)
                text(c, title, x + if (symbol == null) 12 else 27, y + 4, 11f, color)
                val touch = RectF(bounds)
                if (touch.intersect(left, y - 17, right, y + 17)) { hit(touch, id); hitLabels[id] = title }
                x += width + 5
            }
            val tr =
                editor.project.transitions.firstOrNull {
                    abs(it.at - editor.cursor) < max(0.2, it.duration)
                }
            val boundary =
                editor.project.clips.any {
                    abs(it.start - editor.cursor) < .08 || abs(it.end - editor.cursor) < .08
                }
            if (tr != null || boundary && editor.project.clips.isNotEmpty())
                chip(
                    tr?.kind ?: "Transition",
                    Color.rgb(222, 137, 46),
                    "transition",
                    if (tr == null) "add" else null,
                )
            for (fx in active) {
                val title =
                    when (fx.kind) {
                        "Text" -> fx.values["text"] ?: "Text"
                        "Crop" -> "%.1f×".format(java.util.Locale.US, fx.number("zoom", 1f))
                        "Color LUTs" -> fx.values["lut"] ?: "Natural"
                        else -> fx.kind
                    }
                chip(title.take(24), Effects.color(fx.kind), "effect:${fx.id}", if (fx.kind == "Text") "text" else null)
            }
            val sound = editor.project.clips.filter { it.audio && it.contains(editor.cursor) }
            for (region in sound.flatMap { clip -> clip.censors.filter { editor.cursor - clip.start >= it.from && editor.cursor - clip.start < it.to } }.distinctBy { it.id })
                chip("Censor audio", cyan, "censor:${region.id}")
            if (sound.any { it.fadeIn != null || it.fadeOut != null }) chip("Audio fades", cyan, "volume")
            val overlapping = editor.project.active(editor.cursor).size > 1
            if (overlapping && active.none { it.kind == "Placement" })
                chip("Placement · default", Effects.color("Placement"), "placement")
            val gain =
                editor.project.clips.firstOrNull {
                    it.audio && it.contains(editor.cursor) && it.gainDb != 0f
                }
            if (gain != null)
                chip("%+.1f dB".format(java.util.Locale.US, gain.gainDb), cyan, "volume", "volume")
            previousPosition?.let { chip("Back to ${timeLabel(it)}", red, "backPoint") }
            c.restore()
            chipLimit = max(0f, x + chipScroll - 5 - right)
            if (chipScroll > chipLimit) {
                chipScroll = chipLimit
                invalidate()
            }
            if (x + chipScroll > left) chipBounds.set(left, y - 17, right, y + 17)
            return active.isNotEmpty() || sound.any { it.censors.isNotEmpty() || it.fadeIn != null || it.fadeOut != null } ||
                overlapping ||
                gain != null ||
                tr != null ||
                boundary ||
                previousPosition != null
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x / unit - ox
        val y = event.y / unit - oy
        if (event.actionMasked == MotionEvent.ACTION_DOWN) previewGesture = previewBounds.contains(x, y)
        if (previewGesture) previewPinch.event(event) else pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previewGesture = previewBounds.contains(x, y)
                panAnchored = true
                panX = x; panY = y
                downX = x
                downY = y
                moved = false
                downCursor = editor.cursor
                downSpan = span
                scrubbing = false
                downChipScroll = chipScroll
                gainChanged = false
                gestureClip = clipAt(x, y)
                audioGesture = lanes.any { it.audio && y - timeline.top in it.top..it.bottom }
                downMoveOffset = editor.move?.offset ?: 0.0
                downLaneScroll = laneScroll
                longPressed = false
                removeCallbacks(longPress)
                if (timeline.contains(x, y) && !moving) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                downGain = gestureClip?.gainDb ?: 0f
                rangeHandle = 0
                editor.range?.let { r ->
                    if (timeline.contains(x, y)) {
                        if (abs(x - xFor(r.start)) < 8) rangeHandle = -1
                        else if (abs(x - xFor(r.endInclusive)) < 8) rangeHandle = 1
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if ((!previewGesture && pinch.isInProgress) || event.pointerCount > 1) return true
                if (previewGesture && inspection.zoom > 1.001f) {
                    if (panAnchored) inspection.pan(x - panX, y - panY)
                    panAnchored = true; panX = x; panY = y
                    if (abs(x - downX) + abs(y - downY) > 3) moved = true
                    invalidate()
                    return true
                }
                val dx = x - downX
                val dy = y - downY
                if (abs(dx) + abs(dy) > 3) { moved = true; removeCallbacks(longPress) }
                if (longPressed) return true
                if (chipBounds.contains(downX, downY) && moved) {
                    chipScroll = (downChipScroll - dx).coerceIn(0f, chipLimit)
                    invalidate()
                } else if (timeline.contains(downX, downY) && moved) {
                    if (rangeHandle != 0) {
                        val range = editor.range ?: return true
                        val t =
                            editor.snapTime(
                                start + (x - timeline.left) / timeline.width() * span,
                                pointsPerSecond,
                            )
                        editor.range =
                            if (rangeHandle < 0)
                                min(t, range.endInclusive - 0.01)..range.endInclusive
                            else range.start..max(t, range.start + 0.01)
                        seek(t)
                        invalidate()
                    } else if (moving && !pickingMoveBoundary && gestureClip?.id in editor.selected) {
                        val before = editor.move?.snap
                        editor.updateMove(downMoveOffset + dx / timeline.width() * span, timeline.width() / span)
                        if (editor.move?.snap != null && editor.move?.snap != before)
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    } else if (abs(dy) > abs(dx) * 1.4f && laneScrollMax > 0 &&
                        (gestureClip == null || !audioGesture || moving)) {
                        scrubbing = false
                        laneScroll = (downLaneScroll - dy).coerceIn(0f, laneScrollMax)
                        invalidate()
                    } else if (
                        !moving && abs(dy) > abs(dx) * 1.4f && audioGesture && gestureClip?.audio == true
                    ) {
                        scrubbing = false
                        if (!gainChanged) {
                            if (gestureClip!!.id !in editor.selected) editor.select(gestureClip!!)
                            gainChanged = true
                        }
                        gestureClip!!.gainDb = (downGain - dy * 0.25f).coerceIn(-60f, 24f)
                        invalidate()
                    } else {
                        scrubbing = true
                        seek(scrubTime(x))
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (longPressed) return true
                if (pinch.isInProgress) return true
                if (gainChanged) {
                    val value = gestureClip!!.gainDb
                    gestureClip!!.gainDb = downGain
                    gainChanged = false
                    editor.setGain(value)
                    return true
                }
                if (moved && moving) {
                    if (!pickingMoveBoundary && gestureClip?.id in editor.selected)
                        editor.updateMove(downMoveOffset + (x - downX) / timeline.width() * span, timeline.width() / span)
                    return true
                }
                if (scrubbing) {
                    seek(scrubTime(x))
                    finishScrub()
                    return true
                }
                if (moved) return true
                val hit = hits.lastOrNull { it.first.contains(x, y) }?.second
                if (hit != null) {
                    if (hit == "play") {
                        if (event.eventTime - lastPlayTap < 300) {
                            action("audition")
                            lastPlayTap = 0
                        } else {
                            action("play")
                            lastPlayTap = event.eventTime
                        }
                    } else activate(hit)
                } else if (timeline.contains(x, y)) {
                    if (pickingMoveBoundary) {
                        boundaryChoices = editor.move?.boundaries?.filter { abs(xFor(it) - x) <= 14 } ?: emptyList()
                        if (boundaryChoices.size == 1) editor.placeMove(boundaryChoices.single())
                        else if (boundaryChoices.isNotEmpty()) action("movePick")
                        if (boundaryChoices.isNotEmpty()) pickingMoveBoundary = false
                        invalidate()
                        return true
                    }
                    if (moving) return true
                    val clip = clipAt(x, y)
                    previousPosition = editor.cursor
                    val marking = editor.mark != null || editor.range != null
                    if (clip != null && !marking && y >= timeline.top + 16) editor.select(clip)
                    else {
                        val time = start + (x - timeline.left) / timeline.width() * span
                        if (y < timeline.top + 16) {
                            val bookmark = editor.project.markers().minByOrNull { abs(it - time) }
                                ?.takeIf { abs(it - time) * pointsPerSecond < 10 }
                            seek(bookmark ?: editor.snapTime(time, pointsPerSecond))
                        } else seek(time)
                    }
                    invalidate()
                } else if (previewBounds.contains(x, y)) {
                    action("play")
                }
                performClick()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                removeCallbacks(longPress)
                moved = true
                panAnchored = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                scrubbing = false
                if (moving && moved && gestureClip?.id in editor.selected)
                    editor.updateMove(downMoveOffset, timeline.width() / span, snapping = false)
            }
        }
        return true
    }

    private fun clipAt(x: Float, y: Float): Clip? {
        if (!timeline.contains(x, y)) return null
        val t = start + (x - timeline.left) / timeline.width() * span
        val lane = lanes.firstOrNull { y - timeline.top in it.top..it.bottom } ?: return null
        return editor.project.clips.lastOrNull {
            it.id in lane.ids &&
                (if (lane.audio) it.audio else it.video) &&
                TimelineLayout.interval(editor.project, it).let { r ->
                    t >= r.start && t < r.endInclusive
                }
        }
    }

    fun resetScroll() {
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun accessibilityItems(): List<EditorAccessibility.Item> = buildList {
        fun bounds(rect: RectF): Rect? {
            val result = Rect()
            val pixels = RectF((rect.left + ox) * unit, (rect.top + oy) * unit,
                (rect.right + ox) * unit, (rect.bottom + oy) * unit)
            pixels.roundOut(result)
            return result.takeIf { it.intersect(0, 0, width, height) }
        }
        fun label(id: String) = hitLabels[id] ?: when (id) {
            "preview" -> if (playing) "Pause video preview" else "Play video preview"
            "play" -> if (playing) "Pause playback" else "Play"
            "previous" -> "Previous edit"
            "next" -> "Next edit"
            "previousMark" -> "Previous bookmark"
            "forwardPoint" -> "Next bookmark"
            "addMedia" -> "Add media at the playhead"
            "fullscreen" -> if (fullscreen) "Exit full preview" else "Full preview"
            "playbackSpeed" -> "Review speed"
            "resetPreviewZoom" -> "Reset preview zoom"
            "snap" -> "Snap to cuts"
            "scale" -> "Timeline view span"
            "clear" -> "Clear selection"
            "empty" -> "Record or add a video"
            else -> id.replace(Regex("([a-z])([A-Z])"), "\$1 \$2").replaceFirstChar { it.uppercase() }
        }
        for ((rect, id) in hits.asReversed().distinctBy { it.second }.asReversed()) {
            val box = bounds(rect) ?: continue
            val value = when (id) {
                "snap" -> if (editor.snap) "On" else "Off"
                "playbackSpeed" -> "${editor.playbackRate}×"
                "preview" -> "${inspection.zoom}×"
                else -> null
            }
            add(EditorAccessibility.Item("control:$id", label(id), box, value, click = { activate(id) }))
        }
        if (fullscreen || timeline.isEmpty) return@buildList
        val duration = editor.project.duration
        bounds(timeline)?.let { box ->
            add(EditorAccessibility.Item("timeline", "Video and audio timeline", box, timeLabel(editor.cursor),
                range = 0f..duration.toFloat(), progress = editor.cursor.toFloat(), adjust = { seek(it.toDouble()); invalidate() }))
        }
        if (moving) {
            if (pickingMoveBoundary) for (time in editor.move?.boundaries.orEmpty()) {
                val x = xFor(time)
                if (x !in timeline.left..timeline.right) continue
                bounds(RectF(x - 14, timeline.top + 18, x + 14, timeline.top + 52))?.let { box ->
                    add(EditorAccessibility.Item("boundary:$time", "Place at ${timeLabel(time)}", box, click = {
                        editor.placeMove(time); pickingMoveBoundary = false; invalidate()
                    }))
                }
            }
            return@buildList
        }
        var videoNumber = 0
        var audioNumber = 0
        for (clip in editor.project.clips) {
            for (audio in listOf(false, true)) {
                if (if (audio) !clip.audio else !clip.video) continue
                val number = if (audio) ++audioNumber else ++videoNumber
                if (clip.end <= start || clip.start >= start + span) continue
                val rect = clipBounds(clip.id, audio) ?: continue
                val box = Rect().also(rect::roundOut)
                val interval = "${timeLabel(clip.start)} to ${timeLabel(clip.end)}"
                val gain = if (audio) ", %+.1f dB".format(clip.gainDb) else ""
                add(EditorAccessibility.Item("clip:$audio:${clip.id}", "${if (audio) "Audio" else "Video"} $number", box,
                    interval + gain, clip.id in editor.selected,
                    click = { editor.select(clip); invalidate() }, longClick = { editor.toggleSelection(clip); invalidate() },
                    range = if (audio) -60f..24f else null, progress = clip.gainDb.coerceIn(-60f, 24f),
                    adjust = if (audio) ({ value -> editor.change { clip.gainDb = value } }) else null, increment = .5f))
            }
        }
        for (transition in editor.project.transitions) {
            val x = xFor(transition.cut)
            if (x !in timeline.left..timeline.right) continue
            bounds(RectF(x - 12, timeline.top + 35, x + 12, timeline.top + 63))?.let { box ->
                add(EditorAccessibility.Item("transition:${transition.id}", "${transition.kind} transition", box,
                    timeLabel(transition.cut), abs(transition.cut - editor.cursor) < .001,
                    click = { editor.clearSelection(); seek(transition.cut); action("transition:${transition.id}") }))
            }
        }
        val picture = TimelineRules.union(editor.project.clips.filter { it.video }.map { TimelineSpan(it.start, it.end) })
        val sites = listOf(0.0) + picture.zipWithNext().map { (left, _) -> left.end } + (picture.lastOrNull()?.end ?: duration)
        for (time in sites.distinct()) {
            val x = xFor(time)
            if (x !in timeline.left..timeline.right) continue
            bounds(RectF(x - 10, timeline.top, x + 10, timeline.top + 16))?.let { box ->
                add(EditorAccessibility.Item("insert:$time", "Insert video at ${timeLabel(time)}", box, click = {
                    editor.clearSelection(); seek(time); action("addVideo")
                }))
            }
        }
    }

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = accessible

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = accessible.hover(event) || super.dispatchHoverEvent(event)

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        for ((i, label) in listOf("Zoom in preview", "Zoom out preview", "Reset preview zoom").withIndex())
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x02000101 + i, label))
        val reading = "%.2f× preview".format(java.util.Locale.US, inspection.zoom)
        if (android.os.Build.VERSION.SDK_INT >= 30) info.stateDescription = reading else info.text = reading
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        when (action) {
            0x02000101 -> inspection.scale(1.5f)
            0x02000102 -> inspection.scale(1 / 1.5f)
            0x02000103 -> inspection.reset()
            else -> return super.performAccessibilityAction(action, arguments)
        }
        onInspection(inspection.zoom)
        invalidate()
        return true
    }
}
