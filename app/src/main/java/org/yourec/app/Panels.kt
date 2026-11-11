package org.yourec.app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import kotlin.math.*

class Panels(private val a: MainActivity) {
    companion object {
        val RED = Color.rgb(255, 55, 74)
        val BG = Color.rgb(17, 17, 17)
        val MUTED = Color.rgb(130, 130, 135)
        val TEAL = Color.rgb(38, 189, 177)
    }

    private val editor
        get() = a.editor

    @Volatile private var panel: FrameLayout? = null
    private var preview: Preview? = null
    private var previewTick: (() -> Unit)? = null
    private var settings: LinearLayout? = null
    private var settingsHeader: FrameLayout? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var draft: Effect? = null
    private var draftReplace: String? = null
    private var draftOrigin = 0.0
    private var cropFrame = true
    private var previewEffects = true
    private var draftShape: Pair<Int, Int>? = null
    private val placements = linkedMapOf<String, Effect>()
    private val placementTracks = mutableMapOf<String, Int>()
    private var placementId: String? = null
    private var placementEditor: (() -> Unit)? = null
    private var draftTransition: Transition? = null
    private var previewTransition: Transition? = null
    private var draftGain: Float? = null
    private var draftRate: Double? = null
    private var captureDraft: MutableList<Effect>? = null
    private var captureDone: ((List<Effect>) -> Unit)? = null
    private var pendingEffects = mutableListOf<Effect>()
    private var initialEffects = mutableListOf<Effect>()
    private var beforeApply: (() -> Unit)? = null
    private var bitmap: Bitmap? = null
    private var title = ""
    private var factor = 1f
    private var landscape = true
    private val ui = Handler(Looper.getMainLooper())
    private var renderPending = false
    private var body: LinearLayout? = null
    private var applyPanel: (() -> Unit)? = null
    private var toolsPanel = false
    private var captureSheet = false
    private var captureExpanded = false
    private var reflowControls: (() -> Unit)? = null
    val isOpen
        get() = panel != null

    fun setFrame(frame: Bitmap) {
        bitmap = frame
        preview?.invalidate()
        previewTick?.invoke()
    }

    fun invalidatePreview() {
        preview?.invalidate()
        previewTick?.invoke()
    }

    private fun px(v: Float) = (v * factor).roundToInt()

    private fun shape(color: Int, radius: Float = 8f) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = px(radius).toFloat()
        }

    private fun text(
        t: String,
        size: Float = 10f,
        color: Int = Color.WHITE,
        bold: Boolean = false,
    ) =
        TextView(a).apply {
            text = t
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * factor * 1.25f)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }

    private fun column() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    private fun row() =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun pad(v: View, n: Float) {
        val p = px(n)
        v.setPadding(p, p, p, p)
    }

    private fun add(parent: LinearLayout, v: View, height: Float = -2f, width: Int = -1) {
        parent.addView(
            v,
            LinearLayout.LayoutParams(width, if (height < 0) height.toInt() else px(height)),
        )
    }

    private fun space(parent: LinearLayout, h: Float = 8f) {
        add(parent, View(a), h)
    }

    private fun label(
        parent: LinearLayout,
        t: String,
        color: Int = Color.WHITE,
        size: Float = 10f,
    ) {
        add(parent, text(t, size, color), 21f)
    }

    private fun hint(parent: LinearLayout, t: String) {
        val v = text(t, 7.5f, MUTED)
        v.setPadding(0, px(3f), 0, px(5f))
        add(parent, v)
    }

    private fun button(
        parent: LinearLayout,
        t: String,
        color: Int = RED,
        fn: () -> Unit,
    ): TextView {
        val v = text(t, 10f, color)
        v.background = shape(Color.rgb(29, 29, 31), 6f)
        v.setPadding(px(10f), 0, px(10f), 0)
        v.setOnClickListener { fn() }
        v.contentDescription = t
        val lp = LinearLayout.LayoutParams(-1, px(36f))
        lp.bottomMargin = px(5f)
        parent.addView(v, lp)
        return v
    }

    private fun toolRow(
        parent: LinearLayout,
        title: String,
        kind: String,
        color: Int,
        click: () -> Unit,
    ) {
        val item =
            row().apply {
                background = shape(tint(color))
                setPadding(px(8f), 0, px(8f), 0)
                setOnClickListener { click() }
            }
        item.addView(Glyph(a, kind, color), LinearLayout.LayoutParams(px(22f), px(24f)))
        item.addView(
            text(title, 10f).apply { setPadding(px(6f), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -1, 1f),
        )
        parent.addView(item, LinearLayout.LayoutParams(-1, px(39f)).apply { bottomMargin = px(7f) })
    }

    private fun grid(
        parent: LinearLayout,
        items: List<Pair<String, Int>>,
        columns: Int = 2,
        height: Float = 36f,
        gap: Float = 5f,
        click: (Int) -> Unit,
    ) {
        for (start in items.indices step columns) {
            val line = row()
            if (landscape && columns == 2 && items.size <= 2) line.weightSum = 4f
            for (i in start until min(start + columns, items.size)) {
                val title = items[i].first.removePrefix("Aa  ").removePrefix("▧  ")
                val item =
                    row().apply {
                        background = shape(tint(items[i].second), 7f)
                        setPadding(px(8f), 0, px(5f), 0)
                        contentDescription = title
                        setOnClickListener { click(i) }
                    }
                item.addView(
                    Glyph(a, title, items[i].second),
                    LinearLayout.LayoutParams(px(17f), px(20f)),
                )
                val label = text(title, 9f)
                label.setPadding(px(5f), 0, 0, 0)
                item.addView(label, LinearLayout.LayoutParams(0, -1, 1f))
                val lp = LinearLayout.LayoutParams(0, px(height), 1f)
                lp.setMargins(0, 0, px(5f), px(gap))
                line.addView(item, lp)
            }
            for (i in min(start + columns, items.size) until start + columns) line.addView(
                View(a),
                LinearLayout.LayoutParams(0, 0, 1f),
            )
            add(parent, line)
        }
    }

    private fun tint(color: Int) =
        Color.rgb(
            (Color.red(color) * 0.08f + 15).toInt(),
            (Color.green(color) * 0.08f + 15).toInt(),
            (Color.blue(color) * 0.08f + 15).toInt(),
        )

    private fun choices(
        parent: LinearLayout,
        items: List<String>,
        current: String,
        color: Int = RED,
        fn: (Int) -> Unit,
    ) {
        val r = row()
        for ((i, t) in items.withIndex()) {
            val v = text(t, 8f, if (t == current) color else Color.WHITE)
            v.gravity = Gravity.CENTER
            v.background = shape(if (t == current) tint(color) else Color.rgb(30, 30, 32), 5f)
            val lp = LinearLayout.LayoutParams(0, px(29f), 1f)
            lp.setMargins(0, 0, px(4f), 0)
            r.addView(v, lp)
            v.setOnClickListener {
                for (j in 0 until r.childCount) {
                    val item = r.getChildAt(j) as TextView
                    item.setTextColor(if (j == i) color else Color.WHITE)
                    item.background = shape(if (j == i) tint(color) else Color.rgb(30, 30, 32), 5f)
                }
                fn(i)
            }
        }
        add(parent, r, 34f)
    }

    private fun slider(
        parent: LinearLayout,
        name: String,
        value: Float,
        lo: Float,
        hi: Float,
        format: (Float) -> String = { "%.0f%%".format(it * 100) },
        color: Int = RED,
        inline: Boolean = false,
        showValue: Boolean = true,
        logarithmic: Boolean = false,
        changed: (Float) -> Unit,
    ): SeekBar {
        val r = row()
        val l = text(name, 9f, Color.WHITE)
        val v = text(format(value), 8f, MUTED)
        v.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        fun position(value: Float) =
            if (logarithmic) ln(value / lo) / ln(hi / lo) else (value - lo) / (hi - lo)
        fun value(position: Float) =
            if (logarithmic) lo * (hi / lo).pow(position) else lo + (hi - lo) * position
        val bar =
            ReferenceSlider(a, factor).apply {
                contentDescription = name
                max = 1000
                progress = (position(value) * 1000).toInt().coerceIn(0, 1000)
                progressTintList = android.content.res.ColorStateList.valueOf(color)
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
        if (inline) {
            r.addView(l, LinearLayout.LayoutParams(px(31f), px(28f)))
            r.addView(bar, LinearLayout.LayoutParams(0, px(28f), 1f))
            if (showValue) r.addView(v, LinearLayout.LayoutParams(px(45f), px(28f)))
            add(parent, r, 30f)
        } else {
            if (name.isNotEmpty()) {
                r.addView(l, LinearLayout.LayoutParams(0, px(23f), 1f))
                if (showValue) r.addView(v, LinearLayout.LayoutParams(px(70f), px(23f)))
                add(parent, r)
            }
            add(parent, bar, 25f)
        }
        v.setOnClickListener {
            val input =
                EditText(a).apply {
                    inputType =
                        android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    setText(value(bar.progress / 1000f).toString())
                    setSelectAllOnFocus(true)
                }
            android.app.AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(name.ifEmpty { "Value" })
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Done") { _, _ ->
                    input.text.toString().toFloatOrNull()?.coerceIn(lo, hi)?.let { number ->
                        bar.progress = (position(number) * 1000).roundToInt()
                        v.text = format(number)
                        changed(number)
                    }
                }
                .show()
        }
        bar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) {
                    val value = value(p / 1000f)
                    v.text = format(value)
                    if (user) {
                        changed(value)
                    }
                }

                override fun onStartTrackingTouch(b: SeekBar) {}

                override fun onStopTrackingTouch(b: SeekBar) {}
            }
        )
        return bar
    }

    private fun toggle(
        parent: LinearLayout,
        t: String,
        value: Boolean,
        fn: (Boolean) -> Unit,
    ): ReferenceToggle {
        val line = row()
        line.addView(text(t, 10f), LinearLayout.LayoutParams(0, -1, 1f))
        val sw =
            ReferenceToggle(a, factor).apply {
                checked = value
                changed = fn
                contentDescription = t
            }
        line.addView(sw, LinearLayout.LayoutParams(px(44f), px(25f)))
        add(parent, line, 34f)
        return sw
    }

    private fun settingCard(
        title: String,
        kind: String,
        value: Boolean,
        label: String = title,
        changed: (Boolean) -> Unit,
    ): LinearLayout {
        var checked = value
        val card =
            column().apply {
                pad(this, 8f)
                contentDescription = title
                isFocusable = true
                background =
                    shape(Color.rgb(31, 31, 31), 11f).apply {
                        setStroke(max(1, px(.65f)), Color.rgb(112, 112, 114))
                    }
            }
        val top = row()
        top.addView(Glyph(a, kind, Color.WHITE), LinearLayout.LayoutParams(px(18f), -1))
        top.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        val mark = FrameLayout(a)
        top.addView(mark, LinearLayout.LayoutParams(px(11f), px(11f)))
        val state = text("", 7f)
        top.addView(state, LinearLayout.LayoutParams(-2, -1).apply { leftMargin = px(4f) })
        add(card, top, 18f)
        space(card, 5f)
        add(card, text(label, 9f).apply { gravity = Gravity.TOP }, 28f)
        fun update() {
            state.text = if (checked) "On" else "Off"
            mark.removeAllViews()
            mark.addView(Glyph(a, if (checked) "Selected" else "Unselected", Color.WHITE))
        }
        update()
        card.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: android.view.accessibility.AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = checked
                }
            }
        card.setOnClickListener {
            checked = !checked
            update()
            changed(checked)
        }
        return card
    }

    fun close(refresh: Boolean = true) {
        captureDraft?.let { chain -> captureDone?.invoke(chain.map { it.copyEffect() }) }
        captureSheet = false
        panel?.let { a.root.removeView(it) }
        panel = null
        preview = null
        previewTick = null
        settings = null
        settingsHeader = null
        body = null
        applyPanel = null
        reflowControls = null
        draft = null
        draftReplace = null
        placements.clear()
        placementTracks.clear()
        placementId = null
        placementEditor = null
        draftTransition = null
        previewTransition = null
        draftGain = null
        draftRate = null
        draftShape = null
        beforeApply = null
        captureDraft = null
        captureDone = null
        progressBar = null
        progressText = null
        a.playback.previewProject = null
        if (refresh) a.playback.refresh()
    }

    private fun setup(
        name: String,
        apply: (() -> Unit)? = null,
        tools: Boolean = false,
        capture: Boolean = false,
    ): LinearLayout {
        close(false)
        captureSheet = capture
        title = name
        draftOrigin = editor.cursor
        cropFrame = true
        previewEffects = true
        applyPanel = apply
        toolsPanel = tools
        return createPanel(name, apply, tools)
    }

    private fun captureHeight(h: Int) =
        minOf(h - px(50f), px(if (captureExpanded) 518f else if (landscape) 206f else 288f)) -
            if (landscape) 0 else px(32f)

    private fun createPanel(name: String, apply: (() -> Unit)?, tools: Boolean): LinearLayout {
        landscape =
            a.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val w = a.root.width.takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels
        val h = a.root.height.takeIf { it > 0 } ?: a.resources.displayMetrics.heightPixels
        factor = min(w / (if (landscape) 640f else 294f), h / (if (landscape) 294f else 640f))
        val root =
            FrameLayout(a).apply {
                setBackgroundColor(BG)
                isClickable = true
                elevation = px(12f).toFloat()
            }
        panel = root
        val height =
            if (captureSheet) captureHeight(h) else if (tools && !landscape) h - px(46f) else h
        if ((tools && !landscape) || captureSheet) {
            root.background =
                shape(BG, if (captureSheet) 16f else 25f).apply {
                    if (captureSheet) setStroke(max(1, px(.6f)), Color.rgb(48, 48, 48))
                }
            root.clipToOutline = true
        }
        a.root.addView(
            root,
            FrameLayout.LayoutParams(-1, height, Gravity.BOTTOM).apply {
                if (captureSheet && !landscape) bottomMargin = px(32f)
            },
        )
        val content = column()
        body = content
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        if (!landscape && !tools && !captureSheet) {
            val safe = View(a).apply { setBackgroundColor(Color.BLACK) }
            add(content, safe, if (name == "Volume") 49f else 44f)
        }
        val header = FrameLayout(a)
        val plain = !captureSheet && name in listOf("Text", "Crop", "Video effects")
        val titles = column()
        val titleView = text(name, 10f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        if (plain) {
            add(titles, titleView, 16f)
            add(
                titles,
                text(
                        if (editor.range != null) rangeLabel()
                        else "${editor.targets().size} clip · entire effect",
                        6.5f,
                        MUTED,
                    )
                    .apply { gravity = Gravity.CENTER },
                11f,
            )
            header.addView(titles, FrameLayout.LayoutParams(-1, px(29f), Gravity.CENTER))
        } else header.addView(titleView, FrameLayout.LayoutParams(-1, -1))
        val cancel =
            if (captureSheet)
                FrameLayout(a)
                    .apply {
                        addView(
                            Glyph(
                                a,
                                if (captureExpanded) "Chevron down" else "Chevron up",
                                Color.WHITE,
                            ),
                            FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER),
                        )
                    }
                    .apply {
                        contentDescription =
                            if (captureExpanded) "Collapse effects" else "Expand effects"
                    }
            else text(if (tools) "" else "Cancel", 10f, RED).apply { gravity = Gravity.CENTER }
        if (!plain && !tools) cancel.background = shape(Color.rgb(37, 37, 39), 15f)
        header.addView(
            cancel,
            FrameLayout.LayoutParams(
                    px(if (captureSheet) 34f else if (plain) 68f else 60f),
                    px(30f),
                    Gravity.LEFT or Gravity.CENTER_VERTICAL,
                )
                .apply { leftMargin = px(if (landscape) if (plain) 42f else 28f else 10f) },
        )
        cancel.setOnClickListener {
            if (captureSheet) {
                captureExpanded = !captureExpanded
                cancel.contentDescription =
                    if (captureExpanded) "Collapse effects" else "Expand effects"
                (cancel as FrameLayout).removeAllViews()
                cancel.addView(
                    Glyph(a, if (captureExpanded) "Chevron down" else "Chevron up", Color.WHITE),
                    FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER),
                )
                root.layoutParams =
                    root.layoutParams.apply { this.height = captureHeight(a.root.height) }
            } else close()
        }
        val done =
            text(
                    if (tools || captureSheet) "Done" else "Apply",
                    10f,
                    if (captureSheet) Color.WHITE else RED,
                )
                .apply {
                    gravity = Gravity.CENTER
                    if (!plain) background = shape(Color.rgb(37, 37, 39), 15f)
                }
        val dlp =
            FrameLayout.LayoutParams(px(55f), px(28f), Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        dlp.rightMargin = px(if (landscape) if (tools) 28f else if (plain) 48f else 28f else 10f)
        header.addView(done, dlp)
        done.setOnClickListener {
            if (apply == null) close()
            else {
                beforeApply?.invoke()
                apply()
            }
        }
        add(
            content,
            header,
            if (landscape)
                if (tools || name == "Transition") 64f
                else if (name == "Enhance audio") 60f else 32f
            else if (name == "Video effects" && !captureSheet) 32f else 40f,
        )
        return content
    }

    fun relayout() {
        if (panel == null) return
        if (title == "Add audio") {
            addAudio(draftOrigin)
            return
        }
        if (title == "Projects" || title == "Recently deleted") {
            projects(title == "Recently deleted")
            return
        }
        val oldBody = body ?: return
        val controls = settings
        val scrollPosition = (controls?.parent as? ScrollView)?.scrollY ?: 0
        val children = (1 until oldBody.childCount).map { oldBody.getChildAt(it) }
        controls?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        panel?.let { a.root.removeView(it) }
        val next = createPanel(title, applyPanel, toolsPanel)
        if (controls != null) contentWithPreview(next, controls)
        else
            for (child in children) {
                oldBody.removeView(child)
                next.addView(child)
            }
        reflowControls?.invoke()
        (controls?.parent as? ScrollView)?.let { it.post { it.scrollTo(0, scrollPosition) } }
        renderDraft()
    }

    private fun contentWithPreview(
        body: LinearLayout,
        retained: LinearLayout? = null,
    ): LinearLayout {
        val horizontal = landscape
        if (horizontal && title in listOf("Text", "Video effects", "Transition")) {
            body.background =
                object : android.graphics.drawable.Drawable() {
                    private val paint = Paint()

                    override fun draw(canvas: Canvas) {
                        canvas.drawColor(Color.BLACK)
                        paint.color = BG
                        canvas.drawRect(
                            px(375f).toFloat(),
                            0f,
                            bounds.right.toFloat(),
                            bounds.bottom.toFloat(),
                            paint,
                        )
                    }

                    override fun setAlpha(alpha: Int) {}

                    override fun setColorFilter(filter: ColorFilter?) {}

                    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.OPAQUE
                }
            if (title != "Transition") (body.getChildAt(0) as? FrameLayout)?.setBackgroundColor(BG)
        }
        val container =
            LinearLayout(a).apply {
                orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            }
        body.addView(
            container,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                if (horizontal) {
                    leftMargin = px(45f)
                    rightMargin = px(45f)
                }
            },
        )
        val left = column().apply { setBackgroundColor(Color.BLACK) }
        val preview =
            Preview(
                a,
                if (horizontal && title == "Transition") px(18f).toFloat()
                else if (!horizontal && title in listOf("Crop", "Placement"))
                    px(if (title == "Crop") 14f else 18f).toFloat()
                else 0f,
            )
        this.preview = preview
        val fraction =
            when (title) {
                "Enhance audio" -> 0.45f
                "Text" -> 320f / 550f
                "Transition" -> 320f / 550f
                else -> 0.64f
            }
        val videoHeight =
            if (horizontal) 0
            else
                px(
                    when (title) {
                        "Volume" -> 190f
                        "Video effects" -> 196f
                        "Crop" -> 256f
                        "Placement" -> 284f
                        else -> 230f
                    }
                )
        val fixedAudio = horizontal && title == "Enhance audio"
        val previewHeight =
            if (fixedAudio) px(550f * 0.45f * editor.project.height / editor.project.width)
            else videoHeight
        if (horizontal && title == "Transition") {
            val value = draftTransition
            val before =
                editor.project.clips.indexOfFirst {
                    it.id == value?.leftId ||
                        value?.leftId == null && abs(it.end - (value?.at ?: 0.0)) < .02
                }
            val after =
                editor.project.clips.indexOfFirst {
                    it.id == value?.rightId ||
                        value?.rightId == null && abs(it.start - (value?.at ?: 0.0)) < .02
                }
            val description =
                if (before < 0) "Start"
                else if (after < 0) "End" else "Clip ${before+1} → ${after+1}"
            add(left, text(description, 8f, MUTED).apply { gravity = Gravity.CENTER }, 18f)
        }
        left.addView(
            preview,
            LinearLayout.LayoutParams(
                -1,
                if (horizontal && !fixedAudio) 0 else previewHeight,
                if (horizontal && !fixedAudio) 1f else 0f,
            ),
        )
        val region = previewRegion()
        val length = max(0.001, region.endInclusive - region.start)
        val seek =
            ReferenceSlider(a, factor).apply {
                max = 1000
                progress =
                    ((editor.cursor - region.start) / length * 1000).toInt().coerceIn(0, 1000)
                progressTintList = android.content.res.ColorStateList.valueOf(RED)
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) {
                    if (user) {
                        val current = previewRegion()
                        editor.cursor =
                            current.start + (current.endInclusive - current.start) * v / 1000
                        renderDraft()
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar) {}

                override fun onStopTrackingTouch(s: SeekBar) {}
            }
        )
        val r = row()
        val play =
            text(if (horizontal && title != "Enhance audio") "▶" else "▶ Play preview", 9f, RED)
        play.contentDescription = "Play preview"
        play.setOnClickListener {
            if (a.playback.playing) a.playback.pause()
            else {
                val current = previewRegion()
                if (editor.cursor !in current || editor.cursor >= current.endInclusive - 0.01)
                    editor.cursor = current.start
                a.playback.play(current.endInclusive)
            }
        }
        val time =
            text(
                "${timeLabel(max(0.0, editor.cursor - region.start))} / ${timeLabel(length)}",
                7f,
                MUTED,
            )
        var endTime: TextView? = null
        if (horizontal && title != "Volume" && title != "Enhance audio") {
            if (title == "Text") {
                val end =
                    text(timeLabel(length), 7f, MUTED).apply {
                        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    }
                endTime = end
                r.setPadding(px(10f), 0, px(10f), 0)
                time.text = timeLabel(max(0.0, editor.cursor - region.start))
                r.addView(time, LinearLayout.LayoutParams(px(40f), px(30f)))
                r.addView(seek, LinearLayout.LayoutParams(0, px(30f), 1f))
                r.addView(end, LinearLayout.LayoutParams(px(40f), px(30f)))
            } else {
                play.gravity = Gravity.CENTER
                r.addView(play, LinearLayout.LayoutParams(px(38f), px(30f)))
                r.addView(seek, LinearLayout.LayoutParams(0, px(30f), 1f))
                time.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                r.addView(time, LinearLayout.LayoutParams(px(87f), px(30f)))
            }
            add(left, r, 38f)
            space(left, if (title == "Text") 12f else if (title == "Transition") 14f else 8f)
        } else if (!horizontal && title == "Video effects") {
            play.text = "▶"
            r.setPadding(px(10f), 0, px(10f), 0)
            r.addView(play, LinearLayout.LayoutParams(px(28f), px(32f)))
            r.addView(seek, LinearLayout.LayoutParams(0, px(32f), 1f))
            r.addView(time, LinearLayout.LayoutParams(px(76f), px(32f)))
            add(left, r, 32f)
        } else {
            if (title == "Placement") {
                val summary = text(rangeLabel(), 9f)
                summary.setPadding(px(12f), 0, px(12f), 0)
                add(left, summary, 30f)
            }
            if (title == "Crop") {
                val tabs = row()
                val tabViews = mutableListOf<TextView>()
                for (name in listOf("Frame", "Inspect")) {
                    val tab = text(name, 9f, RED).apply { gravity = Gravity.CENTER }
                    if ((name == "Frame") == cropFrame)
                        tab.background = shape(Color.rgb(35, 35, 37), 15f)
                    tabViews.add(tab)
                    tab.setOnClickListener {
                        cropFrame = name == "Frame"
                        tabViews.forEachIndexed { i, view ->
                            view.background =
                                if ((i == 0) == cropFrame) shape(Color.rgb(35, 35, 37), 15f)
                                else null
                        }
                        renderDraft()
                    }
                    tabs.addView(tab, LinearLayout.LayoutParams(px(60f), px(30f)))
                }
                tabs.setPadding(px(10f), 0, 0, 0)
                add(left, tabs, 40f)
                val line = row()
                line.setPadding(px(10f), 0, px(10f), 0)
                line.addView(time, LinearLayout.LayoutParams(px(46f), px(28f)))
                line.addView(seek, LinearLayout.LayoutParams(0, px(28f), 1f))
                line.addView(
                    text(timeLabel(length), 7f, MUTED),
                    LinearLayout.LayoutParams(px(42f), px(28f)),
                )
                add(left, line, 28f)
            } else {
                r.setPadding(px(10f), 0, px(10f), 0)
                r.addView(time, LinearLayout.LayoutParams(0, px(30f), 1f))
                r.addView(play, LinearLayout.LayoutParams(-2, px(30f)))
                add(left, r, 30f)
                if (title !in listOf("Volume", "Enhance audio")) {
                    val inset = column().apply { setPadding(px(12f), 0, px(12f), 0) }
                    add(inset, seek, 30f)
                    add(left, inset)
                }
            }
        }
        previewTick = {
            play.text =
                if (a.playback.playing) {
                    if (title == "Video effects" || horizontal && title != "Enhance audio") "Ⅱ"
                    else "Ⅱ Pause preview"
                } else if (title == "Video effects" || horizontal && title != "Enhance audio") "▶"
                else "▶ Play preview"
            play.contentDescription = if (a.playback.playing) "Pause preview" else "Play preview"
            val current = previewRegion()
            val duration = max(0.001, current.endInclusive - current.start)
            val elapsed = (editor.cursor - current.start).coerceIn(0.0, duration)
            endTime?.text = timeLabel(duration)
            time.text =
                if (horizontal && title == "Text" || title == "Crop") timeLabel(elapsed)
                else "${timeLabel(elapsed)} / ${timeLabel(duration)}"
            if (!seek.isPressed) seek.progress = (elapsed / duration * 1000).toInt()
        }
        if (horizontal) container.addView(left, LinearLayout.LayoutParams(0, -1, fraction))
        else container.addView(left, LinearLayout.LayoutParams(-1, -2))
        val scroll =
            ScrollView(a).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
            }
        val controls = retained ?: column()
        pad(controls, 10f)
        if (horizontal && title in listOf("Text", "Transition"))
            controls.setPadding(px(20f), px(if (title == "Text") 13f else 10f), px(10f), px(10f))
        else if (horizontal && title == "Video effects")
            controls.setPadding(px(10f), px(13f), px(10f), px(10f))
        scroll.addView(controls)
        val right = column()
        if (title == "Video effects") {
            settingsHeader =
                FrameLayout(a).also { right.addView(it, LinearLayout.LayoutParams(-1, 0)) }
        }
        right.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (title == "Volume") {
            val footer = FrameLayout(a)
            val remove =
                text("Remove", 10f, RED).apply {
                    gravity = Gravity.CENTER
                    background = shape(Color.rgb(37, 37, 39), 18f)
                    setOnClickListener {
                        editor.setGain(0f)
                        close()
                    }
                }
            footer.addView(remove, FrameLayout.LayoutParams(px(68f), px(34f), Gravity.CENTER))
            right.addView(footer, LinearLayout.LayoutParams(-1, px(66f)))
        }
        if (horizontal) container.addView(right, LinearLayout.LayoutParams(0, -1, 1f - fraction))
        else container.addView(right, LinearLayout.LayoutParams(-1, 0, 1f))
        settings = controls
        return controls
    }

    private fun videoNavigation(
        parent: LinearLayout,
        view: View?,
        height: Float = 25f,
        top: Float = 0f,
    ) {
        (parent.parent as? ScrollView)?.scrollTo(0, 0)
        val header = settingsHeader
        if (header == null) {
            if (view != null) add(parent, view, height)
            return
        }
        header.removeAllViews()
        header.layoutParams =
            header.layoutParams.apply { this.height = if (view == null) 0 else px(height + top) }
        header.setPadding(px(10f), px(top), px(10f), 0)
        if (view != null) header.addView(view, FrameLayout.LayoutParams(-1, px(height)))
    }

    private fun previewRegion(): ClosedFloatingPointRange<Double> {
        draftTransition?.let { value ->
            val current =
                a.playback.previewProject?.transitions?.firstOrNull { it.id == value.id } ?: value
            val length = current.duration + current.hold
            return max(0.0, current.at - length / 2)..min(
                    a.playback.previewProject?.duration ?: editor.project.duration,
                    current.at + length / 2,
                )
        }
        editor.range?.let {
            return it
        }
        val targets = editor.targets()
        val first = targets.minOfOrNull { it.start } ?: 0.0
        val last = targets.maxOfOrNull { it.end } ?: editor.project.duration
        val fx = draft
        if (fx != null && fx.kind in listOf("Text", "Image")) {
            val base = if (draftReplace == null && editor.selected.isEmpty()) draftOrigin else first
            val from = (base + fx.from).coerceIn(first, last)
            return from..min(last, base + fx.to)
        }
        return first..last
    }

    private fun privacy() {
        val message = a.assets.open("privacy.txt").bufferedReader().use { it.readText() }
        android.app.AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Privacy policy")
            .setMessage(message)
            .setPositiveButton("Done", null)
            .show()
    }

    fun projects(deleted: Boolean = false) {
        close(false)
        title = if (deleted) "Recently deleted" else "Projects"
        val width = a.root.width.takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels
        val height = a.root.height.takeIf { it > 0 } ?: a.resources.displayMetrics.heightPixels
        landscape = width > height
        factor = min(width / if (landscape) 640f else 294f, height / if (landscape) 294f else 640f)
        val root =
            FrameLayout(a).apply {
                setBackgroundColor(Color.rgb(17, 17, 19))
                isClickable = true
            }
        panel = root
        a.root.addView(root, FrameLayout.LayoutParams(-1, -1))
        val body = column()
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        if (!landscape) space(body, 1f)
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false }
        val content = column()
        content.setPadding(
            px(if (landscape) 60f else 16f),
            0,
            px(if (landscape) 60f else 16f),
            px(18f),
        )
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val heading = row()
        val brand =
            android.text.SpannableString("yourec").apply {
                setSpan(
                    android.text.style.ForegroundColorSpan(RED),
                    3,
                    6,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        heading.addView(
            text("", 16f, Color.WHITE, true).apply {
                text = brand
                typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            },
            LinearLayout.LayoutParams(0, px(46f), 1f),
        )
        heading.addView(text("PROJECTS", 6f, MUTED), LinearLayout.LayoutParams(-2, px(46f)))
        add(content, heading, 46f)
        add(
            content,
            text(
                    if (deleted) "Recently deleted" else "Pick up where you left off.",
                    12f,
                    Color.WHITE,
                    true,
                )
                .apply { typeface = Typeface.create("sans-serif-black", Typeface.NORMAL) },
            34f,
        )
        fun pill(label: String, background: Int, color: Int, click: () -> Unit): LinearLayout =
            row().apply {
                gravity = Gravity.CENTER
                this.background =
                    shape(
                        background,
                        if (label == "Continue recording" || label.contains("Edit")) 21f else 16f,
                    )
                setOnClickListener { click() }
                contentDescription = label
                val kind =
                    when {
                        label.contains("Import") -> "Import"
                        label == "Privacy policy" -> "Privacy"
                        label.startsWith("Recently deleted") -> "Delete"
                        label.contains("Edit") -> "Edit"
                        else -> null
                    }
                if (kind != null)
                    addView(
                        Glyph(a, kind, color),
                        LinearLayout.LayoutParams(px(14f), px(16f)).apply { rightMargin = px(4f) },
                    )
                addView(
                    text(label.removePrefix("⇩ ").removePrefix("☷ "), 10f, color),
                    LinearLayout.LayoutParams(-2, -1),
                )
            }
        val actions = row()
        actions.addView(
            pill("+ New project", Color.rgb(50, 18, 24), RED) {
                a.newProject()
                close(false)
                a.showCapture()
            },
            LinearLayout.LayoutParams(px(105f), px(28f)),
        )
        actions.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(
            pill("⇩ Import", Color.rgb(50, 18, 24), RED) {
                close(false)
                a.importProject()
            },
            LinearLayout.LayoutParams(px(75f), px(28f)),
        )
        if (!deleted) {
            add(content, actions, 32f)
            space(content, 13f)
            add(
                content,
                pill("Privacy policy", Color.TRANSPARENT, RED) { privacy() }
                    .apply { gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL },
                30f,
            )
            space(content, 15f)
        } else {
            add(content, pill("‹ Projects", Color.TRANSPARENT, RED) { projects() }, 32f)
            space(content, 10f)
        }
        val thumbnails = mutableListOf<() -> Unit>()
        for (project in if (deleted) a.store.recentlyDeleted() else a.store.list()) {
            val card =
                column().apply {
                    background = shape(Color.rgb(27, 27, 31), 15f)
                    setPadding(px(14f), px(14f), px(14f), px(14f))
                    setOnClickListener {
                        if (!deleted) {
                            a.openProject(project)
                            projects()
                        }
                    }
                }
            val details = row()
            val thumbnail =
                ImageView(a).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = shape(Color.BLACK, 6f)
                    setImageDrawable(null)
                    clipToOutline = true
                }
            val image = FrameLayout(a)
            image.addView(thumbnail, FrameLayout.LayoutParams(-1, -1))
            if (project.clips.none { it.video })
                image.addView(
                    Glyph(a, "Film", MUTED),
                    FrameLayout.LayoutParams(px(20f), px(24f), Gravity.CENTER),
                )
            details.addView(image, LinearLayout.LayoutParams(px(46f), px(42f)))
            val name =
                text(project.name, 10f, Color.WHITE, true).apply {
                    typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                    setPadding(px(6f), 0, 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            fun rename() {
                val input =
                    EditText(a).apply {
                        setSingleLine()
                        setText(project.name)
                        setSelectAllOnFocus(true)
                    }
                android.app.AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Rename project")
                    .setView(input)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save") { _, _ ->
                        val value = input.text.toString().trim()
                        if (value.isNotEmpty()) {
                            project.name = value
                            if (editor.project.id == project.id) editor.project.name = value
                            a.store.save(project, editor.project.id == project.id)
                            projects()
                        }
                    }
                    .show()
            }
            if (!deleted) name.setOnClickListener { rename() }
            details.addView(name, LinearLayout.LayoutParams(0, px(42f), 1f))
            if (!deleted)
                details.addView(
                    Glyph(a, "Rename", MUTED).apply { setOnClickListener { rename() } },
                    LinearLayout.LayoutParams(px(15f), px(30f)).apply { rightMargin = px(6f) },
                )
            if (!deleted)
                details.addView(
                    Glyph(
                        a,
                        if (editor.project.id == project.id) "Selected" else "Record",
                        if (editor.project.id == project.id) Color.rgb(44, 205, 85)
                        else Color.TRANSPARENT,
                    ),
                    LinearLayout.LayoutParams(px(20f), px(42f)),
                )
            val menu =
                Glyph(a, "More", RED).apply { contentDescription = "Options for ${project.name}" }
            menu.setOnClickListener {
                if (deleted) {
                    a.choose(project.name, listOf("Restore", "Delete permanently")) { action ->
                        if (action == 0) {
                            a.store.restore(project)
                            projects(true)
                        } else
                            a.choose(
                                "Delete permanently?",
                                listOf("Delete permanently", "Cancel"),
                            ) {
                                if (it == 0) {
                                    a.store.erase(project)
                                    projects(true)
                                }
                            }
                    }
                    return@setOnClickListener
                }
                a.choose(project.name, listOf("Rename", "Duplicate", "Delete")) { action ->
                    when (action) {
                        0 -> rename()
                        1 -> {
                            a.store.save(
                                project.duplicate().copy(id = uid(), name = "${project.name} copy"),
                                false,
                            )
                            projects()
                        }
                        2 ->
                            a.choose("Delete project?", listOf("Delete project", "Cancel")) {
                                if (it == 0) {
                                    a.store.delete(project)
                                    if (editor.project.id == project.id)
                                        editor.load(a.store.list().firstOrNull() ?: Project())
                                    projects()
                                }
                            }
                    }
                }
            }
            details.addView(menu, LinearLayout.LayoutParams(px(28f), px(24f)))
            add(card, details, 42f)
            add(
                card,
                text(
                    "${project.clips.map { it.path }.distinct().size.let { "$it ${if (it == 1) "take" else "takes"}" }} · ${timeLabel(project.duration, false)} edited",
                    8f,
                    MUTED,
                ),
                22f,
            )
            space(card, 8f)
            val buttons = row()
            buttons.addView(
                pill("Continue recording", Color.rgb(250, 49, 67), Color.WHITE) {
                    close(false)
                    a.openProject(project)
                    a.showCapture()
                },
                LinearLayout.LayoutParams(px(125f), px(42f)),
            )
            buttons.addView(
                pill("☷ Edit", Color.rgb(22, 87, 179), Color.WHITE) {
                    close(false)
                    a.openProject(project)
                },
                LinearLayout.LayoutParams(px(62f), px(42f)).apply { leftMargin = px(6f) },
            )
            if (deleted) {
                add(
                    card,
                    pill("Restore", Color.rgb(22, 87, 179), Color.WHITE) {
                        a.store.restore(project)
                        projects(true)
                    },
                    38f,
                )
            } else add(card, buttons, 42f)
            content.addView(
                card,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12f) },
            )
            project.clips
                .firstOrNull { it.video }
                ?.let { clip ->
                    thumbnails.add {
                        val reader = android.media.MediaMetadataRetriever()
                        val bitmap =
                            try {
                                reader.setDataSource(clip.path)
                                SourceMirror.frame(
                                    clip.path,
                                    reader.getScaledFrameAtTime(
                                        (clip.sourceIn * 1_000_000).toLong(),
                                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                        144,
                                        81,
                                    ),
                                )
                            } catch (_: Exception) {
                                null
                            } finally {
                                reader.release()
                            }
                        ui.post {
                            if (panel === root) thumbnail.setImageBitmap(bitmap)
                            else bitmap?.recycle()
                        }
                    }
                }
        }
        if (!deleted) {
            space(content, 3f)
            add(
                content,
                pill(
                        "Recently deleted (${a.store.recentlyDeleted().size})",
                        Color.TRANSPARENT,
                        RED,
                    ) {
                        projects(true)
                    }
                    .apply { gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL },
                38f,
            )
        }
        Thread {
                for (load in thumbnails) {
                    if (panel !== root) break
                    load()
                }
            }
            .start()
        val tabs =
            row().apply {
                setBackgroundColor(Color.rgb(22, 22, 30))
                setPadding(px(if (landscape) 42f else 10f), 0, px(if (landscape) 42f else 10f), 0)
            }
        for (name in listOf("Record", "Projects", "Edit")) {
            val color = if (name == "Projects") RED else MUTED
            val tab =
                column().apply {
                    gravity = Gravity.CENTER
                    contentDescription = name
                }
            tab.addView(Glyph(a, name, color), LinearLayout.LayoutParams(px(23f), px(25f)))
            tab.addView(
                text(name, 7f, color).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, px(19f)),
            )
            tab.setOnClickListener {
                if (name != "Projects") {
                    close(false)
                    if (name == "Record") a.showCapture() else a.showEditor()
                }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(0, px(59f), 1f))
        }
        add(body, tabs, 59f)
    }

    fun tools() {
        val body = setup("Tools", tools = true)
        val scroll = ScrollView(a)
        val content = column()
        pad(content, 12f)
        if (landscape) content.setPadding(px(60f), px(12f), px(60f), px(12f))
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        hint(content, "Insert video at ${timeLabel(editor.cursor)}")
        toolRow(content, "Add video", "Add video", Effects.color("Video effects")) {
            close()
            a.importVideo()
        }
        toolRow(content, "Add video layer", "Add video", Effects.color("Placement")) {
            close()
            a.importVideo(layer = true)
        }
        toolRow(content, "Add audio", "Volume", TEAL) { addAudio() }
        toolRow(content, "Transition", "Transition", Effects.color("Transition")) { transition() }
        hint(content, "Select a clip boundary, the start, or the end.")
        if (editor.clipboard.isNotEmpty())
            button(content, "Paste", RED) {
                editor.paste()
                close()
            }
        label(content, "Overlays")
        hint(content, rangeLabel())
        grid(
            content,
            listOf("Aa  Text" to Effects.color("Text"), "▧  Image" to Effects.color("Transition")),
        ) {
            if (it == 0) effect("Text")
            else
                a.importImage { path ->
                    effect("Image", Effect(kind = "Image", values = mutableMapOf("path" to path)))
                }
        }
        label(content, "Adjust")
        hint(content, rangeLabel())
        val kinds =
            listOf(
                "Video effects",
                "Stabilize",
                "Crop",
                "Mirror",
                "Color LUTs",
                "Speed",
                "Pauses",
                "Enhance audio",
                "Placement",
            )
        grid(
            content,
            kinds.map { it to Effects.color(it) },
            if (landscape) 4 else 2,
            height = if (landscape) 36f else 40f,
            gap = if (landscape) 5f else 7f,
        ) { i ->
            when (kinds[i]) {
                "Video effects" -> videoEffects()
                "Enhance audio" -> audio()
                "Speed" -> speed()
                "Pauses" -> pauses()
                else -> effect(kinds[i])
            }
        }
        space(content)
        val applied = editor.project.clips.flatMap { it.effects }.distinctBy { it.kind }
        if (applied.isNotEmpty()) {
            label(content, "In this project")
            for (effect in applied) {
                val suffix =
                    when (effect.kind) {
                        "Crop" -> " · %.1f×".format(java.util.Locale.US, effect.number("zoom", 1f))
                        "Color LUTs" -> " · ${effect.values["lut"] ?: "Natural"}"
                        else -> ""
                    }
                toolRow(
                    content,
                    effect.kind + suffix + "   ›",
                    effect.kind,
                    Effects.color(effect.kind),
                ) {
                    editExisting(effect.id)
                }
            }
        }
        button(content, "Auto captions", Effects.color("Text")) { captions() }
        label(content, "Settings")
        val settings = row()
        settings.weightSum = if (landscape) 4f else 2f
        if (landscape) settings.setPadding(0, 0, px(16f), 0)
        settings.addView(
            settingCard("Snap to cuts", "Snap", editor.snap) { editor.snap = it },
            LinearLayout.LayoutParams(0, px(72f), 1f).apply { rightMargin = px(8f) },
        )
        settings.addView(
            settingCard(
                "Match listening levels",
                "Listening",
                editor.matchLoudness,
                "Match listening\nlevels",
            ) {
                editor.matchLoudness = it
            },
            LinearLayout.LayoutParams(0, px(72f), 1f),
        )
        add(content, settings)
    }

    fun addAudio(at: Double = editor.cursor) {
        a.playback.pause()
        val content = setup("Add audio")
        val root = panel ?: return
        root.background = shape(Color.rgb(32, 32, 34), 26f).apply {
            setStroke(max(1, px(.5f)), Color.rgb(65, 65, 67))
        }
        root.clipToOutline = true
        root.layoutParams = FrameLayout.LayoutParams(
            a.root.width - px(12f),
            min(a.root.height - px(45f), px(if (landscape) 245f else 330f)),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = px(6f) }
        content.removeAllViews()
        val handle = View(a).apply { background = shape(Color.rgb(105, 105, 108), 2f) }
        content.addView(handle, LinearLayout.LayoutParams(px(42f), px(3f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = px(5f)
        })
        val header = FrameLayout(a)
        header.addView(text("Add audio", 10f, Color.WHITE, true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -1))
        val cancel = text("Cancel", 10f, RED).apply {
            gravity = Gravity.CENTER
            background = shape(Color.rgb(55, 55, 58), 16f)
            setOnClickListener { close() }
        }
        header.addView(cancel, FrameLayout.LayoutParams(px(60f), px(30f), Gravity.CENTER_VERTICAL)
            .apply { leftMargin = px(10f) })
        add(content, header, 42f)
        val scroll = ScrollView(a)
        val controls = column().apply { setPadding(px(14f), px(10f), px(14f), px(14f)) }
        scroll.addView(controls)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        add(controls, text("At ${timeLabel(at)}", 10f, Color.WHITE, true), 28f)
        space(controls, 8f)
        add(controls, text("Adds a separate sound layer. Your existing video and audio stay in place.",
            10f, MUTED), 44f)
        space(controls, 8f)
        val choose = row().apply {
            gravity = Gravity.CENTER
            background = shape(Color.rgb(0, 207, 185), 24f)
            contentDescription = "Choose audio from Files"
            setOnClickListener {
                close()
                a.importAudio(at)
            }
        }
        choose.addView(Glyph(a, "Folder", Color.BLACK), LinearLayout.LayoutParams(px(18f), px(20f)))
        choose.addView(text("Choose audio from Files", 10f, Color.BLACK),
            LinearLayout.LayoutParams(-2, -1).apply { leftMargin = px(8f) })
        add(controls, choose, 44f)
        space(controls, 12f)
        add(controls, text("WAV, AIFF, M4A, MP3 and other supported audio files. Use Move and Volume to arrange the sound after importing.",
            8f, MUTED))
    }

    private fun rangeLabel() =
        editor.range?.let {
            "Selected range · ${timeLabel(it.start)}–${timeLabel(it.endInclusive)}"
        }
            ?: "${editor.targets().size} clip${if(editor.targets().size==1)""else"s"} · ${timeLabel(editor.cursor)}"

    fun editExisting(id: String) {
        val fx = editor.project.clips.flatMap { it.effects }.firstOrNull { it.id == id } ?: return
        when {
            Effects.audio.contains(fx.kind) -> audio(fx)
            fx.kind in Effects.video -> videoEffects(fx)
            else -> effect(fx.kind, fx)
        }
    }

    private fun renderDraft() {
        if (renderPending) return
        renderPending = true
        ui.postDelayed(
            {
                renderPending = false
                if (panel == null) return@postDelayed
                captureDraft?.let { chain ->
                    captureDone?.invoke(chain.map { it.copyEffect() })
                    return@postDelayed
                }
                val temporary =
                    Editor(editor.project.duplicate()).apply {
                        cursor =
                            if (
                                draft?.kind in listOf("Text", "Image") &&
                                    draftReplace == null &&
                                    editor.selected.isEmpty()
                            )
                                draftOrigin
                            else editor.cursor
                        range = editor.range
                        selected.addAll(editor.selected)
                    }
                val effects =
                    if (title == "Video effects" || title == "Enhance audio") pendingEffects
                    else draft?.let { listOf(it) } ?: emptyList()
                draftGain?.let { temporary.setGain(it) }
                draftRate?.let { temporary.setRate(it) }
                when (title) {
                    "Video effects" ->
                        temporary.replaceChain(
                            if (previewEffects) effects else emptyList(),
                            Effects.video,
                        )
                    "Enhance audio" -> temporary.replaceChain(effects, Effects.audio)
                    "Placement" -> temporary.applyPlacements(placements, placementTracks)
                    else ->
                        effects.forEach {
                            if (title == "Crop" && cropFrame)
                                temporary.targets().forEach { clip ->
                                    clip.effects.removeAll { it.kind == "Crop" }
                                }
                            else temporary.applyEffect(it, draftReplace)
                        }
                }
                val p = temporary.project
                draftShape?.let { (width, height) ->
                    p.width = width
                    p.height = height
                }
                draftTransition?.let { t ->
                    temporary.putTransition(t)
                    temporary.project.transitions
                        .firstOrNull { it.id == t.id }
                        ?.let { current ->
                            val old = previewTransition
                            if (
                                old == null ||
                                    old.at != current.at ||
                                    old.duration != current.duration ||
                                    old.hold != current.hold
                            ) {
                                val position =
                                    if (old == null) .5
                                    else
                                        ((editor.cursor - old.at) / (old.duration + old.hold) + .5)
                                            .coerceIn(0.0, 1.0)
                                editor.cursor =
                                    current.at + (position - .5) * (current.duration + current.hold)
                            }
                            previewTransition = current.copy()
                        }
                }
                a.playback.previewProject = p
                a.playback.refresh(p)
            },
            50,
        )
    }

    fun effect(kind: String, existing: Effect? = null) {
        val fx = existing?.copyEffect() ?: Effect(kind = kind)
        val origin = editor.cursor
        val replace =
            existing?.id?.takeIf { id ->
                editor.project.clips.any { c -> c.effects.any { it.id == id } }
            }
        val body =
            setup(
                kind,
                {
                    val position = editor.cursor
                    if (
                        replace == null &&
                            kind in listOf("Text", "Image") &&
                            editor.selected.isEmpty() &&
                            editor.range == null
                    )
                        editor.cursor = origin
                    if (kind == "Placement") editor.applyPlacements(placements, placementTracks)
                    else {
                        val shape = draftShape
                        if (shape != null) editor.beginBatch()
                        editor.applyEffect(fx, replace)
                        if (shape != null) {
                            editor.project.width = shape.first
                            editor.project.height = shape.second
                            editor.endBatch(false)
                        }
                    }
                    editor.cursor = position.coerceAtMost(editor.project.duration)
                    close()
                },
            )
        draft = fx
        draftReplace = replace
        if (kind == "Placement") {
            for (clip in editor.targets().filter { it.video }) {
                placements[clip.id] =
                    clip.effects.lastOrNull { it.kind == "Placement" }?.copyEffect()
                        ?: Effect(kind = "Placement")
            }
            placementId =
                editor.targets().filter { it.id in placements }.maxByOrNull { it.track }?.id
            draft = placements[placementId]
        }
        pendingEffects.clear()
        draftTransition = null
        val controls = contentWithPreview(body)
        when (kind) {
            "Text" -> {
                val input =
                    EditText(a).apply {
                        setText(fx.values["text"] ?: "")
                        hint = "Write something"
                        setTextColor(Color.WHITE)
                        setHintTextColor(MUTED)
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * factor)
                        background = shape(Color.BLACK, 3f)
                        setPadding(px(5f), 0, px(5f), 0)
                        maxLines = 3
                    }
                input.addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) {}

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) {
                            fx.values["text"] = s.toString()
                            renderDraft()
                        }

                        override fun afterTextChanged(e: Editable?) {}
                    }
                )
                add(controls, input, 22f)
                space(controls, 7f)
                val styleRow = row()
                val style =
                    text("Style: ${fx.values["style"] ?: "Bold"}", 9f, RED, true).apply {
                        gravity = Gravity.CENTER
                        background = shape(Color.rgb(34, 34, 36), 16f)
                    }
                style.setOnClickListener {
                    fx.values["style"] = if (fx.values["style"] == "Regular") "Bold" else "Regular"
                    style.text = "Style: ${fx.values["style"]}"
                    renderDraft()
                }
                styleRow.addView(style, LinearLayout.LayoutParams(px(75f), px(32f)))
                val colors = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
                val swatches = row()
                val names = listOf("White", "Black", "Yellow", "Red", "Blue", "Green")
                val values =
                    listOf("#ffffff", "#000000", "#ffdd00", "#ff374a", "#438bff", "#40c875")
                for ((i, value) in values.withIndex()) {
                    val swatch =
                        object : View(a) {
                            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

                            override fun onDraw(canvas: Canvas) {
                                val cx = width / 2f
                                val cy = height / 2f
                                paint.style = Paint.Style.STROKE
                                paint.strokeWidth = factor
                                paint.color =
                                    if ((fx.values["color"] ?: "#ffffff") == value) Color.WHITE
                                    else Color.rgb(48, 48, 52)
                                canvas.drawCircle(cx, cy, px(10f).toFloat(), paint)
                                paint.style = Paint.Style.FILL
                                paint.color = Color.parseColor(value)
                                canvas.drawCircle(cx, cy, px(7.5f).toFloat(), paint)
                            }
                        }
                    swatch.contentDescription = names[i]
                    swatch.setOnClickListener {
                        fx.values["color"] = value
                        for (j in 0 until swatches.childCount) swatches.getChildAt(j).invalidate()
                        renderDraft()
                    }
                    swatches.addView(swatch, LinearLayout.LayoutParams(px(35f), px(32f)))
                }
                colors.addView(swatches)
                styleRow.addView(colors, LinearLayout.LayoutParams(0, px(32f), 1f))
                add(controls, styleRow, 36f)
                hint(controls, "Drag to move · pinch to resize")
                slider(
                    controls,
                    "Size",
                    fx.number("size", 0.07f),
                    0.015f,
                    0.25f,
                    format = { "%.0f".format(it * 1000) },
                    inline = true,
                    showValue = false,
                ) {
                    fx.values["size"] = it.toString()
                    renderDraft()
                }
                val positionRow = row()
                val position = text("Position", 10f, RED)
                positionRow.addView(position, LinearLayout.LayoutParams(0, px(29f), 1f))
                positionRow.addView(text("›", 17f), LinearLayout.LayoutParams(px(12f), px(29f)))
                position.setOnClickListener {
                    a.choose(
                        "Position",
                        listOf(
                            "Top left",
                            "Top center",
                            "Top right",
                            "Center left",
                            "Center",
                            "Center right",
                            "Bottom left",
                            "Bottom center",
                            "Bottom right",
                        ),
                    ) { i ->
                        fx.values["x"] = listOf("0.05", "0.5", "0.95")[i % 3]
                        fx.values["y"] = listOf("0.15", "0.5", "0.9")[i / 3]
                        fx.values["align"] = listOf("Left", "Center", "Right")[i % 3]
                        renderDraft()
                    }
                }
                positionRow.setOnClickListener { position.performClick() }
                add(controls, positionRow, 29f)
                duration(controls, fx)
                if (existing != null)
                    button(controls, "⌫ Remove text") {
                        editor.removeEffect(existing.id)
                        close()
                    }
            }
            "Crop",
            "Zoom",
            "Rotation" -> {
                val shapeRow = row()
                val shapeLabel = text("Video shape\nWhole project", 9f, RED)
                shapeRow.addView(shapeLabel, LinearLayout.LayoutParams(0, px(40f), 1f))
                val shapeValue = text("Original  ⌃", 9f, RED)
                shapeRow.addView(shapeValue, LinearLayout.LayoutParams(-2, px(40f)))
                shapeRow.setOnClickListener {
                    val names = listOf("Original", "16:9", "9:16", "1:1", "4:5")
                    a.choose("Video shape", names) { i ->
                        val native =
                            editor.project.clips
                                .firstOrNull { it.video }
                                ?.let { ProjectStore.shape(it.path) }
                                ?: (editor.project.width to editor.project.height)
                        val ratios =
                            listOf(
                                native.first.toDouble() / native.second,
                                16.0 / 9,
                                9.0 / 16,
                                1.0,
                                4.0 / 5,
                            )
                        val ratio = ratios[i]
                        val edge = max(editor.project.width, editor.project.height)
                        draftShape =
                            if (i == 0) native
                            else
                                (if (ratio >= 1) edge else (edge * ratio).roundToInt()) / 2 * 2 to
                                    (if (ratio >= 1) (edge / ratio).roundToInt() else edge) / 2 * 2
                        shapeValue.text = "${names[i]}  ⌃"
                        renderDraft()
                    }
                }
                add(controls, shapeRow, 40f)
                hint(controls, "Pinch to crop · drag to frame")
                var rotation: SeekBar? = null
                val angleRow = row()
                angleRow.addView(text("Rotation", 9f), LinearLayout.LayoutParams(0, px(24f), 1f))
                val angle = text("%.0f°".format(fx.number("rotation")), 8f, RED)
                angleRow.addView(angle, LinearLayout.LayoutParams(px(35f), px(24f)))
                angleRow.addView(
                    Glyph(a, "Rotate", RED).apply {
                        contentDescription = "Rotate 90 degrees"
                        setOnClickListener {
                            fx.values["quarter"] =
                                ((fx.number("quarter").toInt() + 1) % 4).toString()
                            renderDraft()
                        }
                    },
                    LinearLayout.LayoutParams(px(25f), px(24f)),
                )
                add(controls, angleRow, 24f)
                rotation =
                    slider(
                        controls,
                        "",
                        fx.number("rotation"),
                        -45f,
                        45f,
                        format = { "%.0f°".format(it) },
                        showValue = false,
                    ) {
                        fx.values["rotation"] = it.toString()
                        angle.text = "%.0f°".format(it)
                        renderDraft()
                    }
                hint(controls, "Edges stay filled. Tap the angle to reset.")
                angle.contentDescription = "Reset rotation"
                angle.setOnClickListener {
                    fx.values["rotation"] = "0"
                    rotation?.progress = 500
                    angle.text = "0°"
                    renderDraft()
                }
                var zoom: SeekBar? = null
                zoom =
                    slider(
                        controls,
                        "Zoom",
                        fx.number("zoom", 1f),
                        1f,
                        5f,
                        format = { "%.1f×".format(it) },
                        inline = true,
                    ) {
                        fx.values["zoom"] = it.toString()
                        renderDraft()
                    }
                val reset = row()
                reset.addView(
                    text("Reset framing", 10f, RED).apply {
                        setOnClickListener {
                            fx.values.clear()
                            rotation?.progress = 500
                            zoom?.progress = 0
                            angle.text = "0°"
                            renderDraft()
                        }
                    },
                    LinearLayout.LayoutParams(0, px(34f), 1f),
                )
                reset.addView(
                    text("${editor.project.width} × ${editor.project.height} px", 6.5f, MUTED),
                    LinearLayout.LayoutParams(-2, px(34f)),
                )
                add(controls, reset, 40f)
                val position = text("Position                                      ›", 10f, RED)
                position.setOnClickListener {
                    a.choose("Position", listOf("Center", "Top", "Bottom", "Left", "Right")) { i ->
                        fx.values["x"] = listOf("0", "0", "0", "-0.25", "0.25")[i]
                        fx.values["y"] = listOf("0", "-0.25", "0.25", "0", "0")[i]
                        renderDraft()
                    }
                }
                add(controls, position, 34f)
                hint(
                    controls,
                    "Video shape applies to the whole project. Other clips keep their full image unless cropped.",
                )
                hint(controls, "Framing changes instantly at the edges of this selection.")
                if (existing != null)
                    button(controls, "Remove crop") {
                        editor.removeEffect(existing.id)
                        close()
                    }
            }
            "Placement" -> placementControls(controls)
            "Color LUTs" -> {
                choices(
                    controls,
                    listOf("Natural", "Warm", "Cool", "Cinema"),
                    fx.values["lut"] ?: "Natural",
                ) {
                    fx.values["lut"] = listOf("Natural", "Warm", "Cool", "Cinema")[it]
                    renderDraft()
                }
                choices(controls, listOf("Mono"), fx.values["lut"] ?: "Natural") {
                    fx.values["lut"] = "Mono"
                    renderDraft()
                }
                slider(controls, "Strength", fx.number("strength", 1f), 0f, 1f) {
                    fx.values["strength"] = it.toString()
                    renderDraft()
                }
                hint(controls, "Creativity only: the transform does not change the source.")
                if (existing != null)
                    button(controls, "Remove LUT") {
                        editor.removeEffect(existing.id)
                        close()
                    }
            }
            "Stabilize" -> {
                label(controls, "Stabilization", TEAL)
                hint(
                    controls,
                    "Track edges to smooth camera movement. A small crop keeps the frame covered.",
                )
                slider(controls, "Strength", fx.number("strength", 0.5f), 0f, 1f, color = TEAL) {
                    fx.values["strength"] = it.toString()
                    renderDraft()
                }
                if (existing != null)
                    button(controls, "Remove stabilization") {
                        editor.removeEffect(existing.id)
                        close()
                    }
            }
            "Mirror" -> {
                choices(
                    controls,
                    listOf("Horizontal", "Vertical"),
                    if (fx.number("vertical") == 1f) "Vertical" else "Horizontal",
                ) {
                    fx.values["vertical"] = it.toString()
                    renderDraft()
                }
                if (existing != null)
                    button(controls, "Remove mirror") {
                        editor.removeEffect(existing.id)
                        close()
                    }
            }
            "Image" -> {
                hint(controls, "Drag to move · pinch to resize")
                slider(controls, "Size", fx.number("size", 0.3f), 0.05f, 1f) {
                    fx.values["size"] = it.toString()
                    renderDraft()
                }
                duration(controls, fx)
                if (existing != null)
                    button(controls, "Remove image") {
                        editor.removeEffect(existing.id)
                        close()
                    }
            }
        }
        renderDraft()
    }

    private fun placementControls(parent: LinearLayout) {
        fun build() {
            parent.removeAllViews()
            val id = placementId ?: return
            val fx = placements[id] ?: return
            hint(parent, "Drag to move · pinch to resize\nDrag edge circles to crop")
            val line = row()
            val number = editor.project.clips.indexOfFirst { it.id == id } + 1
            val selected = text("V$number · front ⌃", 10f, RED)
            selected.setOnClickListener {
                val ids = placements.keys.toList()
                a.choose(
                    "Video layer",
                    ids.map {
                        "V${editor.project.clips.indexOfFirst { clip -> clip.id == it } + 1}"
                    },
                ) {
                    placementId = ids[it]
                    draft = placements[placementId]
                    placementEditor?.invoke()
                    renderDraft()
                }
            }
            line.addView(selected, LinearLayout.LayoutParams(0, px(35f), 1f))
            val layout =
                text("▥ Layout", 10f, RED).apply {
                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                }
            layout.setOnClickListener {
                a.choose(
                    "Layout",
                    listOf(
                        "Full frame",
                        "Picture in picture",
                        "Left half",
                        "Right half",
                        "Top half",
                        "Bottom half",
                    ),
                ) {
                    val values =
                        listOf(
                            floatArrayOf(0f, 0f, 1f, 1f),
                            floatArrayOf(.65f, .05f, .3f, .3f),
                            floatArrayOf(0f, 0f, .5f, 1f),
                            floatArrayOf(.5f, 0f, .5f, 1f),
                            floatArrayOf(0f, 0f, 1f, .5f),
                            floatArrayOf(0f, .5f, 1f, .5f),
                        )[it]
                    listOf("x", "y", "w", "h").forEachIndexed { index, key ->
                        fx.values[key] = values[index].toString()
                    }
                    listOf("left", "right", "top", "bottom", "panX", "panY", "contentZoom")
                        .forEach { key -> fx.values.remove(key) }
                    renderDraft()
                }
            }
            line.addView(layout, LinearLayout.LayoutParams(px(75f), px(35f)))
            add(parent, line, 35f)
            toggle(parent, "Reframe inside window", fx.number("reframe") == 1f) {
                fx.values["reframe"] = if (it) "1" else "0"
                renderDraft()
            }
            val order = row()
            for (front in listOf(true, false)) {
                val button =
                    text(if (front) "Bring to front" else "Send to back", 9f, RED).apply {
                        gravity = Gravity.CENTER
                        background = shape(Color.rgb(42, 25, 27), 14f)
                    }
                button.setOnClickListener {
                    if (front)
                        placementTracks[id] =
                            max(
                                editor.project.clips.maxOfOrNull { it.track } ?: 0,
                                placementTracks.values.maxOrNull() ?: 0,
                            ) + 1
                    else {
                        for (clip in
                            editor.project.clips.filter {
                                it.video && it.id != id
                            }) placementTracks[clip.id] =
                            (placementTracks[clip.id] ?: clip.track) + 1
                        placementTracks[id] = 0
                    }
                    renderDraft()
                }
                order.addView(
                    button,
                    LinearLayout.LayoutParams(px(102f), px(29f)).apply { rightMargin = px(6f) },
                )
            }
            add(parent, order, 34f)
        }
        placementEditor = { build() }
        build()
    }

    private fun duration(parent: LinearLayout, fx: Effect) {
        val targets = editor.targets()
        val base =
            editor.range?.start
                ?: if (draftReplace == null && editor.selected.isEmpty()) draftOrigin
                else targets.minOfOrNull { it.start } ?: 0.0
        val end =
            editor.range?.endInclusive ?: targets.minOfOrNull { it.end } ?: editor.project.duration
        val length = max(0.05, end - base)
        if (fx.to == Double.MAX_VALUE) fx.to = min(length, 3.0)
        fx.from = fx.from.coerceIn(0.0, max(0.0, length - .05))
        fx.to = fx.to.coerceIn(fx.from + .05, length)
        val labels = mutableListOf<TextView>()
        val bars = mutableListOf<SeekBar>()
        val duration =
            text("", 7f, MUTED).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }
        fun refresh() {
            labels.getOrNull(0)?.text = "Start ${timeLabel(base + fx.from)}"
            labels.getOrNull(1)?.text = "End ${timeLabel(base + fx.to)}"
            duration.text = "%.2f s".format(java.util.Locale.US, fx.to - fx.from)
            bars.getOrNull(0)?.progress = (fx.from / length * 1000).roundToInt()
            bars.getOrNull(1)?.progress = (fx.to / length * 1000).roundToInt()
            renderDraft()
        }
        space(parent, 6f)
        choices(
            parent,
            listOf("2 s", "3 s", "5 s"),
            "${(fx.to-fx.from).toInt()} s",
            Effects.color("Text"),
        ) {
            fx.to = min(length, fx.from + listOf(2.0, 3.0, 5.0)[it])
            refresh()
        }
        for (start in listOf(true, false)) {
            val line = row()
            val label = text("", 7f)
            labels.add(label)
            line.addView(label, LinearLayout.LayoutParams(0, px(23f), 1f))
            if (start) line.addView(duration, LinearLayout.LayoutParams(px(60f), px(23f)))
            add(parent, line, 23f)
            val bar =
                ReferenceSlider(a, factor).apply {
                    max = 1000
                    progressTintList = android.content.res.ColorStateList.valueOf(RED)
                    contentDescription = if (start) "Effect start" else "Effect end"
                }
            bars.add(bar)
            bar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(view: SeekBar, value: Int, user: Boolean) {
                        if (!user) return
                        val time = length * value / 1000
                        if (start) fx.from = min(time, fx.to - .05)
                        else fx.to = max(time, fx.from + .05)
                        refresh()
                    }

                    override fun onStartTrackingTouch(view: SeekBar) {}

                    override fun onStopTrackingTouch(view: SeekBar) {}
                }
            )
            label.setOnClickListener {
                val input =
                    EditText(a).apply {
                        setSingleLine()
                        setText(timeLabel(base + if (start) fx.from else fx.to))
                        setSelectAllOnFocus(true)
                    }
                android.app.AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(if (start) "Start" else "End")
                    .setView(input)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Done") { _, _ ->
                        val parts =
                            input.text.toString().trim().split(':').mapNotNull {
                                it.toDoubleOrNull()
                            }
                        if (parts.isNotEmpty()) {
                            val time =
                                (parts.fold(0.0) { sum, value -> sum * 60 + value } - base)
                                    .coerceIn(0.0, length)
                            if (start) fx.from = min(time, fx.to - .05)
                            else fx.to = max(time, fx.from + .05)
                            refresh()
                        }
                    }
                    .show()
            }
            add(parent, bar, 25f)
        }
        refresh()
    }

    fun videoEffects(existing: Effect? = null) {
        val current =
            editor
                .targets()
                .flatMap { it.effects }
                .filter { it.kind in Effects.video }
                .distinctBy { it.kind }
                .map { it.copyEffect() }
                .toMutableList()
        if (existing != null && current.none { it.id == existing.id })
            current.add(existing.copyEffect())
        val body =
            setup(
                "Video effects",
                {
                    applyChain(current, false)
                    close()
                },
            )
        pendingEffects = current
        initialEffects = current.map { it.copyEffect() }.toMutableList()
        draft = null
        val controls = contentWithPreview(body)
        if (existing != null)
            videoParameters(
                controls,
                current.firstOrNull { it.id == existing.id } ?: existing,
                current,
            )
        else if (current.isEmpty()) categories(controls, current)
        else appliedVideo(controls, current)
        renderDraft()
    }

    private fun appliedVideo(parent: LinearLayout, chain: MutableList<Effect>) {
        parent.removeAllViews()
        reflowControls = { appliedVideo(parent, chain) }
        parent.setPadding(px(10f), px(if (landscape || captureSheet) 0f else 10f), px(10f), px(10f))
        draft = null
        val heading = row()
        heading.addView(
            text("Applied effects · ${chain.size}", 9f),
            LinearLayout.LayoutParams(0, px(25f), 1f),
        )
        heading.addView(
            text("+ Add", 9f, if (captureSheet) Color.WHITE else RED).apply {
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                setOnClickListener { categories(parent, chain) }
            },
            LinearLayout.LayoutParams(px(50f), px(25f)),
        )
        videoNavigation(parent, heading)
        if (captureDraft == null) hint(parent, "At the playhead · tap an effect to adjust it.")
        for (fx in chain.toList()) {
            val card =
                EffectCard(a, factor) {
                    chain.remove(fx)
                    appliedVideo(parent, chain)
                    renderDraft()
                }
            card.background = shape(Color.rgb(26, 26, 26), 8f)
            val r = row().apply { setPadding(px(10f), px(8f), px(8f), px(8f)) }
            val category =
                Effects.categories.entries.firstOrNull { fx.kind in it.value }?.key
                    ?: if (fx.kind == "Undercranking") "Time" else "Color & style"
            r.addView(
                Glyph(a, category, categoryColor(category)),
                LinearLayout.LayoutParams(px(18f), px(22f)).apply { rightMargin = px(9f) },
            )
            val description = column()
            add(description, text(fx.kind, 9.5f), -2f)
            add(
                description,
                text(if (fx.enabled) "On · tap to edit" else "Off · tap to edit", 6.5f, MUTED),
                -2f,
            )
            description.setOnClickListener { videoParameters(parent, fx, chain) }
            r.addView(description, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(text("›", 13f, MUTED), LinearLayout.LayoutParams(px(12f), px(26f)))
            val sw =
                ReferenceToggle(a, factor).apply {
                    checked = fx.enabled
                    contentDescription = "${fx.kind} enabled"
                    changed = { value ->
                        fx.enabled = value
                        (description.getChildAt(1) as TextView).text =
                            if (value) "On · tap to edit" else "Off · tap to edit"
                        renderDraft()
                    }
                }
            r.addView(sw, LinearLayout.LayoutParams(px(44f), px(25f)))
            card.addView(r, FrameLayout.LayoutParams(-1, -2))
            card.minimumHeight = px(57f)
            card.setOnClickListener { videoParameters(parent, fx, chain) }
            parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })
        }
    }

    private fun categoryColor(category: String) =
        when (category) {
            "Presets" -> TEAL
            "Color & style" -> Color.rgb(224, 133, 49)
            "Time" -> Color.rgb(179, 67, 213)
            else -> Color.rgb(43, 184, 213)
        }

    private fun categories(parent: LinearLayout, chain: MutableList<Effect>) {
        parent.removeAllViews()
        reflowControls = { categories(parent, chain) }
        videoNavigation(parent, null)
        parent.setPadding(px(10f), px(if (landscape) 0f else 10f), px(10f), px(10f))
        draft = null
        label(parent, "Add effects", size = 9f)
        if (chain.isNotEmpty())
            button(parent, "‹ Applied · ${chain.size}") { appliedVideo(parent, chain) }
        for ((category, items) in Effects.categories) {
            val card =
                row().apply {
                    background = shape(tint(categoryColor(category)), 7f)
                    setPadding(px(10f), 0, px(8f), 0)
                }
            card.addView(
                Glyph(a, category, categoryColor(category)),
                LinearLayout.LayoutParams(px(20f), px(24f)).apply { rightMargin = px(9f) },
            )
            val description = column()
            add(description, text(category, 9f), 17f)
            add(
                description,
                text(
                    if (category == "Presets") "Combined effects" else "${items.size} effects",
                    7f,
                    MUTED,
                ),
                12f,
            )
            card.addView(description, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(text("›", 14f, MUTED), LinearLayout.LayoutParams(px(10f), px(24f)))
            parent.addView(
                card,
                LinearLayout.LayoutParams(-1, px(41f)).apply { bottomMargin = px(6f) },
            )
            card.setOnClickListener { videoCategory(parent, chain, category, items) }
        }
    }

    private fun videoCategory(
        parent: LinearLayout,
        chain: MutableList<Effect>,
        category: String,
        items: List<String>,
    ) {
        parent.removeAllViews()
        parent.setPadding(px(10f), 0, px(10f), px(10f))
        reflowControls = { videoCategory(parent, chain, category, items) }
        val heading = row().apply { setOnClickListener { categories(parent, chain) } }
        heading.addView(text("‹", 20f, RED), LinearLayout.LayoutParams(px(28f), px(29f)))
        heading.addView(
            text(category, 10f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, px(29f), 1f),
        )
        videoNavigation(parent, heading, 32f)
        space(parent, 14f)
        hint(
            parent,
            when (category) {
                "Color & style" -> "Color, contrast, grain and glow"
                "Presets" -> "Combined effects"
                else -> category
            },
        )
        space(parent, 5f)
        for (kind in items) {
            val item =
                row().apply {
                    background = shape(Color.rgb(26, 26, 26), 8f)
                    setPadding(px(10f), px(9f), px(10f), px(9f))
                }
            item.addView(
                Glyph(a, category, categoryColor(category)),
                LinearLayout.LayoutParams(px(18f), px(23f)).apply { rightMargin = px(10f) },
            )
            val description = column()
            add(description, text(kind, 10f, Color.WHITE, true))
            val detail =
                when (kind) {
                    "Night fix" ->
                        "Lift dark footage and clean noise. Choose fast cleanup or motion-aligned restoration. Editor only."
                    "Color Correction" ->
                        "Balance exposure, contrast, white balance and color before your look."
                    "Silent Tint" -> "Tint the picture with a single film color."
                    "Three-Strip" -> "Rich colors, deeper reds and dense blacks."
                    "RGB Offset" ->
                        "Separate green and magenta edges without changing the colors of flat areas."
                    "Quantization" -> "Reduce colors with a scene or fixed palette."
                    "Day for Night" -> "Turn daylight into a cool, dark night look."
                    "Color Isolation" ->
                        "Keep one color or the person in color; make the rest monochrome."
                    "Noir" -> "Black and white with deep shadows and contrast."
                    else -> "Tap to add"
                }
            add(description, text(detail, 7.5f, MUTED).apply { setPadding(0, px(3f), 0, 0) })
            item.addView(description, LinearLayout.LayoutParams(0, -2, 1f))
            item.addView(
                Glyph(a, "Add", categoryColor(category)),
                LinearLayout.LayoutParams(px(15f), px(20f)).apply { leftMargin = px(27f) },
            )
            parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })
            if (captureDraft != null && kind == "Night fix") {
                item.isEnabled = false
                item.alpha = .45f
            }
            item.setOnClickListener {
                if (category == "Presets") {
                    if (kind == "Clean") chain.clear()
                    val kinds =
                        when (kind) {
                            "Expressionism" ->
                                listOf("Expressionism", "Silent Tint", "16mm", "Undercranking")
                            "Silent cinema" -> listOf("Noir", "Film grain", "Vignette")
                            "Vintage film" -> listOf("Sepia", "Film grain")
                            else -> emptyList()
                        }
                    chain.addAll(kinds.map { Effect(kind = it) })
                    appliedVideo(parent, chain)
                    renderDraft()
                } else {
                    val fx = Effect(kind = kind)
                    chain.add(fx)
                    videoParameters(parent, fx, chain)
                    renderDraft()
                }
            }
        }
    }

    private fun videoParameters(parent: LinearLayout, fx: Effect, chain: MutableList<Effect>) {
        parent.removeAllViews()
        reflowControls = { videoParameters(parent, fx, chain) }
        parent.setPadding(
            px(10f),
            if (settingsHeader != null) 0 else px(if (landscape) 13f else 10f),
            px(10f),
            px(10f),
        )
        draft = fx
        val nav = row()
        val back = text("‹ Applied · ${chain.size}", 9f, RED)
        back.setOnClickListener { appliedVideo(parent, chain) }
        nav.addView(back, LinearLayout.LayoutParams(0, px(25f), 1f))
        val add =
            text("+ Add", 9f, RED).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }
        add.setOnClickListener { categories(parent, chain) }
        nav.addView(add, LinearLayout.LayoutParams(px(55f), px(25f)))
        videoNavigation(parent, nav, top = if (landscape) 13f else 10f)
        nav.translationY = if (landscape) -px(7f).toFloat() else 0f
        val heading = text(fx.kind, 11f, Color.WHITE, true)
        heading.translationY = if (landscape) -px(4f).toFloat() else 0f
        add(parent, heading, 21f)
        val cinema = fx.kind in listOf("Kaleidoscope", "Widescreen 1953")
        val orange = if (cinema) Color.rgb(43, 199, 231) else Color.rgb(240, 137, 35)
        val actions = row()
        actions.translationY = if (landscape) -px(4f).toFloat() else 0f
        actions.translationX = if (landscape) px(2f).toFloat() else 0f
        val animate =
            row().apply {
                contentDescription = "Animate"
                background = shape(Color.rgb(32, 32, 32), 16f)
                setPadding(px(8f), 0, px(8f), 0)
            }
        val gold = if (cinema) orange else Color.rgb(200, 135, 66)
        animate.addView(Glyph(a, "Animate", gold), LinearLayout.LayoutParams(px(12f), px(14f)))
        animate.addView(
            text("Animate", 7f, gold),
            LinearLayout.LayoutParams(-2, -1).apply { leftMargin = px(6f) },
        )
        animate.setOnClickListener {
            a.choose("Animate", listOf("Off", "Fade in", "Fade out", "Fade in and out")) {
                fx.values["animate"] = it.toString()
                renderDraft()
            }
        }
        actions.addView(animate, LinearLayout.LayoutParams(px(71f), px(32f)))
        val power =
            object : View(a) {
                    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

                    override fun onDraw(canvas: Canvas) {
                        paint.color = if (fx.enabled) Color.BLACK else MUTED
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = factor * 1.3f
                        paint.strokeCap = Paint.Cap.ROUND
                        val x = width / 2f
                        val y = height / 2f
                        val r = factor * 6f
                        canvas.drawArc(x - r, y - r, x + r, y + r, -45f, 270f, false, paint)
                        canvas.drawLine(x, y - r - factor * 1.5f, x, y, paint)
                    }
                }
                .apply {
                    background = shape(if (fx.enabled) orange else Color.rgb(40, 40, 42), 18f)
                }
        power.contentDescription = "Effect enabled"
        power.setOnClickListener {
            fx.enabled = !fx.enabled
            power.invalidate()
            power.background = shape(if (fx.enabled) orange else Color.rgb(40, 40, 42), 18f)
            renderDraft()
        }
        actions.addView(
            power,
            LinearLayout.LayoutParams(px(32f), px(32f)).apply { leftMargin = px(6f) },
        )
        if (cinema && !landscape) {
            parent.removeView(heading)
            actions.addView(heading, 0, LinearLayout.LayoutParams(-2, px(32f)))
            val help =
                Glyph(a, "Help", MUTED).apply {
                    contentDescription = "About ${fx.kind}"
                    setOnClickListener {
                        android.app.AlertDialog.Builder(a)
                            .setTitle(fx.kind)
                            .setMessage(
                                if (fx.kind == "Widescreen 1953")
                                    "Cinema framing with horizontal flares from bright highlights."
                                else "Mirrors repeated angular sections around the chosen center."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            actions.addView(help, 1, LinearLayout.LayoutParams(px(25f), px(32f)))
            actions.addView(View(a), 2, LinearLayout.LayoutParams(0, 1, 1f))
        }
        add(parent, actions, 41f)
        slider(parent, "Intensity", fx.number("strength", 1f), 0f, 1f, color = orange) {
            fx.values["strength"] = it.toString()
            renderDraft()
        }
        fun pick(value: String, action: () -> Unit) {
            val line =
                row().apply {
                    background = shape(Color.rgb(29, 29, 31), 7f)
                    setPadding(px(9f), 0, px(9f), 0)
                    setOnClickListener { action() }
                }
            line.addView(text(value, 10f, orange), LinearLayout.LayoutParams(0, -1, 1f))
            line.addView(Glyph(a, "Chevrons", orange), LinearLayout.LayoutParams(px(14f), px(20f)))
            parent.addView(
                line,
                LinearLayout.LayoutParams(-1, px(32f)).apply { bottomMargin = px(8f) },
            )
        }
        when (fx.kind) {
            "Kaleidoscope" -> {
                slider(
                    parent,
                    "Segments",
                    fx.number("segments", 4f),
                    2f,
                    24f,
                    format = { it.toInt().toString() },
                    color = orange,
                ) {
                    fx.values["segments"] = it.toInt().toString()
                    renderDraft()
                }
                slider(parent, "Rotation", fx.number("rotation"), -180f, 180f, color = orange) {
                    fx.values["rotation"] = it.toString()
                    renderDraft()
                }
                slider(parent, "Zoom", fx.number("zoom", .75f), .5f, 4f, color = orange) {
                    fx.values["zoom"] = it.toString()
                    renderDraft()
                }
                for ((name, key) in
                    listOf("Center X" to "centerX", "Center Y" to "centerY")) slider(
                    parent,
                    name,
                    fx.number(key, .5f),
                    0f,
                    1f,
                    color = orange,
                ) {
                    fx.values[key] = it.toString()
                    renderDraft()
                }
            }
            "Widescreen 1953" -> {
                label(parent, "Ratio")
                pick("%.2f:1".format(java.util.Locale.US, fx.number("ratio", 2.39f))) {
                    val ratios = listOf(2.39f, 2.35f, 2f, 1.85f)
                    a.choose("Ratio", listOf("2.39:1", "2.35:1", "2:1", "1.85:1")) {
                        fx.values["ratio"] = ratios[it].toString()
                        videoParameters(parent, fx, chain)
                        renderDraft()
                    }
                }
                label(parent, "Bars")
                pick(fx.values["bars"] ?: "Black") {
                    a.choose("Bars", listOf("Black", "White")) {
                        fx.values["bars"] = if (it == 0) "Black" else "White"
                        videoParameters(parent, fx, chain)
                        renderDraft()
                    }
                }
                slider(
                    parent,
                    "Flares",
                    fx.number("flares", .4f),
                    0f,
                    1f,
                    format = { "%.1f".format(java.util.Locale.getDefault(), it) },
                    color = orange,
                ) {
                    fx.values["flares"] = it.toString()
                    renderDraft()
                }
                label(parent, "Flare color")
                button(parent, (fx.values["flareColor"] ?: "#4ddfff") + "  ⌃", orange) {
                    val names = listOf("Cyan", "Blue", "Warm", "Red", "White")
                    val colors = listOf("#4ddfff", "#5389ff", "#ffd69a", "#ff5656", "#ffffff")
                    a.choose("Flare color", names) {
                        fx.values["flareColor"] = colors[it]
                        videoParameters(parent, fx, chain)
                        renderDraft()
                    }
                }
            }
            "RGB Offset" -> {
                slider(
                    parent,
                    "Offset",
                    fx.number("distance", .025f),
                    0f,
                    .15f,
                    format = { "%.1f%%".format(java.util.Locale.US, it * 100) },
                    color = orange,
                ) {
                    fx.values["distance"] = it.toString()
                    renderDraft()
                }
                slider(parent, "Angle", fx.number("angle", 90f), -180f, 180f, color = orange) {
                    fx.values["angle"] = it.toString()
                    renderDraft()
                }
            }
            "Color Isolation" -> {
                val mode = fx.values["mode"] ?: "Color"
                choices(parent, listOf("Color", "Person"), mode, orange) {
                    fx.values["mode"] = if (it == 1) "Person" else "Color"
                    videoParameters(parent, fx, chain)
                    renderDraft()
                }
                if (mode == "Color") {
                    slider(
                        parent,
                        "Hue",
                        fx.number("hue"),
                        0f,
                        360f,
                        format = { "%.0f°".format(it) },
                        color = orange,
                    ) {
                        fx.values["hue"] = it.toString()
                        renderDraft()
                    }
                    slider(
                        parent,
                        "Tolerance",
                        fx.number("tolerance", 30f),
                        0f,
                        180f,
                        format = { "%.0f°".format(it) },
                        color = orange,
                    ) {
                        fx.values["tolerance"] = it.toString()
                        renderDraft()
                    }
                }
                slider(parent, "Softness", fx.number("softness", .15f), 0f, 1f, color = orange) {
                    fx.values["softness"] = it.toString()
                    renderDraft()
                }
            }
            "Night fix" -> {
                choices(
                    parent,
                    listOf("Fast cleanup", "Motion aligned"),
                    if (fx.number("motion") == 1f) "Motion aligned" else "Fast cleanup",
                    orange,
                ) {
                    fx.values["motion"] = it.toString()
                    renderDraft()
                }
                slider(parent, "Cleanup", fx.number("cleanup", .5f), 0f, 1f, color = orange) {
                    fx.values["cleanup"] = it.toString()
                    renderDraft()
                }
                hint(
                    parent,
                    "Lift dark footage and clean noise. Motion-aligned restoration is available in the editor.",
                )
            }
            "Color Correction" -> {
                fun colorParameter(
                    name: String,
                    key: String,
                    initial: Float,
                    low: Float,
                    high: Float,
                ) {
                    slider(
                        parent,
                        name,
                        fx.number(key, initial),
                        low,
                        high,
                        format = {
                            if (key == "exposure") "%+.1f EV".format(it) else "%.2f".format(it)
                        },
                        color = orange,
                    ) {
                        fx.values[key] = it.toString()
                        renderDraft()
                    }
                }
                colorParameter("Exposure", "exposure", 0f, -3f, 3f)
                colorParameter("Contrast", "contrast", 1f, 0f, 2f)
                colorParameter("Saturation", "saturation", 1f, 0f, 2f)
                colorParameter("Temperature", "temperature", 0f, -1f, 1f)
                colorParameter("Tint", "tint", 0f, -1f, 1f)
                button(parent, "Reset color") {
                    fx.values.clear()
                    videoParameters(parent, fx, chain)
                    renderDraft()
                }
            }
            "Quantization" -> {
                space(parent, 8f)
                val heading = row()
                heading.addView(text("Colors", 10f), LinearLayout.LayoutParams(0, -1, 1f))
                val count = text(fx.number("colors", 5f).toInt().toString(), 9f, MUTED)
                count.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                heading.addView(count, LinearLayout.LayoutParams(px(30f), -1))
                add(parent, heading, 17f)
                val buttons = mutableListOf<TextView>()
                val colors = listOf(2, 3, 4, 5, 6, 8, 12, 16)
                fun select(value: Int) {
                    count.text = value.toString()
                    buttons.forEachIndexed { i, button ->
                        button.background =
                            shape(
                                if (colors[i] == value) Color.rgb(58, 58, 60)
                                else Color.rgb(30, 30, 30),
                                5f,
                            )
                    }
                }
                for (start in colors.indices step 4) {
                    val line = row()
                    for (i in start until start + 4) {
                        val button = text(colors[i].toString(), 11f, Color.rgb(197, 137, 78))
                        button.gravity = Gravity.CENTER
                        buttons.add(button)
                        line.addView(
                            button,
                            LinearLayout.LayoutParams(0, px(32f), 1f).apply { rightMargin = px(5f) },
                        )
                        button.setOnClickListener {
                            fx.values["colors"] = colors[i].toString()
                            select(colors[i])
                            renderDraft()
                        }
                    }
                    add(parent, line, 38f)
                }
                select(fx.number("colors", 5f).toInt())
                toggle(parent, "B&W", fx.number("bw") == 1f) {
                    fx.values["bw"] = if (it) "1" else "0"
                    renderDraft()
                }
                label(parent, "Palette")
                val palettes = listOf("From scene", "Pop 1", "Pop 2", "Pop 3")
                val palette =
                    text(
                        palettes[fx.number("palette").toInt().coerceIn(0, 3)] +
                            "                       ⌃",
                        10f,
                        orange,
                    )
                palette.background = shape(Color.rgb(28, 28, 30), 5f)
                palette.setPadding(px(8f), 0, px(8f), 0)
                palette.setOnClickListener {
                    a.choose("Palette", palettes, fx.number("palette").toInt(), palette) {
                        fx.values["palette"] = it.toString()
                        palette.text = palettes[it] + "                       ⌃"
                        renderDraft()
                    }
                }
                add(parent, palette, 32f)
                space(parent, 7f)
                slider(
                    parent,
                    "Smoothing",
                    fx.number("smoothing", 0.5f),
                    0f,
                    1f,
                    format = { "%.1f".format(it) },
                    color = orange,
                ) {
                    fx.values["smoothing"] = it.toString()
                    renderDraft()
                }
                toggle(parent, "Dithering", fx.number("dither") == 1f) {
                    fx.values["dither"] = if (it) "1" else "0"
                    renderDraft()
                }
            }
            "Zoom" ->
                slider(
                    parent,
                    "Zoom",
                    fx.number("zoom", 1f),
                    1f,
                    5f,
                    format = { "%.1f×".format(it) },
                ) {
                    fx.values["zoom"] = it.toString()
                    renderDraft()
                }
            "Rotation" ->
                slider(
                    parent,
                    "Rotation",
                    fx.number("rotation"),
                    -180f,
                    180f,
                    format = { "%.0f°".format(it) },
                ) {
                    fx.values["rotation"] = it.toString()
                    renderDraft()
                }
            "Strobe" ->
                slider(
                    parent,
                    "Frequency",
                    fx.number("frequency", 8f),
                    1f,
                    30f,
                    format = { "%.0f Hz".format(it) },
                ) {
                    fx.values["frequency"] = it.toString()
                    renderDraft()
                }
            "Undercranking" ->
                slider(
                    parent,
                    "Playback rate",
                    fx.number("rate", 1.25f),
                    1f,
                    2f,
                    format = { "%.2f×".format(java.util.Locale.US, it) },
                    color = orange,
                ) {
                    fx.values["rate"] = it.toString()
                    renderDraft()
                }
            "Exposure",
            "Warmth" ->
                slider(
                    parent,
                    "Amount",
                    fx.number("value"),
                    -1f,
                    1f,
                    format = { "%+.2f".format(it) },
                ) {
                    fx.values["value"] = it.toString()
                    renderDraft()
                }
        }
        button(parent, "Remove effect") {
            chain.remove(fx)
            appliedVideo(parent, chain)
            renderDraft()
        }
    }

    private fun applyChain(chain: List<Effect>, audio: Boolean) {
        val kinds = if (audio) Effects.audio else Effects.video
        editor.replaceChain(chain, kinds)
    }

    fun audio(existing: Effect? = null, initial: List<Effect>? = null) {
        val chain =
            (initial
                    ?: editor
                        .targets()
                        .flatMap { clip ->
                            clip.effects
                                .filter { it.kind in Effects.audio }
                                .mapIndexed { index, effect -> index to effect }
                        }
                        .distinctBy { (index, effect) ->
                            Triple(index, effect.kind, effect.values.toMap())
                        }
                        .map { it.second })
                .map { it.copyEffect() }
                .toMutableList()
        val body =
            setup(
                "Enhance audio",
                {
                    applyChain(chain, true)
                    close()
                },
            )
        pendingEffects = chain
        draft = null
        val controls = contentWithPreview(body)
        var selectedFilter =
            existing?.let { e -> chain.firstOrNull { it.id == e.id } } ?: chain.lastOrNull()
        fun rack(selected: Effect? = selectedFilter) {
            selectedFilter = selected?.takeIf { it in chain } ?: chain.lastOrNull()
            controls.removeAllViews()
            for (name in chain.map { it.kind }.filter { it in AudioModels.neural }.distinct()) if (
                !AudioModels.ready(name)
            )
                button(controls, "Prepare $name") {
                    a.prepareAudioModel(name) { audio(selectedFilter, chain) }
                }
            val targets = editor.targets().filter { it.audio }
            val scope = editor.range
            val from = scope?.start ?: targets.minOfOrNull { it.start } ?: 0.0
            val to = scope?.endInclusive ?: targets.maxOfOrNull { it.end } ?: 0.0
            label(
                controls,
                targets.mapIndexed { i, _ -> "♪ ${i+1}" }.joinToString(" + ") +
                    " · ${timeLabel(from)}–${timeLabel(to)}",
                size = 8f,
            )
            add(
                controls,
                text("+  Add filter", 10f, RED).apply {
                    contentDescription = "Add filter"
                    setOnClickListener {
                        val available =
                            Effects.audio.filter {
                                it !in listOf("AI voice isolation", "Studio clarity")
                            }
                        a.choose("Add filter", available) { i ->
                            val fx = Effect(kind = available[i])
                            if (fx.kind in AudioModels.neural && !AudioModels.ready(fx.kind)) {
                                a.prepareAudioModel(fx.kind) { success ->
                                    audio(
                                        if (success) fx else selectedFilter,
                                        if (success) chain + fx else chain,
                                    )
                                }
                            } else {
                                chain.add(fx)
                                rack(fx)
                                renderDraft()
                            }
                        }
                    }
                },
                36f,
            )
            val selected = selectedFilter
            if (selected == null) {
                label(controls, "Build your sound")
                hint(
                    controls,
                    "Add a filter above. Combine noise reduction, restoration, speech detail and EQ in any order.",
                )
                return
            }
            space(controls, 10f)
            val rackRow = row().apply { gravity = Gravity.TOP }
            val rackParent = column()
            val detail =
                column().apply {
                    background =
                        shape(Color.rgb(21, 25, 30), 14f).apply {
                            setStroke(px(.7f), Color.rgb(36, 47, 61))
                        }
                    setPadding(px(8f), px(4f), px(8f), px(8f))
                }
            if (landscape) {
                rackRow.addView(rackParent, LinearLayout.LayoutParams(px(112f), -2))
                rackRow.addView(
                    detail,
                    LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(5f) },
                )
                add(controls, rackRow)
            } else {
                add(controls, rackParent)
                add(controls, detail)
            }
            val scroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
            val columns = row()
            val views = mutableMapOf<String, FilterColumn>()
            for (fx in chain) {
                val v =
                    FilterColumn(a, fx, fx.id == selected.id) {
                        rack(fx)
                        renderDraft()
                    }
                views[fx.id] = v
                columns.addView(
                    v,
                    LinearLayout.LayoutParams(px(70f), px(142f)).apply { rightMargin = px(6f) },
                )
            }
            scroll.addView(columns)
            add(rackParent, scroll, 146f)
            val selectedIndex = chain.indexOf(selected)
            scroll.post { scroll.smoothScrollTo(px(max(0, selectedIndex * 76 - 45).toFloat()), 0) }
            label(detail, "●  ${selected.kind}", size = 9f)
            val actions = row()
            fun action(label: String, enabled: Boolean = true, block: () -> Unit) {
                val item =
                    text(label, 9f, if (enabled) RED else MUTED).apply {
                        gravity = Gravity.CENTER
                        isEnabled = enabled
                        setOnClickListener { block() }
                    }
                actions.addView(item, LinearLayout.LayoutParams(0, px(30f), 1f))
            }
            action("←", selectedIndex > 0) {
                chain.removeAt(selectedIndex)
                chain.add(selectedIndex - 1, selected)
                rack(selected)
                renderDraft()
            }
            action("→", selectedIndex < chain.lastIndex) {
                chain.removeAt(selectedIndex)
                chain.add(selectedIndex + 1, selected)
                rack(selected)
                renderDraft()
            }
            action("Reset") {
                selected.values.clear()
                rack(selected)
                renderDraft()
            }
            action("⌫") {
                chain.remove(selected)
                rack(null)
                renderDraft()
            }
            add(detail, actions, 30f)
            if (selected.kind in listOf("Voice EQ", "Low-cut filter")) {
                val eq = selected.kind == "Voice EQ"
                slider(
                    detail,
                    "Frequency",
                    selected.number("frequency", if (eq) 1800f else 80f),
                    if (eq) 80f else 20f,
                    if (eq) 10000f else 500f,
                    format = {
                        if (it < 1000) "%.0f Hz".format(it) else "%.1f kHz".format(it / 1000)
                    },
                    color = if (eq) Color.rgb(70, 150, 230) else TEAL,
                    logarithmic = true,
                ) {
                    selected.values["frequency"] = it.toString()
                    views[selected.id]?.invalidate()
                    renderDraft()
                }
            } else {
                slider(detail, "Strength", selected.number("strength", 1f), 0f, 1f, color = TEAL) {
                    selected.values["strength"] = it.toString()
                    views[selected.id]?.invalidate()
                    renderDraft()
                }
            }
        }
        reflowControls = { rack() }
        rack()
        renderDraft()
    }

    fun transition(value: Transition? = null) {
        val at =
            value?.at
                ?: editor.project.clips
                    .flatMap { listOf(it.start, it.end) }
                    .minByOrNull { abs(it - editor.cursor) }
                ?: 0.0
        val current =
            editor.project.transitions.firstOrNull {
                it.id == value?.id || abs(it.cut - at) <= max(.05, it.duration / 2) + .001
            }
        val t = value ?: current?.copy() ?: Transition(at = at)
        val left =
            editor.project.clips.firstOrNull { it.id == t.leftId }
                ?: editor.project.clips.firstOrNull { it.video && abs(it.end - at) < .02 }
        val right =
            editor.project.clips.firstOrNull { it.id == t.rightId }
                ?: editor.project.clips.firstOrNull { it.video && abs(it.start - at) < .02 }
        val maximum = max(.001, editor.transitionMaximum(left, right, current?.id))
        val minimum = min(.05, maximum)
        t.duration = t.duration.coerceIn(minimum, maximum)
        val body =
            setup(
                "Transition",
                {
                    editor.putTransition(t)
                    close()
                },
            )
        draftTransition = t
        pendingEffects.clear()
        draft = null
        val controls = contentWithPreview(body)
        fun parameters() {
            controls.removeAllViews()
            val color = Effects.color("Transition")
            val style = row()
            style.addView(Glyph(a, t.kind, color), LinearLayout.LayoutParams(px(17f), px(20f)))
            style.addView(
                text("  ${t.kind}", 10f, color),
                LinearLayout.LayoutParams(0, px(40f), 1f),
            )
            style.addView(text("⌃", 10f, color), LinearLayout.LayoutParams(px(15f), px(40f)))
            style.setOnClickListener { transitionStyles(t) }
            add(controls, style, 40f)
            val length = text("", 6f, MUTED)
            fun updateLength() {
                val delta = t.duration - (current?.overlap ?: 0.0)
                val change =
                    if (abs(delta) < .005) "Project length unchanged"
                    else
                        "Project %.2f s ${if (delta > 0) "shorter" else "longer"}"
                            .format(java.util.Locale.US, abs(delta))
                length.text =
                    "%.2f s total · %s".format(java.util.Locale.US, t.duration + t.hold, change)
            }
            slider(
                controls,
                "Blend duration",
                t.duration.toFloat(),
                minimum.toFloat(),
                maximum.toFloat(),
                format = { "%.2f s".format(it) },
                color = Effects.color("Transition"),
            ) {
                t.duration = it.toDouble()
                updateLength()
                renderDraft()
            }
            val presets = row()
            for ((index, duration) in listOf(.5, 1.0, 2.0, 3.0, 5.0).withIndex()) {
                val label = listOf("0.5 s", "1 s", "2 s", "3 s", "5 s")[index]
                val enabled = duration <= maximum
                val chip =
                    text(label, 8f, if (enabled) color else MUTED).apply {
                        gravity = Gravity.CENTER
                        background =
                            shape(
                                if (enabled) Color.rgb(55, 35, 19) else Color.rgb(37, 37, 39),
                                15f,
                            )
                        isEnabled = enabled
                    }
                chip.setOnClickListener {
                    t.duration = duration
                    parameters()
                    renderDraft()
                }
                presets.addView(
                    chip,
                    LinearLayout.LayoutParams(0, px(25f), 1f).apply { rightMargin = px(5f) },
                )
            }
            add(controls, presets, 30f)
            updateLength()
            add(controls, length, 20f)
            if (t.kind == "Wipe") {
                choices(
                    controls,
                    listOf("Soft edge", "Hard edge"),
                    if (t.soft) "Soft edge" else "Hard edge",
                    Color.WHITE,
                ) {
                    t.soft = it == 0
                    renderDraft()
                }
                slider(
                    controls,
                    "Angle",
                    t.angle,
                    -180f,
                    180f,
                    format = { "%.0f°".format(it) },
                    color = color,
                ) {
                    t.angle = it
                    renderDraft()
                }
            }
            if (t.kind == "Hitchcock")
                choices(
                    controls,
                    listOf("Shadows first", "Highlights first"),
                    if (t.amount >= 0.5f) "Shadows first" else "Highlights first",
                    Effects.color("Transition"),
                ) {
                    t.amount = if (it == 0) 1f else 0f
                    renderDraft()
                }
            if (t.kind == "Defocus")
                slider(
                    controls,
                    "Optical blur",
                    t.amount,
                    0f,
                    1f,
                    color = Effects.color("Transition"),
                ) {
                    t.amount = it
                    renderDraft()
                }
            slider(
                controls,
                "Hold at halfway",
                t.hold.toFloat(),
                0f,
                3f,
                format = { "%.2f s".format(it) },
                color = Effects.color("Transition"),
            ) {
                t.hold = it.toDouble()
                updateLength()
                renderDraft()
            }
            if (current != null)
                button(controls, "Remove transition", Effects.color("Transition")) {
                    editor.removeTransition(current.id)
                    close()
                }
        }
        parameters()
        renderDraft()
    }

    private fun transitionStyles(value: Transition) {
        val body = setup("Transition style", { transition(value) }, tools = true)
        panel?.setBackgroundColor(Color.BLACK)
        val scroll = ScrollView(a)
        val content = column()
        pad(content, if (landscape) 12f else 8f)
        if (landscape) content.setPadding(px(57f), px(5f), px(57f), 0)
        scroll.addView(content)
        add(body, scroll)
        for (pair in Effects.transitions.chunked(2)) {
            val line = row()
            for (kind in pair) {
                val color = Effects.color("Transition")
                val selected = kind == value.kind
                val item =
                    row().apply {
                        gravity = Gravity.CENTER
                        background =
                            shape(if (selected) tint(color) else Color.rgb(18, 18, 19), 6f).apply {
                                if (selected) setStroke(px(1f), color)
                            }
                        setOnClickListener {
                            value.kind = kind
                            transition(value)
                        }
                    }
                item.addView(
                    Glyph(a, kind, Color.WHITE),
                    LinearLayout.LayoutParams(px(17f), px(20f)),
                )
                item.addView(
                    text(kind, 9f).apply { setPadding(px(5f), 0, 0, 0) },
                    LinearLayout.LayoutParams(-2, -1),
                )
                line.addView(
                    item,
                    LinearLayout.LayoutParams(0, px(39f), 1f).apply {
                        setMargins(0, 0, px(5f), px(6f))
                    },
                )
            }
            add(content, line)
        }
    }

    fun volume() {
        val initial = editor.targets().firstOrNull()?.gainDb ?: 0f
        var gain = initial
        var audible = if (initial > -60f) initial else 0f
        var mute: ReferenceToggle? = null
        val body =
            setup(
                "Volume",
                {
                    editor.setGain(gain)
                    close()
                },
            )
        draftGain = gain
        val controls = contentWithPreview(body)
        label(controls, "This audio section")
        val value = text("%+.1f dB".format(java.util.Locale.US, gain), 14f)
        add(controls, value, 30f)
        space(controls, 14f)
        var bar: SeekBar? = null
        fun update(level: Float) {
            gain = level.coerceIn(-60f, 24f)
            if (gain > -60f) audible = gain
            mute?.checked = gain <= -60f
            draftGain = gain
            value.text = "%+.1f dB".format(java.util.Locale.US, gain)
            bar?.progress = ((gain + 60) / 84 * 1000).roundToInt()
            renderDraft()
        }
        bar =
            slider(
                controls,
                "",
                gain,
                -60f,
                24f,
                format = { "%+.1f dB".format(java.util.Locale.US, it) },
                color = Effects.color("Video effects"),
            ) {
                update(it)
            }
        space(controls, 10f)
        val adjustment = row()
        adjustment.addView(text("Adjust by 0.5 dB", 10f), LinearLayout.LayoutParams(0, px(34f), 1f))
        for ((label, delta) in listOf("−" to -0.5f, "+" to 0.5f)) {
            val step =
                text(label, 14f).apply {
                    gravity = Gravity.CENTER
                    background = shape(Color.rgb(31, 31, 33), 14f)
                    contentDescription =
                        if (delta < 0) "Decrease gain by 0.5 dB" else "Increase gain by 0.5 dB"
                }
            step.setOnClickListener { update(gain + delta) }
            adjustment.addView(step, LinearLayout.LayoutParams(px(29f), px(25f)))
        }
        add(controls, adjustment, 34f)
        mute = toggle(controls, "Mute", gain <= -60) { update(if (it) -60f else audible) }
        val reset = text("Reset to 0 dB", 10f, RED)
        reset.setOnClickListener { update(0f) }
        add(controls, reset, 30f)
        renderDraft()
    }

    fun speed() {
        var rate = editor.targets().firstOrNull()?.rate ?: 1.0
        val body =
            setup(
                "Speed",
                {
                    editor.setRate(rate)
                    close()
                },
            )
        val controls = contentWithPreview(body)
        draftRate = rate
        slider(controls, "Speed", rate.toFloat(), 0.1f, 4f, format = { "%.2f×".format(it) }) {
            rate = it.toDouble()
            draftRate = rate
            renderDraft()
        }
        choices(controls, listOf("0.5×", "1×", "1.5×", "2×", "3×"), "${rate}×") {
            rate = listOf(0.5, 1.0, 1.5, 2.0, 3.0)[it]
            draftRate = rate
            renderDraft()
        }
        renderDraft()
    }

    fun move() {
        val targets = editor.targets()
        var at = targets.minOfOrNull { it.start } ?: return
        var track = targets.first().track
        val body =
            setup(
                "Move",
                {
                    editor.moveSelection(at, track)
                    a.editorView.moving = false
                    close()
                },
            )
        val controls = contentWithPreview(body)
        slider(
            controls,
            "Position",
            at.toFloat(),
            0f,
            (editor.project.duration + 10).toFloat(),
            format = { timeLabel(it.toDouble()) },
        ) {
            at = it.toDouble()
        }
        label(controls, "Track")
        choices(controls, listOf("1", "2", "3", "4"), "${track+1}") { track = it }
        hint(controls, "Overlapping clips on separate tracks can be arranged with Placement.")
    }

    fun pauses() {
        var threshold = 0.02f
        var duration = 0.5f
        val body = setup("Pauses", { removePauses(threshold, duration) })
        val controls = contentWithPreview(body)
        label(controls, "Remove silent pauses")
        slider(
            controls,
            "Silence threshold",
            threshold,
            0.001f,
            0.1f,
            format = { "%.0f dB".format(20 * log10(it)) },
        ) {
            threshold = it
        }
        slider(controls, "Minimum pause", duration, 0.2f, 3f, format = { "%.1f s".format(it) }) {
            duration = it
        }
        hint(
            controls,
            "Detect pauses in the selected audio and remove them from linked video and audio.",
        )
    }

    private fun removePauses(threshold: Float, duration: Float) {
        val targets = editor.targets().filter { it.audio }
        if (targets.isEmpty()) {
            close()
            return
        }
        progress("Finding pauses", 0f, null)
        Thread {
                try {
                    val cuts = mutableListOf<ClosedFloatingPointRange<Double>>()
                    val region =
                        editor.range ?: (targets.minOf { it.start }..targets.maxOf { it.end })
                    val project = editor.project.duplicate()
                    AudioMixer(project, a.audioCache).use { mixer ->
                        mixer.prepare()
                        mixer.seek(region.start)
                        var position = region.start
                        var silence: Double? = null
                        while (position < region.endInclusive) {
                            val frames =
                                min(1024, ceil((region.endInclusive - position) * 48000).toInt())
                            if (frames <= 0) break
                            val peak = mixer.read(frames).maxOf { abs(it) }
                            if (peak < threshold) {
                                if (silence == null) silence = position
                            } else if (silence != null) {
                                if (position - silence >= duration) cuts.add(silence..position)
                                silence = null
                            }
                            position += frames / 48000.0
                        }
                        if (silence != null && region.endInclusive - silence >= duration)
                            cuts.add(silence..region.endInclusive)
                    }
                    ui.post {
                        editor.beginBatch()
                        for (r in cuts.sortedByDescending { it.start }) {
                            editor.range = r
                            editor.selected.clear()
                            editor.deleteSelection()
                        }
                        close()
                        editor.endBatch(false)
                        a.message("Removed ${cuts.size} pauses")
                    }
                } catch (e: Exception) {
                    ui.post {
                        close()
                        a.message(e.message ?: "Cannot analyze audio")
                    }
                }
            }
            .start()
    }

    fun captions() {
        val job =
            Captions(
                a.audioCache,
                java.io.File(a.getExternalFilesDir(null), "models/qwen3-asr-0.6b"),
            )
        var language = ""
        val body =
            setup(
                "Auto captions",
                {
                    val snapshot = editor.project.duplicate()
                    progress("Generating captions", 0f) { job.cancelled = true }
                    Thread {
                            try {
                                val captions =
                                    job.generate(snapshot, language) { p ->
                                        ui.post { updateProgress(p) }
                                    }
                                ui.post {
                                    editor.change {
                                        for (caption in captions) for (clip in
                                            editor.project.clips.filter {
                                                it.video &&
                                                    it.start < caption.end &&
                                                    it.end > caption.start
                                            }) {
                                            clip.effects.add(
                                                Effect(
                                                    kind = "Text",
                                                    from = max(0.0, caption.start - clip.start),
                                                    to =
                                                        min(
                                                            clip.duration,
                                                            caption.end - clip.start,
                                                        ),
                                                    values =
                                                        mutableMapOf(
                                                            "text" to caption.text,
                                                            "x" to "0.05",
                                                            "y" to "0.9",
                                                            "size" to "0.045",
                                                            "style" to "Bold",
                                                        ),
                                                )
                                            )
                                        }
                                    }
                                    close()
                                    a.message("Added ${captions.size} captions")
                                }
                            } catch (e: Exception) {
                                ui.post {
                                    close()
                                    a.message(e.message ?: "Cannot generate captions")
                                }
                            }
                        }
                        .start()
                },
            )
        val controls = contentWithPreview(body)
        label(controls, "Automatic subtitles")
        if (job.ready) {
            button(controls, "Language · Auto detect") {
                val languages =
                    listOf(
                        "Auto detect",
                        "English",
                        "Italian",
                        "French",
                        "German",
                        "Spanish",
                        "Japanese",
                        "Chinese",
                        "Korean",
                        "Portuguese",
                        "Russian",
                    )
                a.choose("Language", languages) { language = if (it == 0) "" else languages[it] }
            }
            hint(
                controls,
                "Captions are generated on your phone. You can edit them with the Text tool.",
            )
        } else {
            hint(
                controls,
                "Download Qwen3-ASR 0.6B once (1.9 GB). Transcription then works offline.",
            )
            button(controls, "Download caption model") {
                progress("Downloading caption model", 0f) { job.cancelled = true }
                Thread {
                        try {
                            job.download { p -> ui.post { updateProgress(p) } }
                            ui.post { captions() }
                        } catch (e: Exception) {
                            ui.post {
                                close()
                                a.message(e.message ?: "Download failed")
                            }
                        }
                    }
                    .start()
            }
        }
    }

    fun export() {
        if (editor.project.duration <= 0) {
            a.message("Add a video or record first")
            return
        }
        var size = 1080
        val body =
            setup(
                "Export",
                {
                    close()
                    val p = editor.project
                    val ratio = p.width.toDouble() / p.height
                    var w = if (ratio >= 1) (size * ratio).roundToInt() else size
                    var h = if (ratio >= 1) size else (size / ratio).roundToInt()
                    w = w / 2 * 2
                    h = h / 2 * 2
                    a.export(w, h)
                },
            )
        val controls = contentWithPreview(body)
        label(controls, "Video quality")
        choices(controls, listOf("720p", "1080p", "4K"), "1080p") {
            size = listOf(720, 1080, 2160)[it]
        }
        label(controls, "H.264 · ${editor.project.fps} fps · AAC 48 kHz", MUTED)
        label(controls, "Duration · ${timeLabel(editor.project.duration)}", MUTED)
        hint(controls, "Save the edited video to Movies/YouRec, then play or share it.")
    }

    fun progress(name: String, value: Float, cancel: (() -> Unit)?) {
        val body =
            setup(
                name,
                {
                    cancel?.invoke()
                    close()
                },
                tools = true,
            )
        val content = column()
        pad(content, 20f)
        add(body, content)
        progressText = text("${(value*100).toInt()}%", 12f)
        add(content, progressText!!, 35f)
        progressBar =
            ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = (value * 1000).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(RED)
            }
        add(content, progressBar!!, 8f)
        if (cancel != null)
            button(content, "Cancel") {
                cancel()
                close()
            }
    }

    fun updateProgress(value: Float) {
        progressBar?.progress = (value * 1000).toInt()
        progressText?.text = "${(value*100).toInt()}%"
    }

    fun captureEffects(current: List<Effect>, done: (List<Effect>) -> Unit) {
        val chain = current.map { it.copyEffect() }.toMutableList()
        captureExpanded = false
        val body =
            setup(
                "Video effects",
                {
                    done(chain)
                    close()
                },
                capture = true,
            )
        captureDraft = chain
        captureDone = done
        pendingEffects = chain
        val scroll = ScrollView(a)
        val controls = column()
        pad(controls, 12f)
        scroll.addView(controls)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val clear =
            text("Clear all effects", 11f).apply {
                setPadding(px(12f), 0, px(12f), 0)
                contentDescription = "Clear all effects"
                setOnClickListener {
                    chain.clear()
                    appliedVideo(controls, chain)
                    renderDraft()
                }
            }
        add(body, clear, 42f)
        appliedVideo(controls, chain)
    }

    private inner class Preview(context: Context, private val inset: Float = 0f) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var bounds = RectF()
        private var lastX = 0f
        private var lastY = 0f
        private var placementEdge = 0
        private val scale =
            ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(d: ScaleGestureDetector): Boolean {
                        val fx = draft ?: return false
                        if (fx.kind == "Placement") {
                            if (fx.number("reframe") == 1f)
                                fx.values["contentZoom"] =
                                    (fx.number("contentZoom", 1f) * d.scaleFactor)
                                        .coerceIn(.2f, 8f)
                                        .toString()
                            else {
                                val width = fx.number("w", 1f)
                                val height = fx.number("h", 1f)
                                val next = (width * d.scaleFactor).coerceIn(.05f, 2f)
                                val nextHeight = height * next / width
                                fx.values["x"] = (fx.number("x") - (next - width) / 2).toString()
                                fx.values["y"] =
                                    (fx.number("y") - (nextHeight - height) / 2).toString()
                                fx.values["w"] = next.toString()
                                fx.values["h"] = nextHeight.toString()
                            }
                            renderDraft()
                            return true
                        }
                        val key =
                            if (fx.kind == "Crop" || fx.kind == "Zoom") "zoom"
                            else if (fx.kind == "Placement") "w" else "size"
                        val initial =
                            if (key == "zoom" || key == "w") 1f
                            else if (fx.kind == "Text") 0.07f else 0.3f
                        fx.values[key] =
                            (fx.number(key, initial) * d.scaleFactor)
                                .coerceIn(
                                    if (key == "zoom") 1f else 0.01f,
                                    if (key == "zoom") 5f else 1f,
                                )
                                .toString()
                        if (key == "w") fx.values["h"] = fx.values[key]!!
                        renderDraft()
                        return true
                    }
                },
            )

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawColor(Color.BLACK)
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            val b = bitmap ?: a.editorView.bitmap ?: return
            val ratio = min((width - inset * 2) / b.width, height.toFloat() / b.height)
            bounds =
                RectF(
                    (width - b.width * ratio) / 2,
                    (height - b.height * ratio) / 2,
                    (width + b.width * ratio) / 2,
                    (height + b.height * ratio) / 2,
                )
            val fx = draft
            if (fx?.kind == "Crop" && cropFrame) {
                c.save()
                c.translate(fx.number("x") * bounds.width(), fx.number("y") * bounds.height())
                val radians =
                    Math.toRadians((fx.number("rotation") + fx.number("quarter") * 90).toDouble())
                val cover =
                    max(
                            abs(cos(radians)) +
                                abs(sin(radians)) * bounds.height() / bounds.width(),
                            abs(cos(radians)) + abs(sin(radians)) * bounds.width() / bounds.height(),
                        )
                        .toFloat()
                val zoom = fx.number("zoom", 1f) * cover
                c.scale(zoom, zoom, bounds.centerX(), bounds.centerY())
                c.rotate(
                    fx.number("rotation") + fx.number("quarter") * 90,
                    bounds.centerX(),
                    bounds.centerY(),
                )
                c.drawBitmap(b, null, bounds, paint)
                c.restore()
                paint.color = Color.argb(110, 0, 0, 0)
                c.drawRect(0f, 0f, width.toFloat(), bounds.top, paint)
                c.drawRect(0f, bounds.bottom, width.toFloat(), height.toFloat(), paint)
                c.drawRect(0f, bounds.top, bounds.left, bounds.bottom, paint)
                c.drawRect(bounds.right, bounds.top, width.toFloat(), bounds.bottom, paint)
                paint.color = Color.argb(50, 255, 255, 255)
                paint.strokeWidth = factor * 0.5f
                for (i in 1..2) {
                    val x = bounds.left + bounds.width() * i / 3
                    val y = bounds.top + bounds.height() * i / 3
                    c.drawLine(x, bounds.top, x, bounds.bottom, paint)
                    c.drawLine(bounds.left, y, bounds.right, y, paint)
                }
            } else c.drawBitmap(b, null, bounds, paint)
            if (title == "Video effects") {
                val badge =
                    RectF(
                        bounds.left + factor * 5,
                        bounds.top + factor * 3,
                        bounds.left + factor * 74,
                        bounds.top + factor * 28,
                    )
                paint.color = Color.argb(190, 10, 10, 10)
                c.drawRoundRect(badge, factor * 13, factor * 13, paint)
                paint.color = if (previewEffects) RED else MUTED
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = factor
                c.drawOval(
                    badge.left + factor * 10,
                    badge.centerY() - factor * 3,
                    badge.left + factor * 21,
                    badge.centerY() + factor * 3,
                    paint,
                )
                c.drawCircle(badge.left + factor * 15.5f, badge.centerY(), factor * 1.5f, paint)
                paint.style = Paint.Style.FILL
                paint.textSize = factor * 9
                paint.textAlign = Paint.Align.LEFT
                c.drawText("Effects", badge.left + factor * 28, badge.centerY() + factor * 3, paint)
            }
            if (fx?.kind == "Crop" && cropFrame) {
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = factor
                c.drawRect(bounds, paint)
                paint.style = Paint.Style.FILL
            }
            if (fx?.kind == "Placement") {
                val window = placementWindow(fx)
                paint.color = Color.rgb(222, 194, 40)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = factor
                c.drawRect(window, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                for ((x, y) in
                    listOf(
                        window.left to window.centerY(),
                        window.right to window.centerY(),
                        window.centerX() to window.top,
                        window.centerX() to window.bottom,
                    )) c.drawCircle(x, y, factor * 4.5f, paint)
            }
            a.editorView.preparing?.let { progress ->
                val box =
                    RectF(
                        width / 2f - factor * 70,
                        height / 2f - factor * 22,
                        width / 2f + factor * 70,
                        height / 2f + factor * 22,
                    )
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(220, 75, 80, 80)
                c.drawRoundRect(box, factor * 9, factor * 9, paint)
                paint.color = RED
                c.drawRoundRect(
                    box.left + factor * 12,
                    box.top + factor * 10,
                    box.left + factor * 12 + factor * 116 * progress,
                    box.top + factor * 13,
                    factor * 2,
                    factor * 2,
                    paint,
                )
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = factor * 8
                c.drawText(
                    "Preparing preview · ${(progress*100).toInt()}%",
                    box.centerX(),
                    box.top + factor * 29,
                    paint,
                )
            }
        }

        private fun placementWindow(fx: Effect): RectF {
            val x = bounds.left + fx.number("x") * bounds.width()
            val y = bounds.top + fx.number("y") * bounds.height()
            val width = fx.number("w", 1f) * bounds.width()
            val height = fx.number("h", 1f) * bounds.height()
            return RectF(
                x + fx.number("left") * width,
                y + fx.number("top") * height,
                x + fx.number("right", 1f) * width,
                y + fx.number("bottom", 1f) * height,
            )
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (
                title == "Video effects" &&
                    e.actionMasked == MotionEvent.ACTION_DOWN &&
                    e.x in bounds.left..bounds.left + factor * 80 &&
                    e.y in bounds.top..bounds.top + factor * 32
            ) {
                previewEffects = !previewEffects
                renderDraft()
                invalidate()
                return true
            }
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x
                    lastY = e.y
                    placementEdge = 0
                    if (draft?.kind == "Placement") {
                        val current = placementWindow(draft!!)
                        val handles =
                            listOf(
                                current.left to current.centerY(),
                                current.right to current.centerY(),
                                current.centerX() to current.top,
                                current.centerX() to current.bottom,
                            )
                        placementEdge =
                            handles.indexOfFirst { (x, y) ->
                                hypot(e.x - x, e.y - y) < factor * 14
                            } + 1
                        if (placementEdge == 0) {
                            val id =
                                placements.keys
                                    .sortedByDescending { id ->
                                        placementTracks[id]
                                            ?: editor.project.clips.first { it.id == id }.track
                                    }
                                    .firstOrNull { id ->
                                        placementWindow(placements.getValue(id)).contains(e.x, e.y)
                                    }
                            if (id != null && id != placementId) {
                                placementId = id
                                draft = placements[id]
                                placementEditor?.invoke()
                                renderDraft()
                            }
                        }
                    }
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scale.isInProgress && e.pointerCount == 1) {
                        val fx = draft ?: return true
                        val dx = (e.x - lastX) / max(1f, bounds.width())
                        val dy = (e.y - lastY) / max(1f, bounds.height())
                        if (fx.kind == "Placement" && placementEdge > 0) {
                            val key = listOf("left", "right", "top", "bottom")[placementEdge - 1]
                            val delta =
                                if (placementEdge <= 2) dx / fx.number("w", 1f)
                                else dy / fx.number("h", 1f)
                            val lower =
                                when (placementEdge) {
                                    2 -> fx.number("left") + .05f
                                    4 -> fx.number("top") + .05f
                                    else -> 0f
                                }
                            val upper =
                                when (placementEdge) {
                                    1 -> fx.number("right", 1f) - .05f
                                    3 -> fx.number("bottom", 1f) - .05f
                                    else -> 1f
                                }
                            fx.values[key] =
                                (fx.number(
                                        key,
                                        if (placementEdge == 2 || placementEdge == 4) 1f else 0f,
                                    ) + delta)
                                    .coerceIn(lower, upper)
                                    .toString()
                        } else if (fx.kind == "Placement" && fx.number("reframe") == 1f) {
                            fx.values["panX"] =
                                (fx.number("panX") + dx / fx.number("w", 1f))
                                    .coerceIn(-2f, 2f)
                                    .toString()
                            fx.values["panY"] =
                                (fx.number("panY") + dy / fx.number("h", 1f))
                                    .coerceIn(-2f, 2f)
                                    .toString()
                        } else {
                            fx.values["x"] =
                                (fx.number("x", if (fx.kind == "Text") 0.05f else 0f) + dx)
                                    .coerceIn(-1f, 1f)
                                    .toString()
                            fx.values["y"] =
                                (fx.number("y", if (fx.kind == "Text") 0.15f else 0f) + dy)
                                    .coerceIn(-1f, 1f)
                                    .toString()
                        }
                        renderDraft()
                    }
                    lastX = e.x
                    lastY = e.y
                }
                MotionEvent.ACTION_UP -> {
                    parent.requestDisallowInterceptTouchEvent(false)
                    performClick()
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private inner class FilterColumn(
        context: Context,
        private val fx: Effect,
        private val selected: Boolean,
        private val changed: () -> Unit,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downY = 0f
        private var value = 0f
        private val eq = fx.kind == "Voice EQ"
        private val lowCut = fx.kind == "Low-cut filter"
        private val color = if (eq) Color.rgb(70, 150, 230) else TEAL
        private val key = if (eq) "gain" else if (lowCut) "frequency" else "strength"
        private val fallback = if (eq) 3f else if (lowCut) 80f else 1f
        private val low = if (eq) -18f else if (lowCut) 20f else 0f
        private val high = if (eq) 18f else if (lowCut) 500f else 1f

        init {
            contentDescription = fx.kind
            isClickable = true
        }

        override fun onDraw(c: Canvas) {
            val unit = factor
            val top = px(36f).toFloat()
            val bottom = height - unit * 2
            val middle = width / 2f
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(55, 60, 62)
            paint.strokeWidth = unit
            for (i in -1..1) c.drawLine(
                unit * 3,
                unit * (17 + i * 2),
                unit * 10,
                unit * (17 + i * 2),
                paint,
            )
            paint.color = tint(color)
            c.drawCircle(width - unit * 17, unit * 17, unit * 16, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = unit * 1.2f
            paint.color = if (fx.enabled) color else MUTED
            val x = width - unit * 17
            c.drawArc(x - unit * 5, unit * 12, x + unit * 5, unit * 22, -45f, 270f, false, paint)
            c.drawLine(x, unit * 10, x, unit * 17, paint)
            val rect = RectF(unit, top, width - unit, bottom)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(23, 29, 34)
            c.drawRoundRect(rect, unit * 13, unit * 13, paint)
            c.save()
            val fraction = ((fx.number(key, fallback) - low) / (high - low)).coerceIn(0f, 1f)
            c.clipRect(unit, top + (bottom - top) * (1 - fraction), width - unit, bottom)
            paint.color =
                if (fx.enabled)
                    Color.argb(125, Color.red(color), Color.green(color), Color.blue(color))
                else Color.rgb(45, 45, 48)
            c.drawRoundRect(rect, unit * 13, unit * 13, paint)
            c.restore()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = unit * if (selected) 1.5f else .8f
            paint.color = if (selected) Color.WHITE else color
            c.drawRoundRect(rect, unit * 13, unit * 13, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = unit * 11
            paint.textAlign = Paint.Align.CENTER
            val name =
                when (fx.kind) {
                    "AI voice isolation",
                    "Noise reduction" -> "Denoise"
                    "Studio clarity",
                    "Studio restoration" -> "Studio"
                    "Speech detail" -> "Detail"
                    "Low-cut filter" -> "Low cut"
                    "Voice EQ" -> "EQ"
                    "Compressor" -> "Comp"
                    "Noise gate" -> "Gate"
                    else -> "Limiter"
                }
            c.drawText(name, middle, top + unit * 19, paint)
            paint.textSize = unit * 7
            paint.color = Color.LTGRAY
            val description =
                if (eq) "%.1f kHz".format(fx.number("frequency", 1800f) / 1000)
                else
                    when (fx.kind) {
                        "AI voice isolation" -> "RNNoise"
                        "Noise reduction" -> "DeepFilterNet3"
                        "Studio restoration" -> "VoiceFixer"
                        "Speech detail",
                        "Studio clarity" -> "LavaSR v2"
                        else -> ""
                    }
            c.drawText(description, middle, top + unit * 38, paint)
            paint.textSize = unit * 12
            paint.color = Color.WHITE
            val level =
                if (eq) "%+.1f dB".format(fx.number(key, fallback))
                else if (lowCut) "%.0f Hz".format(fx.number(key, fallback))
                else "%.0f%%".format(fx.number(key, fallback) * 100)
            c.drawText(level, middle, bottom - unit * 12, paint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.y
                    value = fx.number(key, fallback)
                }
                MotionEvent.ACTION_MOVE ->
                    if (downY >= px(36f) && abs(e.y - downY) > px(3f)) {
                        parent.requestDisallowInterceptTouchEvent(true)
                        fx.values[key] =
                            (value + (downY - e.y) / (height - px(36f)) * (high - low))
                                .coerceIn(low, high)
                                .toString()
                        invalidate()
                        renderDraft()
                    }
                MotionEvent.ACTION_UP -> {
                    parent.requestDisallowInterceptTouchEvent(false)
                    if (downY < px(36f) && e.x > width / 2) fx.enabled = !fx.enabled
                    changed()
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
