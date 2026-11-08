package org.yourec.app

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
    var fillPreview = false
    var moving = false
    var movingOrigin = 0.0
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
    private var downTime = 0L
    private var downCursor = 0.0
    private var downGain = 0f
    private var gainChanged = false
    private var audioGesture = false
    private var rangeHandle = 0
    private var lastPlayTap = 0L
    private var lastTap = 0L
    private var lastClip: String? = null
    private var gestureClip: Clip? = null
    private val red = Color.rgb(255, 55, 74)
    private val grey = Color.rgb(130, 130, 134)
    private val cyan = Color.rgb(5, 191, 179)
    private val yellow = Color.rgb(255, 211, 0)
    private val pinch =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (
                        previewBounds.contains(
                            detector.focusX / unit - ox,
                            detector.focusY / unit - oy,
                        )
                    )
                        fillPreview = detector.scaleFactor > 1
                    else {
                        editor.wholeProject = false
                        editor.scale =
                            (editor.scale / detector.scaleFactor).coerceIn(
                                1.0,
                                max(1.0, editor.project.duration),
                            )
                    }
                    invalidate()
                    return true
                }
            },
        )

    init {
        isFocusable = true
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
        if (enabled) hit(r, id)
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
            "back" -> path(0.5f, -1f, -0.5f, 0f, 0.5f, 1f)
            "undo",
            "redo" -> {
                val sign = if (name == "redo") -1 else 1
                path(
                    -1f * sign,
                    -0.1f,
                    0.5f * sign,
                    -0.1f,
                    1f * sign,
                    0.4f,
                    0.5f * sign,
                    1f,
                    0f,
                    1f,
                )
                path(-0.4f * sign, -0.8f, -1f * sign, -0.1f, -0.4f * sign, 0.5f)
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
            "move",
            "add" -> {
                line(-1f, 0f, 1f, 0f)
                line(0f, -1f, 0f, 1f)
            }
            "scale" -> {
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
        canvas.save()
        canvas.scale(unit, unit)
        canvas.translate(ox, oy)
        hits.clear()
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
        hit(RectF(addX - 14, addY - 18, addX + 14, addY + 18), "addVideo")
        drawTimeline(canvas)
        val fxY = timeline.bottom + 12f
        val hasChips = drawSelection(canvas, fxY)
        if (editor.selected.isEmpty() && editor.range == null) {
            text(
                canvas,
                "Scroll to scrub · pinch to zoom",
                timeline.left + 5,
                if (hasChips) fxY + 23f else if (landscape) 261f else 518f,
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
        bitmap?.let { b ->
            val ratio =
                if (fillPreview) max(r.width() / b.width, r.height() / b.height)
                else min(r.width() / b.width, r.height() / b.height)
            val dest =
                RectF(
                    r.centerX() - b.width * ratio / 2,
                    r.centerY() - b.height * ratio / 2,
                    r.centerX() + b.width * ratio / 2,
                    r.centerY() + b.height * ratio / 2,
                )
            c.save()
            c.clipRect(r)
            p.alpha = 255
            c.drawBitmap(b, null, dest, p)
            c.restore()
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
        val ids = listOf("backPoint", "previous", "play", "next", "forwardPoint")
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
        button(
            c,
            RectF(x + 2 * (cell + gap), y, x + 3 * cell + 2 * gap, y + first),
            "split",
            "Split",
            "split",
            editor.project.duration > 0 && !moving,
        )
        button(
            c,
            RectF(x + cell + gap, y, x + 2 * cell + gap, y + first),
            "mark",
            if (editor.mark != null) "${if(landscape)"End" else "Mark end"}"
            else if (landscape) "Start" else "Mark start",
            "mark",
            editor.project.duration > 0 && !moving,
        )
        button(
            c,
            RectF(x + 3 * (cell + gap), y, x + w, y + first),
            "tools",
            "Tools",
            "tools",
            !moving,
        )
        button(
            c,
            RectF(x, y, x + cell, y + first),
            "export",
            "Export",
            "export",
            editor.project.duration > 0 && !moving,
        )
        val sy = y + first + 6f
        val h = if (landscape) 32f else 35f
        val u = w * 0.11f
        var left = x
        for ((name, enabled) in listOf("undo" to editor.canUndo, "redo" to editor.canRedo)) {
            icon(c, name, left + u / 2, sy + h / 2, 11f, if (enabled) Color.WHITE else grey)
            hit(RectF(left, sy, left + u, sy + h), name)
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
            if (editor.selected.isNotEmpty() && editor.targets().any { it.linked }) "Unlink"
            else "Link",
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
            editor.selected.isNotEmpty() || editor.range != null,
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
        span = max(1.0, if (editor.wholeProject) editor.project.duration else editor.scale)
        start = TimelineLayout.start(editor.cursor, span, editor.wholeProject)
        lanes = TimelineLayout.lanes(editor.project, timeline.height())
        rect(c, timeline, Color.rgb(13, 13, 14))
        c.save()
        c.clipRect(timeline)
        val tick =
            when {
                span <= 2 -> 0.1
                span <= 10 -> 1.0
                span <= 60 -> 5.0
                span <= 300 -> 30.0
                else -> 60.0
            }
        var t = max(0.0, ceil(start / tick) * tick)
        while (t <= min(editor.project.duration, start + span)) {
            val x = xFor(t)
            text(
                c,
                if (span <= 2) "%.1f".format(java.util.Locale.US, t)
                else if (span < 60) t.toInt().toString() else timeLabel(t, false),
                x,
                timeline.top + 9,
                7f,
                grey,
                Paint.Align.CENTER,
            )
            t += tick
        }
        for ((n, clip) in editor.project.clips.withIndex()) {
            val interval = TimelineLayout.interval(editor.project, clip)
            if (interval.endInclusive - interval.start < .000001) continue
            if (interval.endInclusive < start || interval.start > start + span) continue
            index.request(clip)
            val left = xFor(interval.start)
            val right = xFor(interval.endInclusive)
            for (lane in
                lanes.filter {
                    it.track == clip.track && if (it.audio) clip.audio else clip.video
                }) {
                val top = timeline.top + lane.top
                val bottom = timeline.top + lane.bottom
                val clipR = RectF(left, top, right, bottom)
                c.save()
                c.clipRect(clipR)
                if (!lane.audio) {
                    val list = index.thumbnails[clip.path]
                    if (!list.isNullOrEmpty()) {
                        val tw = max(1f, (bottom - top) * list.first().width / list.first().height)
                        var x = TimelineLayout.thumbnailStart(left, timeline.left, tw)
                        while (x < min(right, timeline.right)) {
                            val at =
                                (start + (x - timeline.left) / timeline.width() * span -
                                    clip.start) * clip.effectiveRate + clip.sourceIn
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
                    text(c, "V${n+1}", max(left, timeline.left) + 3, min(bottom - 1, top + 9), 7f)
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
                            (start + (x - timeline.left) / timeline.width() * span - clip.start) *
                                clip.effectiveRate + clip.sourceIn
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
                    text(c, "♪${n+1}", max(left, timeline.left) + 3, clipR.bottom - 3, 7f)
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
            rect(
                c,
                RectF(x - 5, timeline.top + 44, x + 5, timeline.top + 54),
                Color.rgb(90, 45, 12),
                2f,
            )
            text(c, "◀▶", x, timeline.top + 51, 5f, Color.rgb(239, 151, 45), Paint.Align.CENTER)
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

    private fun drawSelection(c: Canvas, y: Float): Boolean {
        var x = timeline.left + 4
        if (moving) {
            val offset = (editor.targets().minOfOrNull { it.start } ?: movingOrigin) - movingOrigin
            text(
                c,
                "Moving Video + audio · %+.2f s".format(java.util.Locale.US, offset),
                x,
                y,
                7f,
                grey,
            )
            text(c, "Cancel", timeline.right - 60, y, 8f, red)
            hit(RectF(timeline.right - 70, y - 13, timeline.right - 27, y + 10), "moveCancel")
            text(c, "Done", timeline.right - 22, y, 8f, red)
            hit(RectF(timeline.right - 28, y - 13, timeline.right, y + 10), "moveDone")
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
            text(c, "Inserts time", x + 45, y, 7f, grey)
            icon(c, "close", x + 104, y - 3, 6f, red)
            hit(RectF(x, y - 13, x + 92, y + 10), "paste")
            hit(RectF(x + 94, y - 13, x + 118, y + 10), "dismissPaste")
            return true
        }
        if (editor.selected.isNotEmpty() || editor.range != null) {
            text(
                c,
                if (editor.range != null)
                    "Video + audio · ${timeLabel(editor.range!!.start)}–${timeLabel(editor.range!!.endInclusive)}"
                else
                    "${editor.targets().joinToString(" + "){(if(it.video)"V"else"♪") + (editor.project.clips.indexOf(it)+1)}}",
                x,
                y,
                7f,
                red,
            )
            x += if (landscape) 95f else 100f
            for ((symbol, id) in
                listOf("copy" to "copy", "delete" to "delete", "close" to "clear")) {
                icon(c, symbol, x, y - 2, 8f, if (id == "clear") grey else red)
                hit(RectF(x - 12, y - 13, x + 12, y + 10), id)
                x += 25
            }
        } else {
            val active =
                editor.project.clips
                    .filter { it.contains(editor.cursor) }
                    .flatMap { clip ->
                        clip.effects.filter { it.active(editor.cursor - clip.start) }
                    }
                    .distinctBy { it.kind }
            for (fx in active) {
                val title =
                    when (fx.kind) {
                        "Text" -> fx.values["text"] ?: "Text"
                        "Crop" -> "%.1f×".format(java.util.Locale.US, fx.number("zoom", 1f))
                        "Color LUTs" -> fx.values["lut"] ?: "Natural"
                        else -> fx.kind
                    }
                p.textSize = 8f
                val w = p.measureText(title).coerceAtMost(90f) + 17
                val r = RectF(x, y - 9, x + w, y + 9)
                rect(
                    c,
                    r,
                    Color.argb(
                        35,
                        Color.red(Effects.color(fx.kind)),
                        Color.green(Effects.color(fx.kind)),
                        Color.blue(Effects.color(fx.kind)),
                    ),
                    9f,
                )
                text(c, title.take(24), x + 8, y + 3, 8f, Effects.color(fx.kind))
                hit(r, "effect:${fx.id}")
                x += w + 4
            }
            val gain =
                editor.project.clips.firstOrNull {
                    it.audio && it.contains(editor.cursor) && it.gainDb != 0f
                }
            if (gain != null) {
                val title = "%+.1f dB".format(java.util.Locale.US, gain.gainDb)
                p.textSize = 8f
                val width = p.measureText(title) + 25
                val bounds = RectF(x, y - 9, x + width, y + 9)
                rect(c, bounds, Color.rgb(14, 35, 33), 9f)
                icon(c, "volume", x + 9, y, 5f, cyan)
                text(c, title, x + 18, y + 3, 8f, cyan)
                hit(bounds, "volume")
                x += width + 4
            }
            val tr =
                editor.project.transitions.firstOrNull {
                    abs(it.at - editor.cursor) < max(0.2, it.duration)
                }
            val boundary =
                editor.project.clips.any {
                    abs(it.start - editor.cursor) < .08 || abs(it.end - editor.cursor) < .08
                }
            if (tr != null || boundary && editor.project.clips.isNotEmpty()) {
                val title = tr?.kind ?: "Transition"
                p.textSize = 8f
                val w = p.measureText(title) + 18
                val r = RectF(x, y - 9, x + w, y + 9)
                rect(c, r, Color.rgb(39, 25, 14), 9f)
                text(c, title, x + 8, y + 3, 8f, Color.rgb(222, 137, 46))
                hit(r, "transition")
            }
            if (previousPosition != null) {
                text(
                    c,
                    "Back to ${timeLabel(previousPosition!!)}",
                    bw - 10,
                    y + 4,
                    8f,
                    red,
                    Paint.Align.RIGHT,
                )
                hit(RectF(bw - 100, y - 11, bw, y + 13), "backPoint")
            }
            return active.isNotEmpty() ||
                gain != null ||
                tr != null ||
                boundary ||
                previousPosition != null
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        val x = event.x / unit - ox
        val y = event.y / unit - oy
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                downTime = event.eventTime
                moved = false
                downCursor = editor.cursor
                gainChanged = false
                gestureClip = clipAt(x, y)
                audioGesture = lanes.any { it.audio && y - timeline.top in it.top..it.bottom }
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
                if (pinch.isInProgress || event.pointerCount > 1) return true
                val dx = x - downX
                val dy = y - downY
                if (abs(dx) + abs(dy) > 3) moved = true
                if (timeline.contains(downX, downY) && moved) {
                    if (rangeHandle != 0) {
                        val range = editor.range ?: return true
                        val t =
                            editor.snapTime(
                                start + (x - timeline.left) / timeline.width() * span,
                                span / timeline.width() * 5,
                            )
                        editor.range =
                            if (rangeHandle < 0)
                                min(t, range.endInclusive - 0.01)..range.endInclusive
                            else range.start..max(t, range.start + 0.01)
                        seek(t)
                        invalidate()
                    } else if (moving && gestureClip != null) {
                        seek(
                            (downCursor + dx / timeline.width() * span).coerceIn(
                                0.0,
                                editor.project.duration,
                            )
                        )
                    } else if (
                        abs(dy) > abs(dx) * 1.4f && audioGesture && gestureClip?.audio == true
                    ) {
                        if (!gainChanged) {
                            if (gestureClip!!.id !in editor.selected) editor.select(gestureClip!!)
                            gainChanged = true
                        }
                        gestureClip!!.gainDb = (downGain - dy * 0.25f).coerceIn(-60f, 24f)
                        invalidate()
                    } else {
                        val t = downCursor - dx / timeline.width() * span
                        seek(editor.snapTime(t, span / timeline.width() * 6))
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pinch.isInProgress) return true
                if (gainChanged) {
                    val value = gestureClip!!.gainDb
                    gestureClip!!.gainDb = downGain
                    gainChanged = false
                    editor.setGain(value)
                    return true
                }
                if (moved && moving && gestureClip != null) {
                    val target =
                        (gestureClip!!.start + (x - downX) / timeline.width() * span).coerceAtLeast(
                            0.0
                        )
                    val targetTrack = max(0, gestureClip!!.track + ((y - downY) / 35).roundToInt())
                    editor.moveSelection(target, targetTrack)
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
                    } else action(hit)
                } else if (timeline.contains(x, y)) {
                    val clip = clipAt(x, y)
                    previousPosition = editor.cursor
                    val marking = editor.mark != null || editor.range != null
                    if (clip != null && !marking) editor.select(clip)
                    seek(
                        editor.snapTime(
                            start + (x - timeline.left) / timeline.width() * span,
                            span / timeline.width() * 5,
                        )
                    )
                    lastClip = clip?.id
                    lastTap = event.eventTime
                    invalidate()
                } else if (previewBounds.contains(x, y)) {
                    if (event.eventTime - lastTap < 300) {
                        fillPreview = !fillPreview
                        invalidate()
                    } else action("play")
                    lastTap = event.eventTime
                }
                performClick()
            }
        }
        return true
    }

    private fun clipAt(x: Float, y: Float): Clip? {
        if (!timeline.contains(x, y)) return null
        val t = start + (x - timeline.left) / timeline.width() * span
        val lane = lanes.firstOrNull { y - timeline.top in it.top..it.bottom } ?: return null
        return editor.project.clips.lastOrNull {
            it.track == lane.track &&
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
}
