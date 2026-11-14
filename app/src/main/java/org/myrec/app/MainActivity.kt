package org.myrec.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    lateinit var editor: Editor
        private set

    lateinit var store: ProjectStore
        private set

    lateinit var playback: Playback
        private set

    lateinit var editorView: EditorView
        private set

    lateinit var audioCache: AudioCache
        private set

    lateinit var root: FrameLayout
        private set

    lateinit var panels: Panels
        private set

    private lateinit var mediaIndex: MediaIndex
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var capture: Capture? = null
    private var captureMode = false
    private data class MediaImport(
        val project: String, val at: Double, val layer: Boolean, val audio: Boolean,
        val base: Project? = null,
    )
    private var pendingImport: MediaImport? = null
    private var recordingAt: MediaImport? = null
    internal var voiceDraft: VoiceDraft? = null
        private set
    private var pendingSave: File? = null
    private data class MediaRepair(val project: Project, val path: String)
    private var pendingRepair: MediaRepair? = null
    private var imageCallback: ((String) -> Unit)? = null
    private var importAsProject = false
    private var exporting: Export? = null
    internal var choices: PopupWindow? = null
        private set
    internal var linkDialog: AlertDialog? = null
        private set
    internal var importDialog: AlertDialog? = null
        private set
    private var lastMessage = ""
    private var lastMessageAt = 0L
    private var saveWarning: TextView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        updateBars()
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        val testSession =
            intent.getBooleanExtra("testSession", false) &&
                applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val testSessionId =
            intent.getStringExtra("testSessionId")?.takeIf { it.matches(Regex("[a-zA-Z0-9-]+")) }
                ?: "shared"
        store =
            ProjectStore(
                if (testSession) File(cacheDir, "test-projects/$testSessionId")
                else File(filesDir, "projects")
            )
        store.cleanTransfers()
        store.recoverCaptures()
        val saved = store.currentSession()
        editor = Editor(saved.project).apply { load(saved.project, saved) }
        val editorSettings = getSharedPreferences(if (testSession) "editor-test-$testSessionId" else "editor", MODE_PRIVATE)
        editor.snap = editorSettings.getBoolean("snapToCuts", true)
        Studio.directory = File(getExternalFilesDir(null), "models/studio-lava-v1")
        Studio.cacheDirectory = File(cacheDir, "studio-audio")
        AudioModels.configure(this, File(getExternalFilesDir(null), "models"))
        Person.configure(this)
        audioCache = AudioCache(File(cacheDir, "audio"))
        Stabilizer.directory = File(cacheDir, "stabilization")
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        mediaIndex = MediaIndex(audioCache) { editorView.invalidate() }
        editorView = EditorView(this, editor, mediaIndex, ::action) { playback.seek(it) }
        editor.onSnapChange = {
            editorSettings.edit().putBoolean("snapToCuts", it).apply()
            editorView.invalidate()
        }
        playback =
            Playback(
                editor,
                audioCache,
                {
                    editorView.bitmap = it
                    panels.setFrame(it)
                },
                {
                    editorView.playing = playback.playing
                    editorView.invalidate()
                    panels.invalidatePreview()
                },
                { message(it) },
                {
                    editorView.preparing = it
                    editorView.invalidate()
                    panels.invalidatePreview()
                },
            )
        panels = Panels(this)
        root.addView(editorView, FrameLayout.LayoutParams(-1, -1))
        editor.onChange = {
            saveProject()
            editorView.resetScroll()
            editorView.invalidate()
            playback.refresh()
        }
        playback.refresh()
        updateBars()
        if (store.problems.isNotEmpty()) root.post {
            message(store.problems.values.joinToString("\n"))
            panels.projects()
        }
        indexOriginals(editor.project.id)
        if (intent.action == Intent.ACTION_SEND) {
            val uri =
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) importUri(uri, false)
        }
    }

    private fun action(id: String) {
        if (id.startsWith("censor:")) { panels.censor(id.substringAfter(':')); return }
        if (id.startsWith("effect:")) {
            panels.editExisting(id.substringAfter(':'))
            return
        }
        when (id) {
            "empty" ->
                choose("MyRec", listOf("Record video", "Add video", "Projects")) {
                    when (it) {
                        0 -> showCapture()
                        1 -> importVideo()
                        2 -> projects()
                    }
                }
            "projects" -> projects()
            "addVideo" -> importVideo()
            "addMedia" -> {
                playback.pause()
                choose("Add media", listOf("Insert video", "Add video layer", "Add audio")) {
                    when (it) {
                        0 -> importVideo()
                        1 -> importVideo(layer = true)
                        2 -> panels.addAudio()
                    }
                }
            }
            "play" -> playback.toggle()
            "audition" -> playback.audition()
            "previous" -> {
                playback.pause()
                editorView.previousPosition = editor.cursor
                editor.jump(false)
                playback.refresh()
            }
            "next" -> {
                playback.pause()
                editorView.previousPosition = editor.cursor
                editor.jump(true)
                playback.refresh()
            }
            "backPoint", "previousMark" -> {
                val t = (if (id == "previousMark")
                    editor.project.markers().lastOrNull { it < editor.cursor - .01 } else null)
                    ?: editorView.previousPosition ?: 0.0
                editorView.previousPosition = editor.cursor
                playback.seek(t)
            }
            "forwardPoint" -> {
                editorView.previousPosition = editor.cursor
                playback.seek(editor.project.markers().firstOrNull { it > editor.cursor + .01 }
                    ?: editor.project.duration)
            }
            "split" -> {
                playback.pause()
                editor.split()
            }
            "mark" -> {
                playback.pause()
                editor.markRange(editorView.pointsPerSecond, editorView::wavePeak)?.let(::message)
                playback.seek(editor.cursor)
            }
            "tools" -> {
                playback.pause()
                panels.tools()
            }
            "export" -> panels.export()
            "undo" -> {
                playback.pause()
                editor.undo()
            }
            "redo" -> {
                playback.pause()
                editor.redo()
            }
            "snap" -> {
                editor.snap = !editor.snap
            }
            "link" -> {
                try {
                    if (editor.selectionLinked) editor.unlink()
                    else {
                        val plan = editor.linkPlan()
                        if (!plan.needsIntersection) editor.linkSelection()
                        else {
                            val original = editor.project.duplicate()
                            val interval = plan.intersection!!
                            linkDialog = AlertDialog.Builder(this).setTitle("Link shared interval?")
                                .setMessage("Split at ${timeLabel(interval.start)} and ${timeLabel(interval.end)}, then link only the shared interval? Outside parts stay separate. Nothing is moved or deleted.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Link intersection") { _, _ ->
                                    try { editor.linkSelection(true, original) }
                                    catch (e: Exception) { message(e.message ?: "Cannot link these parts") }
                                }.create().also { dialog ->
                                    dialog.setOnDismissListener { linkDialog = null }
                                    dialog.show()
                                }
                        }
                    }
                } catch (e: Exception) {
                    message(e.message ?: "Cannot link these parts")
                }
            }
            "restoreSync" -> editor.restoreSync()
            "closeGap" -> editor.closeGap()
            "move" -> {
                playback.pause()
                editor.beginMove()
            }
            "moveDone" -> editor.finishMove(false)
            "moveCancel" -> editor.finishMove(true)
            "moveSelected" -> editor.changeMoveScope(MoveScope.SELECTED)
            "moveLeft" -> editor.changeMoveScope(MoveScope.LEFT)
            "moveRight" -> editor.changeMoveScope(MoveScope.RIGHT)
            "moveBoth" -> editor.changeMoveScope(MoveScope.BOTH)
            "movePrevious" -> editor.move?.let { draft ->
                draft.boundaries.lastOrNull { it < draft.position }?.let(editor::placeMove)
            }
            "moveNext" -> editor.move?.let { draft ->
                draft.boundaries.firstOrNull { it > draft.position }?.let(editor::placeMove)
            }
            "moveChoose" -> editor.move?.let { draft ->
                choose("Move to a boundary", draft.boundaries.map { timeLabel(it) }) {
                    editor.placeMove(draft.boundaries[it])
                }
            }
            "movePick" -> {
                val choices = editorView.boundaryChoices
                choose("Move to a boundary", choices.map { "%.4f s".format(java.util.Locale.US, it) }) {
                    editor.placeMove(choices[it])
                }
            }
            "volume" -> panels.volume()
            "placement" -> panels.effect("Placement")
            "scale" ->
                choose(
                    "Timeline scale",
                    listOf("1 second", "10 seconds", "1 minute", "5 minutes", "Whole project"),
                ) {
                    editor.wholeProject = it == 4
                    editor.scale =
                        listOf(1.0, 10.0, 60.0, 300.0, editor.project.duration)[it].coerceAtLeast(
                            1.0
                        )
                    editorView.resetScroll()
                    editorView.invalidate()
                }
            "playbackSpeed" ->
                choose("Playback speed", listOf("0.5×", "1×", "1.5×", "2×", "3×")) {
                    val playing = playback.playing
                    playback.pause()
                    editor.playbackRate = listOf(0.5f, 1f, 1.5f, 2f, 3f)[it]
                    if (playing) playback.play()
                    editorView.invalidate()
                }
            "fullscreen" -> {
                editorView.fullscreen = !editorView.fullscreen
                editorView.invalidate()
            }
            "copy" -> {
                playback.pause()
                editor.copySelection()
                editor.clearSelection()
                editorView.showPaste = true
                editorView.invalidate()
            }
            "paste" -> {
                playback.pause()
                val targets = editor.pasteTargets()
                val original = editor.project.duplicate()
                fun apply(index: Int?) {
                    if (editor.project != original) { message("The timeline changed. Choose Paste again."); return }
                    editor.paste(index?.let { targets[it] })
                    editor.clearSelection()
                    editorView.showPaste = true
                    editorView.invalidate()
                }
                if (targets.size > 1) choose("Paste at this boundary", targets.map { it.label }) { apply(it) }
                else apply(if (targets.isEmpty()) null else 0)
            }
            "cut" -> {
                playback.pause()
                editor.cutSelection()
                editorView.showPaste = true
                editorView.invalidate()
            }
            "dismissPaste" -> {
                editorView.showPaste = false
                editorView.invalidate()
            }
            "delete" -> {
                playback.pause()
                editor.deleteSelection()
            }
            "clear" -> {
                editor.clearSelection()
                editorView.invalidate()
            }
            "transition" -> panels.transition()
        }
    }

    fun showCapture() {
        playback.pause()
        panels.close()
        val missing =
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 10)
            return
        }
        captureMode = true
        editorView.visibility = View.GONE
        if (capture == null)
            capture =
                Capture(
                    this,
                    store,
                    { clip ->
                        val insertion = recordingAt
                        if (insertion == null) {
                            if (editor.project.clips.isEmpty()) setProjectFormat(clip)
                            editor.appendRecording(clip)
                        } else {
                            recordingAt = null
                            editor.insertBatch(listOf(clip), insertion.at)
                            ui.post { capture?.suspendCamera(); showEditor() }
                        }
                    },
                    ::showEditor,
                )
        if (capture!!.parent == null) root.addView(capture, FrameLayout.LayoutParams(-1, -1))
        capture!!.visibility = View.VISIBLE
        capture!!.resumeCamera()
        updateBars()
    }

    fun showEditor() {
        if (capture?.recording != true) recordingAt = null
        captureMode = false
        capture?.visibility = View.GONE
        editorView.visibility = View.VISIBLE
        playback.refresh()
        editorView.invalidate()
        updateBars()
    }

    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        grants: IntArray,
    ) {
        super.onRequestPermissionsResult(code, permissions, grants)
        val granted = grants.isNotEmpty() && grants.all { it == PackageManager.PERMISSION_GRANTED }
        if (code == 10 && granted) showCapture()
        else if (code == 10) { recordingAt = null; message("Camera and microphone permissions are needed to record") }
        if (code == 11 && granted) voiceover()
        else if (code == 11) message("Microphone permission is needed for voiceover")
    }

    internal fun recordingInsertion(): Double? = recordingAt?.takeIf { it.project == editor.project.id }?.at

    fun recordHere() {
        if (editor.move != null) return
        recordingAt = MediaImport(editor.project.id, editor.cursor, false, false, editor.project.duplicate())
        showCapture()
    }

    fun voiceover() {
        playback.pause(); panels.close()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11); return
        }
        if (voiceDraft != null) return
        val expected = editor.project.duplicate()
        val position = editor.cursor
        voiceDraft = VoiceDraft(this, { file ->
            check(editor.project == expected) { "The project changed. Close this draft and choose Voiceover here again." }
            val target = File(store.media, "voice-${uid()}.wav")
            file.copyTo(target)
            val receipt = store.beginCapture(editor.project, target, emptyList(), position, true)
            val clip = ProjectStore.inspect(target).copy(id = receipt.id, group = receipt.id,
                occurrence = receipt.id, video = false, linked = false)
            store.finishCapture(receipt, clip)
            store.registerMedia(expected.id, target.path)
            editor.addLayer(clip, position)
            if (saveProject()) store.acknowledgeCapture(receipt)
        }, { voiceDraft = null }).also { it.show() }
    }

    fun saveProject(): Boolean {
        try {
            store.save(editor)
            saveWarning?.visibility = View.GONE
            return true
        } catch (e: Exception) {
            if (saveWarning == null && ::root.isInitialized) {
                saveWarning = TextView(this).apply {
                    text = "Changes not saved · Retry"
                    contentDescription = text
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(130, 35, 35))
                    setPadding(24, 16, 24, 16)
                    setOnClickListener { saveProject() }
                }
                root.addView(saveWarning, FrameLayout.LayoutParams(-2, -2,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            }
            saveWarning?.visibility = View.VISIBLE
            saveWarning?.bringToFront()
            android.util.Log.w("MyRec", "Cannot save project", e)
            message("Cannot save changes. They are kept in memory; tap Retry.")
            return false
        }
    }

    fun retryProjects() {
        store.problems.clear()
        val saved = store.currentSession()
        if (store.problems.isEmpty()) editor.load(saved.project, saved)
        panels.projects()
    }

    fun duplicateProject(project: Project) {
        if (!saveProject()) return
        panels.progress("Duplicating project…", 0f, null)
        worker.execute {
            try {
                store.duplicate(project) { progress -> ui.post { panels.updateProgress(progress) } }
                ui.post { panels.close(); panels.projects() }
            } catch (e: Exception) {
                ui.post { panels.close(); message(e.message ?: "Cannot duplicate project") }
            }
        }
    }

    fun newProject() {
        playback.pause()
        if (capture?.recording == true) capture?.stop()
        if (!saveProject()) return
        editor.load(Project(name = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date())))
        editorView.bitmap = null
        editorView.invalidate()
    }

    fun openProject(project: Project) {
        playback.pause()
        if (project.id == editor.project.id) { showEditor(); return }
        if (!saveProject()) return
        try {
            val saved = store.open(project.id)
            editor.load(saved.project, saved)
            indexOriginals(saved.project.id)
            showEditor()
        } catch (e: Exception) { message(e.message ?: "Cannot open project") }
    }

    private fun projects() {
        if (editor.move != null) editor.finishMove(true)
        playback.pause()
        panels.projects()
    }

    fun choose(
        title: String,
        items: List<String>,
        checked: Int = -1,
        anchor: View? = null,
        selected: (Int) -> Unit,
    ) {
        choices?.dismiss()
        val landscape = root.width > root.height
        val scale =
            minOf(
                root.width / (if (landscape) 640f else 294f),
                root.height / (if (landscape) 294f else 640f),
            )
        fun px(n: Float) = (n * scale).toInt()
        val list =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(5f), px(6f), px(5f), px(6f))
                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(
                            if (title == "Palette") Color.argb(240, 55, 55, 57)
                            else Color.rgb(37, 37, 39)
                        )
                        cornerRadius = px(if (title == "Palette") 24f else 14f).toFloat()
                    }
            }
        val scroll =
            ScrollView(this).apply {
                addView(list)
                isVerticalScrollBarEnabled = false
            }
        val width =
            px(
                if (title == "Palette") 185f
                else if (title == "Camera") 187f else if (title == "Microphone") 180f else 165f
            )
        val rowHeight = if (title == "Camera") 32f else if (title == "Palette") 31f else 29f
        val height = minOf(px(items.size * rowHeight + 12), root.height - px(80f))
        val popup =
            PopupWindow(scroll, width, height, true).apply {
                elevation = px(10f).toFloat()
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                isOutsideTouchable = true
            }
        choices = popup
        popup.setOnDismissListener { if (choices === popup) choices = null }
        for ((i, item) in items.withIndex()) {
            val label =
                TextView(this).apply {
                    text =
                        if (i == checked && title != "Camera")
                            android.text.SpannableString("✓   $item").apply {
                                setSpan(
                                    android.text.style.ForegroundColorSpan(Color.rgb(225, 166, 99)),
                                    0,
                                    1,
                                    0,
                                )
                            }
                        else item
                    setTextColor(Color.WHITE)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 12f * scale)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        px(if (title == "Palette") if (i == checked) 23f else 17f else 10f),
                        0,
                        px(6f),
                        0,
                    )
                    contentDescription = item
                    setOnClickListener {
                        popup.dismiss()
                        selected(i)
                    }
                }
            if (title == "Camera") {
                val item =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(px(12f), 0, px(8f), 0)
                        contentDescription = items[i]
                        setOnClickListener {
                            popup.dismiss()
                            selected(i)
                        }
                    }
                item.addView(
                    Glyph(this, if (i == checked) "Check" else "Camera", Panels.RED),
                    LinearLayout.LayoutParams(px(18f), px(18f)),
                )
                label.contentDescription = null
                label.setPadding(px(9f), 0, 0, 0)
                item.addView(label, LinearLayout.LayoutParams(0, -1, 1f))
                list.addView(item, LinearLayout.LayoutParams(-1, px(rowHeight)))
            } else list.addView(label, LinearLayout.LayoutParams(-1, px(rowHeight)))
        }
        val location = IntArray(2)
        anchor?.getLocationInWindow(location)
        val x =
            when (title) {
                "Timeline scale" -> root.width - width - px(if (landscape) 46f else 3f)
                "Playback speed",
                "Microphone" -> px(if (landscape) 46f else 8f)
                "Camera" ->
                    capture?.controlBounds("camera")?.left?.toInt()
                        ?: px(if (landscape) 124f else 62f)
                "Palette" ->
                    if (anchor != null) location[0] + anchor.width - width
                    else if (landscape) px(399f) else root.width - width - px(12f)
                else -> (root.width - width) / 2
            }
        val y =
            when (title) {
                "Timeline scale" -> px(if (landscape) 20f else 280f)
                "Playback speed" -> px(if (landscape) 25f else 230f)
                "Microphone" -> px(if (landscape) 33f else 62f)
                "Camera" ->
                    (capture?.controlBounds("camera")?.bottom?.toInt()
                        ?: (root.height - px(if (landscape) 40f else 119f))) - height
                "Palette" ->
                    if (anchor != null) location[1] + anchor.height - height
                    else px(if (landscape) 62f else 300f)
                else -> (root.height - height) / 2
            }
        if (title == "Palette" || title == "Camera") {
            val backdrop =
                android.graphics.Bitmap.createBitmap(
                    maxOf(1, width / 2),
                    maxOf(1, height / 2),
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            val canvas = android.graphics.Canvas(backdrop)
            canvas.scale(.5f, .5f)
            canvas.translate(-x.toFloat(), -y.toFloat())
            root.draw(canvas)
            if (title == "Camera") capture?.drawCamera(canvas)
            val frame =
                FrameLayout(this).apply {
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(Color.rgb(61, 61, 63))
                            cornerRadius = px(24f).toFloat()
                        }
                    clipToOutline = true
                }
            frame.addView(
                ImageView(this).apply {
                    setImageBitmap(backdrop)
                    scaleType = ImageView.ScaleType.FIT_XY
                    if (Build.VERSION.SDK_INT >= 31)
                        setRenderEffect(
                            android.graphics.RenderEffect.createBlurEffect(
                                px(12f).toFloat(),
                                px(12f).toFloat(),
                                android.graphics.Shader.TileMode.CLAMP,
                            )
                        )
                },
                FrameLayout.LayoutParams(-1, -1),
            )
            frame.addView(
                View(this).apply {
                    setBackgroundColor(
                        if (title == "Camera") Color.argb(175, 35, 35, 35)
                        else Color.argb(225, 61, 61, 63)
                    )
                },
                FrameLayout.LayoutParams(-1, -1),
            )
            list.background = null
            frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            popup.contentView = frame
        }
        popup.showAtLocation(
            root,
            Gravity.TOP or Gravity.LEFT,
            x.coerceAtLeast(0),
            y.coerceAtLeast(0),
        )
    }

    fun message(text: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post { message(text) }
            return
        }
        if (lastMessage == text && SystemClock.elapsedRealtime() - lastMessageAt < 5000) return
        lastMessage = text
        lastMessageAt = SystemClock.elapsedRealtime()
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    fun importVideo(layer: Boolean = false) {
        pendingImport = MediaImport(editor.project.id, editor.cursor, layer, false, editor.project.duplicate())
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
            20,
        )
    }

    fun importAudio(at: Double) {
        pendingImport = MediaImport(editor.project.id, at, true, true)
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            22,
        )
    }

    fun importProject() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "application/zip", "application/x-zip-compressed"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }, 23)
    }

    private fun documentName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Imported project"
    } catch (_: Exception) { "Imported project" }

    fun saveDocument(file: File, type: String, name: String = file.name) {
        if (!file.isFile) { message("The original file is missing"); return }
        pendingSave = file
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            this.type = type
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, name.replace('/', '-').replace('\\', '-'))
        }, 30)
    }

    fun exportProject(project: Project) {
        if (!saveProject()) return
        val cancelled = AtomicBoolean(false)
        panels.progress("Preparing project ZIP…", 0f) { cancelled.set(true) }
        worker.execute {
            val file = File(cacheDir, "MyRec-${uid()}.zip")
            try {
                ProjectArchive.export(store, project, file, { cancelled.get() }) { value ->
                    ui.post { panels.updateProgress(value) }
                }
                ui.post {
                    panels.close()
                    saveDocument(file, "application/zip", "${project.name.take(80)}.zip")
                }
            } catch (e: Exception) {
                ui.post { panels.close(); message(e.message ?: "Cannot archive project") }
            }
        }
    }

    private fun restoreProject(uri: Uri) {
        if (!saveProject()) return
        val cancelled = AtomicBoolean(false)
        panels.progress("Restoring project…", 0f) { cancelled.set(true) }
        worker.execute {
            val archive = File(cacheDir, ".restore-${uid()}.zip")
            try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (cancelled.get()) throw java.io.InterruptedIOException("Restore cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            check(cacheDir.usableSpace > 64L * 1024 * 1024) { "Not enough free storage" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                val result = ProjectArchive.restore(store, archive, { cancelled.get() }) { value ->
                    ui.post { panels.updateProgress(value) }
                }
                ui.post {
                    panels.close()
                    openProject(result.project)
                    if (result.missing.isNotEmpty()) message("Project restored with missing media. All editing history was kept.")
                }
            } catch (e: Exception) {
                ui.post { panels.close(); message(e.message ?: "Cannot restore project") }
            } finally { archive.delete() }
        }
    }

    private fun indexOriginals(project: String) {
        if (!File(store.root, "$project.json").isFile) return
        worker.execute {
            try { store.indexOriginals(project) }
            catch (e: Exception) {
                store.problems["checksum:$project"] = "Source verification could not finish: ${e.message}"
            }
        }
    }

    fun relinkOriginal(project: Project, path: String) {
        if (!saveProject()) return
        try {
            check(store.trustedHash(project.id, path) != null) {
                "No checksum was recorded before this original went missing. Its identity cannot be verified."
            }
            pendingRepair = MediaRepair(project.duplicate(), path)
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 31)
        } catch (e: Exception) { message(e.message ?: "Cannot relink this original") }
    }

    private fun repairOriginal(repair: MediaRepair, uri: Uri) {
        val cancelled = AtomicBoolean(false)
        panels.progress("Verifying original…", 0f) { cancelled.set(true) }
        worker.execute {
            val file = File(cacheDir, ".repair-${uid()}")
            try {
                contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (cancelled.get()) throw java.io.InterruptedIOException("Repair cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(cacheDir.usableSpace > 64L * 1024 * 1024) { "Not enough free storage" }
                        output.write(buffer, 0, count)
                    }
                } }
                val path = store.relink(repair.project, repair.path, file, { cancelled.get() })
                ui.post {
                    panels.close()
                    if (editor.project.id == repair.project.id) {
                        // Edits made while the source was being copied stay in
                        // the live session; only the source path changes.
                        val cursor = editor.cursor
                        val selected = editor.selected.toSet()
                        val saved = ProjectAssets.remap(editor.session(), editor.project.id, editor.project.name) {
                            if (it == repair.path) path else it
                        }
                        editor.load(saved.project, saved)
                        editor.cursor = cursor.coerceIn(0.0, editor.project.duration)
                        editor.selected.addAll(selected)
                        playback.refresh()
                    }
                    message("Original verified and relinked")
                }
            } catch (e: Exception) {
                ui.post { panels.close(); message(e.message ?: "Cannot relink original") }
            } finally { file.delete() }
        }
    }

    fun reviewOriginal(source: Clip) {
        if (!File(source.path).isFile) { message("This original file is missing"); return }
        playback.pause()
        val player = VideoView(this)
        val controls = MediaController(this)
        player.setMediaController(controls)
        player.setVideoPath(source.path)
        val dialog = Dialog(this).apply {
            setTitle(File(source.path).name)
            setContentView(player)
            setOnDismissListener { player.stopPlayback() }
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .9f).toInt(),
            (resources.displayMetrics.heightPixels * .7f).toInt())
        player.setOnPreparedListener { player.start() }
        player.setOnErrorListener { _, _, _ -> dialog.dismiss(); message("Cannot play this original"); true }
    }

    private var lutCallback: ((String, String) -> Unit)? = null

    fun importLut(callback: (String, String) -> Unit) {
        lutCallback = callback
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, 32)
    }

    fun importImage(callback: (String) -> Unit) {
        imageCallback = callback
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            21,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 32) {
            val callback = lutCallback
            lutCallback = null
            val uri = data?.data
            if (resultCode == RESULT_OK && callback != null && uri != null) {
                val project = editor.project.id
                worker.execute {
                    try {
                        val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                check(output.size() + n <= CubeLut.MAX_BYTES) { "The LUT is too large" }
                                output.write(buffer, 0, n)
                            }
                            output.toByteArray()
                        }
                        val (file, cube) = LutAssets.ingest(bytes, store.media)
                        store.registerMedia(project, file.path)
                        val name = cube.title?.takeIf { it.isNotBlank() }
                            ?: documentName(uri).substringBeforeLast('.').take(120)
                        ui.post { if (editor.project.id == project) callback(file.path, name) }
                    } catch (e: Exception) { message(e.message ?: "Cannot import LUT") }
                }
            }
            return
        }
        if (requestCode == 31) {
            val repair = pendingRepair
            pendingRepair = null
            if (resultCode == RESULT_OK && repair != null) data?.data?.let { repairOriginal(repair, it) }
            return
        }
        if (requestCode == 30) {
            val file = pendingSave
            pendingSave = null
            val uri = data?.data
            if (resultCode == RESULT_OK && file != null && uri != null) worker.execute {
                try {
                    contentResolver.openOutputStream(uri, "wt")!!.use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                    message("Saved ${file.name}")
                } catch (e: Exception) { message(e.message ?: "Cannot save file") }
            }
            return
        }
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == 20) importAsProject = false
            if (requestCode == 20 || requestCode == 22) pendingImport = null
            return
        }
        if (requestCode == 23) data.data?.let { uri ->
            val type = contentResolver.getType(uri)
            if (type in listOf("application/zip", "application/x-zip-compressed") ||
                documentName(uri).endsWith(".zip", true)) restoreProject(uri)
            else {
                if (!saveProject()) return
                val previous = editor.project.id
                newProject()
                if (editor.project.id != previous) importUri(uri, false)
            }
        }
        if (requestCode == 20) {
            val asProject = importAsProject
            importAsProject = false
            val target = pendingImport
            pendingImport = null
            val picked = data.clipData
            val uris = if (picked == null) listOfNotNull(data.data)
                else (0 until picked.itemCount).map { picked.getItemAt(it).uri }
            reviewImport(uris, target, asProject)
        }
        if (requestCode == 22) {
            val target = pendingImport
            pendingImport = null
            data.data?.let { importUri(it, false, target) }
        }
        if (requestCode == 21) data.data?.let { importUri(it, true) }
    }

    private fun reviewImport(uris: List<Uri>, target: MediaImport?, asProject: Boolean) {
        if (uris.isEmpty()) return
        fun start(ordered: List<Uri>) {
            var destination = target ?: MediaImport(editor.project.id, editor.cursor, false, false, editor.project.duplicate())
            if (asProject) {
                val previous = editor.project.id
                newProject()
                if (editor.project.id == previous) return
                destination = destination.copy(project = editor.project.id, at = 0.0, base = editor.project.duplicate())
            }
            importBatch(ordered, destination)
        }
        if (uris.size == 1) { start(uris); return }
        val ordered = uris.toMutableList()
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        fun update() {
            list.removeAllViews()
            for ((index, uri) in ordered.withIndex()) {
                val row = LinearLayout(this)
                row.addView(TextView(this).apply {
                    text = "${index + 1}. ${documentName(uri)}"; setPadding(16, 12, 8, 12)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                for (direction in listOf(-1, 1)) row.addView(Button(this).apply {
                    text = if (direction < 0) "↑" else "↓"
                    contentDescription = "Move item ${index + 1} ${if (direction < 0) "up" else "down"}"
                    isEnabled = index + direction in ordered.indices
                    setOnClickListener {
                        val item = ordered.removeAt(index); ordered.add(index + direction, item); update()
                    }
                }, LinearLayout.LayoutParams(64, -2))
                list.addView(row)
            }
        }
        update()
        importDialog = AlertDialog.Builder(this).setTitle("Import order").setView(scroll)
            .setNegativeButton("Cancel", null).setPositiveButton("Import") { _, _ -> start(ordered.toList()) }
            .create().also { dialog -> dialog.setOnDismissListener { importDialog = null }; dialog.show() }
    }

    private fun importBatch(uris: List<Uri>, target: MediaImport) {
        val expected = target.base ?: editor.project.duplicate()
        panels.progress("Importing ${uris.size} items…", 0f, null)
        worker.execute {
            val files = mutableListOf<File>()
            try {
                val clips = uris.mapIndexed { index, uri ->
                    val ext = android.webkit.MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(contentResolver.getType(uri)) ?: "media"
                    val file = File(store.media, "import-${uid()}.$ext")
                    files.add(file)
                    contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                    val clip = ProjectStore.inspect(file)
                    ui.post { panels.progress("Importing ${uris.size} items…", (index + 1f) / uris.size, null) }
                    clip
                }
                val shape = clips.firstOrNull { it.video }?.let { ProjectStore.shape(it.path) }
                ui.post {
                    panels.close()
                    if (editor.project != expected) {
                        files.forEach { it.delete() }
                        message("Import cancelled because the project changed")
                    } else try {
                        for (file in files) store.registerMedia(target.project, file.path)
                        editor.insertBatch(clips, target.at, target.layer, shape)
                        playback.refresh()
                    } catch (e: Exception) { message(e.message ?: "Cannot import these items") }
                }
            } catch (e: Exception) {
                files.forEach { it.delete() }
                ui.post { panels.close(); message(e.message ?: "Cannot import these items") }
            }
        }
    }

    private fun importUri(uri: Uri, image: Boolean, target: MediaImport? = null) {
        val project = target?.project ?: editor.project.id
        panels.progress("Importing…", 0f, null)
        worker.execute {
            var file: File? = null
            try {
                val ext = android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(contentResolver.getType(uri)) ?: "media"
                val imported = File(store.media, "import-${uid()}.$ext")
                file = imported
                contentResolver.openInputStream(uri)!!.use { input ->
                    imported.outputStream().use { input.copyTo(it) }
                }
                store.registerMedia(project, imported.absolutePath)
                val clip = if (!image) ProjectStore.inspect(imported) else null
                if (target?.audio == true) {
                    require(clip?.audio == true) { "This file has no playable audio" }
                    clip.video = false
                    clip.linked = false
                }
                ui.post {
                    panels.close()
                    if (image) {
                        imageCallback?.invoke(imported.absolutePath)
                        imageCallback = null
                    } else if (clip != null) {
                        if (project != editor.project.id) {
                            imported.delete()
                            message("Import cancelled because the project changed")
                            return@post
                        }
                        if (clip.video && editor.project.clips.none { it.video }) setProjectFormat(clip)
                        if (target?.layer == true) editor.addLayer(clip, target.at)
                        else editor.insert(clip)
                        editor.scale = if (editor.project.duration < 10) 10.0 else editor.scale
                        playback.refresh()
                    }
                }
            } catch (e: Exception) {
                file?.delete()
                ui.post {
                    panels.close()
                    message(e.message ?: "Cannot import this file")
                }
            }
        }
    }

    private fun setProjectFormat(clip: Clip) {
        val (w, h) = ProjectStore.shape(clip.path)
        editor.project.width = w
        editor.project.height = h
        playback.frameWidth = if (w >= h) 768 else 432
        playback.frameHeight = (playback.frameWidth.toFloat() * h / w).toInt()
    }

    fun export(width: Int, height: Int, color: ExportColor = ExportColor.SDR) {
        if (exporting != null || editor.project.duration <= 0) return
        playback.pause()
        val snapshot = editor.project.duplicate()
        val exporter = Export(audioCache)
        exporting = exporter
        val file = File(cacheDir, "MyRec-${System.currentTimeMillis()}.mp4")
        panels.progress("Exporting", 0f) { exporter.cancelled = true }
        worker.execute {
            try {
                exporter.run(snapshot, file, width, height, color) { progress ->
                    ui.post { panels.updateProgress(progress) }
                }
                val values =
                    ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyRec")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                val uri =
                    contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("Cannot create exported video")
                try {
                    contentResolver.openOutputStream(uri)!!.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    if (Build.VERSION.SDK_INT >= 29)
                        contentResolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                            null,
                            null,
                        )
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
                ui.post {
                    panels.close()
                    message("Saved to Movies/MyRec")
                    choose("Video exported", listOf("Play video", "Share video", "Done")) {
                        when (it) {
                            0 ->
                                startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(uri, "video/mp4")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                )
                            1 ->
                                startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND)
                                            .setType("video/mp4")
                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                        "Share video",
                                    )
                                )
                        }
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    panels.close()
                    message(e.message ?: "Export failed")
                }
            } finally {
                file.delete()
                exporting = null
            }
        }
    }

    fun tryStrobe(seconds: Float) { capture?.tryStrobe(seconds) }

    fun captureEffects(current: List<Effect>, done: (List<Effect>) -> Unit) {
        panels.captureEffects(current, done)
    }

    fun prepareStudio(done: (Boolean) -> Unit) {
        if (Studio.ready) {
            done(true)
            return
        }
        Studio.downloadCancelled = false
        panels.progress("Preparing studio audio", 0f) { Studio.downloadCancelled = true }
        worker.execute {
            var success = false
            try {
                Studio.download { progress -> ui.post { panels.updateProgress(progress) } }
                success = true
            } catch (error: Exception) {
                if (!Studio.downloadCancelled)
                    ui.post { message(error.message ?: "Cannot prepare studio audio") }
            }
            ui.post {
                if (!isFinishing && !isDestroyed) {
                    panels.close()
                    done(success)
                }
            }
        }
    }

    fun prepareAudioModel(name: String, done: (Boolean) -> Unit) {
        if (AudioModels.kind(name) == 2) {
            prepareStudio(done)
            return
        }
        if (AudioModels.ready(name)) {
            done(true)
            return
        }
        AudioModels.cancelled = false
        val size = AudioModels.bytes(name) / 1000000.0
        panels.progress("Downloading $name · %.1f MB".format(java.util.Locale.US, size), 0f) {
            AudioModels.cancelled = true
        }
        worker.execute {
            var success = false
            try {
                AudioModels.download(name) { value -> ui.post { panels.updateProgress(value) } }
                success = true
            } catch (error: Exception) {
                if (!AudioModels.cancelled)
                    ui.post { message(error.message ?: "Cannot prepare audio model") }
            }
            ui.post {
                if (!isFinishing && !isDestroyed) {
                    panels.close()
                    done(success)
                }
            }
        }
    }

    private fun updateBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        choices?.dismiss()
        editorView.invalidate()
        updateBars()
        root.addOnLayoutChangeListener(
            object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    root.removeOnLayoutChangeListener(this)
                    panels.relayout()
                }
            }
        )
        root.requestLayout()
    }

    override fun onResume() {
        super.onResume()
        if (captureMode) capture?.resumeCamera()
    }

    override fun onPause() {
        voiceDraft?.pause()
        super.onPause()
        playback.pause()
        capture?.suspendCamera()
        if (::editor.isInitialized) saveProject()
    }

    @Deprecated("Platform back support")
    override fun onBackPressed() {
        when {
            choices?.isShowing == true -> choices?.dismiss()
            panels.isOpen -> panels.cancel()
            editor.move != null -> editor.finishMove(true)
            editorView.fullscreen -> {
                editorView.fullscreen = false
                editorView.invalidate()
            }
            captureMode -> {
                if (capture?.recording == true) capture?.stop()
                capture?.suspendCamera()
                showEditor()
            }
            else -> panels.projects()
        }
    }

    override fun onDestroy() {
        voiceDraft?.close()
        importDialog?.dismiss()
        linkDialog?.dismiss()
        choices?.dismiss()
        playback.close()
        mediaIndex.close()
        capture?.destroy()
        exporting?.cancelled = true
        worker.shutdown()
        super.onDestroy()
    }
}
