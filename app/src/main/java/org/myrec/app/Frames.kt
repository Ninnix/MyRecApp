package org.myrec.app

import android.graphics.*
import android.media.MediaMetadataRetriever
import java.io.Closeable
import kotlin.math.*

object Effects {
    val categories =
        linkedMapOf(
            "Presets" to listOf("Expressionism", "Silent cinema", "Vintage film", "Clean"),
            "Color & style" to
                listOf(
                    "Night fix",
                    "Color Correction",
                    "Silent Tint",
                    "Three-Strip",
                    "RGB Offset",
                    "Quantization",
                    "Bleach Bypass",
                    "Day for Night",
                    "Color Isolation",
                    "Noir",
                    "Saturation",
                    "Exposure",
                    "Vignette",
                    "Film grain",
                ),
            "Camera & frame" to
                listOf("Zoom", "Mirror", "Rotation", "Kaleidoscope", "Dolly Zoom", "Widescreen 1953"),
            "Time" to listOf("Negative Strobe", "Step Printing", "Strobe", "Freeze", "Echo"),
            "Background" to listOf("Blur", "Letterbox"),
        )
    val legacy = listOf("Sepia", "Invert", "Contrast", "Warmth", "16mm", "Undercranking")
    val video
        get() = categories.values.flatten() + legacy

    fun correct(bitmap: Bitmap, effect: Effect, strength: Float, person: FloatArray? = null, elapsed: Double = 0.0) {
        if (effect.kind == "Color LUTs") {
            val cube = effect.values["path"]?.let { LutAssets.load(it) }
                ?: CubeLut.builtin(effect.values["lut"] ?: "Natural")
            Native.colorLut(bitmap, cube.samples, cube.size, cube.oneDimensional, cube.minimum + cube.maximum,
                when (effect.values["input"]) { "Apple Log" -> 1; "Apple Log 2" -> 2; else -> 0 },
                if (effect.values["path"] == null && (effect.values["lut"] ?: "Natural") == "Natural") 0f else strength,
                effect.number("exposure"), effect.number("highlights"),
                when (effect.values["source"]) { "Apple Log" -> 1; "Apple Log 2" -> 2; else -> 0 },
                effect.number("warmth"), effect.number("tint"))
            return
        }
        if (strength <= 0f) return
        FilmColor.definitions[effect.kind]?.let {
            Native.filmColor(bitmap, it.kernel, strength, FilmColor.uniforms(effect))
            return
        }
        when (effect.kind) {
            "Dolly Zoom" -> {
                var t = (elapsed / effect.number("duration", 2.5f)).toFloat().coerceIn(0f, 1f)
                if (effect.values["easing"] != "Linear") t = if (t < .5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
                val scale = 1 + effect.number("amount", .3f) * if (effect.values["direction"] == "Compress") 1 - t else t
                Native.dolly(bitmap, person ?: Person.mask(bitmap), scale, effect.number("targetX", .5f), effect.number("targetY", .5f), strength)
            }
            "Kaleidoscope" -> Native.kaleidoscope(bitmap, strength,
                effect.number("divisions", 2f).toInt(), effect.number("centerX", .5f), effect.number("centerY", .5f),
                effect.number("splitAngle"), effect.number("sourcePanX"), effect.number("sourcePanY"), effect.number("sourceAngle"))
            "RGB Offset" ->
                Native.rgbChannels(bitmap, strength,
                    effect.number("redX"), effect.number("redY"),
                    effect.number("greenX"), effect.number("greenY"),
                    effect.number("blueX"), effect.number("blueY"))
            "Widescreen 1953" ->
                Native.widescreen(
                    bitmap,
                    strength,
                    effect.number("ratio", 2.39f),
                    if (effect.values["bars"] == "White") 1 else 0,
                    effect.number("flares", .4f),
                    runCatching { Color.parseColor(effect.values["flareColor"] ?: "#4ddfff") }
                        .getOrDefault(Color.CYAN),
                )
            "Color Correction" ->
                Native.primaryColor(bitmap, strength,
                    EffectContracts.correction.map { effect.number(it.id, it.initial.toFloat()) }.toFloatArray())
            "Night fix" -> Native.night(bitmap, strength, effect.number("cleanup", .5f))
            "Color Isolation" ->
                Native.isolate(
                    bitmap,
                    strength,
                    effect.number("hue"),
                    effect.number("tolerance", 30f),
                    effect.number("softness", .15f),
                    if (effect.values["mode"] == "Person") person ?: Person.mask(bitmap) else null,
                )
        }
    }

    val transitions =
        listOf(
            "Dissolve",
            "Fade through color",
            "Wipe",
            "Split screen",
            "Hitchcock",
            "Whip pan",
            "Flash Cut",
            "Still",
            "Defocus",
        )
    val audio =
        listOf(
            "Noise reduction",
            "Studio restoration",
            "Speech detail",
            "AI voice isolation",
            "Studio clarity",
            "Low-cut filter",
            "Voice EQ",
            "Output gain",
            "Compressor",
            "Noise gate",
            "Limiter",
        )

    fun color(kind: String): Int =
        when (kind) {
            "Text",
            "Image" -> Color.rgb(180, 79, 210)
            "Crop",
            "Rotation" -> Color.rgb(128, 105, 220)
            "Color LUTs",
            "Volume" -> Color.rgb(238, 66, 100)
            "Placement",
            "Transition" -> Color.rgb(223, 132, 45)
            "Speed" -> Color.rgb(55, 132, 235)
            "Pauses" -> Color.rgb(220, 220, 222)
            "Enhance audio",
            "Stabilize",
            "Mirror" -> Color.rgb(32, 191, 182)
            else -> Color.rgb(51, 179, 221)
        }

    fun pixelId(kind: String) =
        when (kind) {
            "Mono",
            "Noir" -> 1
            "Quantization" -> 5
            "Sepia" -> 8
            "Invert" -> 9
            "Vignette" -> 10
            "Contrast" -> 11
            "Saturation" -> 12
            "Film grain",
            "16mm" -> 13
            "Blur" -> 14
            else -> 0
        }

    fun transitionId(kind: String) =
        when (kind) {
            "Wipe" -> 1
            "Split screen" -> 2
            "Hitchcock" -> 3
            "Fade through color" -> 4
            "Flash Cut" -> 5
            "Still" -> 6
            "Defocus" -> 7
            "Whip pan" -> 8
            else -> 0
        }
}

class Frames(private val onAnalysis: (Float?) -> Unit = {}) : Closeable {
    private var fps = 30
    private var extended = false
    private var tenBit = false
    private val decoders = linkedMapOf<String, VideoDecoder>()
    private val failed = mutableSetOf<String>()
    private val photoPaths = mutableMapOf<String, Boolean>()
    private val photos = linkedMapOf<String, Bitmap>()
    private var preserveHdr = false
    private val readers = linkedMapOf<String, MediaMetadataRetriever>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun reader(path: String): MediaMetadataRetriever =
        readers.getOrPut(path) {
            if (readers.size >= 8) {
                val key = readers.keys.first()
                readers.remove(key)?.release()
            }
            MediaMetadataRetriever().apply { setDataSource(path) }
        }

    fun frame(path: String, time: Double, width: Int, height: Int, stream: String = path, rawColor: Boolean = false): Bitmap? {
        if (photoPaths.getOrPut(path) { PhotoSource.shape(path) != null }) {
            val key = "$path:$width:$height"
            val original = photos.getOrPut(key) {
                if (photos.size >= 2) photos.remove(photos.keys.first())?.recycle()
                PhotoSource.frame(path, width, height)
            }
            return if (extended) WorkingColor.bitmap(original.width, original.height).also {
                Canvas(it).drawBitmap(original, 0f, 0f, null)
            } else original.copy(Bitmap.Config.ARGB_8888, true)
        }
        val key = "$stream:$rawColor:$extended:$tenBit"
        val failure = "$path:$rawColor:$extended:$tenBit"
        if (failure !in failed)
            try {
                val decoder =
                    decoders.getOrPut(key) {
                        if (decoders.size >= 4) decoders.remove(decoders.keys.first())?.close()
                        VideoDecoder(path, rawColor, extended, tenBit)
                    }
                decoder.frame(time, width, height)?.let {
                    return it
                }
            } catch (e: Exception) {
                android.util.Log.w("MyRec", "Video decoder failed", e)
                decoders.remove(key)?.close()
                failed.add(failure)
            }
        check(!rawColor) { "Cannot read unmanaged Log values from this source" }
        check(!extended || !WorkingColor.hdr(path)) { "Cannot preserve this source's HDR samples with the available decoder" }
        return stillFrame(path, time, width, height)?.let { input ->
            if (!extended) input else WorkingColor.bitmap(input.width, input.height).also {
                Canvas(it).drawBitmap(input, 0f, 0f, null); input.recycle()
            }
        }
    }

    private fun stillFrame(path: String, time: Double, width: Int, height: Int): Bitmap? =
        try {
            SourceMirror.frame(
                path,
                reader(path)
                    .getScaledFrameAtTime(
                        (max(0.0, time) * 1_000_000).toLong(),
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        max(2, width),
                        max(2, height),
                    ),
            )
        } catch (_: Exception) {
            null
        }

    fun render(project: Project, at: Double, width: Int, height: Int, keepLinear: Boolean = false): Bitmap {
        fps = project.fps.coerceAtLeast(1)
        tenBit = keepLinear
        preserveHdr = keepLinear
        val hasHdr = project.clips.any { it.video && WorkingColor.hdr(it.path) }
        extended = keepLinear || hasHdr || project.clips.any { it.video && it.effects.any { fx -> fx.enabled &&
            (fx.kind == "Step Printing" || fx.kind == "Color LUTs" && fx.values["source"] in listOf("Apple Log", "Apple Log 2")) } }
        val output = if (extended) WorkingColor.bitmap(width, height) else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val t = at.coerceIn(0.0, max(0.0, project.duration - 0.001))
        data class Layer(
            val track: Int,
            val clip: Clip? = null,
            val transition: Transition? = null,
            val before: Clip? = null,
            val after: Clip? = null,
        )
        val layers = mutableListOf<Layer>()
        val connected = mutableSetOf<String>()
        for (transition in project.transitions.filter { t >= it.begin && t <= it.end }) {
            val before =
                project.transitionSource(transition.leftId, t)
                    ?: if (transition.leftId == null && transition.rightId == null)
                        project.clips
                            .filter { it.video && it.end <= transition.at + .02 }
                            .maxByOrNull { it.end }
                    else null
            val after =
                project.transitionSource(transition.rightId, t)
                    ?: if (transition.leftId == null && transition.rightId == null)
                        project.clips
                            .filter { it.video && it.start >= transition.at - .02 }
                            .minByOrNull { it.start }
                    else null
            project.clips
                .filter { it.group == before?.group || it.group == after?.group }
                .forEach { connected.add(it.id) }
            layers.add(
                Layer(
                    max(before?.track ?: 0, after?.track ?: 0),
                    transition = transition,
                    before = before,
                    after = after,
                )
            )
        }
        project
            .active(t)
            .filter { it.id !in connected }
            .forEach { layers.add(Layer(it.track, clip = it)) }
        for (layer in layers.sortedBy { it.track }) {
            val clip = layer.clip
            if (clip != null) drawClip(canvas, clip, t, width, height)
            else drawTransition(output, layer.transition!!, layer.before, layer.after, t)
        }
        return if (extended && !keepLinear) WorkingColor.display(output, toneMap = false).also { output.recycle() } else output
    }

    private fun drawTransition(
        output: Bitmap,
        transition: Transition,
        before: Clip?,
        after: Clip?,
        t: Double,
    ) {
        val a = output.copy(output.config!!, true)
        val b = output.copy(output.config!!, true)
        try {
            if (before != null)
                drawClip(
                    Canvas(a),
                    before,
                    t.coerceIn(before.start, before.end - .001),
                    output.width,
                    output.height,
                )
            if (after != null)
                drawClip(
                    Canvas(b),
                    after,
                    t.coerceIn(after.start, after.end - .001),
                    output.width,
                    output.height,
                )
            val progress = transition.progress(t)
            Native.blend(
                a,
                b,
                output,
                Effects.transitionId(transition.kind),
                progress,
                transition.amount,
                transition.angle,
                transition.soft,
            )
        } finally {
            a.recycle()
            b.recycle()
        }
    }

    private fun sequenceFrame(clip: Clip, effects: List<Effect>, source: Double, width: Int, height: Int, raw: Boolean): Bitmap? {
        val step = effects.firstOrNull { it.kind == "Step Printing" }
        if (step == null || clip.frozen != null || clip.photo) return frame(clip.path, source, width, height,
            "${clip.path}:${clip.sourceIn}:${clip.start}", raw)
        val original = clip.effects.first { it.id == step.id }
        val index = VideoFrameIndex.get(clip.path, onAnalysis)
        val times = index.block(source, original.phase?.origin ?: clip.sourceIn,
            step.number("step", 3f).toInt(), clip.sourceIn, clip.sourceOut)
        val stream = "${clip.path}:step:${step.id}"
        val first = frame(clip.path, times[0], width, height, stream, raw) ?: return null
        val average = first.copy(first.config!!, true)
        try {
            for (i in 1 until times.size) {
                val next = frame(clip.path, times[i], width, height, stream, raw) ?: error("A source frame is unavailable")
                try { Native.workingAverage(average, next, 1f / (i + 1)) } finally { next.recycle() }
            }
            Native.workingAverage(first, average, step.number("smear", .7f))
            return first
        } catch (error: Throwable) { first.recycle(); throw error }
        finally { average.recycle() }
    }

    private fun drawClip(canvas: Canvas, c: Clip, at: Double, width: Int, height: Int) {
        val local = at - c.start
        val fx = EffectOrder.ordered(c.effects)
            .filter { it.active(local) }.map { it.sampled(c.sourceTime(at)) }
        var sourceTime = c.sourceTime(at)
        fx.firstOrNull { it.kind == "Freeze" }
            ?.let { sourceTime = c.sourceIn + it.number("time").toDouble() }
        fx.firstOrNull { it.kind == "Strobe" }
            ?.let {
                val hz = it.number("frequency", 8f)
                sourceTime = floor(sourceTime * hz) / hz
            }
        val grade = fx.lastOrNull { it.kind == "Color LUTs" }
        val rawLog = grade?.values?.get("source") in listOf("Apple Log", "Apple Log 2")
        val raw = sequenceFrame(c, fx, sourceTime, width, height, rawLog) ?: return
        val bitmap =
            if (raw.isMutable && raw.config == (if (extended) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888)) raw
            else raw.copy(if (extended) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888, true).also { raw.recycle() }
        grade?.let { Effects.correct(bitmap, it, it.strength(local, c.duration)) }
        val mapped = extended && WorkingColor.hdr(c.path) && (!preserveHdr || fx.any { it.kind in Effects.video &&
            it.kind !in setOf("Kaleidoscope", "Zoom", "Crop", "Mirror", "Rotation", "Stabilize", "Placement") || it.kind == "Freeze Frame" })
        if (mapped) Native.workingToneMap(bitmap)
        val person =
            if (
                fx.any {
                    (it.kind == "Dolly Zoom" || it.kind == "Color Isolation" && it.values["mode"] == "Person") &&
                        it.strength(local, c.duration) > 0f
                }
            )
                Person.mask(bitmap)
            else null
        fx.firstOrNull { it.kind == "Night fix" && it.number("motion") == 1f }
            ?.let { effect ->
                val time = max(c.sourceIn, sourceTime - 1.0 / fps)
                if (time < sourceTime - .001) {
                    frame(c.path, time, width, height, "${c.path}:night:${c.sourceIn}", rawColor = rawLog)?.let {
                        previous ->
                        try {
                            grade?.let { Effects.correct(previous, it, it.strength(local, c.duration)) }
                            if (mapped) Native.workingToneMap(previous)
                            if (previous.width == bitmap.width && previous.height == bitmap.height)
                                Native.temporal(
                                    bitmap,
                                    previous,
                                    Native.similarity(previous, bitmap),
                                    effect.strength(local, c.duration),
                                )
                        } finally {
                            previous.recycle()
                        }
                    }
                }
            }
        for (e in fx.filter { it.kind != "Color LUTs" }) {
            if (e.kind == "Negative Strobe") {
                val original = c.effects.first { it.id == e.id }
                if (TimeEffects.negative(original, sourceTime, fps.toDouble(), c.frozen?.let { it.offset + local }))
                    Native.workingNegative(bitmap, e.strength(local, c.duration))
            }
            Effects.correct(bitmap, e, e.strength(local, c.duration), person,
                sourceTime - (e.values["origin"]?.toDoubleOrNull() ?: c.sourceTime(c.start + e.from)))
            var kind = e.kind
            val id = Effects.pixelId(kind)
            if (id > 0)
                Native.image(
                    bitmap,
                    id,
                    e.strength(local, c.duration),
                    if (id == 13) floor(sourceTime * 30).toFloat() else e.number("colors", 5f),
                    if (kind == "Quantization") e.number("smoothing", 0.5f) else 0f,
                    e.number("dither") == 1f,
                    e.number("palette").toInt(),
                    e.number("bw") == 1f,
                )
            if (kind == "Exposure" || kind == "Warmth") {
                val v = e.number("value", 0f) * e.strength(local, c.duration)
                val matrix =
                    if (kind == "Exposure")
                        ColorMatrix(
                            floatArrayOf(
                                1f,
                                0f,
                                0f,
                                0f,
                                v * 80,
                                0f,
                                1f,
                                0f,
                                0f,
                                v * 80,
                                0f,
                                0f,
                                1f,
                                0f,
                                v * 80,
                                0f,
                                0f,
                                0f,
                                1f,
                                0f,
                            )
                        )
                    else
                        ColorMatrix(
                            floatArrayOf(
                                1f + v * 0.2f,
                                0f,
                                0f,
                                0f,
                                0f,
                                0f,
                                1f,
                                0f,
                                0f,
                                0f,
                                0f,
                                0f,
                                1f - v * 0.2f,
                                0f,
                                0f,
                                0f,
                                0f,
                                0f,
                                1f,
                                0f,
                            )
                        )
                val p = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
                Canvas(bitmap).drawBitmap(bitmap, 0f, 0f, p)
            }
        }
        canvas.save()
        val placement = fx.lastOrNull { it.kind == "Placement" }
        val rect =
            if (placement != null)
                RectF(
                    placement.number("x") * width,
                    placement.number("y") * height,
                    (placement.number("x") + placement.number("w", 1f)) * width,
                    (placement.number("y") + placement.number("h", 1f)) * height,
                )
            else RectF(0f, 0f, width.toFloat(), height.toFloat())
        val window =
            if (placement == null) RectF(rect)
            else
                RectF(
                    rect.left + placement.number("left") * rect.width(),
                    rect.top + placement.number("top") * rect.height(),
                    rect.left + placement.number("right", 1f) * rect.width(),
                    rect.top + placement.number("bottom", 1f) * rect.height(),
                )
        canvas.clipRect(window)
        val frame = if (placement?.number("reframe") == 1f) window else rect
        val crop = fx.lastOrNull { it.kind == "Crop" || it.kind == "Zoom" }
        val ratio =
            if (placement?.number("reframe") == 1f || crop != null)
                max(frame.width() / bitmap.width, frame.height() / bitmap.height)
            else min(frame.width() / bitmap.width, frame.height() / bitmap.height)
        val dest =
            RectF(
                frame.centerX() - bitmap.width * ratio / 2,
                frame.centerY() - bitmap.height * ratio / 2,
                frame.centerX() + bitmap.width * ratio / 2,
                frame.centerY() + bitmap.height * ratio / 2,
            )
        if (placement != null) {
            canvas.translate(
                placement.number("panX") * frame.width(),
                placement.number("panY") * frame.height(),
            )
            val contentZoom = placement.number("contentZoom", 1f)
            canvas.scale(contentZoom, contentZoom, frame.centerX(), frame.centerY())
        }
        val angle =
            crop?.let { it.number("rotation") + it.number("quarter") * 90 }
                ?: fx.lastOrNull { it.kind == "Rotation" }?.number("rotation", 0f)
                ?: 0f
        val radians = Math.toRadians(angle.toDouble())
        val cover =
            max(
                    abs(cos(radians)) + abs(sin(radians)) * rect.height() / rect.width(),
                    abs(cos(radians)) + abs(sin(radians)) * rect.width() / rect.height(),
                )
                .toFloat()
        val zoom = (crop?.number("zoom", 1f) ?: 1f) * if (crop != null) cover else 1f
        val dx = crop?.number("x", 0f) ?: 0f
        val dy = crop?.number("y", 0f) ?: 0f
        c.frozen?.let { hold ->
            val settings = fx.firstOrNull { it.kind == "Freeze Frame" }
            val push = settings?.number("pushIn") ?: 0f
            val length = settings?.number("hold", (hold.offset + hold.duration).toFloat()) ?: (hold.offset + hold.duration).toFloat()
            val scale = 1 + push * ((hold.offset + local) / length).toFloat().coerceIn(0f, 1f)
            canvas.scale(scale, scale, rect.centerX(), rect.centerY())
        }
        canvas.translate(dx * rect.width(), dy * rect.height())
        canvas.scale(zoom, zoom, rect.centerX(), rect.centerY())
        canvas.rotate(angle, rect.centerX(), rect.centerY())
        val mirror = fx.lastOrNull { it.kind == "Mirror" }
        if (mirror != null)
            canvas.scale(
                if (mirror.number("vertical") == 1f) 1f else -1f,
                if (mirror.number("vertical") == 1f) -1f else 1f,
                rect.centerX(),
                rect.centerY(),
            )
        val stabilization = fx.lastOrNull { it.kind == "Stabilize" }
        if (stabilization != null && stabilization.number("strength", 0.5f) > 0f) {
            canvas.concat(
                Stabilizer.matrix(
                    c,
                    sourceTime,
                    stabilization.number("strength", 0.5f),
                    dest,
                    onAnalysis,
                )
            )
        }
        paint.colorFilter = null
        paint.alpha = 255
        canvas.drawBitmap(bitmap, null, dest, paint)
        if (fx.any { it.kind == "Echo" }) {
            val previous =
                stillFrame(c.path, max(c.sourceIn, sourceTime - 0.18), bitmap.width, bitmap.height)
            if (previous != null) {
                paint.alpha = (fx.last { it.kind == "Echo" }.number("strength", 0.4f) * 160).toInt()
                canvas.drawBitmap(previous, null, dest, paint)
                paint.alpha = 255
                previous.recycle()
            }
        }
        canvas.restore()
        bitmap.recycle()
        for (e in fx) {
            if (e.kind == "Letterbox") {
                paint.color = Color.BLACK
                val margin = height * e.number("strength", 0.12f).coerceIn(0f, 0.4f)
                canvas.drawRect(0f, 0f, width.toFloat(), margin, paint)
                canvas.drawRect(0f, height - margin, width.toFloat(), height.toFloat(), paint)
            }
            if (e.kind == "Text" && e.values["design"] == "1") {
                val phase = e.phase
                val elapsed = if (phase == null) local - e.from else phase.offset + phase.elapsed(c.sourceTime(at)) - e.number("titleOrigin")
                TitleStyle.draw(canvas, e, width, height, elapsed, e.number("titleDuration", (min(e.to, c.duration) - e.from).toFloat()).toDouble())
            } else if (e.kind == "Text") {
                paint.color =
                    try {
                        Color.parseColor(e.values["color"] ?: "#ffffff")
                    } catch (_: Exception) {
                        Color.WHITE
                    }
                paint.textSize = width * e.number("size", 0.07f)
                paint.typeface =
                    Typeface.create(
                        "sans-serif",
                        if (e.values["style"] == "Regular") Typeface.NORMAL else Typeface.BOLD,
                    )
                paint.setShadowLayer(width * 0.003f, 0f, 1f, Color.BLACK)
                val x = width * e.number("x", 0.05f)
                val y = height * e.number("y", 0.15f)
                paint.textAlign =
                    when (e.values["align"]) {
                        "Center" -> Paint.Align.CENTER
                        "Right" -> Paint.Align.RIGHT
                        else -> Paint.Align.LEFT
                    }
                val lines = mutableListOf<String>()
                val maxWidth =
                    (when (paint.textAlign) {
                            Paint.Align.CENTER -> min(x, width - x) * 2
                            Paint.Align.RIGHT -> x
                            else -> width - x
                        } - width * 0.05f)
                        .coerceAtLeast(width * 0.2f)
                for (paragraph in (e.values["text"] ?: "").split('\n')) {
                    var remaining = paragraph
                    while (remaining.isNotEmpty()) {
                        var count =
                            paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                        if (count < remaining.length) {
                            val space = remaining.lastIndexOf(' ', count - 1)
                            if (space > 0) count = space
                        }
                        lines.add(remaining.take(count))
                        remaining = remaining.drop(count).trimStart()
                    }
                }
                val lineHeight = paint.textSize * 1.15f
                val baseline = if (y > height * 0.65f) y - (lines.size - 1) * lineHeight else y
                lines.forEachIndexed { i, line ->
                    canvas.drawText(line, x, baseline + i * lineHeight, paint)
                }
                paint.clearShadowLayer()
                paint.textAlign = Paint.Align.LEFT
            }
            if (e.kind == "Image") {
                val overlay = BitmapFactory.decodeFile(e.values["path"] ?: "") ?: continue
                val x = width * e.number("x", 0.1f)
                val y = height * e.number("y", 0.1f)
                val w = width * e.number("size", 0.3f)
                canvas.drawBitmap(
                    overlay,
                    null,
                    RectF(x, y, x + w, y + w * overlay.height / overlay.width),
                    paint,
                )
                overlay.recycle()
            }
        }
    }

    override fun close() {
        photos.values.forEach { it.recycle() }
        photos.clear(); photoPaths.clear()
        decoders.values.forEach { it.close() }
        decoders.clear()
        readers.values.forEach { it.release() }
        readers.clear()
    }
}
