package org.myrec.app

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
    private var coveredEditor = false
    private var preview: Preview? = null
    private var previewTick: (() -> Unit)? = null
    private var coverPicker: CoverPicker? = null
    private var controlsTick: (() -> Unit)? = null
    private var rgbPads: RGBOffsetPads? = null
    private var videoDraft: VideoEffectsDraft? = null
    private var effectsClip: String? = null
    private var rgbAnimating = false
    private var correctionTab = 0
    private var correctionAdvanced = false
    private var kaleidoscopeFine = false
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
    private var audioDraft: AudioEditsDraft? = null
    private var censorDraft: Project? = null
    private var subtitlesDraft: SubtitlesDraft? = null
    private var pausesDraft: PausesDraft? = null
    private var freezeDraft: Project? = null
    private var previewSpan: ClosedFloatingPointRange<Double>? = null
    private var draftRate: Double? = null
    private var captureDraft: MutableList<Effect>? = null
    private var captureDone: ((List<Effect>) -> Unit)? = null
    private var pendingEffects = mutableListOf<Effect>()
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
    private var panelClosed: (() -> Unit)? = null
    private var cancelPanel: (() -> Unit)? = null
    private var headerDone: TextView? = null
    private var scopeHeader: TextView? = null
    private var gradeActions: LinearLayout? = null
    private var gradeInspector: LutInspector? = null
    val isOpen
        get() = panel != null

    fun setFrame(frame: Bitmap) {
        bitmap = frame
        preview?.invalidate()
        previewTick?.invoke()
        controlsTick?.invoke()
        rgbPads?.refresh()
        val work = videoDraft
        val clip = work?.clip(editor.cursor)?.id
        if (work != null && title == "Video effects") {
            val visible = work.visible(editor.cursor)
            val inspected = draft
            if (inspected != null && inspected.id !in visible) {
                settings?.let { appliedVideo(it, work.chain) }
            } else if (draft == null && clip != effectsClip) {
                effectsClip = clip
                reflowControls?.invoke()
            }
        }
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
        reset: (() -> Unit)? = null,
        help: String? = null,
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
            if (reset != null) { reset(); return@setOnClickListener }
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
        if (help != null) l.setOnLongClickListener {
            android.app.AlertDialog.Builder(a).setTitle(name).setMessage(help).setPositiveButton("Got it", null).show()
            true
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
        draft?.takeIf { StabilizationSettings.modern(it) }?.let { effect ->
            if (editor.project.clips.none { clip -> clip.effects.any { it.id == effect.id && StabilizationSettings.modern(it) &&
                    it.enabled && StabilizationSettings.level(it) != StabilizationLevel.Off } }) a.playback.cancelPreparation()
        }
        coverPicker?.close(); coverPicker = null
        gradeInspector?.close(); gradeInspector = null
        gradeActions = null; scopeHeader = null
        val cleanup = panelClosed
        panelClosed = null
        cancelPanel = null
        headerDone = null
        cleanup?.invoke()
        captureDraft?.let { chain -> captureDone?.invoke(chain.map { it.copyEffect() }) }
        captureSheet = false
        panel?.let { a.root.removeView(it) }
        panel = null
        if (coveredEditor && a.editorView.visibility == View.INVISIBLE) a.editorView.visibility = View.VISIBLE
        coveredEditor = false
        preview = null
        previewTick = null
        controlsTick = null
        rgbPads = null
        rgbAnimating = false
        videoDraft = null
        effectsClip = null
        correctionTab = 0
        correctionAdvanced = false
        kaleidoscopeFine = false
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
        audioDraft = null
        censorDraft = null
        subtitlesDraft = null
        pausesDraft = null
        freezeDraft = null
        previewSpan = null
        draftRate = null
        draftShape = null
        captureDraft = null
        captureDone = null
        progressBar = null
        progressText = null
        a.playback.previewProject = null
        a.playback.originalStabilization = false
        if (refresh) a.playback.refresh()
    }

    fun cancel() {
        cancelPanel?.invoke() ?: close()
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
        val plain = !captureSheet && name in listOf("Text", "Crop", "Video effects", "Color LUTs")
        val titles = column()
        val titleView = text(name, 10f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        if (plain) {
            add(titles, titleView, 16f)
            scopeHeader = text(if (editor.range != null) rangeLabel() else "${editor.targets().size} clip · entire effect", 6.5f, MUTED)
                .apply { gravity = Gravity.CENTER }
            add(titles, scopeHeader!!, 11f)
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
            } else cancel()
        }
        val done =
            text(
                    if (tools || captureSheet || name == "Voice recording") "Done" else "Apply",
                    10f,
                    if (captureSheet) Color.WHITE else RED,
                )
                .apply {
                    gravity = Gravity.CENTER
                    if (!plain) background = shape(Color.rgb(37, 37, 39), 15f)
                }
        headerDone = done
        val dlp =
            FrameLayout.LayoutParams(px(55f), px(28f), Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        dlp.rightMargin = px(if (landscape) if (tools) 28f else if (plain) 48f else 28f else 10f)
        header.addView(done, dlp)
        done.setOnClickListener {
            if (apply == null) close()
            else {
                apply()
            }
        }
        add(
            content,
            header,
            if (landscape)
                if (tools || name == "Transition") 64f
                else if (name == "Enhance audio") 60f else 32f
            else if (name in listOf("Text", "Color LUTs", "Video effects") && !captureSheet) 32f else 40f,
        )
        return content
    }

    fun relayout() {
        if (panel == null) return
        if (title == "Select cover") {
            panel?.let { a.root.removeView(it) }
            coverPicker?.attach(createPanel(title,null,false),InspectorUi(a,factor))
            return
        }
        if (title == "Voice recording") {
            val oldBody = body ?: return
            val scroll = oldBody.getChildAt(oldBody.childCount - 1)
            oldBody.removeView(scroll)
            panel?.let { a.root.removeView(it) }
            add(createPanel(title, applyPanel, toolsPanel), scroll, -1f)
            reflowControls?.invoke()
            return
        }
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
        if (horizontal && title in listOf("Text", "Video effects", "Transition", "Color LUTs")) {
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
                "Text", "Color LUTs" -> 320f / 550f
                "Transition" -> 320f / 550f
                else -> 0.64f
            }
        val videoHeight =
            if (horizontal) 0
            else
                px(
                    when (title) {
                        "Text" -> 300f
                        "Color LUTs" -> 230f
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
                        videoDraft?.endEndpoint()
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
        if (title in listOf("Text", "Color LUTs")) {
            play.text = "▶"
            if (title == "Color LUTs") {
                val toolbar = row().apply { setPadding(px(10f), 0, px(10f), 0) }
                toolbar.addView(play, LinearLayout.LayoutParams(px(28f), px(34f)))
                gradeActions = row().also { toolbar.addView(it, LinearLayout.LayoutParams(0, px(34f), 1f)) }
                add(left, toolbar, 40f)
            } else r.addView(play, LinearLayout.LayoutParams(px(28f), px(32f)))
            time.text = timeLabel(max(0.0, editor.cursor - region.start)); time.setTextColor(Color.WHITE)
            time.setTextSize(TypedValue.COMPLEX_UNIT_PX, 8f * factor)
            r.setPadding(px(10f), 0, px(10f), 0)
            r.addView(time, LinearLayout.LayoutParams(px(40f), px(32f)))
            r.addView(seek, LinearLayout.LayoutParams(0, px(32f), 1f))
            endTime = text(timeLabel(length), 8f, MUTED).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }
            r.addView(endTime, LinearLayout.LayoutParams(px(40f), px(32f)))
            add(left, r, 34f)
        } else if (horizontal && title != "Volume" && title != "Enhance audio") {
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
                        preview.resetInspection()
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
                    if (title in listOf("Text", "Color LUTs", "Video effects") || horizontal && title != "Enhance audio") "Ⅱ"
                    else "Ⅱ Pause preview"
                } else if (title in listOf("Text", "Color LUTs", "Video effects") || horizontal && title != "Enhance audio") "▶"
                else "▶ Play preview"
            play.contentDescription = if (a.playback.playing) "Pause preview" else "Play preview"
            val current = previewRegion()
            val duration = max(0.001, current.endInclusive - current.start)
            val elapsed = (editor.cursor - current.start).coerceIn(0.0, duration)
            endTime?.text = timeLabel(duration)
            time.text =
                if (title in listOf("Text", "Color LUTs", "Crop")) timeLabel(elapsed)
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
                        try { audioDraft?.let { work -> work.gain(0f); work.commit(editor) }; close() }
                        catch (e: IllegalStateException) { a.message(e.message ?: "Unable to reset volume") }
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
        val duration = a.playback.previewProject?.duration ?: editor.project.duration
        previewSpan?.let { return it.start.coerceIn(0.0, duration)..it.endInclusive.coerceIn(0.0, duration) }
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
            return it.start.coerceAtMost(duration)..it.endInclusive.coerceAtMost(duration)
        }
        val targets = editor.targets()
        val first = (targets.minOfOrNull { it.start } ?: 0.0).coerceAtMost(duration)
        val last = (targets.maxOfOrNull { it.end } ?: duration).coerceAtMost(duration)
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

    fun originals(project: Project) {
        a.playback.pause()
        val content = setup("Original recordings", tools = true)
        val scroll = ScrollView(a)
        val list = column()
        pad(list, 16f)
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        try {
            val sources = a.store.originals(project)
            if (sources.isEmpty()) hint(list, "No original recordings in this project.")
            val available = sources.map { java.io.File(it.path) }.filter { it.isFile }
            if (available.size > 1) button(list,"Save all originals to Files") { a.saveOriginals(available) }
            if (available.isNotEmpty()) button(list,"Create recovered project") { a.recoverOriginals(available,"${project.name.take(95)} · Recovered") }
            for (source in sources) {
                val file = java.io.File(source.path)
                label(list, file.name)
                hint(list, "${timeLabel(source.sourceOut)} · ${if (file.isFile) "Original source" else "Missing original"}")
                if (file.isFile) {
                    button(list, "Review original") { a.reviewOriginal(source,project) }
                    button(list, "Save original to Files") {
                        a.saveDocument(file, if (source.video) "video/mp4" else "audio/*")
                    }
                } else button(list, "Relink original") { a.relinkOriginal(project, source.path) }
                if (file.isFile) button(list, "Insert at playhead") {
                    a.openProject(project)
                    if (editor.project.id != project.id) return@button
                    editor.insert(source.duplicate(true))
                    close()
                }
                space(list, 14f)
            }
            val saved = a.store.open(project.id)
            val missingImages = ProjectAssets.paths(saved).filter { path ->
                sources.none { it.path == path } && !java.io.File(path).isFile
            }
            for (path in missingImages) {
                label(list, java.io.File(path).name)
                button(list, "Relink missing image") { a.relinkOriginal(project, path) }
            }
            if (saved.project.clips.any { !java.io.File(it.path).isFile })
                button(list, "Remove unavailable takes") {
                    a.openProject(project)
                    if (editor.project.id != project.id) return@button
                    val ids = editor.project.clips.filter { !java.io.File(it.path).isFile }.map { it.id }.toSet()
                    if (ids.isNotEmpty()) {
                        editor.clearSelection()
                        editor.selected.addAll(ids)
                        editor.deleteSelection(false)
                    }
                    close()
                }
        } catch (e: Exception) { hint(list, e.message ?: "Cannot open originals") }
    }

    fun recoveryOriginals(project: String? = null) {
        a.playback.pause()
        val content = setup("Original files",tools = true)
        val scroll = ScrollView(a); val list = column(); pad(list,16f); scroll.addView(list)
        content.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        hint(list,"Browse and save files independently of editing metadata. No timeline is reconstructed or changed.")
        try {
            val files = OriginalMedia.files(a.store,project)
            if (files.isEmpty()) hint(list,"No original media files found.")
            if (files.size > 1) button(list,"Save all originals to Files") { a.saveOriginals(files) }
            if (files.isNotEmpty()) button(list,"Create recovered project") { a.recoverOriginals(files) }
            for (file in files) {
                label(list,file.name)
                hint(list,android.text.format.Formatter.formatShortFileSize(a,file.length()))
                button(list,"Review original") { a.reviewOriginalFile(file,list) }
                button(list,"Save original to Files") { a.saveDocument(file,OriginalMedia.type(file)) }
                space(list,14f)
            }
        } catch (failure: Exception) { hint(list,failure.message ?: "Cannot list original files") }
    }

    fun projects(deleted: Boolean = false) {
        close(false)
        coveredEditor = a.editorView.visibility == View.VISIBLE
        if (coveredEditor) a.editorView.visibility = View.INVISIBLE
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
            android.text.SpannableString("myrec").apply {
                setSpan(
                    android.text.style.ForegroundColorSpan(RED),
                    2,
                    5,
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
        val projects = if (deleted) a.store.recentlyDeleted() else a.store.list()
        if (a.store.problems.isNotEmpty()) {
            hint(content, a.store.problems.values.distinct().joinToString("\n"))
            button(content, "Retry opening project", RED) { a.retryProjects() }
            button(content,"Browse original files",RED) { recoveryOriginals() }
        }
        val thumbnails = mutableListOf<() -> Unit>()
        for (project in projects) {
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
                            try {
                                if (!a.saveProject()) return@setPositiveButton
                                a.store.rename(project, value)
                                if (editor.project.id == project.id) editor.project.name = value
                                projects()
                            } catch (e: Exception) { a.message(e.message ?: "Cannot rename project") }
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
                Glyph(a, "More", RED).apply { contentDescription = "Options for ${project.name}"; tag = "project.options:${project.id}" }
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
                a.choose(project.name, listOf("Rename", "Duplicate", "Delete", "Original recordings", "Export video", "Zip project")) { action ->
                    when (action) {
                        0 -> rename()
                        1 -> a.duplicateProject(project)
                        3 -> originals(project)
                        4 -> { a.openProject(project); if (editor.project.id == project.id) export() }
                        5 -> a.exportProject(project)
                        2 ->
                            a.choose("Delete project?", listOf("Delete project", "Cancel")) {
                                if (it == 0) {
                                    a.store.delete(project)
                                    if (editor.project.id == project.id) {
                                        val saved = a.store.currentSession()
                                        editor.load(saved.project, saved)
                                    }
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
        toolRow(content, "Select cover", "Image", TEAL) { cover() }
        toolRow(content, "Record here", "Camera", RED) { close(); a.recordHere() }
        toolRow(content, "Voiceover here", "Volume", TEAL) { close(); a.voiceover() }
        toolRow(content, "Censor audio", "Volume", TEAL) { censor() }
        toolRow(content, "Transition", "Transition", Effects.color("Transition")) { transition() }
        hint(content, "Select a clip boundary, the start, or the end.")
        if (editor.clipboard.isNotEmpty())
            button(content, "Paste", RED) {
                close()
                a.editorView.action("paste")
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
                "Freeze Frame",
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
                        "Color LUTs" -> " · ${effect.values["name"] ?: effect.values["lut"] ?: "Natural"}"
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

    fun voiceover(work: VoiceDraft) {
        val content = setup("Voice recording", { work.commit() })
        panelClosed = { work.close() }
        cancelPanel = { work.discard(true) }
        val scroll = ScrollView(a).apply { isFillViewport = true }
        add(content, scroll, -1f)
        val frame = FrameLayout(a)
        scroll.addView(frame)
        fun build() {
            frame.removeAllViews()
            val controls = column().apply { setPadding(px(16f), px(12f), px(16f), px(20f)) }
            frame.addView(controls, FrameLayout.LayoutParams(min(a.root.width, px(336f)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            val microphone = row().apply {
                gravity = Gravity.CENTER
                contentDescription = "Choose microphone"
                setOnClickListener { work.chooseInput() }
            }
            microphone.addView(Glyph(a, "Microphone", TEAL), LinearLayout.LayoutParams(px(18f), px(18f)))
            val input = text(work.inputName, 10f, Color.WHITE, true).apply { setPadding(px(7f), 0, px(7f), 0) }
            microphone.addView(input)
            microphone.addView(Glyph(a, "Chevron down", MUTED), LinearLayout.LayoutParams(px(10f), px(12f)))
            add(controls, microphone, 44f)
            space(controls, 12f)
            val clock = text(timeLabel(work.clock), 28f).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                fontFeatureSettings = "tnum"
                contentDescription = "Elapsed time"
            }
            add(controls, clock, 44f)
            val meter = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(TEAL)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(40, 40, 43))
            }
            val meterFrame = FrameLayout(a)
            meterFrame.addView(meter, FrameLayout.LayoutParams(px(96f), px(4f), Gravity.CENTER))
            add(controls, meterFrame, 12f)
            space(controls, 16f)
            val transport = row()
            fun transportButton(color: Int, weight: Float, action: () -> Unit): Pair<LinearLayout, TextView> {
                val control = row().apply {
                    gravity = Gravity.CENTER
                    background = shape(color, 7f)
                    setOnClickListener { action() }
                }
                val caption = text("", 11f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
                control.addView(caption)
                transport.addView(control, LinearLayout.LayoutParams(0, px(44f), weight))
                return control to caption
            }
            val (record, recordText) = transportButton(RED, 1f) { work.record() }
            transport.addView(View(a), LinearLayout.LayoutParams(px(10f), 1))
            val (play, playText) = transportButton(Color.rgb(37, 37, 39), .55f) { work.play() }
            add(controls, transport)
            space(controls, 12f)
            val reset = text("Start over", 10f, MUTED).apply {
                gravity = Gravity.CENTER
                contentDescription = "Start over"
                setOnClickListener { work.discard(false) }
            }
            add(controls, reset, 44f)
            work.changed = {
                input.text = work.inputName
                microphone.isEnabled = !work.recording
                microphone.alpha = if (work.recording) .4f else 1f
                clock.text = timeLabel(work.clock)
                meter.progress = (work.level * 1000).toInt()
                meter.alpha = if (work.recording) 1f else 0f
                val recordTitle = if (work.recording) "Stop" else if (work.hasAudio) "Record more" else "Record"
                recordText.text = "${if (work.recording) "■" else "●"}  $recordTitle"
                record.contentDescription = recordTitle
                playText.text = if (work.playing) "■  Stop" else "▶  Play"
                play.contentDescription = if (work.playing) "Stop playback" else "Play"
                play.isEnabled = work.hasAudio && !work.recording
                play.alpha = if (play.isEnabled) 1f else .35f
                reset.visibility = if (work.hasAudio) View.VISIBLE else View.INVISIBLE
                headerDone?.isEnabled = work.hasAudio
                headerDone?.alpha = if (work.hasAudio) 1f else .35f
            }
            work.changed()
        }
        reflowControls = { build() }
        build()
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
            fx.subtitle != null -> reviewSubtitles(SubtitlesDraft(editor))
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
                draftRate?.let { temporary.setRate(it) }
                when (title) {
                    "Video effects" -> {
                        temporary.project = checkNotNull(videoDraft).snapshot(bypass = !previewEffects)
                    }
                    "Volume" -> temporary.project = checkNotNull(audioDraft).snapshot()
                    "Censor audio" -> temporary.project = checkNotNull(censorDraft).duplicate()
                    "Subtitles" -> temporary.project = checkNotNull(subtitlesDraft).project.duplicate()
                    "Pauses" -> temporary.project = checkNotNull(pausesDraft).snapshot()
                    "Freeze Frame" -> temporary.project = checkNotNull(freezeDraft).duplicate()
                    "Enhance audio" -> temporary.replaceChain(effects, Effects.audio)
                    "Placement" -> temporary.applyPlacements(placements, placementTracks)
                    "Color LUTs" -> {
                        temporary.targets().forEach { clip -> clip.effects.removeAll { it.kind == "Color LUTs" } }
                        if (previewEffects) draft?.let { temporary.applyEffect(it) }
                    }
                    "Stabilize" -> {
                        draft?.let { temporary.applyEffect(it,draftReplace) }
                    }
                    else ->
                        effects.forEach {
                            if (title == "Crop" && cropFrame)
                                temporary.targets().forEach { clip ->
                                    clip.effects.removeAll { it.kind == "Crop" }
                                }
                            else temporary.applyEffect(it, draftReplace)
                        }
                }
                if (title in listOf("Text", "Color LUTs")) {
                    val targets = editor.targets()
                    val first = editor.range?.start ?: if (title == "Text" && draftReplace == null && editor.selected.isEmpty()) draftOrigin else targets.minOfOrNull { it.start } ?: 0.0
                    val last = editor.range?.endInclusive ?: targets.maxOfOrNull { it.end } ?: editor.project.duration
                    val from = first + if (title == "Text") draft?.from ?: 0.0 else 0.0
                    val to = if (title == "Text") min(last, first + (draft?.to ?: last)) else last
                    scopeHeader?.text = if (title == "Text") "${if (targets.count { it.video } == 1) "This clip" else "${targets.count { it.video }} clips"} · ${timeLabel(from)}–${timeLabel(to)}"
                        else "Video + audio · ${targets.sumOf { (if (it.video) 1 else 0) + (if (it.audio) 1 else 0) }} items · ${timeLabel(first)}–${timeLabel(last)}"
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
                a.playback.originalStabilization = title == "Stabilize" && !previewEffects
                a.playback.previewProject = p
                a.playback.refresh(p)
            },
            50,
        )
    }

    fun effect(kind: String, existing: Effect? = null) {
        if (kind == "Freeze Frame") { freezeFrame(existing); return }
        val fx = existing?.copyEffect() ?: Effect.create(kind)
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
                TitleStyle.initialize(fx, existing == null)
                val targets = editor.targets().filter { it.video }
                val base = editor.range?.start ?: if (replace == null && editor.selected.isEmpty()) origin else targets.minOfOrNull { it.start } ?: 0.0
                val end = editor.range?.endInclusive ?: targets.maxOfOrNull { it.end } ?: editor.project.duration
                val inspectorUi = InspectorUi(a, factor)
                val inspector = TitleInspector(inspectorUi, fx, base, max(0.0, end - base), { renderDraft() }) { exit ->
                    a.playback.pause()
                    val (entrance, release) = TitleMotion.ends(fx, fx.to - fx.from)
                    editor.cursor = base + fx.from + if (exit) max(0.0, fx.to - fx.from - release - .35) else 0.0
                    renderDraft()
                    ui.postDelayed({ if (draft === fx) a.playback.play(min(base + fx.to, editor.cursor + (if (exit) release else entrance) + .7)) }, 80)
                }
                fun build() {
                    inspectorUi.scale = factor
                    inspector.build(controls)
                    if (existing != null) button(controls, "Remove text") { editor.removeEffect(existing.id); close() }
                }
                reflowControls = { build() }
                build()
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
                    rotation.progress = 500
                    angle.text = "0°"
                    renderDraft()
                }
                val zoom =
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
                            rotation.progress = 500
                            zoom.progress = 0
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
                val inspectorUi = InspectorUi(a, factor)
                val inspector = LutInspector(inspectorUi, fx, { renderDraft() }) { after -> previewEffects = after; renderDraft() }
                gradeInspector = inspector
                fun build() {
                    inspectorUi.scale = factor
                    inspector.build(controls)
                    gradeActions?.let { inspector.transport(it) }
                    if (existing != null) button(controls, "Remove LUT") { editor.removeEffect(existing.id); close() }
                }
                controlsTick = { inspector.tick() }
                reflowControls = { build() }
                build()
            }
            "Stabilize" -> {
                fun build() {
                    controls.removeAllViews()
                    label(controls,"Stabilization level",if (StabilizationSettings.modern(fx)) Color.WHITE else TEAL)
                    if (StabilizationSettings.modern(fx)) {
                        val detail = text(StabilizationSettings.level(fx).detail,8f,MUTED)
                        choices(controls,StabilizationLevel.entries.map { it.name },StabilizationSettings.level(fx).name,RED) {
                            fx.values["level"] = StabilizationLevel.entries[it].name
                            detail.text = StabilizationSettings.level(fx).detail
                            renderDraft()
                        }
                        add(controls,detail,34f)
                        choices(controls,listOf("Original","Stabilized"),if (previewEffects) "Stabilized" else "Original",RED) {
                            previewEffects = it == 1; renderDraft()
                        }
                        val crop = text("Analyzing motion…",8f,Color.WHITE,bold = true).apply { contentDescription = "Stabilization crop" }
                        val warning = text("",7.5f,MUTED)
                        val cachedPlans = mutableMapOf<StabilizationLevel,List<StabilizationPlan>>()
                        add(controls,crop,30f); add(controls,warning,42f)
                        controlsTick = {
                            val project = a.playback.previewProject ?: editor.project
                            val currentPlans = project.clips.mapNotNull { clip -> clip.effects.firstOrNull { it.id == fx.id && StabilizationSettings.modern(it) }
                                ?.let { StabilizationRenderer.status(clip,it) } }
                            val level = StabilizationSettings.level(fx)
                            if (currentPlans.isNotEmpty()) cachedPlans[level] = currentPlans
                            val plans = cachedPlans[level].orEmpty()
                            crop.text = if (StabilizationSettings.level(fx) == StabilizationLevel.Off) "Full frame kept"
                                else if (plans.isEmpty()) if (previewEffects) "Analyzing motion…" else "Original"
                                else if (plans.all { it.zoom < 1.001 }) "Full frame kept"
                                else "Zoomed in ${String.format(java.util.Locale.US,"%.2f",plans.maxOf { it.zoom })}× to hide edges"
                            warning.text = when {
                                plans.any { it.trackingFraction < .65 } -> "Some motion could not be tracked. Those sections keep their original movement."
                                plans.any { it.limited } -> "Smoothing is limited to keep more of your frame."
                                else -> ""
                            }
                        }
                    } else {
                        hint(controls,"This saved edit uses the original stabilization processing.")
                        slider(controls,"Strength",fx.number("strength",.5f),0f,1f,color = TEAL) { fx.values["strength"] = it.toString(); renderDraft() }
                        button(controls,"Use stabilization levels",TEAL) { StabilizationSettings.initialize(fx); build(); renderDraft() }
                    }
                    if (existing != null) button(controls,"Remove stabilization") { editor.removeEffect(existing.id); close() }
                }
                reflowControls = { build() }
                build()
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
        val work = VideoEffectsDraft(editor, Effects.video.toSet())
        val current = work.chain
        if (existing != null && current.none { it.id == existing.id })
            current.add(existing.copyEffect())
        val body =
            setup(
                "Video effects",
                {
                    try { work.commit(editor); close() }
                    catch (e: IllegalStateException) { a.message(e.message ?: "Unable to apply effects") }
                },
            )
        videoDraft = work
        pendingEffects = current
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
        controlsTick = null
        parent.removeAllViews()
        reflowControls = { appliedVideo(parent, chain) }
        parent.setPadding(px(10f), px(if (landscape || captureSheet) 0f else 10f), px(10f), px(10f))
        draft = null
        videoDraft?.flush()
        effectsClip = videoDraft?.clip(editor.cursor)?.id
        val visible = videoDraft?.visible(editor.cursor)
        val active = chain.filter { visible == null || it.id in visible }
        val ordered = EffectOrder.ordered(active)
        val heading = row()
        heading.addView(
            text("Applied effects · ${active.size}", 9f),
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
        for (fx in ordered) {
            videoDraft?.adopt(fx, editor.cursor)
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
        controlsTick = null
        parent.removeAllViews()
        reflowControls = { categories(parent, chain) }
        videoNavigation(parent, null)
        parent.setPadding(px(10f), px(if (landscape) 0f else 10f), px(10f), px(10f))
        draft = null
        label(parent, "Add effects", size = 9f)
        if (chain.isNotEmpty())
            button(parent, "‹ Applied · ${videoDraft?.visible(editor.cursor)?.size ?: chain.size}") { appliedVideo(parent, chain) }
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
        controlsTick = null
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
                    "RGB Offset" -> "Slide the red, green and blue images out of alignment."
                    "Quantization" -> "Reduce colors with a scene or fixed palette."
                    "Day for Night" -> "Turn daylight into a cool, dark night look."
                    "Color Isolation" ->
                        "Keep one color or the person in color; make the rest monochrome."
                    "Noir" -> "Black and white with deep shadows and contrast."
                    "Halation" -> "Soften bright lights with a warm glow."
                    else -> "Tap to add"
                }
            add(description, text(detail, 7.5f, MUTED).apply { setPadding(0, px(3f), 0, 0) })
            item.addView(description, LinearLayout.LayoutParams(0, -2, 1f))
            item.addView(
                Glyph(a, "Add", categoryColor(category)),
                LinearLayout.LayoutParams(px(15f), px(20f)).apply { leftMargin = px(27f) },
            )
            parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8f) })
            if (captureDraft != null && kind in listOf("Night fix", "Dolly Zoom", "Step Printing")) {
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
                    chain.addAll(kinds.map { Effect.create(it) })
                    appliedVideo(parent, chain)
                    renderDraft()
                } else {
                    val fx = Effect.create(kind)
                    chain.add(fx)
                    videoParameters(parent, fx, chain)
                    renderDraft()
                }
            }
        }
    }

    private fun correctionTiming(parent: LinearLayout, fx: Effect, chain: MutableList<Effect>) {
        val work = videoDraft ?: return
        val motion = work.effect(fx.id, editor.cursor)?.motion
        if (motion == null) {
            editor.cursor = work.selectEndpoint(fx, false, editor.cursor)
        }
        parent.removeAllViews()
        reflowControls = { correctionTiming(parent, fx, chain) }
        button(parent, "‹ Color Correction") { videoParameters(parent, fx, chain) }
        val timing = work.effect(fx.id, editor.cursor)?.motion?.timing ?: return
        fun update(next: EffectTiming, rebuild: Boolean = false) {
            a.playback.pause()
            editor.cursor = work.timing(fx, next, editor.cursor)
            renderDraft()
            if (rebuild) correctionTiming(parent, fx, chain)
        }
        add(parent, text("Animation timing", 11f, Color.WHITE, true), 28f)
        button(parent, "Curve · ${timing.curve.title}") {
            a.choose("Curve", EffectCurve.entries.map { it.title }) {
                update(timing.copy(curve = EffectCurve.entries[it]), true)
            }
        }
        if (timing.curve.hasRise) {
            val duration = work.effect(fx.id, editor.cursor)?.motion?.duration?.toFloat() ?: 1f
            slider(parent, "Rise", timing.rise.toFloat().coerceAtMost(duration), 0f, duration,
                format = { "%.2f s".format(java.util.Locale.US, it) }) {
                val current = work.effect(fx.id, editor.cursor)?.motion?.timing ?: timing
                update(current.copy(rise = it.toDouble()))
            }
            if (timing.curve == EffectCurve.RiseHoldReturn) slider(parent, "Release", timing.release.toFloat().coerceAtMost(duration), 0f, duration,
                format = { "%.2f s".format(java.util.Locale.US, it) }) {
                val current = work.effect(fx.id, editor.cursor)?.motion?.timing ?: timing
                update(current.copy(release = it.toDouble()))
            }
            button(parent, "Ramp · ${timing.ramp.title}") {
                a.choose("Ramp", EffectCurve.entries.take(4).map { it.title }) {
                    update(timing.copy(ramp = EffectCurve.entries[it]), true)
                }
            }
        }
        button(parent, "Done") { videoParameters(parent, fx, chain) }
        renderDraft()
    }

    private fun videoParameters(parent: LinearLayout, fx: Effect, chain: MutableList<Effect>) {
        rgbPads = null
        videoDraft?.flush()
        videoDraft?.adopt(fx, editor.cursor)
        fun sourceClip(): Clip? {
            videoDraft?.let { return it.clip(editor.cursor) }
            val targets = editor.targets().filter { it.video }
            return targets.firstOrNull { it.contains(editor.cursor) } ?: targets.firstOrNull()
        }
        fun sourceTime(): Double {
            if (captureDraft != null) return 0.0
            return sourceClip()?.sourceTime(editor.cursor) ?: 0.0
        }
        fun readNumber(key: String, fallback: Float = 0f) =
            videoDraft?.number(fx, key, editor.cursor, fallback) ?: fx.numberAt(key, sourceTime(), fallback)
        fun editNumber(key: String, value: Float, animate: Boolean = rgbAnimating) {
            val work = videoDraft
            if (work != null) work.editNumber(fx, key, value, editor.cursor, animate)
            else fx.editNumber(key, value, sourceTime(), sourceClip()?.sourceIn ?: 0.0, animate)
        }
        val ticks = mutableListOf<() -> Unit>()
        controlsTick = { ticks.forEach { it() } }
        fun bind(bar: SeekBar, key: String, low: Float, high: Float) {
            ticks.add {
                if (!bar.isPressed) bar.progress = ((readNumber(key) - low) / (high - low) * bar.max).roundToInt().coerceIn(0, bar.max)
            }
        }
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
        val back = text("‹ Applied · ${videoDraft?.visible(editor.cursor)?.size ?: chain.size}", 9f, RED)
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
        if (fx.kind == "RGB Offset") {
            val line = row()
            line.addView(heading, LinearLayout.LayoutParams(0, px(28f), 1f))
            line.addView(Glyph(a, "Help", MUTED).apply {
                contentDescription = "About RGB Offset"
                setPadding(px(7f), px(7f), px(7f), px(7f))
                setOnClickListener {
                    android.app.AlertDialog.Builder(a).setTitle("RGB Offset")
                        .setMessage("Drag each cross to shift its color layer. The center is the original registration. The outer ring moves a channel by 12% of the picture’s shorter side. Double-tap a pad to center that channel.\n\nFilm technique\nColor dye-transfer printing aligned three separate matrices to register the color image.")
                        .setPositiveButton("Done", null).show()
                }
            }, LinearLayout.LayoutParams(px(28f), px(28f)))
            add(parent, line, 28f)
        } else add(parent, heading, 21f)
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
            val work = videoDraft
            if (fx.kind == "Color Correction" && work != null) {
                a.choose("Correction animation", listOf("Edit A", "Edit B", "Timing", "Done editing endpoints", "Remove animation")) { option ->
                    a.playback.pause()
                    when (option) {
                        0, 1 -> editor.cursor = work.selectEndpoint(fx, option == 1, editor.cursor)
                        2 -> { correctionTiming(parent, fx, chain); return@choose }
                        3 -> work.endEndpoint()
                        4 -> work.removeMotion(fx, editor.cursor)
                    }
                    videoParameters(parent, fx, chain)
                    renderDraft()
                }
            } else if (fx.parameters.isNotEmpty()) {
                rgbAnimating = !rgbAnimating
                videoParameters(parent, fx, chain)
            } else a.choose("Animate", listOf("Off", "Fade in", "Fade out", "Fade in and out")) {
                fx.values["animate"] = it.toString()
                renderDraft()
            }
        }
        if (fx.parameters.isNotEmpty()) {
            val animated = if (fx.kind == "Color Correction") videoDraft?.effect(fx.id, editor.cursor)?.motion != null else rgbAnimating
            animate.isSelected = animated
            animate.background = shape(if (animated) Color.rgb(78, 47, 22) else Color.rgb(32, 32, 32), 16f)
            if (android.os.Build.VERSION.SDK_INT >= 30) animate.stateDescription = if (animated) "On" else "Off"
            if (captureDraft != null) animate.visibility = View.GONE
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
            rgbPads?.enable(fx.enabled)
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
        if (fx.parameters.isNotEmpty() && rgbAnimating)
            hint(parent, "Animating · changes add keyframes at ${timeLabel(editor.cursor)}")
        if (fx.kind !in setOf("Color Correction", "Step Printing")) {
            slider(parent, "Intensity", readNumber(if (fx.parameters.isEmpty()) "strength" else "intensity", 1f), 0f, 1f, color = orange) {
                if (fx.parameters.isNotEmpty()) editNumber("intensity", it) else fx.setNumber("strength", it)
                renderDraft()
            }
        }
        fun pick(value: String, enabled: Boolean = true, action: () -> Unit): View {
            val line =
                row().apply {
                    background = shape(Color.rgb(29, 29, 31), 7f)
                    setPadding(px(9f), 0, px(9f), 0)
                    isEnabled = enabled
                    alpha = if (enabled) 1f else .4f
                    setOnClickListener { action() }
                }
            line.addView(text(value, 10f, orange), LinearLayout.LayoutParams(0, -1, 1f))
            line.addView(Glyph(a, "Chevrons", orange), LinearLayout.LayoutParams(px(14f), px(20f)))
            parent.addView(
                line,
                LinearLayout.LayoutParams(-1, px(32f)).apply { bottomMargin = px(8f) },
            )
            return line
        }
        if (fx.kind in TimeEffects.kinds) {
            val titles = mapOf("frequency" to "Cycles per second", "timedBurst" to "Limit duration", "burstLength" to "Burst length (s)",
                "step" to "Step frames", "smear" to "Smear", "speed" to "Speed")
            for (p in fx.parameters.filter { it.id != "intensity" }) {
                val title = titles.getValue(p.id)
                when (val type = p.kind) {
                    is ParameterKind.Stepper -> {
                        label(parent, title)
                        choices(parent, type.values.map { if (it % 1 == 0.0) it.toInt().toString() else it.toString() },
                            readNumber(p.id).let { if (it % 1 == 0f) it.toInt().toString() else it.toString() }, orange) {
                            editNumber(p.id, type.values[it].toFloat(), rgbAnimating); renderDraft()
                        }
                    }
                    is ParameterKind.Slider -> {
                        if (p.id == "burstLength" && (fx.parameter("timedBurst")?.value as? EffectValue.Toggle)?.value != true) continue
                        val bar = slider(parent, title, readNumber(p.id), type.min.toFloat(), type.max.toFloat(), color = orange) {
                            editNumber(p.id, ((it / type.step).roundToInt() * type.step).toFloat(), rgbAnimating && p.id != "burstLength")
                            renderDraft()
                        }
                        bind(bar, p.id, type.min.toFloat(), type.max.toFloat())
                    }
                    ParameterKind.Toggle -> toggle(parent, title, (p.value as EffectValue.Toggle).value) { value ->
                        videoDraft?.editValue(fx, p.id, EffectValue.Toggle(value), editor.cursor, false)
                            ?: run { p.value = EffectValue.Toggle(value) }
                        videoParameters(parent, fx, chain); renderDraft()
                    }
                    else -> error("Invalid time-effect control")
                }
            }
            if (fx.kind == "Negative Strobe") {
                hint(parent, "One cycle includes a positive and a negative image. Frequency is limited to half the output frame rate.")
                if (captureDraft != null) button(parent, "Try strobe") {
                    a.tryStrobe(if ((fx.parameter("timedBurst")?.value as? EffectValue.Toggle)?.value == true) fx.number("burstLength", 1f) else 2f)
                }
            } else hint(parent, "Repeat actual source frames and blend motion within each group. Speed applies to the selection.")
        } else if (FilmColor.definition(fx) != null) {
            val definition = FilmColor.definition(fx)!!
            fun current(id: String) = videoDraft?.value(fx, id, editor.cursor) ?: fx.parameter(id)?.at(sourceTime())
            fun edit(id: String, value: EffectValue) {
                val work = videoDraft
                if (work != null) work.editValue(fx, id, value, editor.cursor, rgbAnimating)
                else fx.parameter(id)?.let { p -> require(p.kind.accepts(value)); p.value = value }
                videoParameters(parent, fx, chain)
                renderDraft()
            }
            for (control in definition.controls) when (val kind = control.kind) {
                is ParameterKind.Slider -> {
                    fun reset() {
                        editNumber(control.id, (control.initial as EffectValue.Number).value.toFloat())
                        videoParameters(parent, fx, chain)
                        renderDraft()
                    }
                    val bar = slider(parent, control.title, readNumber(control.id), kind.min.toFloat(), kind.max.toFloat(),
                        color = orange, reset = ::reset) {
                        editNumber(control.id, ((it / kind.step).roundToInt() * kind.step).toFloat().coerceIn(kind.min.toFloat(), kind.max.toFloat()))
                        val dependent = when (control.id) { "vignette" -> "iris"; "flash" -> "flashColor"; "grain" -> "grainSize"; else -> null }
                        if (dependent != null) for (tag in listOf("film.$dependent", "film.$dependent.label"))
                            parent.findViewWithTag<View>(tag)?.visibility = if (it > 0f) View.VISIBLE else View.GONE
                        renderDraft()
                    }
                    bind(bar, control.id, kind.min.toFloat(), kind.max.toFloat())
                }
                is ParameterKind.Picker -> {
                    val value = (current(control.id) as? EffectValue.Choice)?.value ?: (control.initial as EffectValue.Choice).value
                    val visible = when (control.id) { "iris" -> readNumber("vignette") > 0f; "flashColor" -> readNumber("flash") > 0f; "grainSize" -> readNumber("grain") > 0f; else -> true }
                    add(parent, text(control.title, 10f).apply {
                        tag = "film.${control.id}.label"; visibility = if (visible) View.VISIBLE else View.GONE
                    }, 21f)
                    pick(value) {
                        a.choose(control.title, kind.values, kind.values.indexOf(value)) {
                            edit(control.id, EffectValue.Choice(kind.values[it]))
                        }
                    }.apply { tag = "film.${control.id}"; visibility = if (visible) View.VISIBLE else View.GONE }
                }
                ParameterKind.Toggle -> toggle(parent, control.title,
                    (current(control.id) as? EffectValue.Toggle)?.value ?: false) {
                    edit(control.id, EffectValue.Toggle(it))
                }
                else -> error("Invalid color control")
            }
            hint(parent, definition.help)
        } else when (fx.kind) {
            "Dolly Zoom" -> {
                label(parent, "Direction")
                choices(parent, listOf("Stretch", "Compress"), fx.values["direction"] ?: "Stretch", orange) {
                    fx.values["direction"] = listOf("Stretch", "Compress")[it]; renderDraft()
                }
                slider(parent, "Strength", fx.number("amount", .3f), .1f, .5f, color = orange) {
                    fx.values["amount"] = it.toString(); renderDraft()
                }
                slider(parent, "Duration", fx.number("duration", 2.5f), 1f, 5f, color = orange,
                    format = { "%.1f s".format(it) }) { fx.values["duration"] = it.toString(); renderDraft() }
                choices(parent, listOf("In-out cubic", "Linear"), fx.values["easing"] ?: "In-out cubic", orange) {
                    fx.values["easing"] = listOf("In-out cubic", "Linear")[it]; renderDraft()
                }
                slider(parent, "Subject X", fx.number("targetX", .5f), 0f, 1f, color = orange) {
                    fx.values["targetX"] = it.toString(); renderDraft()
                }
                slider(parent, "Subject Y", fx.number("targetY", .5f), 0f, 1f, color = orange) {
                    fx.values["targetY"] = it.toString(); renderDraft()
                }
                hint(parent, "Keep the person still while stretching or compressing the background.")
            }
            "Kaleidoscope" -> {
                label(parent, "Divisions")
                choices(parent, (2..9).map { it.toString() }, readNumber("divisions", 2f).toInt().toString(), orange) {
                    editNumber("divisions", (it + 2).toFloat())
                    renderDraft()
                }
                hint(parent, "Drag the picture to pan. Twist with two fingers to rotate the source. Drag the center or round handle to adjust the split.")
                button(parent, (if (kaleidoscopeFine) "⌄ " else "› ") + "Fine adjustments") {
                    kaleidoscopeFine = !kaleidoscopeFine
                    videoParameters(parent, fx, chain)
                }
                if (kaleidoscopeFine) {
                    for (p in Kaleidoscope.controls) {
                        fun reset() {
                            editNumber(p.id, p.initial.toFloat())
                            videoParameters(parent, fx, chain); renderDraft()
                        }
                        val bar = slider(parent, p.title, readNumber(p.id, p.initial.toFloat()), p.min.toFloat(), p.max.toFloat(),
                            format = { if (p.id.endsWith("Angle")) "%.0f°".format(it) else "%.0f%%".format(it * 100) },
                            color = orange, reset = ::reset) {
                            editNumber(p.id, ((it / p.step).roundToInt() * p.step).toFloat().coerceIn(p.min.toFloat(), p.max.toFloat()))
                            renderDraft()
                        }
                        bind(bar, p.id, p.min.toFloat(), p.max.toFloat())
                    }
                    button(parent, "Reset split") {
                        for (key in listOf("centerX", "centerY", "splitAngle")) editNumber(key, if (key == "splitAngle") 0f else .5f)
                        videoParameters(parent, fx, chain); renderDraft()
                    }
                    button(parent, "Center source") {
                        editNumber("sourcePanX", 0f); editNumber("sourcePanY", 0f)
                        videoParameters(parent, fx, chain); renderDraft()
                    }
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
                val pads = RGBOffsetPads(a, factor, { key -> readNumber(key) }) { channel, x, y ->
                    editNumber(channel + "X", x)
                    editNumber(channel + "Y", y)
                    renderDraft()
                }
                rgbPads = pads
                pads.enable(fx.enabled)
                parent.addView(pads, LinearLayout.LayoutParams(-1, -2))
                hint(parent, "Drag a cross to shift its color. Double-tap to center it.")
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
                videoDraft?.endpoint?.let { b ->
                    hint(parent, "Editing endpoint ${if (b) "B" else "A"}")
                }
                fun controls(ids: List<String>) {
                    for (id in ids) {
                        val p = EffectContracts.correction.firstOrNull { it.id == id }
                        val name = p?.title ?: "Intensity"
                        val min = p?.min?.toFloat() ?: 0f
                        val max = p?.max?.toFloat() ?: 1f
                        val initial = p?.initial?.toFloat() ?: 1f
                        fun reset() {
                            editNumber(id, initial)
                            videoParameters(parent, fx, chain)
                            renderDraft()
                        }
                        val bar = slider(parent, name, readNumber(id, initial), min, max,
                            format = { EffectContracts.display(id, it) }, color = orange,
                            reset = ::reset, help = EffectContracts.help(id)) {
                            val step = p?.step?.toFloat() ?: .01f
                            editNumber(id, ((it / step).roundToInt() * step).coerceIn(min, max))
                            if (id == "contrast") {
                                val enabled = readNumber("contrast", 1f) != 1f
                                parent.findViewWithTag<View>("correction.pivot")?.isEnabled = enabled
                                parent.findViewWithTag<View>("correction.pivotHint")?.visibility = if (enabled) View.GONE else View.VISIBLE
                            }
                            renderDraft()
                        }
                        bind(bar, id, min, max)
                    bar.tag = "correction.$id"
                        bar.isEnabled = id != "pivot" || readNumber("contrast", 1f) != 1f
                    }
                }
                choices(parent, listOf("Light", "Color"), if (correctionTab == 0) "Light" else "Color", orange) {
                    correctionTab = it
                    videoParameters(parent, fx, chain)
                }
                hint(parent, "Before color looks · tap a value to reset")
                if (correctionTab == 0) {
                    controls(listOf("exposure", "contrast", "shadows", "highlights"))
                    button(parent, (if (correctionAdvanced) "⌄ " else "› ") + "Advanced light") {
                        correctionAdvanced = !correctionAdvanced
                        videoParameters(parent, fx, chain)
                    }
                    if (correctionAdvanced) {
                        controls(listOf("blacks", "whites", "pivot"))
                        add(parent, text("Adjust contrast to use the pivot.", 7.5f, MUTED).apply {
                            tag = "correction.pivotHint"
                            visibility = if (readNumber("contrast", 1f) == 1f) View.VISIBLE else View.GONE
                        })
                    }
                } else controls(listOf("saturation", "vibrance", "temperature", "tint", "hue"))
                controls(listOf("intensity"))
                hint(parent, "Hold a label for help")
                button(parent, "Reset correction") {
                    android.app.AlertDialog.Builder(a).setTitle("Reset this correction?")
                        .setMessage(if (fx.motion != null) "Returns the selected animation endpoint to neutral."
                            else "Returns all controls to neutral and removes their keyframes. Other effects are kept.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Reset all adjustments") { _, _ ->
                            val work = videoDraft
                            if (work != null) work.reset(fx, editor.cursor)
                            else {
                                fx.values.clear(); fx.parameters.clear()
                                fx.parameters.addAll(EffectContracts.parameters(fx.kind))
                            }
                            videoParameters(parent, fx, chain)
                            renderDraft()
                        }.show()
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
                    editor.replaceChain(chain, Effects.audio)
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
                            val fx = AudioFilter.effect(available[i])
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
                val modern = selected.values["dsp"] == "2"
                selected.values.clear()
                if (modern) selected.values.putAll(AudioFilter.effect(selected.kind).values)
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
                    if (eq) 12000f else 500f,
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
            } else if (selected.kind == "Output gain") {
                slider(detail, "Gain", selected.number("gain"), -24f, 24f,
                    format = { "%+.1f dB".format(it) }, color = RED) {
                    selected.values["gain"] = it.toString(); views[selected.id]?.invalidate(); renderDraft()
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
        val work = AudioEditsDraft(editor)
        if (work.clips.isEmpty()) { a.message("Select an audio section first"); return }
        val initial = work.clips.first().gainDb
        var gain = initial
        var audible = if (initial > -60f) initial else 0f
        var mute: ReferenceToggle? = null
        val body =
            setup(
                "Volume",
                {
                    try { work.commit(editor); close() }
                    catch (e: IllegalStateException) { a.message(e.message ?: "Unable to apply volume") }
                },
            )
        audioDraft = work
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
            work.gain(gain)
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
        for (out in listOf(false, true)) {
            val target = work.target(out) ?: continue
            val name = if (out) "Fade out" else "Fade in"
            val fade = if (out) target.fadeOut else target.fadeIn
            label(controls, name)
            hint(controls, "Audio ${editor.project.clips.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }?.plus(1) ?: target.track + 1} · ${timeLabel(target.start)}–${timeLabel(target.end)}")
            val bar = slider(controls, "$name duration", (fade?.remaining(target.duration) ?: 0.0).toFloat(), 0f, target.duration.toFloat(),
                format = { if (it <= 0) "Off" else "%.2f s".format(java.util.Locale.US, it) }) {
                work.fade(out, it.toDouble()); renderDraft()
            }
            button(controls, "Turn $name off") { work.fade(out, 0.0); bar.progress = 0; renderDraft() }
            button(controls, "Audition $name") {
                a.playback.pause()
                editor.cursor = if (out) max(target.start, target.end - max(2.0, (target.fadeOut?.remaining(target.duration) ?: 0.0) + .5)) else target.start
                a.playback.previewProject = work.snapshot()
                a.playback.play(if (out) target.end else min(target.end, target.start + max(2.0, (target.fadeIn?.remaining(target.duration) ?: 0.0) + .5)))
            }
        }
        renderDraft()
    }

    fun censor(existingId: String? = null) {
        val original = editor.project.duplicate()
        val existing = original.clips.flatMap { c -> c.censors.filter { it.id == existingId }.map { c to it } }
        val targets = editor.targets().filter { it.audio }
        if (targets.isEmpty() && existing.isEmpty()) { a.message("Select an audio section first"); return }
        var from = existing.minOfOrNull { it.first.start + it.second.from } ?: editor.range?.start ?: targets.minOf { it.start }
        var to = existing.maxOfOrNull { it.first.start + it.second.to } ?: editor.range?.endInclusive ?: targets.maxOf { it.end }
        val selected = (if (existing.isEmpty()) targets.map { it.id } else existing.map { it.first.id }).toMutableSet()
        var config = existing.firstOrNull()?.second?.settings ?: AudioCensorSettings()
        val event = existingId ?: uid()
        fun state(remove: Boolean = false): Project {
            val next = original.duplicate()
            if (existingId != null) next.clips.forEach { c -> c.censors.removeAll { it.id == existingId } }
            if (!remove) censorAudio(next, selected, from, to, config, event)
            else if (existingId == null) censorAudio(next, selected, from, to, null)
            return next
        }
        val body = setup("Censor audio", {
            try { editor.commitDraft(original, state()); close() }
            catch (e: IllegalStateException) { a.message(e.message ?: "Unable to apply censoring") }
        })
        fun update() {
            a.playback.pause()
            censorDraft = state()
            val start = max(0.0, from - 1)
            previewSpan = start..min(original.duration, min(to + 1, start + 10))
            renderDraft()
        }
        update()
        val controls = contentWithPreview(body)
        fun controls() {
            controls.removeAllViews()
            reflowControls = ::controls
            label(controls, "Censor range")
            slider(controls, "Start", from.toFloat(), 0f, (to - 1.0 / original.fps).coerceAtLeast(0.0).toFloat(),
                format = { timeLabel(it.toDouble()) }) { from = it.toDouble().coerceAtMost(to - min(1.0 / original.fps, to / 2)); update() }
            slider(controls, "End", to.toFloat(), (from + 1.0 / original.fps).coerceAtMost(original.duration).toFloat(), original.duration.toFloat(),
                format = { timeLabel(it.toDouble()) }) { to = it.toDouble().coerceIn(from + min(1.0 / original.fps, (original.duration - from) / 2), original.duration); update() }
            for ((index, c) in original.clips.withIndex().filter { it.value.audio && it.value.start < to && it.value.end > from })
                toggle(controls, "Audio ${index + 1} · ${timeLabel(c.start)}–${timeLabel(c.end)}", c.id in selected) {
                    if (it) selected.add(c.id) else selected.remove(c.id)
                    update()
                }
            label(controls, "Waveform")
            choices(controls, CensorWaveform.entries.map { it.name }, config.waveform.name, TEAL) {
                config = config.copy(waveform = CensorWaveform.entries[it]); update()
            }
            slider(controls, "Frequency", config.frequency.toFloat(), 100f, 4000f, format = { "%.0f Hz".format(it) }) {
                config = config.copy(frequency = it.toDouble()); update()
            }
            slider(controls, "Beep level", config.levelDb.toFloat(), -36f, -6f, format = { "%.1f dB".format(it) }) {
                config = config.copy(levelDb = it.toDouble()); update()
            }
            label(controls, "Envelope")
            val names = listOf("Short fade", "Soft fade", "Pulsed")
            choices(controls, names, names[config.envelope.ordinal], TEAL) {
                config = config.copy(envelope = CensorEnvelope.entries[it]); update()
            }
            hint(controls, "Track Volume applies. Where selected tracks overlap, the loudest track carries the beep. Original sound stays muted throughout the range.")
            button(controls, "Remove censoring") {
                try { editor.commitDraft(original, state(remove = true)); close() }
                catch (e: IllegalStateException) { a.message(e.message ?: "Unable to remove censoring") }
            }
        }
        controls()
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

    fun freezeFrame(existing: Effect? = null) {
        val original = editor.project.duplicate()
        val held = existing?.let { fx -> original.clips.firstOrNull { c -> c.frozen != null && c.effects.any { it.id == fx.id } } }
        val position = held?.start ?: editor.cursor
        var seconds = held?.duration?.toFloat() ?: 3f
        var push = existing?.number("pushIn", .05f) ?: .05f
        var continueAudio = existing?.values?.get("audio") == "Continue"
        fun rebuild() {
            val temporary = Editor(original.duplicate()).apply { cursor = position }
            if (held == null) temporary.insertFreeze(seconds.toDouble(), push, continueAudio)
            else temporary.resizeFreeze(held.id, seconds.toDouble(), push, continueAudio)
            freezeDraft = temporary.project
            previewSpan = position..min(temporary.project.duration, position + seconds + 1)
        }
        val body = setup("Freeze Frame", { editor.commitDraft(original, checkNotNull(freezeDraft)); close() })
        rebuild()
        val controls = contentWithPreview(body)
        label(controls, if (held == null) "Insert a held frame" else "Edit held frame")
        hint(controls, "The hold adds time at the playhead. The following footage resumes afterward.")
        slider(controls, "Hold duration", seconds, min(.5f, seconds), (10 - (held?.frozen?.offset ?: 0.0)).toFloat(), format = { "%.1f s".format(it) }) {
            seconds = it; rebuild(); renderDraft()
        }
        slider(controls, "Push-in", push, 0f, .15f) { push = it; rebuild(); renderDraft() }
        choices(controls, listOf("Silence", "Continue"), if (continueAudio) "Continue" else "Silence") {
            continueAudio = it == 1; rebuild(); renderDraft()
        }
        if (held != null) button(controls, "Remove hold") {
            val temporary = Editor(original.duplicate()).apply {
                selected.add(held.id); deleteSelection()
            }
            editor.commitDraft(original, temporary.project); close()
        }
        renderDraft()
    }

    fun pauses() = pauseReview(PausesDraft(editor))

    private fun pauseReview(work: PausesDraft) {
        val body = setup("Pauses", { work.commit(editor); close() })
        pausesDraft = work
        val controls = contentWithPreview(body)
        val initial = work.reviews.values.firstOrNull()
        if (initial == null) { hint(controls, "Select audio to find pauses."); return }
        label(controls, "Review silent pauses")
        choices(controls, listOf("Automatic", "Manual"), if (initial.automatic) "Automatic" else "Manual") { i ->
            work.reviews.replaceAll { _, s -> s.copy(automatic = i == 0) }
        }
        slider(controls, "Silence threshold", initial.thresholdDb.toFloat(), -70f, -15f,
            format = { "%.0f dB".format(it) }) { value -> work.reviews.replaceAll { _, s -> s.copy(thresholdDb = value.toDouble()) } }
        slider(controls, "Minimum pause", initial.minimum.toFloat(), .2f, 3f,
            format = { "%.2f s".format(it) }) { value -> work.reviews.replaceAll { _, s -> s.copy(minimum = value.toDouble()) } }
        slider(controls, "Keep before", initial.before.toFloat(), 0f, .5f,
            format = { "%.2f s".format(it) }) { value ->
            work.reviews.replaceAll { _, s -> s.copy(before = value.toDouble()) }; renderDraft()
        }
        slider(controls, "Keep after", initial.after.toFloat(), 0f, .5f,
            format = { "%.2f s".format(it) }) { value ->
            work.reviews.replaceAll { _, s -> s.copy(after = value.toDouble()) }; renderDraft()
        }
        button(controls, "Find pauses") {
            work.cancelled = false
            progress("Finding pauses", 0f) { work.cancelled = true }
            Thread {
                try {
                    work.analyze(a.audioCache) { p -> ui.post { updateProgress(p) } }
                    ui.post { if (!work.cancelled) pauseReview(work) }
                } catch (e: Exception) { ui.post { close(); a.message(e.message ?: "Cannot analyze audio") } }
            }.start()
        }
        hint(controls, "Checked pauses are skipped. Original sound and review decisions stay in the project.")
        for (clip in work.clips.distinctBy { "${it.path}:${it.start}:${it.sourceIn}:${it.sourceOut}" }) {
            val review = work.reviews.getValue(clip.id)
            hint(controls, "${timeLabel(clip.start)} · ${review.candidates.size} candidates · ${review.thresholdDb.toInt()} dB")
            for (candidate in review.candidates) {
                val toggle = CheckBox(a).apply {
                    text = "${timeLabel(candidate.raw.start)} · %.2f s".format(candidate.raw.duration / clip.effectiveRate)
                    setTextColor(Color.WHITE); isChecked = candidate.enabled
                    setOnCheckedChangeListener { _, checked ->
                        work.reviews.replaceAll { _, current -> current.copy(candidates = current.candidates.map {
                            if (it.id == candidate.id) it.copy(enabled = checked) else it
                        }) }
                        renderDraft()
                    }
                }
                add(controls, toggle, 42f)
            }
        }
        button(controls, "Keep all pauses") { work.reset(); pauseReview(work) }
        renderDraft()
    }

    private fun reviewSubtitles(work: SubtitlesDraft) {
        val body = setup("Subtitles", { work.commit(editor); close() })
        subtitlesDraft = work
        val controls = contentWithPreview(body)
        label(controls, "Transcript")
        hint(controls, "Tap a phrase to correct it. Timing is estimated within detected speech.")
        button(controls, "Copy transcript") {
            val clipboard = a.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", work.transcript()))
            a.message("Transcript copied")
        }
        button(controls, "Words per caption") {
            a.choose("Words per caption", listOf("1", "3", "6", "10")) { i ->
                work.regroup(listOf(1, 3, 6, 10)[i]); reviewSubtitles(work)
            }
        }
        slider(controls, "Vertical position", work.project.clips.flatMap { it.effects }
            .firstOrNull { it.subtitle != null }?.number("y", .9f) ?: .9f, .05f, .95f) {
            work.placement("y", it.toString()); renderDraft()
        }
        for (phrase in work.rows()) {
            button(controls, "${timeLabel(phrase.time)} · ${phrase.text}") {
                editor.cursor = phrase.time; renderDraft()
                val input = EditText(a).apply { setText(phrase.text); setTextColor(Color.WHITE) }
                android.app.AlertDialog.Builder(a).setTitle("Correct subtitle").setView(input)
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Remove") { _, _ -> work.remove(phrase.id); reviewSubtitles(work) }
                    .setPositiveButton("Save") { _, _ -> work.correct(phrase.id, input.text.toString()); reviewSubtitles(work) }
                    .show()
            }
        }
        renderDraft()
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
                    val targets = editor.targets().filter { it.video }.map { it.id }.toSet()
                    val scope = editor.range ?: (editor.targets().minOfOrNull { it.start } ?: 0.0)..
                        (editor.targets().maxOfOrNull { it.end } ?: snapshot.duration)
                    progress("Generating captions", 0f) { job.cancelled = true }
                    Thread {
                            try {
                                val captions =
                                    job.generate(snapshot, language, scope) { p ->
                                        ui.post { updateProgress(p) }
                                    }
                                ui.post {
                                    if (editor.project != snapshot) {
                                        close(); a.message("The project changed. Generate subtitles again.")
                                    } else {
                                        val result = snapshot.duplicate()
                                        result.clips.filter { it.id in targets }.forEach { clip ->
                                            clip.effects.removeAll { it.subtitle != null && clip.start + it.from < scope.endInclusive && clip.start + it.to > scope.start }
                                        }
                                        val series = uid()
                                        for (caption in captions) SubtitlesDraft.attach(result, series, caption.words, targets)
                                        reviewSubtitles(SubtitlesDraft(editor, result))
                                    }
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
        if (editor.project.clips.any { c -> c.effects.any { it.subtitle != null } })
            button(controls, "Review subtitles") { reviewSubtitles(SubtitlesDraft(editor)) }
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
                "Recognize the selection on your phone, then review and correct the words. Word timing is estimated.",
            )
        } else {
            hint(
                controls,
                "Download Qwen3-ASR 0.6B once (636 MB). Transcription then works offline.",
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

    fun cover() {
        a.playback.pause()
        val source = CoverSource(editor.project,editor.selected.toSet(),editor.range)
        val content = setup("Select cover")
        val picker = CoverPicker(a,source)
        coverPicker = picker
        picker.attach(content,InspectorUi(a,factor))
    }

    fun export() {
        if (editor.project.duration <= 0) {
            a.message("Add a video or record first")
            return
        }
        var size = 1080
        var color = ExportColor.SDR
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
                    a.export(w, h, color)
                },
            )
        val controls = contentWithPreview(body)
        label(controls, "Video quality")
        choices(controls, listOf("720p", "1080p", "4K"), "1080p") {
            size = listOf(720, 1080, 2160)[it]
        }
        label(controls, "Color")
        choices(controls, listOf("SDR · H.264", "HDR · HEVC 10-bit HLG"), "SDR · H.264") {
            color = if (it == 1) ExportColor.HLG else ExportColor.SDR
        }
        hint(controls, "HDR requires a Main10 encoder, an HLG recording surface and 10-bit source decoding. Unsupported hardware reports the unavailable path.")
        label(controls, "${editor.project.fps} fps · AAC 48 kHz", MUTED)
        label(controls, "Duration · ${timeLabel(editor.project.duration)}", MUTED)
        hint(controls, "Save the edited video to Movies/MyRec, then play or share it.")
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
        private val inspection = PreviewViewport()
        private var inspectMode = false
        private var panAnchored = true
        private var controlGesture = false
        private val inspectButton = RectF()
        private val resetButton = RectF()
        private val inspectionPinch = InspectionGesture { factor, x, y, oldX, oldY ->
            inspection.scale(factor, x - inset, y, oldX - inset, oldY)
            panAnchored = false
            invalidate()
        }
        private fun editsContent() = draft?.kind in listOf("Crop", "Zoom", "Placement", "Text", "Image", "Kaleidoscope")
        private fun inspecting() = if (draft?.kind == "Crop") !cropFrame else inspectMode || !editsContent()

        init { contentDescription = "Tool preview"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

        fun resetInspection() { inspection.reset(); invalidate() }

        private fun toggleInspection() {
            inspection.reset()
            if (draft?.kind == "Crop") { cropFrame = !cropFrame; renderDraft() }
            else inspectMode = !inspectMode
            invalidate()
        }
        private val scale =
            ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                        panAnchored = false
                        return true
                    }
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
                            else "size"
                        val initial =
                            if (key == "zoom") 1f
                            else if (fx.kind == "Text") 0.07f else 0.3f
                        fx.values[key] =
                            (fx.number(key, initial) * d.scaleFactor)
                                .coerceIn(
                                    if (key == "zoom") 1f else 0.01f,
                                    if (key == "zoom") 5f else 1f,
                                )
                                .toString()
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
            inspection.configure(width - inset * 2, height.toFloat(), b.width.toFloat(), b.height.toFloat())
            c.save()
            c.clipRect(inset, 0f, width - inset, height.toFloat())
            c.translate(inspection.x, inspection.y)
            c.scale(inspection.zoom, inspection.zoom, width / 2f, height / 2f)
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
            if (fx?.kind == "Kaleidoscope" && fx.enabled && previewEffects && !inspecting()) splitGuide.draw(c, bounds, factor, opticalEffect(fx))
            c.restore()
            inspectButton.setEmpty()
            resetButton.setEmpty()
            var edge = width - factor * 6
            fun badge(rect: RectF, label: String, span: Float) {
                rect.set(edge - factor * span, height - factor * 32, edge, height - factor * 5)
                paint.color = Color.argb(205, 8, 8, 8)
                c.drawRoundRect(rect, factor * 13, factor * 13, paint)
                paint.color = Color.WHITE; paint.textSize = factor * 8; paint.textAlign = Paint.Align.CENTER
                c.drawText(label, rect.centerX(), rect.centerY() + factor * 3, paint)
                edge = rect.left - factor * 4
            }
            if (editsContent() && fx?.kind != "Crop") badge(inspectButton, if (inspecting()) "Edit" else "Inspect", 48f)
            if (inspection.zoom > 1.001f) badge(resetButton, "%.1f× · Reset".format(java.util.Locale.US, inspection.zoom), 66f)
            paint.textAlign = Paint.Align.LEFT
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

        private fun editOptical(key: String, value: Float) {
            val fx = draft ?: return
            val work = videoDraft
            if (work != null) work.editNumber(fx, key, value, editor.cursor, rgbAnimating)
            else fx.editNumber(key, value, 0.0, 0.0, rgbAnimating)
            controlsTick?.invoke()
        }
        private val splitGuide = KaleidoscopeGuide(::editOptical, { renderDraft(); invalidate() })
        private val splitActions = listOf(
            Triple("Move split left", "centerX", -.01f), Triple("Move split right", "centerX", .01f),
            Triple("Move split up", "centerY", -.01f), Triple("Move split down", "centerY", .01f),
            Triple("Pan source left", "sourcePanX", -.01f), Triple("Pan source right", "sourcePanX", .01f),
            Triple("Pan source up", "sourcePanY", -.01f), Triple("Pan source down", "sourcePanY", .01f),
            Triple("Rotate split left", "splitAngle", -1f), Triple("Rotate split right", "splitAngle", 1f),
            Triple("Rotate source left", "sourceAngle", -1f), Triple("Rotate source right", "sourceAngle", 1f),
        )

        private fun opticalEffect(fx: Effect): Effect {
            val work = videoDraft ?: return fx.sampled(0.0)
            val c = work.clip(editor.cursor)
            return (work.effect(fx.id, editor.cursor) ?: fx).sampled(c?.sourceTime(editor.cursor) ?: 0.0)
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
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                controlGesture = true
                if (resetButton.contains(e.x, e.y)) { resetInspection(); return true }
                if (inspectButton.contains(e.x, e.y)) { toggleInspection(); return true }
                controlGesture = false
            } else if (controlGesture) return true
            if (
                title == "Video effects" &&
                    e.actionMasked == MotionEvent.ACTION_DOWN &&
                    e.x in bounds.left..bounds.left + factor * 80 &&
                    e.y in bounds.top..bounds.top + factor * 32
            ) {
                previewEffects = !previewEffects
                controlGesture = true
                renderDraft()
                invalidate()
                return true
            }
            val optical = draft?.takeIf { it.kind == "Kaleidoscope" && it.enabled && previewEffects && !inspecting() }
            if (optical != null) {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
                val handled = splitGuide.touch(e, opticalEffect(optical))
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
                    parent.requestDisallowInterceptTouchEvent(false)
                return handled
            }
            if (inspecting()) inspectionPinch.event(e) else scale.onTouchEvent(e)
            if (inspecting()) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = e.x; lastY = e.y; panAnchored = true
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> panAnchored = false
                    MotionEvent.ACTION_MOVE -> {
                        if (e.pointerCount == 1 && panAnchored) inspection.pan(e.x - lastX, e.y - lastY)
                        panAnchored = e.pointerCount == 1
                        lastX = e.x; lastY = e.y
                        invalidate()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
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

        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            for ((i, label) in listOf("Zoom in preview", "Zoom out preview", "Reset preview zoom").withIndex())
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x02000101 + i, label))
            if (editsContent()) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x02000104,
                if (inspecting()) "Resume editing preview" else "Inspect preview"))
            if (draft?.kind == "Kaleidoscope" && draft?.enabled == true && previewEffects)
                splitActions.forEachIndexed { i, action ->
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x02000201 + i, action.first))
                }
            val reading = "%.2f× view".format(java.util.Locale.US, inspection.zoom)
            if (android.os.Build.VERSION.SDK_INT >= 30) info.stateDescription = reading else info.text = reading
        }

        override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
            if (action in 0x02000201..0x0200020c) {
                val fx = draft?.takeIf { it.kind == "Kaleidoscope" && it.enabled && previewEffects } ?: return false
                val (_, key, delta) = splitActions[action - 0x02000201]
                val current = opticalEffect(fx)
                val spec = current.parameter(key)?.kind as? ParameterKind.Slider ?: return false
                val next = current.number(key).toDouble() + delta
                editOptical(key, (if (spec.wrapped) Kaleidoscope.wrapped(next) else next.coerceIn(spec.min, spec.max)).toFloat())
                renderDraft(); invalidate()
                return true
            }
            when (action) {
                0x02000101, 0x02000102 -> {
                    if (!inspecting()) toggleInspection()
                    inspection.scale(if (action == 0x02000101) 1.5f else 1 / 1.5f)
                }
                0x02000103 -> inspection.reset()
                0x02000104 -> toggleInspection()
                else -> return super.performAccessibilityAction(action, arguments)
            }
            invalidate()
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
        private val gain = fx.kind == "Output gain"
        private val modern = fx.values["dsp"] == "2"
        private val color = if (eq) Color.rgb(70, 150, 230) else if (gain) RED else if (lowCut) Color.rgb(186, 135, 247) else TEAL
        private val key = if (eq || gain) "gain" else if (lowCut) "frequency" else "strength"
        private val fallback = if (gain || eq && modern) 0f else if (eq) 3f else if (lowCut) 80f else 1f
        private val low = if (gain || eq && modern) -24f else if (eq) -18f else if (lowCut) 20f else 0f
        private val high = if (gain || eq && modern) 24f else if (eq) 18f else if (lowCut) 500f else 1f

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
            val fraction = if (lowCut && modern) (ln(fx.number(key, fallback) / 20f) / ln(25f)).coerceIn(0f, 1f)
                else ((fx.number(key, fallback) - low) / (high - low)).coerceIn(0f, 1f)
            val levelY = top + (bottom - top) * (1 - fraction)
            if ((eq || gain) && modern) c.clipRect(unit, min((top + bottom) / 2, levelY), width - unit, max((top + bottom) / 2, levelY))
            else c.clipRect(unit, levelY, width - unit, bottom)
            paint.color =
                if (fx.enabled)
                    Color.argb(125, Color.red(color), Color.green(color), Color.blue(color))
                else Color.rgb(45, 45, 48)
            c.drawRoundRect(rect, unit * 13, unit * 13, paint)
            c.restore()
            if ((eq || gain) && modern) {
                paint.color = Color.GRAY; paint.strokeWidth = unit * .6f
                c.drawLine(unit * 2, (top + bottom) / 2, width - unit * 2, (top + bottom) / 2, paint)
            }
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
                    "Output gain" -> "Gain"
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
                if (eq || gain) "%+.1f dB".format(fx.number(key, fallback))
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
                        val delta = (downY - e.y) / (height - px(36f))
                        fx.values[key] = if (lowCut && modern)
                            (20f * 25f.pow((ln(value / 20f) / ln(25f) + delta).coerceIn(0f, 1f))).roundToInt().toString()
                        else (value + delta * (high - low)).coerceIn(low, high).toString()
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
