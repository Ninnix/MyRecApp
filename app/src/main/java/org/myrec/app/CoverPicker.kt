package org.myrec.app

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class CoverPicker(private val a: MainActivity, private val source: CoverSource) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var generation = 0
    private lateinit var parent: LinearLayout
    private lateinit var ui: InspectorUi
    private var picks = source.pool.picks()
    private val thumbnails = mutableMapOf<Int,Bitmap>()
    private val jpegs = mutableMapOf<Int,File>()
    private var selected: Int? = null
    private var full: Bitmap? = null
    private var loaded: Int? = null

    fun attach(body: LinearLayout, controls: InspectorUi) {
        parent = body; ui = controls
        show()
    }
    private fun show() {
        val token = ++generation
        val previous = parent.findViewWithTag<View>("cover.content")
        if (previous != null) parent.removeView(previous)
        val content = ui.column().apply {
            tag = "cover.content"
            setPadding(ui.px(12f),ui.px(8f),ui.px(12f),ui.px(12f))
        }
        parent.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        val index = selected
        if (index != null) detail(content,index,token) else grid(content,token)
    }
    private fun grid(content: LinearLayout, token: Int) {
        val heading = ui.row()
        val labels = ui.column()
        ui.add(labels,ui.text(source.scopeLabel,11f,bold = true),20f)
        ui.add(labels,ui.text(source.rangeLabel,9f,Panels.MUTED),18f)
        heading.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(ui.action("Update","Reset",Color.WHITE) {
            picks = source.pool.picks(picks); show()
            val keep = picks.toSet()
            for (old in thumbnails.keys.toList()) if (old !in keep) thumbnails.remove(old)?.recycle()
        }.apply { isEnabled = source.pool.count > 9; alpha = if (isEnabled) 1f else .4f },LinearLayout.LayoutParams(ui.px(85f),ui.px(36f)))
        ui.add(content,heading); ui.gap(content,6f)
        if (picks.isEmpty()) {
            ui.add(content,ui.text("No video in this range",13f,bold = true),44f)
            ui.add(content,ui.text("Select a range that contains video.",10f,Panels.MUTED),32f)
            return
        }
        val scroll = ScrollView(a)
        val tiles = ui.column(); scroll.addView(tiles)
        content.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val columns = if (a.root.width > a.root.height) 3 else 2
        val size = source.size(512)
        val images = mutableMapOf<Int,ImageView>()
        val failed = mutableMapOf<Int,TextView>()
        for (start in picks.indices step columns) {
            val row = ui.row()
            for (position in 0 until columns) {
                val pick = picks.getOrNull(start + position)
                val tile = ui.column().apply { setPadding(ui.px(4f),ui.px(4f),ui.px(4f),ui.px(7f)) }
                if (pick != null) {
                    val image = ImageView(a).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(Color.rgb(21,21,23)); setImageBitmap(thumbnails[pick])
                    }
                    images[pick] = image
                    val tileWidth = if (columns == 3) 185f else 125f
                    val imageHeight = (tileWidth * size.second / size.first).coerceIn(55f,220f)
                    ui.add(tile,image,imageHeight)
                    val label = ui.text(timeLabel(source.pool.time(pick)),8f,Panels.MUTED)
                    failed[pick] = label; ui.add(tile,label,24f)
                    tile.contentDescription = "Frame at ${timeLabel(source.pool.time(pick))}"
                    tile.tag = "cover.frame.$pick"
                    tile.isFocusable = true; tile.setOnClickListener { selected = pick; show() }
                }
                row.addView(tile,LinearLayout.LayoutParams(0,-2,1f))
            }
            ui.add(tiles,row)
        }
        val missing = picks.filter { it !in thumbnails }
        worker.execute {
            Frames().use { frames ->
                for (pick in missing) {
                    if (closed || token != generation) break
                    var image: Bitmap? = null
                    try { image = frames.render(source.project,source.pool.time(pick),size.first,size.second) }
                    catch (_: Exception) {}
                    val ready = image
                    main.post {
                        if (closed || token != generation) ready?.recycle()
                        else if (ready != null) { thumbnails[pick] = ready; images[pick]?.setImageBitmap(ready) }
                        else failed[pick]?.text = "Couldn't load this frame"
                    }
                }
            }
        }
    }
    private fun detail(content: LinearLayout, index: Int, token: Int) {
        ui.add(content,ui.action("Select cover","Back",Color.WHITE) { selected = null; show() }
            .apply { contentDescription = "Back to cover choices" },34f)
        ui.add(content,ui.text(timeLabel(source.pool.time(index)),10f,Panels.MUTED),25f)
        val image = ImageView(a).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Cover image"
            setImageBitmap(if (loaded == index) full else thumbnails[index])
        }
        fun go(pick: Int) {
            if (pick in 0 until source.pool.count && pick!=index) { selected=pick;show() }
        }
        var downX=0f
        var downY=0f
        image.setOnTouchListener { _,event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX=event.x;downY=event.y }
                MotionEvent.ACTION_UP -> {
                    val dx=event.x-downX
                    if (kotlin.math.abs(dx)>ui.px(28f) && kotlin.math.abs(dx)>kotlin.math.abs(event.y-downY))
                        go(index+if (dx<0) 1 else -1)
                }
            }
            true
        }
        content.addView(image,LinearLayout.LayoutParams(-1,0,1f))
        val navigation=ui.row()
        fun navigate(label: String,enabled: Boolean,action: () -> Unit) {
            navigation.addView(ui.action(label,click=action).apply { isEnabled=enabled },
                LinearLayout.LayoutParams(0,ui.px(40f),1f))
        }
        navigate("Previous frame",index>0) { go(index-1) }
        navigate("Update",source.pool.count>1) {
            val next=kotlin.random.Random.nextInt(source.pool.count-1)
            go(if (next<index) next else next+1)
        }
        navigate("Next frame",index+1<source.pool.count) { go(index+1) }
        ui.add(content,navigation)
        val status = ui.text("Preparing full-resolution JPEG…",9f,Panels.MUTED)
        ui.add(content,status,28f)
        val actions = ui.row()
        val save = ui.action("Save JPEG","Export") { jpegs[index]?.let { a.saveDocument(it,"image/jpeg",source.filename(index)) } }
        val share = ui.action("Share JPEG","Export") {
            jpegs[index]?.let { file ->
                val uri = SharedImages.uri(a,file)
                val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM,uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("JPEG",uri) }
                try { a.startActivity(Intent.createChooser(intent,"Share JPEG")) }
                catch (_: android.content.ActivityNotFoundException) { a.message("No app is available to share this JPEG") }
            }
        }
        save.isEnabled = false; share.isEnabled = false
        actions.addView(save,LinearLayout.LayoutParams(0,ui.px(42f),1f))
        actions.addView(share,LinearLayout.LayoutParams(0,ui.px(42f),1f).apply { leftMargin = ui.px(8f) })
        ui.add(content,actions)
        fun ready(bitmap: Bitmap, file: File) {
            if (full !== bitmap) full?.recycle()
            full = bitmap; loaded = index; jpegs[index] = file
            image.setImageBitmap(bitmap)
            status.text = "${bitmap.width} × ${bitmap.height} pixels"
            image.contentDescription = "Cover image, ${bitmap.width} × ${bitmap.height} pixels"
            save.isEnabled = true; share.isEnabled = true
        }
        val cached = jpegs[index]?.takeIf { it.isFile }
        if (cached != null && loaded == index && full != null) { ready(full!!,cached); return }
        worker.execute {
            var bitmap: Bitmap? = null
            var file: File? = null
            var created = false
            var error: String? = null
            try {
                if (closed || token != generation) return@execute
                if (cached != null) {
                    bitmap = BitmapFactory.decodeFile(cached.path) ?: error("Couldn't read this JPEG")
                    file = cached
                } else {
                    val project = source.project
                    require(project.width.toLong() * project.height <= 32000000) { "This full-resolution cover is too large" }
                    bitmap = Frames().use { it.render(project,source.pool.time(index),project.width,project.height) }
                    if (closed || token != generation) { bitmap.recycle(); return@execute }
                    val target = File(SharedImages.directory(a),"${uid()}_${source.filename(index)}")
                    val partial = File(target.parentFile,target.name + ".partial")
                    try {
                        FileOutputStream(partial).use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,output)) { "Couldn't encode this JPEG" }
                            output.fd.sync()
                        }
                        check(partial.renameTo(target)) { "Couldn't save this JPEG" }
                        file = target
                        created = true
                    } finally { partial.delete() }
                }
            } catch (e: Exception) { error = e.message ?: "Couldn't load this frame" }
            catch (_: OutOfMemoryError) { error = "Not enough memory for this full-resolution frame" }
            val result = bitmap; val jpeg = file
            main.post {
                if (closed || token != generation) { result?.recycle(); if (created) jpeg?.delete() }
                else if (result != null && jpeg != null) ready(result,jpeg)
                else {
                    result?.recycle(); status.text = error ?: "Couldn't load this frame"
                    if (cached!=null) jpegs.remove(index)
                    ui.add(content,ui.action("Try again","Reset") { show() },38f)
                }
            }
        }
    }
    fun close() {
        closed = true; generation++
        parent.findViewWithTag<View>("cover.content")?.let { parent.removeView(it) }
        thumbnails.values.forEach { it.recycle() }; thumbnails.clear()
        full?.recycle(); full = null
        worker.shutdownNow()
    }
}
