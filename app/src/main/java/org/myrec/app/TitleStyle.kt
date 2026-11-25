package org.myrec.app

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import kotlin.math.*

object TitleStyle {
    private lateinit var context: Context
    fun configure(context: Context) { this.context = context.applicationContext }
    data class Family(val id: String, val name: String, val file: String, val weights: List<Int> = listOf(400, 500, 600, 700, 800), val italic: Boolean = true)
    val families = listOf(
        Family("system", "System", ""),
        Family("atkinsonhyperlegiblenext", "Atkinson Hyperlegible Next", "AtkinsonHyperlegibleNext[wght].ttf"),
        Family("barlowcondensed", "Barlow Condensed", "BarlowCondensed-Regular.ttf", listOf(400, 700)),
        Family("archivoblack", "Archivo Black", "ArchivoBlack-Regular.ttf", listOf(400), false),
        Family("sourceserif4", "Source Serif 4", "SourceSerif4[opsz,wght].ttf"),
        Family("fraunces", "Fraunces", "Fraunces[SOFT,WONK,opsz,wght].ttf"),
        Family("ibmplexmono", "IBM Plex Mono", "IBMPlexMono-Regular.ttf", listOf(400, 500, 700)),
        Family("bodonimoda", "Bodoni Moda", "BodoniModa[opsz,wght].ttf"),
        Family("unbounded", "Unbounded", "Unbounded[wght].ttf", italic = false),
        Family("caveat", "Caveat", "Caveat[wght].ttf", listOf(400, 500, 600, 700), false),
    )
    data class Gradient(val name: String, val first: String, val last: String, val angle: Float)
    val gradients = listOf(
        Gradient("Sunset", "#fff0ba", "#f58283", 105f), Gradient("Golden hour", "#fff5d6", "#e4a044", 85f),
        Gradient("Ember", "#ffcf74", "#c84939", 30f), Gradient("Rose", "#ffdae2", "#ba5486", 120f),
        Gradient("Lavender", "#eee0ff", "#9a87dd", 95f), Gradient("Midnight", "#91aff1", "#45417b", 45f),
        Gradient("Glacier", "#f6ffff", "#50afcd", 100f), Gradient("Lagoon", "#72e7df", "#386ab1", 20f),
        Gradient("Sea glass", "#e4fadb", "#69b7a7", 130f), Gradient("Olive", "#f4e8aa", "#829969", 70f),
        Gradient("Silver", "#ffffff", "#9ca9b8", 90f), Gradient("Copper", "#f0c6a5", "#a36556", 145f),
    )
    val defaults = mapOf("design" to "1", "face" to "system", "weight" to "700", "italic" to "false",
        "align" to "Center", "tracking" to "0", "lineSpacing" to "0", "color" to "#ffffff",
        "outlined" to "false", "border" to "#000000", "borderWidth" to ".045", "shadow" to "true",
        "shade" to "#d2000000", "shadowBlur" to ".12", "shadowOffset" to ".05", "plate" to "None",
        "backing" to "#cd111218", "padding" to ".28", "radius" to ".14")
    val lookCollections = linkedMapOf(
        "Editorial" to linkedMapOf(
            "Clean" to mapOf(
                "face" to "atkinsonhyperlegiblenext", "weight" to "600", "lineSpacing" to "0.04",
                "shade" to "#f0090b0d", "shadowBlur" to "0.085", "shadowOffset" to "0.035"),
            "Field report" to mapOf(
                "face" to "barlowcondensed", "weight" to "700", "align" to "Left",
                "tracking" to "0.02", "lineSpacing" to "0.05", "color" to "#f8f8f4",
                "shadow" to "false", "plate" to "Each line", "backing" to "#e0102335",
                "padding" to "0.15", "radius" to "0"),
            "Dateline" to mapOf(
                "face" to "ibmplexmono", "weight" to "500", "align" to "Left",
                "tracking" to "0.025", "lineSpacing" to "0.06", "color" to "#ffd79b",
                "shade" to "#fa21170f", "shadowBlur" to "0.055", "shadowOffset" to "0.055"),
            "Caption box" to mapOf(
                "face" to "atkinsonhyperlegiblenext", "weight" to "500", "lineSpacing" to "0.09",
                "shadow" to "false", "plate" to "One box", "backing" to "#a8111319",
                "padding" to "0.24", "radius" to "0.24"),
            "Pull quote" to mapOf(
                "face" to "sourceserif4", "weight" to "600", "align" to "Left",
                "tracking" to "-0.012", "lineSpacing" to "0.12", "color" to "#27231f",
                "shadow" to "false", "plate" to "One box", "backing" to "#f4efdf",
                "padding" to "0.31", "radius" to "0.025"),
            "Interview" to mapOf(
                "face" to "system", "weight" to "600", "align" to "Left",
                "tracking" to "-0.01", "lineSpacing" to "0.06", "color" to "#121d28",
                "shadow" to "false", "plate" to "One box", "backing" to "#a6eff6fa",
                "padding" to "0.26", "radius" to "0.14"),
        ),
        "Cinema" to linkedMapOf(
            "Film title" to mapOf(
                "face" to "sourceserif4", "weight" to "500", "tracking" to "0.035",
                "lineSpacing" to "0.10", "color" to "#f9efdc", "shade" to "#f5181519",
                "shadowBlur" to "0.10", "shadowOffset" to "0.045"),
            "Opening credit" to mapOf(
                "face" to "barlowcondensed", "weight" to "400", "tracking" to "0.075",
                "lineSpacing" to "0.16", "color" to "#f4f6fa", "shade" to "#f5101720",
                "shadowBlur" to "0.07", "shadowOffset" to "0.04"),
            "Intertitle" to mapOf(
                "face" to "bodonimoda", "weight" to "600", "tracking" to "0.035",
                "lineSpacing" to "0.12", "color" to "#f3e6ce", "shadow" to "false",
                "plate" to "One box", "backing" to "#d619191a", "padding" to "0.40",
                "radius" to "0.035"),
            "Daylight" to mapOf(
                "face" to "bodonimoda", "weight" to "700", "italic" to "true",
                "tracking" to "-0.012", "lineSpacing" to "0.08", "color" to "#28212a",
                "shade" to "#e5fff9ed", "shadowBlur" to "0.12", "shadowOffset" to "0"),
            "Night drive" to mapOf(
                "face" to "unbounded", "weight" to "500", "tracking" to "0.065",
                "lineSpacing" to "0.12", "color" to "#deedff", "gradientFirst" to "#f4f8fc",
                "gradientLast" to "#a9cdfa", "gradientAngle" to "90", "shade" to "#192d50",
                "shadowBlur" to "0.07", "shadowOffset" to "0.035", "plate" to "One box",
                "backing" to "#66112238", "padding" to "0.22", "radius" to "0.075"),
            "Matinée" to mapOf(
                "face" to "barlowcondensed", "weight" to "700", "italic" to "true",
                "tracking" to "0.02", "color" to "#ffdaa8", "shadow" to "false",
                "plate" to "Each line", "backing" to "#be531e27", "padding" to "0.16",
                "radius" to "0.035"),
        ),
        "Print" to linkedMapOf(
            "Headline" to mapOf(
                "face" to "archivoblack", "weight" to "400", "align" to "Left",
                "tracking" to "-0.035", "color" to "#fffcf2", "shade" to "#151319",
                "shadowBlur" to "0.018", "shadowOffset" to "0.065"),
            "Magazine italic" to mapOf(
                "face" to "fraunces", "weight" to "600", "italic" to "true",
                "tracking" to "-0.018", "lineSpacing" to "0.04", "color" to "#ffe7dc",
                "shade" to "#f5352027", "shadowBlur" to "0.09", "shadowOffset" to "0.04"),
            "Source note" to mapOf(
                "face" to "ibmplexmono", "weight" to "400", "align" to "Left",
                "tracking" to "0.015", "lineSpacing" to "0.12", "color" to "#302822",
                "shadow" to "false", "plate" to "Each line", "backing" to "#b5f4e4c7",
                "padding" to "0.16", "radius" to "0"),
            "Masthead" to mapOf(
                "face" to "bodonimoda", "weight" to "800", "align" to "Left",
                "tracking" to "-0.04", "color" to "#471628", "shadow" to "false",
                "plate" to "One box", "backing" to "#e8f7e9e9", "padding" to "0.12",
                "radius" to "0"),
            "Clothbound" to mapOf(
                "face" to "sourceserif4", "weight" to "500", "tracking" to "0.045",
                "lineSpacing" to "0.10", "color" to "#f1e2b2", "shadow" to "false",
                "plate" to "One box", "backing" to "#c8183f34", "padding" to "0.38",
                "radius" to "0.065"),
            "Field note" to mapOf(
                "face" to "caveat", "weight" to "700", "align" to "Left",
                "lineSpacing" to "0.06", "color" to "#ffe379", "shade" to "#292018",
                "shadowBlur" to "0.095", "shadowOffset" to "0.045"),
        ),
        "Display" to linkedMapOf(
            "Gaffer tape" to mapOf(
                "face" to "caveat", "weight" to "700", "align" to "Left",
                "tracking" to "0.025", "lineSpacing" to "0.06", "color" to "#1a2025",
                "shadow" to "false", "plate" to "Each line", "backing" to "#baf3f1e6",
                "padding" to "0.14", "radius" to "0"),
            "Highlighter" to mapOf(
                "face" to "atkinsonhyperlegiblenext", "weight" to "800", "align" to "Left",
                "tracking" to "-0.025", "lineSpacing" to "0.04", "color" to "#142214",
                "shadow" to "false", "plate" to "Each line", "backing" to "#d9fa68",
                "padding" to "0.13", "radius" to "0.045"),
            "Afterglow" to mapOf(
                "face" to "fraunces", "weight" to "800", "tracking" to "-0.025",
                "lineSpacing" to "0.03", "color" to "#fff0ad", "gradientFirst" to "#fff0ad",
                "gradientLast" to "#ffa68e", "gradientAngle" to "105", "shade" to "#f5462335",
                "shadowBlur" to "0.08", "shadowOffset" to "0.055"),
            "Wide type" to mapOf(
                "face" to "unbounded", "weight" to "800", "align" to "Left",
                "tracking" to "-0.03", "color" to "#ddf4c9", "shade" to "#17362b",
                "shadowBlur" to "0.02", "shadowOffset" to "0.075"),
            "Flyer" to mapOf(
                "face" to "archivoblack", "weight" to "400", "tracking" to "-0.025",
                "lineSpacing" to "0.04", "color" to "#fffff5", "shadow" to "false",
                "plate" to "One box", "backing" to "#e2ac2633", "padding" to "0.22",
                "radius" to "0"),
            "Lilac gel" to mapOf(
                "face" to "unbounded", "weight" to "600", "tracking" to "-0.025",
                "lineSpacing" to "0.05", "color" to "#261a40", "shadow" to "false",
                "plate" to "One box", "backing" to "#aae8d9ff", "padding" to "0.30",
                "radius" to "0.80"),
        ),
        "MyRec" to linkedMapOf(
            "Lower third" to mapOf("face" to "barlowcondensed", "plate" to "One box", "shadow" to "false", "align" to "Left"),
            "Warm editorial" to mapOf("face" to "fraunces", "gradientFirst" to "#fff0ba", "gradientLast" to "#f58283", "gradientAngle" to "105"),
            "Sunset mono" to mapOf("face" to "ibmplexmono", "weight" to "500", "tracking" to ".06", "gradientFirst" to "#fff0ba", "gradientLast" to "#f58283", "gradientAngle" to "105"),
        ),
    )
    val looks = lookCollections.values.flatMap { it.entries }.associate { it.key to it.value }
    fun initialize(fx: Effect, fresh: Boolean) {
        if (fx.values["design"] == "1") return
        for ((key, value) in defaults) fx.values.putIfAbsent(key, value)
        if (fresh) {
            fx.values["x"] = ".5"; fx.values["y"] = ".5"; fx.values["size"] = ".065"
        } else {
            fx.values["weight"] = if (fx.values["style"] == "Regular") "400" else "700"
            fx.values.putIfAbsent("x", ".05"); fx.values.putIfAbsent("y", ".15")
        }
    }
    fun applyLook(fx: Effect, look: Map<String, String>) {
        val border = listOf("outlined", "border", "borderWidth").associateWith { fx.values[it] ?: defaults.getValue(it) }
        for (key in defaults.keys + listOf("gradientFirst", "gradientLast", "gradientAngle", "path", "fontName", "fontIndex")) fx.values.remove(key)
        fx.values.putAll(defaults); fx.values.putAll(look); fx.values.putAll(border)
    }
    private val fonts = linkedMapOf<String, Typeface>()
    fun font(context: Context, fx: Effect): Typeface {
        val face = fx.values["face"] ?: "system"
        val weight = fx.number("weight", 700f).toInt().coerceIn(100, 1000)
        val italic = fx.values["italic"] == "true"
        val path = fx.values["path"]
        val imported=FontLibrary.Reference.parse(face)
        val index=imported?.index ?: fx.number("fontIndex").toInt()
        val stamp=path?.let { file -> File(file).let { "${it.length()}:${it.lastModified()}" } }
        val key="$face:$weight:$italic:$path:$index:$stamp"
        synchronized(fonts) { fonts[key]?.let { return it } }
        val result=run load@{
            val family = families.firstOrNull { it.id == face }
            if (imported!=null) {
                val file=path?.let(::File) ?: error("The project font is missing")
                require(file.isFile && SourceAudio.hash(file)==imported.digest) { "The project font is missing or changed" }
                val info=java.io.RandomAccessFile(file,"r").use { input -> FontFile.faces(input.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,0,input.length())).getOrNull(index) }
                    ?: error("The font face is missing")
                require(info.postScript==imported.postScript) { "The font face changed" }
                val builder=Typeface.Builder(file).setTtcIndex(index)
                if (info.weights.isNotEmpty()) builder.setFontVariationSettings("'wght' ${weight.coerceIn(info.weights.first(),info.weights.last())}")
                return@load builder.build() ?: error("Cannot load the project font")
            }
            try {
                val base = if (path != null && File(path).isFile) Typeface.Builder(File(path)).setTtcIndex(index).build() ?: error("Invalid font")
                else if (family == null) Typeface.create(face, Typeface.NORMAL)
                else if (family.id == "system") Typeface.DEFAULT
                else {
                    var name = family.file
                    if (family.id in listOf("barlowcondensed", "ibmplexmono")) {
                        val suffix = if (weight >= 600) "Bold" else if (family.id == "ibmplexmono" && weight >= 500) "Medium" else ""
                        name = name.substringBefore('-') + "-" + suffix + if (italic) "Italic.ttf" else if (suffix.isEmpty()) "Regular.ttf" else ".ttf"
                    } else if (italic && family.italic) name = when (family.id) {
                        "atkinsonhyperlegiblenext" -> "AtkinsonHyperlegibleNext-Italic[wght].ttf"
                        "sourceserif4" -> "SourceSerif4-Italic[opsz,wght].ttf"
                        "fraunces" -> "Fraunces-Italic[SOFT,WONK,opsz,wght].ttf"
                        "bodonimoda" -> "BodoniModa-Italic[opsz,wght].ttf"
                        else -> name
                    }
                    Typeface.Builder(context.assets, "title-fonts/${family.id}/$name")
                        .setFontVariationSettings("'wght' $weight").build()
                }
                Typeface.create(base, if (family?.id == "archivoblack") 400 else weight, italic)
            } catch (_: Exception) { Typeface.create(Typeface.DEFAULT, weight, italic) }
        }
        synchronized(fonts) {
            if (fonts.size>=64) fonts.remove(fonts.keys.first())
            fonts[key]=result
        }
        return result
    }
    fun color(fx: Effect, key: String, fallback: String): Int = runCatching { Color.parseColor(fx.values[key] ?: fallback) }.getOrDefault(Color.WHITE)

    fun raster(context: Context, fx: Effect, fontSize: Float, maxWidth: Int): Bitmap {
        val size = fontSize.coerceAtLeast(1f)
        val value=fx.values["text"] ?: ""
        val text=value.substring(0,value.offsetByCodePoints(0,min(500,value.codePointCount(0,value.length))))
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size; typeface = font(context, fx); letterSpacing = fx.number("tracking")
            color = Color.WHITE
        }
        val outline = if (fx.values["outlined"] == "true") fx.number("borderWidth", .045f) else 0f
        val shadow = fx.values["shadow"] != "false"
        val blur = if (shadow) fx.number("shadowBlur", .12f) else 0f
        val offset = if (shadow) fx.number("shadowOffset", .05f) else 0f
        val padding = if (fx.values["plate"] !in listOf(null, "None")) fx.number("padding", .28f) else 0f
        val inset = ceil(size * (max(outline + 2 * blur + abs(offset), padding) + .14f)).toInt() + 2
        val available = (maxWidth - inset * 2).coerceAtLeast(ceil(size).toInt())
        val desired = ceil(Layout.getDesiredWidth(text, paint)).toInt().coerceIn(1, available)
        val alignment = when (fx.values["align"]) { "Left" -> Layout.Alignment.ALIGN_NORMAL; "Right" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER }
        // Layout's API-23 constant has the same value as the API-29 LineBreaker alias.
        @android.annotation.SuppressLint("WrongConstant")
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, desired)
            .setAlignment(alignment).setIncludePad(false).setLineSpacing(size * fx.number("lineSpacing"), 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).build()
        val width = (desired + 2 * inset).coerceIn(1, 16384)
        val height = (layout.height + 2 * inset).coerceIn(1, 16384)
        require(width.toLong() * height <= 32_000_000) { "This title is too large" }
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.translate(inset.toFloat(), inset.toFloat())
        if (padding > 0) {
            val backing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(fx, "backing", "#cd111218") }
            val pad = padding * size; val radius = size * fx.number("radius", .14f)
            if (fx.values["plate"] == "Each line") for (i in 0 until layout.lineCount) {
                if (layout.getLineWidth(i) > 0) canvas.drawRoundRect(layout.getLineLeft(i) - pad, layout.getLineTop(i) - pad,
                    layout.getLineRight(i) + pad, layout.getLineBottom(i) + pad, radius, radius, backing)
            } else canvas.drawRoundRect(-pad, -pad, desired + pad, layout.height + pad, radius, radius, backing)
        }
        // Shape each complete line once so outlining keeps scripts and ligatures together.
        if (shadow) paint.setShadowLayer(max(.01f, blur * size), 0f, offset * size, color(fx, "shade", "#d2000000"))
        if (outline > 0) {
            paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = outline * size * 2; paint.color = color(fx, "border", "#000000")
            layout.draw(canvas); paint.clearShadowLayer()
        }
        paint.style = Paint.Style.FILL; paint.color = color(fx, "color", "#ffffff")
        if (fx.values.containsKey("gradientFirst")) {
            val angle = Math.toRadians(fx.number("gradientAngle", 105f).toDouble())
            val dx = cos(angle).toFloat(); val dy = sin(angle).toFloat()
            val half = (abs(dx) * desired + abs(dy) * layout.height) / 2
            paint.shader = LinearGradient(desired / 2f - dx * half, layout.height / 2f - dy * half,
                desired / 2f + dx * half, layout.height / 2f + dy * half,
                color(fx, "gradientFirst", "#fff0ba"), color(fx, "gradientLast", "#f58283"), Shader.TileMode.CLAMP)
        }
        layout.draw(canvas)
        return image
    }

    private data class RasterKey(val design: Map<String, String>, val size: Float, val width: Int, val height: Int)
    private val rasters = linkedMapOf<RasterKey, Bitmap>()

    @Synchronized fun draw(canvas: Canvas, fx: Effect, width: Int, height: Int, elapsed: Double, duration: Double) {
        val card=TitleCard.enabled(fx)
        if (fx.values["text"].isNullOrBlank() && (!card || fx.values["label"].isNullOrBlank())) return
        val key = RasterKey(fx.values.filterKeys { it !in setOf("x", "y", "size", "entrance", "exit", "inSeconds", "outSeconds", "inDirection", "outDirection", "titleOrigin", "titleDuration") },
            min(width, height) * fx.number("size", .065f), (width * .94f).toInt(), height)
        val image = rasters[key] ?: (if (card) TitleCard.raster(context,fx,key.size,key.width.coerceAtLeast(1),height)
            else raster(context, fx, key.size, key.width)).also { image ->
            while (rasters.isNotEmpty() && rasters.values.sumOf { it.allocationByteCount.toLong() } + image.allocationByteCount > 16 * 1024 * 1024)
                rasters.remove(rasters.keys.first())!!.recycle()
            if (image.allocationByteCount <= 16 * 1024 * 1024) rasters[key] = image
        }
        val pose = TitleMotion.pose(fx, elapsed, duration)
        val x = (width * fx.number("x", .5f) - image.width / 2f).coerceIn(0f, max(0f, width - image.width.toFloat()))
        val y = (height * fx.number("y", .5f) - image.height / 2f).coerceIn(0f, max(0f, height - image.height.toFloat()))
        canvas.save()
        canvas.translate(x + image.height * pose.shift, y + image.height * pose.rise)
        canvas.scale(pose.scale, pose.scale, image.width / 2f, image.height / 2f)
        canvas.clipRect(image.width * pose.left, image.height * pose.top, image.width * pose.right, image.height * pose.bottom)
        canvas.drawBitmap(image, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * pose.opacity).roundToInt() })
        canvas.restore()
        if (rasters[key] !== image) image.recycle()
    }
}

object TitleMotion {
    data class Pose(var opacity: Float = 1f, var scale: Float = 1f, var rise: Float = 0f, var shift: Float = 0f,
                    var left: Float = 0f, var right: Float = 1f, var top: Float = 0f, var bottom: Float = 1f)
    fun ends(fx: Effect, duration: Double): Pair<Double, Double> {
        val a = if (fx.values["entrance"] in listOf(null, "None")) 0.0 else fx.number("inSeconds", .35f).toDouble()
        val b = if (fx.values["exit"] in listOf(null, "None")) 0.0 else fx.number("outSeconds", .35f).toDouble()
        val fit = if (a + b == 0.0) 0.0 else min(1.0, max(0.0, duration - min(.1, max(0.0, duration * .2))) / (a + b))
        return a * fit to b * fit
    }
    fun pose(fx: Effect, time: Double, duration: Double): Pose {
        val (a, b) = ends(fx, duration)
        val entering = a > 0 && time < a
        if (!entering && !(b > 0 && time > duration - b)) return Pose()
        val t = (if (entering) time / a else (duration - time) / b).coerceIn(0.0, 1.0).toFloat()
        val eased = t * t * (3 - 2 * t)
        val p = Pose()
        val move = fx.values[if (entering) "entrance" else "exit"]
        val direction = fx.values[if (entering) "inDirection" else "outDirection"] ?: if (move == "Drift") "Up" else "Right"
        when (move) {
            "Fade" -> p.opacity = eased
            "Drift" -> {
                p.opacity = eased
                val offset = (if (entering) -.32f else .32f) * (1 - eased)
                when (direction) { "Up" -> p.rise = -offset; "Down" -> p.rise = offset; "Left" -> p.shift = -offset; else -> p.shift = offset }
            }
            "Scale" -> { p.opacity = eased; p.scale = 1 + (if (entering) -.12f else .08f) * (1 - eased) }
            "Wipe" -> when (direction) {
                "Right" -> if (entering) p.right = eased else p.left = 1 - eased
                "Left" -> if (entering) p.left = 1 - eased else p.right = eased
                "Down" -> if (entering) p.bottom = eased else p.top = 1 - eased
                else -> if (entering) p.top = 1 - eased else p.bottom = eased
            }
        }
        return p
    }
}
