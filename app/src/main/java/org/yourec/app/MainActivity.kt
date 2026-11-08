package org.yourec.app

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
    private var importKind = "video/*"
    private var imageCallback: ((String) -> Unit)? = null
    private var importAsProject = false
    private var exporting: Export? = null
    private var choices: PopupWindow? = null
    private var lastMessage = ""
    private var lastMessageAt = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        updateBars()
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
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
        editor = Editor(store.current())
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
            store.save(editor.project)
            editorView.resetScroll()
            editorView.invalidate()
            playback.refresh()
        }
        playback.refresh()
        updateBars()
        if (intent.action == Intent.ACTION_SEND) {
            val uri =
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) importUri(uri, false)
        }
    }

    private fun action(id: String) {
        if (id.startsWith("effect:")) {
            panels.editExisting(id.substringAfter(':'))
            return
        }
        when (id) {
            "empty" ->
                choose("YouRec", listOf("Record video", "Add video", "Projects")) {
                    when (it) {
                        0 -> showCapture()
                        1 -> importVideo()
                        2 -> projects()
                    }
                }
            "projects" -> projects()
            "addVideo" -> importVideo()
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
            "backPoint" -> {
                val t = editorView.previousPosition ?: 0.0
                editorView.previousPosition = editor.cursor
                playback.seek(t)
            }
            "forwardPoint" -> {
                editorView.previousPosition = editor.cursor
                playback.seek(editor.project.duration)
            }
            "split" -> {
                playback.pause()
                editor.split()
            }
            "mark" -> editor.markRange()
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
                editorView.invalidate()
            }
            "link" -> {
                if (editor.selected.isNotEmpty()) editor.unlink()
                else {
                    editor.linked = !editor.linked
                    editorView.invalidate()
                }
            }
            "move" -> {
                if (editor.targets().isNotEmpty()) {
                    if (editor.selected.isEmpty())
                        editor.targets().forEach { editor.selected.add(it.id) }
                    editorView.moving = !editorView.moving
                    if (editorView.moving) {
                        editorView.movingOrigin =
                            editor.targets().minOfOrNull { it.start } ?: editor.cursor
                        editor.beginBatch()
                    } else editor.endBatch(false)
                    editorView.invalidate()
                }
            }
            "moveDone" -> {
                editorView.moving = false
                editor.endBatch(false)
                editorView.invalidate()
            }
            "moveCancel" -> {
                editorView.moving = false
                editor.endBatch(true)
                editorView.invalidate()
            }
            "volume" -> panels.volume()
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
                editor.copySelection()
                editor.clearSelection()
                editorView.showPaste = true
                editorView.invalidate()
            }
            "paste" -> {
                editor.paste()
                editorView.showPaste = false
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
                        if (editor.project.clips.isEmpty()) setProjectFormat(clip)
                        editor.append(clip)
                    },
                    ::showEditor,
                )
        if (capture!!.parent == null) root.addView(capture, FrameLayout.LayoutParams(-1, -1))
        capture!!.visibility = View.VISIBLE
        capture!!.resumeCamera()
        updateBars()
    }

    fun showEditor() {
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
        if (code == 10 && grants.all { it == PackageManager.PERMISSION_GRANTED }) showCapture()
        else if (code == 10) message("Camera and microphone permissions are needed to record")
    }

    fun newProject() {
        playback.pause()
        if (capture?.recording == true) capture?.stop()
        store.save(editor.project)
        editor.load(
            Project(name = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date()))
        )
        store.save(editor.project)
        editorView.bitmap = null
        editorView.invalidate()
    }

    fun openProject(project: Project) {
        playback.pause()
        if (project.id == editor.project.id) {
            showEditor()
            return
        }
        store.save(editor.project)
        editor.load(project)
        store.save(project)
        showEditor()
    }

    private fun projects() {
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

    fun importVideo() {
        importKind = "video/*"
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
            20,
        )
    }

    fun importProject() {
        importAsProject = true
        importVideo()
    }

    fun importImage(callback: (String) -> Unit) {
        imageCallback = callback
        importKind = "image/*"
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
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == 20) importAsProject = false
            return
        }
        if (requestCode == 20) {
            if (importAsProject) {
                newProject()
                importAsProject = false
            }
            val clip = data.clipData
            if (clip != null)
                for (i in 0 until clip.itemCount) importUri(clip.getItemAt(i).uri, false)
            else data.data?.let { importUri(it, false) }
        }
        if (requestCode == 21) data.data?.let { importUri(it, true) }
    }

    private fun importUri(uri: Uri, image: Boolean) {
        panels.progress("Importing…", 0f, null)
        worker.execute {
            try {
                val ext = if (image) "png" else "mp4"
                val file = File(store.media, "import-${uid()}.$ext")
                contentResolver.openInputStream(uri)!!.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                val clip = if (!image) ProjectStore.inspect(file) else null
                ui.post {
                    panels.close()
                    if (image) {
                        imageCallback?.invoke(file.absolutePath)
                        imageCallback = null
                    } else if (clip != null) {
                        if (editor.project.clips.isEmpty()) setProjectFormat(clip)
                        editor.insert(clip)
                        editor.scale = if (editor.project.duration < 10) 10.0 else editor.scale
                        playback.refresh()
                    }
                }
            } catch (e: Exception) {
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

    fun export(width: Int, height: Int) {
        if (exporting != null || editor.project.duration <= 0) return
        playback.pause()
        val snapshot = editor.project.duplicate()
        val exporter = Export(audioCache)
        exporting = exporter
        val file = File(cacheDir, "YouRec-${System.currentTimeMillis()}.mp4")
        panels.progress("Exporting", 0f) { exporter.cancelled = true }
        worker.execute {
            try {
                exporter.run(snapshot, file, width, height) { progress ->
                    ui.post { panels.updateProgress(progress) }
                }
                val values =
                    ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/YouRec")
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
                    message("Saved to Movies/YouRec")
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
        super.onPause()
        playback.pause()
        capture?.suspendCamera()
        store.save(editor.project)
    }

    @Deprecated("Platform back support")
    override fun onBackPressed() {
        when {
            choices?.isShowing == true -> choices?.dismiss()
            panels.isOpen -> panels.close()
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
        choices?.dismiss()
        playback.close()
        mediaIndex.close()
        capture?.destroy()
        exporting?.cancelled = true
        worker.shutdown()
        super.onDestroy()
    }
}
