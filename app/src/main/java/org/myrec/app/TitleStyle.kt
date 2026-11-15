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
    val looks = linkedMapOf(
        "Clean" to mapOf("face" to "atkinsonhyperlegiblenext", "weight" to "600"),
        "Film title" to mapOf("face" to "sourceserif4", "weight" to "500"),
        "Lower third" to mapOf("face" to "barlowcondensed", "plate" to "One box", "shadow" to "false", "align" to "Left"),
        "Headline" to mapOf("face" to "archivoblack"),
        "Field note" to mapOf("face" to "caveat", "weight" to "600"),
        "Dateline" to mapOf("face" to "ibmplexmono", "weight" to "500", "tracking" to ".06"),
        "Warm editorial" to mapOf("face" to "fraunces", "gradientFirst" to "#fff0ba", "gradientLast" to "#f58283", "gradientAngle" to "105"),
        "Sunset mono" to mapOf("face" to "ibmplexmono", "weight" to "500", "tracking" to ".06", "gradientFirst" to "#fff0ba", "gradientLast" to "#f58283", "gradientAngle" to "105"),
    )
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
        for (key in defaults.keys + listOf("gradientFirst", "gradientLast", "gradientAngle", "path", "fontName")) fx.values.remove(key)
        fx.values.putAll(defaults); fx.values.putAll(look); fx.values.putAll(border)
    }
    private val fonts = linkedMapOf<String, Typeface>()
    @Synchronized fun font(context: Context, fx: Effect): Typeface {
        val face = fx.values["face"] ?: "system"
        val weight = fx.number("weight", 700f).toInt().coerceIn(100, 1000)
        val italic = fx.values["italic"] == "true"
        val path = fx.values["path"]
        if (fonts.size >= 64) fonts.remove(fonts.keys.first())
        return fonts.getOrPut("$face:$weight:$italic:$path") {
            val family = families.firstOrNull { it.id == face }
            try {
                val base = if (path != null && File(path).isFile) Typeface.createFromFile(path)
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
                        else -> name
                    }
                    Typeface.Builder(context.assets, "title-fonts/${family.id}/$name")
                        .setFontVariationSettings("'wght' $weight").build()
                }
                Typeface.create(base, if (family?.id == "archivoblack") 400 else weight, italic)
            } catch (_: Exception) { Typeface.create(Typeface.DEFAULT, weight, italic) }
        }
    }
    fun color(fx: Effect, key: String, fallback: String): Int = runCatching { Color.parseColor(fx.values[key] ?: fallback) }.getOrDefault(Color.WHITE)

    fun raster(context: Context, fx: Effect, fontSize: Float, maxWidth: Int): Bitmap {
        val size = fontSize.coerceAtLeast(1f)
        val text = (fx.values["text"] ?: "").take(500)
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

    private data class RasterKey(val design: Map<String, String>, val size: Float, val width: Int)
    private val rasters = linkedMapOf<RasterKey, Bitmap>()

    @Synchronized fun draw(canvas: Canvas, fx: Effect, width: Int, height: Int, elapsed: Double, duration: Double) {
        if (fx.values["text"].isNullOrEmpty()) return
        val key = RasterKey(fx.values.filterKeys { it !in setOf("x", "y", "size", "entrance", "exit", "inSeconds", "outSeconds", "inDirection", "outDirection", "titleOrigin", "titleDuration") },
            min(width, height) * fx.number("size", .065f), (width * .94f).toInt())
        val image = rasters[key] ?: raster(context, fx, key.size, key.width).also { image ->
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
