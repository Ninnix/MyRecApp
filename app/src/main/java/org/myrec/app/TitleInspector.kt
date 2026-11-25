package org.myrec.app

import android.graphics.*
import android.text.*
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*

class TitleInspector(private val ui: InspectorUi, private val fx: Effect, private val base: Double,
                     private val length: Double, private val changed: () -> Unit,
                     private val replay: (Boolean) -> Unit) {
    var subtitle=false
    private val a = ui.a
    private val prefs = a.toolLibrary
    private var tab = "Looks"
    private var expanded = false
    private var positionOpen = false
    private var exit = false
    private var parent: LinearLayout? = null
    private val specimens = mutableListOf<View>()
    private val frame get() = 1.0 / a.editor.project.fps.coerceAtLeast(1)
    init {
        if (fx.to == Double.MAX_VALUE) fx.to = min(3.0, length)
        fx.from = fx.from.coerceIn(0.0, max(0.0, length - frame))
        fx.to = fx.to.coerceIn(min(length, fx.from + frame), length)
    }
    private fun change() { specimens.forEach { it.invalidate() }; changed() }
    private fun rebuild() { parent?.let { build(it) }; change() }
    private fun set(key: String, value: Any) { fx.values[key] = value.toString(); change() }
    private fun useFont(path: String, name: String) {
        val file = File(path)
        val target = File(a.store.media, file.name)
        if (file.canonicalFile != target.canonicalFile && !target.exists()) file.copyTo(target)
        a.store.registerMedia(a.editor.project.id, target.path)
        fx.values["path"] = target.path; fx.values["fontName"] = name; if (FontLibrary.Reference.parse(fx.values["face"] ?: "")==null) fx.values["face"]="imported"
    }
    private fun color(parent: LinearLayout, label: String, key: String, initial: String) {
        ui.color(parent, label, TitleStyle.color(fx, key, initial)) { set(key, "#%08x".format(it)) }
    }
    private fun slider(parent: LinearLayout, label: String, key: String, value: Float, low: Float, high: Float) {
        ui.slider(parent, label, fx.number(key, value), low, high) { set(key, it) }
    }
    private fun hideKeyboard() {
        a.currentFocus?.clearFocus()
        (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(a.root.windowToken, 0)
    }
    fun build(parent: LinearLayout) {
        this.parent = parent; parent.removeAllViews(); specimens.clear()
        if (!subtitle) ui.segments(parent,listOf("Single text","Label + headline"),
            if (TitleCard.enabled(fx)) "Label + headline" else "Single text") {
            fx.values["grouped"]=(it=="Label + headline").toString()
            if (it=="Label + headline") fx.values.putIfAbsent("label","First text")
            rebuild()
        }
        if (!subtitle && TitleCard.enabled(fx)) {
            val label=EditText(a).apply {
                setText(fx.values["label"]);hint="Label";contentDescription="Label text"
                setTextColor(Color.WHITE);setHintTextColor(Panels.MUTED)
                filters=arrayOf(InputFilter.LengthFilter(500))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { set("label",s?.toString().orEmpty()) }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
            ui.add(parent,label)
        }
        val input = EditText(a).apply {
            setText(fx.values["text"] ?: ""); hint = if (TitleCard.enabled(fx)) "Headline" else "Write something"
            setTextColor(Color.WHITE); setHintTextColor(Panels.MUTED)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.px(11f).toFloat())
            background = ui.shape(Color.BLACK, 3f, Color.rgb(48, 48, 50))
            setPadding(ui.px(5f), ui.px(3f), ui.px(5f), ui.px(3f))
            minLines = 1; maxLines = 4; filters = arrayOf(InputFilter.LengthFilter(500)); contentDescription = "Title text"
        }
        input.isEnabled=!subtitle
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { fx.values["text"] = s.toString(); change() }
            override fun afterTextChanged(s: Editable?) {}
        })
        ui.add(parent, input); ui.gap(parent, 8f)
        if (!subtitle) timing(parent)
        ui.gap(parent, 8f)
        if (!subtitle && TitleCard.enabled(fx)) {
            groupAppearance(parent)
            return
        }
        val names = listOf("Looks", "Font", "Fill", "Outline", "Shadow", "Background", "Motion")
        ui.grid(parent, names.size, 4, 32f) { index ->
            val name = names[index]
            val item = ui.column().apply {
                gravity = Gravity.CENTER; background = ui.shape(if (tab == name) Color.rgb(27, 64, 76) else Color.rgb(35, 35, 37))
                contentDescription = name; isSelected = tab == name
                setOnClickListener { hideKeyboard(); tab = name; rebuild() }
            }
            item.addView(Glyph(a, if (name == "Background") "Text background" else name, Color.WHITE), LinearLayout.LayoutParams(ui.px(19f), ui.px(18f)))
            item.addView(ui.text(name, 8.5f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, ui.px(12f)))
            item
        }
        ui.divider(parent)
        when (tab) {
            "Looks" -> looks(parent)
            "Font" -> fonts(parent)
            "Fill" -> fill(parent)
            "Outline" -> {
                ui.toggle(parent, "Letter outline", fx.values["outlined"] == "true") { set("outlined", it); rebuild() }
                if (fx.values["outlined"] == "true") {
                    color(parent, "Outline color", "border", "#000000")
                    slider(parent, "Width", "borderWidth", .045f, .01f, .2f)
                }
            }
            "Shadow" -> {
                ui.toggle(parent, "Drop shadow", fx.values["shadow"] != "false") { set("shadow", it); rebuild() }
                if (fx.values["shadow"] != "false") {
                    color(parent, "Shadow color", "shade", "#d2000000")
                    slider(parent, "Blur", "shadowBlur", .12f, 0f, .6f)
                    slider(parent, "Offset", "shadowOffset", .05f, -.5f, .5f)
                }
            }
            "Background" -> {
                ui.segments(parent, listOf("None", "One box", "Each line"), fx.values["plate"] ?: "None") { set("plate", it); rebuild() }
                if (fx.values["plate"] !in listOf(null, "None")) {
                    color(parent, "Box color", "backing", "#cd111218")
                    slider(parent, "Padding", "padding", .28f, 0f, .8f)
                    slider(parent, "Corner radius", "radius", .14f, 0f, .8f)
                }
            }
            "Motion" -> motion(parent)
        }
        ui.gap(parent)
        ui.add(parent, ui.text("Drag to move · pinch to resize", 8f, Panels.MUTED), 20f)
        slider(parent, "Size", "size", .065f, .02f, .3f)
        ui.add(parent, ui.disclosure("Position", positionOpen) { positionOpen = !positionOpen; rebuild() }, 36f)
        if (positionOpen) {
            slider(parent, "Across", "x", .5f, 0f, 1f); slider(parent, "Down", "y", .5f, 0f, 1f)
            ui.add(parent, ui.action("Centre text") { set("x", .5); set("y", .5); rebuild() })
        }
    }

    private fun groupAppearance(parent: LinearLayout) {
        val parts=listOf("Looks","Label","Headline","Card","Motion")
        val part=tab.takeIf { it in parts } ?: "Looks"
        ui.segments(parent,parts,part) { hideKeyboard();tab=it;rebuild() }
        when (part) {
            "Looks" -> groupLooks(parent)
            "Motion" -> motion(parent)
            "Card" -> {
                color(parent,"Card fill","card.fill",TitleCard.fill)
                color(parent,"Card outline","card.border",TitleCard.border)
                for ((key,value) in TitleCard.dimensions) {
                    slider(parent,key.replaceFirstChar { it.uppercase() },"card.$key",value,0f,if (key=="borderWidth") .1f else 1f)
                }
            }
            else -> {
                val key=part.lowercase()
                val style=TitleCard.style(fx,key)
                val faces=TitleStyle.families
                val name=faces.firstOrNull { it.id==style["face"] }?.name ?: style["fontName"] ?: "Imported font"
                ui.add(parent,ui.action("Font · $name","Font") {
                    a.choose("Choose font",faces.map { it.name }) { index ->
                        fx.values.remove("$key.path")
                        fx.values["$key.face"]=faces[index].id
                        fx.values["$key.weight"]=faces[index].weights.first().toString()
                        rebuild()
                    }
                })
                ui.add(parent,ui.action("Font library…","Font",background=Color.TRANSPARENT) {
                    hideKeyboard();a.playback.pause()
                    FontLibraryDialog(a,ui) { face ->
                        fx.values["$key.face"]=face.id;fx.values["$key.path"]=face.file.path
                        fx.values["$key.fontName"]=face.info.name;fx.values["$key.fontIndex"]=face.info.index.toString()
                        fx.values["$key.weight"]=face.info.weight.toString();fx.values["$key.italic"]=face.info.italic.toString()
                        rebuild()
                    }.show()
                })
                color(parent,"Letter color","$key.color",style.getValue("color"))
                slider(parent,"Relative size","${key}Scale",if (key=="label") .36f else 1f,
                    if (key=="label") .2f else .5f,if (key=="label") 1f else 1.8f)
                slider(parent,"Horizontal offset","card.$key.x",0f,-1f,1f)
                slider(parent,"Vertical offset","card.$key.y",0f,-1f,1f)
                ui.add(parent,ui.action("Reset $key position","Reset") {
                    fx.values.remove("card.$key.x");fx.values.remove("card.$key.y");rebuild()
                })
            }
        }
        slider(parent,"Whole title size","size",.065f,.02f,.3f)
        slider(parent,"Horizontal position","x",.5f,0f,1f)
        slider(parent,"Vertical position","y",.5f,0f,1f)
    }

    private fun groupLooks(parent: LinearLayout) {
        val choices=TitleCard.looks.entries.toList()
        ui.grid(parent,choices.size,3,45f) { index ->
            val (name,look)=choices[index]
            ui.action(name) { TitleCard.applyLook(fx,look);rebuild() }
        }
        val saved=JSONArray(prefs.getString("title.cardStyles","[]"))
        for (i in 0 until saved.length()) {
            val item=saved.getJSONObject(i)
            ui.add(parent,ui.action(item.getString("name")) {
                val look=item.getJSONObject("look")
                TitleCard.applyLook(fx,look.keys().asSequence().associateWith { look.getString(it) })
                rebuild()
            })
        }
        ui.add(parent,ui.action("Save as channel style","Bookmark",background=Color.TRANSPARENT) {
            val input=EditText(a).apply { hint="Style name";filters=arrayOf(InputFilter.LengthFilter(60)) }
            android.app.AlertDialog.Builder(a).setTitle("Save card style").setView(input)
                .setNegativeButton("Cancel",null).setPositiveButton("Save") { _,_ ->
                    val name=input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        val values=fx.values.filterKeys(TitleCard::appearance)
                        val index=(0 until saved.length()).firstOrNull { saved.getJSONObject(it).getString("name")==name } ?: saved.length()
                        saved.put(index,JSONObject().put("name",name).put("look",JSONObject(values)))
                        prefs.edit().putString("title.cardStyles",saved.toString()).apply()
                        rebuild()
                    }
                }.show()
        })
    }
    private fun timing(parent: LinearLayout) {
        val label = "${"%.2f".format(java.util.Locale.getDefault(), fx.to - fx.from).trimEnd('0').trimEnd('.', ',')} s"
        val line = ui.row().apply {
            background = ui.shape(); setPadding(ui.px(9f), 0, ui.px(9f), 0)
            contentDescription = "Duration"; setOnClickListener { hideKeyboard(); expanded = !expanded; rebuild() }
        }
        line.addView(Glyph(a, "Duration", Color.WHITE), LinearLayout.LayoutParams(ui.px(18f), ui.px(18f)).apply { rightMargin = ui.px(7f) })
        line.addView(ui.text("Duration"), LinearLayout.LayoutParams(0, -1, 1f))
        line.addView(ui.text(label), LinearLayout.LayoutParams(-2, -1))
        line.addView(Glyph(a, if (expanded) "Chevron up" else "Chevron down", Color.WHITE), LinearLayout.LayoutParams(ui.px(12f), ui.px(14f)).apply { leftMargin = ui.px(8f) })
        ui.add(parent, line, 33f)
        if (!expanded) return
        ui.gap(parent, 6f)
        val presets = (1..10).map { it.toDouble() } + listOf(15.0, 30.0, 60.0)
        ui.grid(parent, presets.size, 5, 32f) { i ->
            val seconds = presets[i]
            ui.action("${seconds.toInt()} s", color = Color.WHITE) {
                fx.to = min(length, fx.from + seconds); rebuild()
            }.apply {
                contentDescription = "Duration ${seconds.toInt()} seconds"
                isEnabled = seconds <= length - fx.from + .00001
                alpha = if (isEnabled) 1f else .3f
                isSelected = abs(fx.to - fx.from - seconds) < .00001
                if (isSelected) background = ui.shape(Color.rgb(70, 30, 37), 7f, Panels.RED)
            }
        }
        val available = ui.row()
        available.addView(ui.text("${"%.2f".format(length - fx.from)} s available", 8f, Panels.MUTED), LinearLayout.LayoutParams(0, -1, 1f))
        available.addView(ui.action("To end", background = Color.TRANSPARENT) { fx.to = length; rebuild() }, LinearLayout.LayoutParams(ui.px(65f), ui.px(32f)))
        ui.add(parent, available, 32f)
        fun trim(start: Boolean) {
            val initial = if (start) fx.from else fx.to
            ui.slider(parent, if (start) "Start" else "End", initial.toFloat(), 0f, length.toFloat(),
                format = { timeLabel(base + it) }) { value ->
                if (start) fx.from = min(value.toDouble(), fx.to - frame).coerceAtLeast(0.0)
                else fx.to = max(value.toDouble(), fx.from + frame).coerceAtMost(length)
                change()
            }.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_UP) parent.post { rebuild() }; false }
        }
        trim(true); trim(false)
    }
    private fun specimen(design: Effect): View = object : View(a) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            design.values["text"] = (fx.values["text"]?.takeIf { it.isNotEmpty() } ?: "Your title").take(48)
            val image = TitleStyle.raster(a, design, 36f, 360)
            val fit = min(1f, min(width / image.width.toFloat(), height / image.height.toFloat()))
            val w = image.width * fit; val h = image.height * fit
            canvas.drawBitmap(image, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), paint)
            image.recycle()
        }
    }.also { specimens.add(it) }
    private fun looks(parent: LinearLayout) {
        val saved = JSONArray(prefs.getString("title.styles", "[]"))
        if (saved.length() > 0) ui.add(parent, ui.text("My styles", bold = true), 24f)
        for (i in 0 until saved.length()) {
            val entry = saved.getJSONObject(i)
            val values = entry.getJSONObject("values").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            val line = ui.row()
            line.addView(ui.action(entry.getString("name"), color = Color.WHITE) {
                TitleStyle.applyLook(fx, values)
                fx.values["path"]?.let { useFont(it, fx.values["fontName"] ?: File(it).name) }
                rebuild()
            }, LinearLayout.LayoutParams(0, ui.px(40f), 1f))
            line.addView(ui.icon("Delete ${entry.getString("name")}", "Delete") {
                a.choose("Delete this saved style?", listOf("Keep style", "Delete style")) { option ->
                    if (option == 1) { saved.remove(i); prefs.edit().putString("title.styles", saved.toString()).apply(); rebuild() }
                }
            }, LinearLayout.LayoutParams(ui.px(36f), ui.px(40f)))
            ui.add(parent, line); ui.gap(parent, 5f)
        }
        for ((collection,styles) in TitleStyle.lookCollections) {
            ui.add(parent, ui.text(collection, bold = true), 24f)
            val looks = styles.entries.toList()
            ui.grid(parent, looks.size, 3, 47f) { i ->
                val (name, values) = looks[i]
                val candidate = fx.copyEffect().also { TitleStyle.applyLook(it, values) }
                ui.column().apply {
                    background = ui.shape(); setPadding(ui.px(5f), ui.px(4f), ui.px(5f), ui.px(4f)); contentDescription = name
                    setOnClickListener { TitleStyle.applyLook(fx, values); rebuild() }
                    addView(specimen(candidate), LinearLayout.LayoutParams(-1, 0, 1f))
                    addView(ui.text(name, 8f, Panels.MUTED), LinearLayout.LayoutParams(-1, ui.px(15f)))
                }
            }
        }
        ui.add(parent, ui.action("Save as channel style", "Bookmark", background = Color.TRANSPARENT) {
            val input = EditText(a).apply { hint = "Style name"; filters = arrayOf(InputFilter.LengthFilter(60)) }
            val dialog = android.app.AlertDialog.Builder(a).setTitle("Save channel style").setView(input)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
            dialog.setOnShowListener { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty() || (0 until saved.length()).any { saved.getJSONObject(it).getString("name").equals(name, true) }) input.error = "Choose a unique style name"
                else {
                    val keys = TitleStyle.defaults.keys + listOf("gradientFirst", "gradientLast", "gradientAngle", "path", "fontName", "fontIndex")
                    val values = fx.values.filterKeys { it in keys }.toMutableMap()
                    values["path"]?.let { path ->
                        val file = File(path); val folder = File(a.toolLibraryDirectory, "fonts").apply { mkdirs() }
                        val target = File(folder, file.name); if (!target.exists()) file.copyTo(target)
                        values["path"] = target.path
                    }
                    saved.put(JSONObject().put("name", name).put("values", JSONObject(values)))
                    prefs.edit().putString("title.styles", saved.toString()).apply(); dialog.dismiss(); rebuild()
                }
            } }; dialog.show()
        })
    }
    private fun fonts(parent: LinearLayout) {
        ui.add(parent,ui.action("Font library…","Font",background=Color.TRANSPARENT) {
            hideKeyboard();a.playback.pause()
            FontLibraryDialog(a,ui) { face ->
                fx.values["face"]=face.id;fx.values["path"]=face.file.path;fx.values["fontName"]=face.info.name
                fx.values["fontIndex"]=face.info.index.toString();fx.values["weight"]=face.info.weight.toString();fx.values["italic"]=face.info.italic.toString()
                rebuild()
            }.show()
        })
        val favorites = prefs.getStringSet("title.favoriteFonts", emptySet())!!.toMutableSet()
        val families = TitleStyle.families.sortedByDescending { it.id in favorites }
        fx.values["path"]?.let { ui.add(parent,ui.text(fx.values["fontName"] ?: File(it).name,11f,Panels.RED),30f) }
        for (family in families) {
            val line = ui.row()
            val caption = ui.text(family.name, 11f).apply {
                typeface = TitleStyle.font(a, fx.copyEffect().also { it.values["face"] = family.id; it.values.remove("path") })
                contentDescription = family.name; isSelected = fx.values["face"] == family.id
                setOnClickListener {
                    fx.values.remove("path"); fx.values.remove("fontName"); fx.values["face"] = family.id
                    if (fx.number("weight", 700f).toInt() !in family.weights) fx.values["weight"] = family.weights.first().toString()
                    if (!family.italic) fx.values["italic"] = "false"
                    rebuild()
                }
                setTextColor(if (isSelected) Panels.RED else Color.WHITE)
            }
            line.addView(caption, LinearLayout.LayoutParams(0, ui.px(40f), 1f))
            line.addView(ui.icon("${if (family.id in favorites) "Unfavorite" else "Favorite"} ${family.name}", if (family.id in favorites) "Star filled" else "Star") {
                if (!favorites.add(family.id)) favorites.remove(family.id)
                prefs.edit().putStringSet("title.favoriteFonts", favorites).apply(); rebuild()
            }, LinearLayout.LayoutParams(ui.px(34f), ui.px(40f)))
            ui.add(parent, line)
        }
        ui.add(parent, ui.action("More device fonts…", background = Color.TRANSPARENT) {
            val fonts = linkedMapOf("Sans serif" to "sans-serif", "Condensed" to "sans-serif-condensed", "Serif" to "serif", "Monospace" to "monospace", "Casual" to "casual", "Cursive" to "cursive")
            a.choose("Device fonts", fonts.keys.toList()) { index ->
                fx.values.remove("path"); fx.values.remove("fontName"); fx.values["face"] = fonts.values.elementAt(index); rebuild()
            }
        })
        val family = TitleStyle.families.firstOrNull { it.id == fx.values["face"] }
        val imported=FontLibrary.Reference.parse(fx.values["face"] ?: "")?.let { ref -> fx.values["path"]?.let { a.fontLibrary.describe(File(it),ref.index) } }
        val weights = imported?.weights ?: family?.weights ?: listOf(400, 500, 600, 700, 800)
        if (weights.size > 1) ui.add(parent, ui.action("Weight · ${fx.number("weight", 700f).toInt()}", color = Color.WHITE) {
            a.choose("Weight", weights.map { when(it) { 400 -> "Regular"; 500 -> "Medium"; 600 -> "Semibold"; 700 -> "Bold"; 800 -> "Extra bold"; else -> it.toString() } }) { set("weight", weights[it]); rebuild() }
        })
        if (imported==null && family?.italic != false) ui.toggle(parent, "Italic", fx.values["italic"] == "true") { set("italic", it) }
        ui.segments(parent, listOf("Left", "Center", "Right"), fx.values["align"] ?: "Center") { set("align", it); rebuild() }
        slider(parent, "Letter spacing", "tracking", 0f, -.05f, .25f)
        slider(parent, "Line spacing", "lineSpacing", 0f, 0f, .8f)
    }
    private fun fill(parent: LinearLayout) {
        ui.segments(parent, listOf("Solid", "Gradient"), if (fx.values.containsKey("gradientFirst")) "Gradient" else "Solid") { mode ->
            if (mode == "Gradient") { fx.values["gradientFirst"] = "#fff0ba"; fx.values["gradientLast"] = "#f58283"; fx.values["gradientAngle"] = "105" }
            else { fx.values.remove("gradientFirst"); fx.values.remove("gradientLast") }
            rebuild()
        }
        if (fx.values.containsKey("gradientFirst")) {
            ui.gap(parent)
            ui.grid(parent, TitleStyle.gradients.size, 3, 42f) { i ->
                val gradient = TitleStyle.gradients[i]
                ui.column().apply {
                    contentDescription = gradient.name; setOnClickListener {
                        fx.values["gradientFirst"] = gradient.first; fx.values["gradientLast"] = gradient.last; set("gradientAngle", gradient.angle); rebuild()
                    }
                    val sample = View(a).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor(gradient.first), Color.parseColor(gradient.last))).apply { cornerRadius = ui.px(5f).toFloat() } }
                    addView(sample, LinearLayout.LayoutParams(-1, ui.px(25f)))
                    addView(ui.text(gradient.name, 8f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, ui.px(17f)))
                }
            }
            color(parent, "First color", "gradientFirst", "#fff0ba"); color(parent, "Second color", "gradientLast", "#f58283")
            ui.slider(parent, "Angle", fx.number("gradientAngle", 105f), 0f, 360f, format = { "${it.roundToInt()}°" }) { set("gradientAngle", it) }
        } else color(parent, "Text color", "color", "#ffffff")
    }
    private fun motion(parent: LinearLayout) {
        ui.segments(parent, listOf("Entrance", "Exit"), if (exit) "Exit" else "Entrance") { exit = it == "Exit"; rebuild() }
        ui.gap(parent)
        val key = if (exit) "exit" else "entrance"
        val modes = listOf("None", "Fade", "Drift", "Scale", "Wipe")
        ui.grid(parent, modes.size, 3, 32f) { i ->
            ui.action(modes[i], color = Color.WHITE, background = if ((fx.values[key] ?: "None") == modes[i]) Color.rgb(27, 64, 76) else Color.rgb(35, 35, 37)) {
                set(key, modes[i]); rebuild(); if (modes[i] != "None") replay(exit)
            }
        }
        val move = fx.values[key] ?: "None"
        if (move in listOf("Drift", "Wipe")) ui.add(parent, ui.action("Direction · ${fx.values[if (exit) "outDirection" else "inDirection"] ?: if (move == "Drift") "Up" else "Right"}", color = Color.WHITE) {
            val directions = listOf("Up", "Down", "Left", "Right")
            a.choose("Direction", directions) { set(if (exit) "outDirection" else "inDirection", directions[it]); rebuild() }
        })
        if (move != "None") {
            ui.slider(parent, "Animation duration", fx.number(if (exit) "outSeconds" else "inSeconds", .35f), .05f, 5f,
                format = { "%.2f s".format(it) }) { set(if (exit) "outSeconds" else "inSeconds", (it * 20).roundToInt() / 20f) }
            ui.add(parent, ui.action(if (exit) "Preview exit" else "Preview entrance", "Play", background = Color.TRANSPARENT) { replay(exit) })
        }
    }
}
