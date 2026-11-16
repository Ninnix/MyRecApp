package org.myrec.app

import android.app.AlertDialog
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

class LutInspector(private val ui: InspectorUi, private val fx: Effect, private val changed: () -> Unit,
                   private val compare: (Boolean) -> Unit) : AutoCloseable {
    init {
        if (fx.number("warmth")==0f && fx.number("tint")==0f && fx.motion?.let { motion ->
            listOf(motion.a,motion.b).any { it["warmth"]!=0.0 || it["tint"]!=0.0 }
        }!=true) fx.values.putIfAbsent("balanceModel","2")
        fx.motion?.let { motion ->
        fx.motion=motion.copy(a=mapOf("localRecovery" to 0.0)+motion.a,b=mapOf("localRecovery" to 0.0)+motion.b)
    } }
    private val a = ui.a
    private val prefs = a.toolLibrary
    private var category = if (fx.values.containsKey("path")) "Imported" else "Built-in"
    private var balanceOpen = false
    private var parent: LinearLayout? = null
    private var actions: LinearLayout? = null
    private var endpoint: Boolean? = null
    private var after = true
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val thumbnails = mutableMapOf<String, Bitmap>()
    private val pictures = mutableMapOf<String, ImageView>()
    @Volatile private var generation = 0
    private var closed = false
    private var thumbnailTime = Double.NaN
    private var scheduled: Runnable? = null
    private val imported get() = JSONArray(prefs.getString("color.imported", "[]"))
    private val grades get() = JSONArray(prefs.getString("color.grades", "[]"))
    private val favorites get() = prefs.getStringSet("color.favorites", emptySet())!!.toMutableSet()
    private val selected get() = fx.values["path"]?.let { File(it).name } ?: fx.values["lut"] ?: "Natural"
    private fun sourceClip() = a.editor.targets().filter { it.video }.let { targets -> targets.firstOrNull { it.contains(a.editor.cursor) } ?: targets.firstOrNull() }
    private fun sourceTime() = sourceClip()?.sourceTime(a.editor.cursor) ?: 0.0
    private fun value(key: String) = fx.numberAt(key, sourceTime(), if (key == "strength") 1f else 0f)
    private fun change(key: String, value: Float) {
        if (key in listOf("warmth","tint")) fx.values["balanceModel"]="2"
        val motion = fx.motion
        if (motion != null && endpoint != null) {
            val pose = (if (endpoint == true) motion.b else motion.a).toMutableMap().apply { put(key, value.toDouble()) }
            fx.motion = if (endpoint == true) motion.copy(b = pose) else motion.copy(a = pose)
        } else if (motion != null) {
            fx.motion = motion.copy(a = motion.a + (key to value.toDouble()), b = motion.b + (key to value.toDouble()))
        }
        fx.values[key] = value.toString(); changed()
    }
    private fun rebuild() { parent?.let { build(it) }; actions?.let { transport(it) }; changed() }
    fun transport(parent: LinearLayout) {
        actions = parent; parent.removeAllViews()
        parent.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        val animate = ui.action(if (endpoint == null) "Animate" else if (endpoint == true) "Edit B" else "Edit A", "Animate", background = Color.rgb(24, 24, 25)) {
            a.choose("Color animation", listOf("Edit A", "Edit B", "Timing", "Done editing endpoints", "Remove animation")) { option ->
                a.playback.pause()
                when (option) {
                    0, 1 -> {
                        val targets = a.editor.targets().filter { it.video }
                        val base = a.editor.range?.start ?: targets.minOfOrNull { it.start } ?: 0.0
                        val end = a.editor.range?.endInclusive ?: targets.maxOfOrNull { it.end } ?: a.editor.project.duration
                        val clip = sourceClip()
                        if (clip != null) {
                            val motion = fx.motion ?: EffectMotion(GradeModel.pose(fx), duration = max(1.0 / a.editor.project.fps, end - base), frame = 1.0 / a.editor.project.fps)
                            endpoint = option == 1
                            a.editor.cursor = base + motion.timing.endpoint(option == 1, motion.duration, motion.frame)
                            fx.motion = motion.anchored(sourceClip() ?: clip, (sourceClip()?.start ?: clip.start) - base)
                        }
                    }
                    2 -> {
                        a.choose("Animation timing", EffectCurve.entries.map { it.title }) { index ->
                            fx.motion?.let { fx.motion = it.copy(timing = it.timing.copy(curve = EffectCurve.entries[index])) }
                            rebuild()
                        }
                    }
                    3 -> endpoint = null
                    4 -> { val pose = fx.motion?.pose(sourceTime()); fx.motion = null; endpoint = null; pose?.forEach { (key, value) -> fx.values[key] = value.toString() } }
                }
                rebuild()
            }
        }
        parent.addView(animate, LinearLayout.LayoutParams(ui.px(80f), ui.px(31f)))
        for (name in listOf("Before", "After")) {
            val selected = (name == "After") == after
            parent.addView(ui.action(name, background = if (selected) Color.rgb(35, 35, 37) else Color.TRANSPARENT) {
                after = name == "After"; compare(after); actions?.let { transport(it) }
            }, LinearLayout.LayoutParams(ui.px(47f), ui.px(31f)).apply { leftMargin = ui.px(4f) })
        }
    }
    fun build(parent: LinearLayout) {
        this.parent = parent; parent.removeAllViews(); pictures.clear()
        val heading = ui.row()
        heading.addView(ui.text(fx.values["name"] ?: CubeLut.title(fx.values["lut"] ?: "Natural"), 12f, bold = true), LinearLayout.LayoutParams(0, ui.px(32f), 1f))
        heading.addView(ui.icon("Favorite look", if (selected in favorites) "Star filled" else "Star") {
            val values = favorites; if (!values.add(selected)) values.remove(selected)
            prefs.edit().putStringSet("color.favorites", values).apply(); rebuild()
        }.apply { isSelected = selected in favorites }, LinearLayout.LayoutParams(ui.px(36f), ui.px(32f)))
        heading.addView(ui.icon("Import LUT", "Import") {
            a.importLut { path, name ->
                if (!closed) {
                    val library = File(a.toolLibraryDirectory, "luts").apply { mkdirs() }
                    val file = File(path); val target = File(library, file.name)
                    if (!target.exists()) file.copyTo(target)
                    val values = imported
                    if ((0 until values.length()).none { values.getJSONObject(it).getString("file") == file.name }) {
                        values.put(JSONObject().put("file", file.name).put("name", name).put("input", "Rec.709"))
                        prefs.edit().putString("color.imported", values.toString()).apply()
                    }
                    fx.values["path"] = path; fx.values["name"] = name; fx.values["input"] = "Rec.709"
                    category = "Imported"; rebuild(); refreshThumbnails()
                }
            }
        }, LinearLayout.LayoutParams(ui.px(36f), ui.px(32f)))
        ui.add(parent, heading)
        ui.slider(parent, "Strength", value("strength"), 0f, 1f, enabled = selected != "Natural", inline = true) { change("strength", it) }
        ui.gap(parent, 8f)
        if (fx.values["source"] in listOf("Apple Log","Apple Log 2","O-Log") || value("localRecovery")>0) {
            ui.slider(parent,"Local highlight recovery",value("localRecovery"),0f,1f,reset=0f,
                format={ "${(it*100).roundToInt()}%" }) { change("localRecovery",it) }
            ui.add(parent,ui.action("Natural recovery",background=Color.TRANSPARENT) { change("localRecovery",1f);rebuild() })
            ui.add(parent,ui.text("Recovers bright illumination while retaining local detail. Available for Log footage.",8f,Panels.MUTED))
        }
        ui.slider(parent, "Highlights", value("highlights"), -1f, 1f,
            format = { "%+.0f".format(java.util.Locale.US, it * 100) }, reset = 0f) { change("highlights", it) }
        ui.add(parent, ui.text("Adjusts bright tones before the look. Move left to lower them.", 8f, Panels.MUTED).apply { maxLines = 2 }, 18f)
        ui.add(parent, ui.disclosure("Exposure & white balance", balanceOpen) { balanceOpen = !balanceOpen; rebuild() }, 32f)
        if (balanceOpen) {
            for ((key, label, low, high) in listOf(
                listOf("exposure", "Exposure", "-4", "4"), listOf("warmth", "Warmth", "-1", "1"), listOf("tint", "Tint", "-1", "1"))) {
                ui.slider(parent, label, value(key), low.toFloat(), high.toFloat(), format = { "%+.2f".format(it) + if (key == "exposure") " EV" else "" }) { change(key, it) }
            }
            ui.add(parent, ui.action("Reset balance", background = Color.TRANSPARENT) {
                for (key in listOf("exposure", "warmth", "tint")) change(key, 0f); rebuild()
            })
        }
        ui.gap(parent)
        ui.segments(parent, listOf("Built-in", "Imported", "Favorites", "Grades"), category) { category = it; rebuild() }
        ui.gap(parent, 10f)
        val added = imported
        when (category) {
            "Grades" -> {
                val entries = grades
                if (entries.length() == 0) ui.add(parent, ui.text("Save a look and its color balance to reuse on another clip.", 8f, Panels.MUTED))
                for (i in 0 until entries.length()) {
                    val grade = entries.getJSONObject(i)
                    ui.add(parent, ui.action(grade.getString("name"), "Color Correction", Color.WHITE) {
                        val values = grade.getJSONObject("values"); fx.values.clear()
                        values.keys().forEach { key -> fx.values[key] = values.getString(key) }
                        fx.motion = null; endpoint = null; restoreLibraryPayload(); rebuild()
                    }); ui.gap(parent, 5f)
                }
            }
            else -> {
                val choices = mutableListOf<Pair<String, String>>()
                if (category != "Imported") for (look in CubeLut.looks) if (category != "Favorites" || look in favorites) choices.add(look to CubeLut.title(look))
                if (category != "Built-in") for (i in 0 until added.length()) {
                    val item = added.getJSONObject(i)
                    if (category != "Favorites" || item.getString("file") in favorites) choices.add(item.getString("file") to item.getString("name"))
                }
                ui.grid(parent, choices.size, 4, 70f) { i ->
                    val (id, name) = choices[i]
                    val tile = ui.column().apply {
                        background = ui.shape(if (id == selected) Color.rgb(25, 62, 73) else Color.TRANSPARENT, 6f,
                            if (id == selected) Color.rgb(57, 203, 244) else Color.rgb(56, 56, 59))
                        setPadding(ui.px(2f), ui.px(2f), ui.px(2f), ui.px(2f)); contentDescription = name; isSelected = id == selected
                        setOnClickListener {
                            if (id in CubeLut.looks) { fx.values.remove("path"); fx.values.remove("name"); fx.values.remove("input"); fx.values["lut"] = id }
                            else {
                                val item = (0 until added.length()).map { added.getJSONObject(it) }.first { it.getString("file") == id }
                                fx.values["path"] = File(a.toolLibraryDirectory, "luts/$id").path
                                fx.values["name"] = item.getString("name"); fx.values["input"] = item.optString("input", "Rec.709")
                                restoreLibraryPayload()
                            }
                            rebuild()
                        }
                        setOnLongClickListener {
                            val values = favorites; if (!values.add(id)) values.remove(id)
                            prefs.edit().putStringSet("color.favorites", values).apply(); rebuild(); true
                        }
                    }
                    val image = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumbnails[id]); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    pictures[id] = image
                    tile.addView(image, LinearLayout.LayoutParams(-1, ui.px(43f)))
                    tile.addView(ui.text(name, 8f).apply { gravity = Gravity.CENTER; maxLines = 2 }, LinearLayout.LayoutParams(-1, 0, 1f))
                    tile
                }
                if (category == "Imported" && added.length() == 0) ui.add(parent, ui.action("Import a .cube LUT from Files", background = Color.TRANSPARENT) {
                    heading.getChildAt(2).performClick()
                })
                if (category == "Favorites" && choices.isEmpty()) ui.add(parent, ui.text("Tap the star beside a look’s name to keep it here.", 8f, Panels.MUTED))
            }
        }
        if (fx.values.containsKey("path")) ui.add(parent, ui.action("LUT expects · ${fx.values["input"] ?: "Rec.709"}", background = Color.TRANSPARENT) {
            val inputs = listOf("Rec.709", "Apple Log", "Apple Log 2", "O-Log")
            a.choose("LUT expects", inputs) { index ->
                fx.values["input"] = inputs[index]
                val entries = imported
                for (i in 0 until entries.length()) if (entries.getJSONObject(i).getString("file") == selected) entries.getJSONObject(i).put("input", inputs[index])
                prefs.edit().putString("color.imported", entries.toString()).apply(); rebuild()
            }
        })
        ui.add(parent, ui.action("Source interpretation · ${fx.values["source"] ?: "Recorded"}", background = Color.TRANSPARENT) {
            val inputs = listOf("Recorded", "Apple Log", "Apple Log 2", "O-Log")
            a.choose("Source interpretation", inputs) { fx.values["source"] = inputs[it]; rebuild();refreshThumbnails() }
        })
        ui.add(parent, ui.action("Save grade", "Bookmark", background = Color.TRANSPARENT) { saveGrade() })
        tick()
    }
    private fun restoreLibraryPayload() {
        fx.values["path"]?.let { path ->
            val file = File(path)
            if (file.parentFile != a.store.media) {
                val (copy, _) = LutAssets.ingest(file.readBytes(), a.store.media)
                a.store.registerMedia(a.editor.project.id, copy.path); fx.values["path"] = copy.path
            }
        }
    }
    private fun saveGrade() {
        val input = EditText(a).apply { setText(fx.values["name"] ?: CubeLut.title(fx.values["lut"] ?: "Natural")); setSelectAllOnFocus(true); filters = arrayOf(android.text.InputFilter.LengthFilter(60)) }
        val dialog = AlertDialog.Builder(a).setTitle("Save grade").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) input.error = "Enter a grade name"
            else {
                val values = fx.values.toMutableMap(); GradeModel.limits.keys.forEach { values[it] = value(it).toString() }
                values["path"]?.let { path ->
                    val file = File(path); val library = File(a.toolLibraryDirectory, "luts").apply { mkdirs() }
                    val target = File(library, file.name); if (!target.exists()) file.copyTo(target)
                    values["path"] = target.path
                }
                val entries = grades; entries.put(JSONObject().put("name", name).put("values", JSONObject(values)))
                prefs.edit().putString("color.grades", entries.toString()).apply(); dialog.dismiss(); category = "Grades"; rebuild()
            }
        } }; dialog.show()
    }
    fun tick() {
        if (closed || a.playback.playing || abs(a.editor.cursor - thumbnailTime) < .05) return
        scheduled?.let { handler.removeCallbacks(it) }
        thumbnailTime = a.editor.cursor
        val job = Runnable { refreshThumbnails() }
        scheduled = job; handler.postDelayed(job, 180)
    }
    private fun refreshThumbnails() {
        if (closed) return
        val token = ++generation
        val project = a.editor.project.duplicate()
        project.clips.forEach { it.effects.removeAll { effect -> effect.kind == "Color LUTs" } }
        val interpretation=fx.values["source"]
        if (interpretation in listOf("Apple Log","Apple Log 2","O-Log")) project.clips.filter { it.video }.forEach {
            it.effects.add(Effect(kind="Color LUTs",to=it.duration,values=mutableMapOf("source" to interpretation!!,"lut" to "Natural")))
        }
        val time = a.editor.cursor
        val looks = CubeLut.looks.map { it to Effect(kind = "Color LUTs", values = mutableMapOf("lut" to it)) }.toMutableList()
        val entries = imported
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i); val name = item.getString("file")
            looks.add(name to Effect(kind = "Color LUTs", values = mutableMapOf("path" to File(a.toolLibraryDirectory, "luts/$name").path, "input" to item.optString("input", "Rec.709"))))
        }
        worker.execute {
            try {
                val width = (240 * min(1.0, project.width.toDouble() / project.height)).roundToInt().coerceAtLeast(2)
                val height = (width.toDouble() * project.height / project.width).roundToInt().coerceAtLeast(2)
                val source = Frames().use { it.render(project, time, width, height) }
                try {
                    for ((id, effect) in looks) {
                        if (closed || token != generation) break
                        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
                        try { Effects.correct(bitmap, effect, 1f) }
                        catch (_: Exception) { bitmap.recycle(); continue }
                        handler.post {
                            if (closed || token != generation) bitmap.recycle()
                            else { pictures[id]?.setImageBitmap(bitmap); thumbnails.put(id, bitmap)?.recycle() }
                        }
                    }
                } finally { source.recycle() }
            } catch (e: Exception) { android.util.Log.w("MyRec", "Cannot prepare look previews", e) }
        }
    }
    override fun close() {
        closed = true; generation++
        scheduled?.let { handler.removeCallbacks(it) }
        pictures.clear(); thumbnails.values.forEach { it.recycle() }; thumbnails.clear(); worker.shutdown()
    }
}
