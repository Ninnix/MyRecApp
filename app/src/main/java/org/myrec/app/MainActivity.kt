package org.myrec.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
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
    companion object {
        @Volatile internal var instrumented=false
    }
    internal lateinit var toolLibrary: SharedPreferences
        private set
    internal lateinit var toolLibraryDirectory: File
        private set
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

    internal lateinit var captureSettings: android.content.SharedPreferences
        private set
    internal lateinit var movieExports: MovieExports
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
    private var pendingOriginals: List<File>? = null
    internal var originalReview: OriginalReview? = null
        private set
    private data class MediaRepair(val project: Project, val path: String)
    private var pendingRepair: MediaRepair? = null
    private var pendingTimeline: Uri? = null
    private var imageCallback: ((String) -> Unit)? = null
    private var effectMediaCallback: ((String) -> Unit)? = null
    private var effectMediaProject: String? = null
    private var effectImageOnly=false
    fun importEffectMedia(imageOnly: Boolean,callback: (String)->Unit) {
        effectMediaCallback=callback; effectMediaProject=editor.project.id;effectImageOnly=imageOnly
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type=if (imageOnly) "image/*" else "*/*"
            if (!imageOnly) putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/*","video/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        },35) } catch (_: android.content.ActivityNotFoundException) { effectMediaCallback=null; message("No media picker is available") }
    }
    private var importAsProject = false
    private var exporting: Export? = null
    private var exportCanPause = false
    private data class MovieRequest(val width: Int, val height: Int, val color: ExportColor, val fps: Int?)
    private var moviePermission: MovieRequest? = null
    internal var choices: PopupWindow? = null
        private set
    internal var linkDialog: AlertDialog? = null
        private set
    internal var importDialog: AlertDialog? = null
        private set
    internal var lastMessage = ""
        private set
    private var lastMessageAt = 0L
    private var saveWarning: TextView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pendingTimeline=state?.getString("timelineMediaDocument")?.let(Uri::parse)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        updateBars()
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        val testSession =
            intent.getBooleanExtra("testSession", false) &&
                (instrumented || applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val testSessionId =
            intent.getStringExtra("testSessionId")?.takeIf { it.matches(Regex("[a-zA-Z0-9-]+")) }
                ?: "shared"
        captureSettings = getSharedPreferences(if (testSession) "capture-test-$testSessionId" else "capture", MODE_PRIVATE)
        movieExports = MovieExports(if (testSession) File(cacheDir, "test-exports/$testSessionId") else File(filesDir, "movie-exports"))
        toolLibrary = getSharedPreferences(if (testSession) "editor-library-test-$testSessionId" else "editor-library", MODE_PRIVATE)
        toolLibraryDirectory = if (testSession) File(cacheDir, "test-libraries/$testSessionId") else File(filesDir, "editor-library")
        fontLibrary=FontLibrary(File(toolLibraryDirectory,"fonts"))
        store =
            ProjectStore(
                if (testSession) File(cacheDir, "test-projects/$testSessionId")
                else File(filesDir, "projects")
            )
        store.cleanInterruptedSaves()
        store.cleanTransfers()
        if (store.needsRecordingRemux()) {
            setContentView(TextView(this).apply {
                text="Recovering recording…"
                gravity=Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            worker.execute {
                store.recoverCaptures()
                ui.post { if (!isFinishing && !isDestroyed) finishStartup(testSession,testSessionId) }
            }
        } else {
            store.recoverCaptures()
            finishStartup(testSession,testSessionId)
        }
    }

    internal val startupComplete get() = ::editor.isInitialized

    private fun finishStartup(testSession: Boolean,testSessionId: String) {
        val saved = currentProjectSession()
        editor = Editor(saved.project).apply { load(saved.project, saved) }
        val editorSettings = getSharedPreferences(if (testSession) "editor-test-$testSessionId" else "editor", MODE_PRIVATE)
        editor.snap = editorSettings.getBoolean("snapToCuts", true)
        Studio.directory = File(getExternalFilesDir(null), "models/studio-lava-v1")
        Studio.cacheDirectory = File(cacheDir, "studio-audio")
        AudioModels.configure(this, File(getExternalFilesDir(null), "models"))
        MediaThermalGate.configure(this)
        Person.configure(this)
        TitleStyle.configure(this)
        audioCache = AudioCache(File(cacheDir, "audio"))
        NightRestore.directory = File(cacheDir,"night-restoration")
        Stabilizer.directory = File(cacheDir, "stabilization")
        StabilizationRenderer.directory = if (testSession) File(cacheDir,"test-stabilization/$testSessionId") else File(cacheDir,"stabilization-paths")
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        mediaIndex = MediaIndex(audioCache) { editorView.invalidate() }
        mediaIndex.editChanged(editor.project)
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
        editorView.onInspection = { if (!panels.isOpen) playback.inspect(it) }
        editorView.onCopyFrame = ::copyFrame
        root.addView(editorView, FrameLayout.LayoutParams(-1, -1))
        editor.onChange = {
            saveProject()
            mediaIndex.editChanged(editor.project)
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
            if (uri != null) importBatch(listOf(uri),MediaImport(editor.project.id,editor.cursor,false,false))
        }
    }

    private fun action(id: String) {
        if (id.startsWith("censor:")) { panels.censor(id.substringAfter(':')); return }
        if (id.startsWith("transition:")) {
            editor.project.transitions.firstOrNull { it.id == id.substringAfter(':') }?.let { panels.transition(it.copy()) }
            return
        }
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
            "bookmarks" -> panels.bookmarks()
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
                try { editor.undo() } catch (error: Exception) { message(error.message ?: "Cannot read this undo state. The edit has been kept.") }
            }
            "redo" -> {
                playback.pause()
                try { editor.redo() } catch (error: Exception) { message(error.message ?: "Cannot read this redo state. The edit has been kept.") }
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

    private fun copyFrame(picture: Bitmap) {
        val frame=picture.copy(Bitmap.Config.ARGB_8888,false) ?: return
        worker.execute {
            val result=runCatching {
                val folder=SharedImages.directory(this)
                val target=File(folder,"clipboard-${uid()}_MyRec frame.png")
                try {
                    target.outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG,100,it)) }
                    SharedImages.uri(this,target)
                } catch (failure: Exception) { target.delete();throw failure }
            }
            frame.recycle()
            ui.post {
                if (!isDestroyed && !isFinishing) result.fold({ uri ->
                    val clipboard=getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newUri(contentResolver,"MyRec frame",uri))
                    if (Build.VERSION.SDK_INT<33) message("Frame copied")
                },{ message(it.message ?: "Cannot copy this frame") })
            }
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
        if (code == 12) {
            val request = moviePermission; moviePermission = null
            if (granted && request != null) export(request.width, request.height, request.color, request.fps)
            else message("Storage permission is needed to save a movie on Android 9")
        }
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
        val saved = currentProjectSession()
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

    internal fun currentProjectSession() = store.currentSession(::datedProject)

    private fun datedProject(): Project {
        val now = Date()
        val locale = resources.configuration.locales[0]
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
        val date = SimpleDateFormat(pattern, locale).format(now)
        val time = android.text.format.DateFormat.getTimeFormat(this).format(now)
        return Project(name = "$date · $time")
    }

    fun newProject() {
        playback.pause()
        if (capture?.recording == true) capture?.stop()
        if (!saveProject()) return
        editor.load(datedProject())
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
        } catch (e: Exception) {
            android.util.Log.w("MyRec","Cannot open project",e)
            message(e.message ?: "Cannot open project")
        }
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
        disabled: Set<Int> = emptySet(),
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
                    isEnabled = i !in disabled
                    alpha = if (isEnabled) 1f else .4f
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
                        isEnabled = i !in disabled
                        alpha = if (isEnabled) 1f else .4f
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
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*","application/zip","application/x-zip-compressed","application/json","application/octet-stream"))
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

    fun saveOriginals(files: List<File>) {
        if (files.isEmpty() || files.any { !it.isFile }) { message("An original is missing"); return }
        pendingOriginals = files.toList()
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),34)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingOriginals = null; message("No folder picker is available")
        }
    }

    private fun copyOriginals(files: List<File>, tree: Uri) {
        val cancelled = AtomicBoolean(false)
        panels.progress("Saving original files…",0f) { cancelled.set(true) }
        worker.execute {
            var folder: Uri? = null
            try {
                val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                    android.provider.DocumentsContract.getTreeDocumentId(tree))
                folder = android.provider.DocumentsContract.createDocument(contentResolver,parent,
                    android.provider.DocumentsContract.Document.MIME_TYPE_DIR,"MyRec originals ${uid().take(8)}")
                    ?: error("Cannot create the originals folder")
                OriginalMedia.copy(files,OriginalMedia.documents(contentResolver,folder),{ cancelled.get() }) {
                    value -> ui.post { if (!isDestroyed) panels.updateProgress(value) }
                }
                if (cancelled.get()) throw java.io.InterruptedIOException("Copy cancelled")
                ui.post { if (!isDestroyed) { panels.close(); message("Saved ${files.size} original files") } }
            } catch (failure: Exception) {
                folder?.let { owned -> try { android.provider.DocumentsContract.deleteDocument(contentResolver,owned) }
                    catch (cleanup: Exception) { failure.addSuppressed(cleanup) } }
                val detail = if (failure.suppressed.isEmpty()) failure.message ?: "Cannot save originals"
                    else "${failure.message}. Some incomplete copies could not be removed."
                ui.post { if (!isDestroyed) { panels.close(); message(detail) } }
            }
        }
    }

    fun recoverOriginals(files: List<File>, title: String = "Recordings · Recovered") {
        val cancelled = AtomicBoolean(false)
        panels.progress("Creating recovered project…",0f) { cancelled.set(true) }
        worker.execute {
            try {
                val result = RecoveredProject.create(store,files,title,{ cancelled.get() }) {
                    value -> ui.post { if (!isDestroyed) panels.updateProgress(value) }
                }
                ui.post { if (!isDestroyed) {
                    panels.projects()
                    message("Created ${result.project.name} with ${result.project.clips.size} takes"+
                        if (result.skipped > 0) ". ${result.skipped} unreadable files remain with the originals." else ".")
                } }
            } catch (failure: Exception) {
                ui.post { if (!isDestroyed) { panels.close(); message(failure.message ?: "Cannot create a recovered project") } }
            }
        }
    }

    fun exportProject(project: Project,yourec: Boolean=false) {
        if (!saveProject()) return
        val cancelled = AtomicBoolean(false)
        panels.progress("Preparing project ZIP…", 0f) { cancelled.set(true) }
        worker.execute {
            val file = File(cacheDir, "MyRec-${uid()}.zip")
            try {
                val progress: (Float)->Unit={ value -> ui.post { panels.updateProgress(value) } }
                if (yourec) YouRecArchive.export(store,project,file,{ cancelled.get() },progress)
                else ProjectArchive.export(store,project,file,{ cancelled.get() },progress)
                ui.post {
                    panels.close()
                    saveDocument(file, "application/zip", "${project.name.take(80)}.zip")
                }
            } catch (e: Exception) {
                ui.post { panels.close(); message(e.message ?: "Cannot archive project") }
            }
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        pendingTimeline?.let { state.putString("timelineMediaDocument",it.toString()) }
        super.onSaveInstanceState(state)
    }

    private fun restoreProject(uri: Uri,timeline: Boolean=false,mediaTree: Uri?=null) {
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
                val progress: (Float)->Unit={ value -> ui.post { panels.updateProgress(value) } }
                val media=mediaTree?.let { TimelineMedia(contentResolver,it) { cancelled.get() } }
                val result=if (timeline) TimelineImport.restore(store,archive,{ cancelled.get() },progress) { media?.resolve(it) }
                    else ProjectArchive.restore(store,archive,{ cancelled.get() },progress)
                ui.post {
                    panels.close()
                    openProject(result.project)
                    if (result.missing.isNotEmpty()) message(if (timeline) "Timeline imported with missing media. Import again with its media folder."
                        else "Project restored with missing media. All editing history was kept.")
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

    fun reviewOriginal(source: Clip, project: Project? = null) {
        if (!File(source.path).isFile) { message("This original file is missing"); return }
        playback.pause()
        originalReview?.dismiss()
        val review = OriginalReview(this,source.duplicate(),project?.duplicate())
        review.onClosed = { if (originalReview === review) originalReview = null }
        originalReview = review; review.show()
    }

    fun reviewOriginalFile(file: File, owner: View) {
        worker.execute {
            try {
                val source = ProjectStore.inspect(file)
                ui.post { if (!isDestroyed && owner.isAttachedToWindow) reviewOriginal(source) }
            } catch (_: Exception) {
                ui.post { if (!isDestroyed && owner.isAttachedToWindow) message("Cannot play this file. You can save the unchanged original.") }
            }
        }
    }

    fun insertOriginalRange(project: Project, source: Clip, start: Double, end: Double): Boolean = try {
        check(ProjectAssets.clips(store.open(project.id)).any { File(it.path).canonicalFile == File(source.path).canonicalFile }) { "This original no longer belongs to the project" }
        val clip = OriginalRange.clip(source,start,end,project.fps)
        openProject(project)
        if (editor.project.id != project.id) false
        else { editor.insertBatch(listOf(clip),editor.project.duration); panels.close(); true }
    } catch (failure: Exception) { message(failure.message ?: "Cannot insert the original range"); false }

    private var lutCallback: ((String, String) -> Unit)? = null
    internal lateinit var fontLibrary: FontLibrary
    private var fontLibraryDone: (() -> Unit)?=null
    private var fontCallback: ((String, String) -> Unit)? = null

    fun importFonts(done: ()->Unit) {
        fontCallback=null;fontLibraryDone=done
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type="*/*";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
        },33)
    }
    fun importFont(callback: (String, String) -> Unit) {
        fontLibraryDone=null;fontCallback = callback
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }, 33)
    }

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
        if (requestCode==48) {
            val document=pendingTimeline;pendingTimeline=null
            if (resultCode==RESULT_OK && document!=null) data?.data?.let { tree ->
                restoreProject(document,true,tree)
            }
            return
        }
        if (requestCode==35) {
            val callback=effectMediaCallback; val project=effectMediaProject;val imageOnly=effectImageOnly
            effectMediaCallback=null; effectMediaProject=null
            val uri=data?.data
            if (resultCode==RESULT_OK && uri!=null && callback!=null && project!=null) worker.execute {
                var file: File?=null
                try {
                    val ext=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(uri)) ?: "media"
                    val imported=File(store.media,"background-${uid()}.$ext"); file=imported
                    contentResolver.openInputStream(uri)!!.use { input -> imported.outputStream().use { input.copyTo(it) } }
                    require(PhotoSource.shape(imported.path)!=null || !imageOnly && ProjectStore.inspect(imported).video) { if (imageOnly) "Choose an image" else "Choose an image or video" }
                    store.registerMedia(project,imported.path)
                    ui.post { if (editor.project.id==project) callback(imported.path) else message("The project changed while importing") }
                } catch (error: Exception) { file?.delete(); ui.post { message(error.message ?: "Cannot import background") } }
            }
            return
        }
        if (requestCode == 34) {
            val files = pendingOriginals; pendingOriginals = null
            if (resultCode == RESULT_OK && files != null) data?.data?.let { copyOriginals(files,it) }
            return
        }
        if (requestCode == 33) {
            val callback=fontCallback;val done=fontLibraryDone;fontCallback=null;fontLibraryDone=null
            val uris=if (data?.clipData!=null) (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri } else listOfNotNull(data?.data)
            if (resultCode==RESULT_OK && uris.isNotEmpty()) {
                val project=editor.project.id
                worker.execute {
                    var added=0;var existing=0;val errors=mutableListOf<String>()
                    var selected: Pair<String,String>?=null
                    for (uri in uris.take(256)) {
                        val temp=File(cacheDir,"font-${uid()}.partial")
                        try {
                            contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { output ->
                                val buffer=ByteArray(65536);var bytes=0L
                                while (true) { val n=input.read(buffer);if (n<0) break;bytes+=n
                                    require(bytes<=FontLibrary.maximumBytes) { "Choose a font smaller than 256 MB" };output.write(buffer,0,n)
                                }
                            } }
                            val (entry,fresh)=fontLibrary.add(temp,documentName(uri))
                            if (fresh) added++ else existing++
                            if (callback!=null && selected==null) {
                                val face=entry.faces.first();val owned=fontLibrary.preserve(face,store.media)
                                store.registerMedia(project,owned.path);selected=owned.path to face.info.name
                            }
                        } catch (error: Exception) { errors.add(error.message ?: "Cannot import font") }
                        finally { temp.delete() }
                    }
                    ui.post {
                        done?.invoke()
                        if (editor.project.id==project) selected?.let { callback?.invoke(it.first,it.second) }
                        message(if (errors.isEmpty()) "$added font files imported · $existing already in library" else "$added imported · ${errors.size} failed: ${errors.first()}")
                    }
                }
            }
            return
        }
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
            if (documentName(uri).endsWith(".otio",true)) choose("Timeline media",listOf("Choose media folder","Use app originals")) { option ->
                if (option==1) restoreProject(uri,true) else {
                    pendingTimeline=uri
                    try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },48) } catch (_: android.content.ActivityNotFoundException) { pendingTimeline=null;message("No folder picker is available") }
                }
            }
            else if (type in listOf("application/zip", "application/x-zip-compressed") ||
                documentName(uri).endsWith(".zip", true)) restoreProject(uri)
            else reviewImport(listOf(uri), null, true)
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
            data.data?.let { importBatch(listOf(it),target ?: MediaImport(editor.project.id,editor.cursor,true,true)) }
        }
        if (requestCode == 21) data.data?.let(::importImageUri)
    }

    private fun reviewImport(uris: List<Uri>, target: MediaImport?, asProject: Boolean) {
        if (uris.isEmpty()) return
        fun start(ordered: List<Uri>) {
            val destination = target ?: MediaImport(editor.project.id, editor.cursor, false, false, editor.project.duplicate())
            importBatch(ordered, destination, asProject)
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

    private fun importBatch(uris: List<Uri>, target: MediaImport, asProject: Boolean = false) {
        val expected = target.base ?: editor.project.duplicate()
        if (asProject && !saveProject()) return
        val newProject = if (asProject) datedProject() else null
        val projectId = newProject?.id ?: target.project
        val name="import-${uid()}"
        val working=File(store.media,".$name")
        val destination=File(store.media,name)
        val signal=android.os.CancellationSignal()
        panels.progress("Importing ${uris.size} items…", 0f) { signal.cancel() }
        ProjectAssets.begin(working,destination)
        worker.execute {
            try {
                check(working.mkdir()) { "Cannot stage the selected media" }
                val marker=".myrec-import"
                java.io.FileOutputStream(File(working,marker)).use { out ->
                    out.write(projectId.toByteArray());out.fd.sync()
                }
                val clips = uris.mapIndexed { index, uri ->
                    signal.throwIfCanceled()
                    val ext = android.webkit.MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(contentResolver.getType(uri))?.takeIf { it.all(Char::isLetterOrDigit) } ?: "media"
                    val file=File(working,"${index+1}.$ext")
                    val descriptor=contentResolver.openAssetFileDescriptor(uri,"r",signal) ?: error("Cannot open the selected media")
                    descriptor.use { asset -> asset.createInputStream().use { input ->
                        val size=asset.length
                        val storage=MediaStorage()
                        storage.requireSpace(working,"Not enough storage to import",size.coerceAtLeast(0))
                        java.io.FileOutputStream(file).use { out ->
                            val buffer=ByteArray(128*1024)
                            var copied=0L
                            while (true) {
                                signal.throwIfCanceled()
                                val count=input.read(buffer)
                                if (count<0) break
                                storage.requireSpace(working,"Not enough storage to import",count.toLong())
                                out.write(buffer,0,count)
                                copied+=count
                            }
                            check(copied>0 && (size<0 || copied==size)) { "The selected media did not transfer completely" }
                            out.fd.sync()
                        }
                    } }
                    ProjectStore.inspect(file).also {
                        check(it.video || it.audio) { "This file has no playable tracks" }
                        if (target.audio) {
                            check(it.audio) { "This file has no playable audio" }
                            it.video=false;it.linked=false
                        }
                        ui.post { if (!isDestroyed) panels.updateProgress((index+1f)/uris.size) }
                    }
                }
                val shape = clips.firstOrNull { it.video }?.let { ProjectStore.shape(it.path) }
                signal.throwIfCanceled()
                store.publishMedia(working,destination)
                val imported=clips.map { clip -> clip.copy(path=File(destination,File(clip.path).name).absolutePath) }
                if (!signal.isCanceled) for (clip in imported) store.registerMedia(projectId,clip.path)
                ui.post {
                    var saving=false
                    try {
                        if (isDestroyed || isFinishing) return@post
                        panels.close()
                        if (signal.isCanceled) return@post
                        check(editor.project==expected) { "Import cancelled because the project changed" }
                        val base=if (newProject!=null) EditSession(newProject) else editor.session()
                        val draft=Editor(base.project.duplicate()).apply {
                            load(project,base)
                            insertBatch(imported,if (asProject) 0.0 else target.at,target.layer,shape)
                        }
                        val session=draft.session()
                        saving=true
                        store.save(session)
                        File(destination,marker).delete()
                        editor.load(session.project,session)
                        editor.cursor=draft.cursor
                        editor.selected.addAll(draft.selected)
                        if (asProject) showEditor()
                        playback.seek(editor.cursor)
                    } catch (e: Exception) {
                        message(e.message ?: "Cannot import these items")
                    } finally {
                        if (!saving) destination.deleteRecursively()
                        ProjectAssets.end(working,destination)
                    }
                }
            } catch (e: Exception) {
                working.deleteRecursively()
                destination.deleteRecursively()
                ProjectAssets.end(working,destination)
                ui.post { if (!isDestroyed) { panels.close();if (!signal.isCanceled) message(e.message ?: "Cannot import these items") } }
            }
        }
    }

    private fun importImageUri(uri: Uri) {
        val project = editor.project.id
        val callback=imageCallback
        imageCallback=null
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
                ui.post {
                    if (!isDestroyed && project==editor.project.id && callback!=null) {
                        panels.close();callback(imported.absolutePath)
                    } else imported.delete()
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
    }

    internal var movieWriter: (File, () -> Boolean) -> Uri = ::writeMovie

    private fun writeMovie(file: File, cancelled: () -> Boolean): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "MyRec-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyRec")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create exported video")
        try {
            contentResolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { input ->
                val buffer = ByteArray(131072)
                while (true) {
                    if (cancelled()) throw java.io.InterruptedIOException("Export cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer,0,count)
                }
            } }
            if (cancelled()) throw java.io.InterruptedIOException("Export cancelled")
            if (Build.VERSION.SDK_INT >= 29) check(contentResolver.update(uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING,0) },null,null) == 1) { "Cannot publish the saved video" }
        } catch (e: Exception) {
            runCatching { contentResolver.delete(uri,null,null) }
            throw e
        }
        return uri
    }

    private fun movieFinished(uri: Uri, movie: MovieExports.Movie) {
        val file=movie.file
        panels.close(); message("Saved to Movies/MyRec")
        val warning=movie.editedAudioPeak?.let { peak ->
            val db=20*kotlin.math.log10(peak)
            "Video exported\nEdited audio peaks %.1f dB above full scale".format(db)
        }
        choose(warning ?: "Video exported",listOf("Play video","Share video","Save to file","Done")) { action ->
            try {
                when (action) {
                    0 -> startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    1 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4")
                        .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share video"))
                    2 -> saveDocument(file,"video/mp4","MyRec-${System.currentTimeMillis()}.mp4")
                }
            } catch (e: ActivityNotFoundException) { message("No app is available to open this video") }
        }
        if (!movie.reused) {
            ExportSound.play()
            root.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun exportJob(title: String, resumable: Boolean = false, prepare: (Export) -> MovieExports.Movie) {
        if (exporting != null) return
        playback.pause()
        val job = Export(audioCache)
        val project = editor.project.id
        exporting = job
        exportCanPause = resumable
        panels.progress(title, 0f, { job.cancelled = true }, if (resumable) ({ job.paused = true }) else null)
        worker.execute {
            var movie: MovieExports.Movie? = null
            try {
                val ready = prepare(job); movie = ready
                if (job.cancelled || job.paused) throw java.io.InterruptedIOException("Export interrupted")
                val uri = movieWriter(ready.file) { job.cancelled || job.paused }
                ui.post {
                    exporting = null
                    exportCanPause = false
                    if (!isFinishing && !isDestroyed) movieFinished(uri,ready)
                }
            } catch (e: Exception) {
                val completed = movie
                if (resumable && job.cancelled) movieExports.discard(project)
                ui.post {
                    exporting = null
                    exportCanPause = false
                    if (isFinishing || isDestroyed) return@post
                    panels.close()
                    if (job.paused && !job.cancelled) {
                        if (editor.project.id == project) panels.export()
                        message(if (completed == null) "Export paused" else "Movie ready to save")
                        return@post
                    }
                    message(e.message ?: "Export failed")
                    if (completed != null && completed.file.isFile && !job.cancelled) {
                        choose("Save completed movie",listOf("Retry saving","Save to file","Keep for later")) { action ->
                            when (action) {
                                0 -> exportJob("Saving video") { completed }
                                1 -> saveDocument(completed.file,"video/mp4","MyRec-${System.currentTimeMillis()}.mp4")
                            }
                        }
                    }
                }
            }
        }
    }

    fun pendingMovie(width: Int, height: Int, color: ExportColor, fps: Int): Boolean =
        movieExports.hasCheckpoint(store, editor.project, width, height, color, fps)

    fun discardMovie() { if (exporting == null) movieExports.discard(editor.project.id) }

    fun export(width: Int, height: Int, color: ExportColor = ExportColor.SDR, fps: Int? = null) {
        if (exporting != null || editor.project.duration <= 0) return
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            moviePermission = MovieRequest(width, height, color, fps)
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),12)
            return
        }
        val snapshot = editor.project.duplicate()
        exportJob("Exporting", resumable = fps != null) { job ->
            if (fps == null) movieExports.movie(store,snapshot,width,height,color,cancelled = { job.cancelled },
                progress = { progress -> ui.post { panels.updateProgress(progress) } },editedAudioPeak={ job.editedAudioPeak }) { target,progress ->
                job.run(snapshot,target,width,height,color,progress)
            } else movieExports.resumable(store, snapshot, width, height, color, fps,
                cancelled = { job.cancelled || job.paused }, progress = { value -> ui.post { panels.updateProgress(value) } },
                editedAudioPeak={ job.editedAudioPeak }
            ) { target, checkpoint, progress ->
                job.render(snapshot, target, width, height, color, fps, checkpoint, progress)
            }
        }
    }

    fun triggerZoom(returning: Boolean=false) { capture?.triggerCameraEffect(returning) }
    fun toggleDolly() { capture?.toggleDolly() }
    override fun onKeyDown(keyCode: Int,event: android.view.KeyEvent): Boolean {
        if (captureMode && event.repeatCount==0 && keyCode in listOf(android.view.KeyEvent.KEYCODE_VOLUME_UP,android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            capture?.triggerCameraEffect(keyCode==android.view.KeyEvent.KEYCODE_VOLUME_DOWN)==true) return true
        return super.onKeyDown(keyCode,event)
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
        if (!::editorView.isInitialized) { updateBars(); return }
        choices?.dismiss()
        editorView.invalidate()
        updateBars()
        root.addOnLayoutChangeListener(
            object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                ) {
                    root.removeOnLayoutChangeListener(this)
                    panels.relayout()
                    originalReview?.relayout()
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
        originalReview?.pause()
        super.onPause()
        if (::playback.isInitialized) playback.pause()
        capture?.suspendCamera()
        if (::editor.isInitialized) saveProject()
    }

    override fun onStop() {
        if (exportCanPause) exporting?.paused = true
        super.onStop()
    }

    @Deprecated("Platform back support")
    override fun onBackPressed() {
        if (!::panels.isInitialized) return
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
        originalReview?.dismiss()
        importDialog?.dismiss()
        linkDialog?.dismiss()
        choices?.dismiss()
        if (::playback.isInitialized) playback.close()
        if (::mediaIndex.isInitialized) mediaIndex.close()
        capture?.destroy()
        exporting?.let { if (!it.paused) it.cancelled = true }
        worker.shutdown()
        super.onDestroy()
    }
}
