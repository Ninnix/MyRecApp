package org.myrec.app

import android.graphics.*
import android.media.MediaMetadataRetriever
import java.io.Closeable
import kotlin.math.*

object Effects {
    val categories=linkedMapOf(
        "Color & style" to listOf("Night fix","Color Correction","RGB Offset","Silent Tint","Three-Strip",
            "Bleach Bypass","Day for Night","Color Isolation","Noir","Expressionism","Quantization","Halation","16mm","Super 8"),
        "Camera & frame" to listOf("Dolly Zoom","Crash Zoom","Kaleidoscope","Widescreen 1953"),
        "Time" to listOf("Negative Strobe","Freeze Frame","Step Printing","Undercranking"),
        "Background" to listOf("Auto Background","Chroma Key"),
        "Extra effects" to listOf("Zoom","Mirror","Rotation","Saturation","Exposure","Vignette","Film grain",
            "Blur","Letterbox","Strobe","Freeze","Echo","Sepia","Invert","Contrast","Warmth"),
        "Presets" to listOf("Expressionist film","Silent cinema","Vintage film","Clean"))
    val legacy = listOf("Sepia", "Invert", "Contrast", "Warmth", "Undercranking")
    val video
        get() = categories.values.flatten() + legacy

    fun correct(bitmap: Bitmap, effect: Effect, strength: Float, person: FloatArray? = null, elapsed: Double = 0.0, frame: Long = 0, decorate: Boolean=true,personWidth: Int=192,personHeight: Int=192) {
        if (effect.kind == "Color LUTs") {
            val cube = effect.values["path"]?.let { LutAssets.load(it) }
                ?: CubeLut.builtin(effect.values["lut"] ?: "Natural")
            Native.colorLut(bitmap, cube.samples, cube.size, cube.oneDimensional, cube.minimum + cube.maximum,
                when (effect.values["input"]) { "Apple Log" -> 1; "Apple Log 2" -> 2; "O-Log" -> 3; else -> 0 },
                if (effect.values["path"] == null && (effect.values["lut"] ?: "Natural") == "Natural") 0f else strength,
                effect.number("exposure"), effect.number("highlights"),
                when (effect.values["source"]) { "Apple Log" -> 1; "Apple Log 2" -> 2; "O-Log" -> 3; else -> 0 },
                effect.number("warmth"), effect.number("tint"),effect.number("localRecovery"),
                if (effect.values["balanceModel"]=="2" && (effect.number("warmth")!=0f || effect.number("tint")!=0f)) WhiteBalance.matrix(effect.number("warmth").toDouble(),effect.number("tint").toDouble()) else null,
                effect.values["path"]==null && effect.values["balanceModel"]=="2")
            return
        }
        if (strength <= 0f) return
        if (effect.kind in BackgroundEffect.kinds) {
            BackgroundRenderer().use { it.render(bitmap,effect,elapsed,0.0,"still") }
            return
        }
        if (ExistingEffects.modern(effect)) {
            when (effect.kind) {
                "Night fix" -> {
                    val original=bitmap.copy(bitmap.config!!,false)
                    try { Native.nightRestore(bitmap,original,strength,effect.number("brightness",2.5f),effect.number("reduction",1f),effect.number("detail",.5f)) }
                    finally { original.recycle() }
                }
                "Crash Zoom" -> {
                    val (zoom,velocity)=ExistingEffects.zoom(effect,elapsed)
                    Native.crashZoom(bitmap,zoom.toFloat(),min(.12,abs(velocity)*.002*effect.number("motionBlur",.6f)).toFloat(),effect.number("targetX",.5f),effect.number("targetY",.5f))
                }
                "Undercranking" -> Native.timeFilm(bitmap,0,strength,floatArrayOf(effect.number("flicker",.5f),effect.number("weave",.4f)),floor(max(0.0,elapsed)*effect.number("fps",16f)+.000001).toLong(),elapsed)
                "Super 8" -> Native.timeFilm(bitmap,1,strength,FilmColor.uniforms(effect),frame,elapsed)
                "Dolly Zoom" -> if (effect.values["captured"]!="1") {
                    var t=(elapsed/effect.number("duration",2.5f)).toFloat().coerceIn(0f,1f)
                    if (ExistingEffects.choice(effect,"easing")!="Linear") t=if (t<.5f) 4*t*t*t else 1-(-2*t+2).pow(3)/2
                    val scale=1+effect.number("strength",.3f)*(if (ExistingEffects.choice(effect,"direction")=="Compress") 1-t else t)
                    Native.dollyMask(bitmap,person ?: Person.mask(bitmap),personWidth,personHeight,scale,effect.number("targetX",.5f),effect.number("targetY",.5f),strength)
                }
            }
            return
        }
        if (FrameEffects.modern(effect)) {
            FrameEffects.correct(bitmap,effect,strength)
            if (decorate && effect.kind!="Blur") FrameEffects.decorate(bitmap,effect,strength)
            return
        }
        if (effect.kind=="Exposure" || effect.kind=="Warmth") {
            Native.legacyVisual(bitmap,if (effect.kind=="Exposure") 1 else 2,strength,effect.number("value"));return
        }
        if (Noir.modern(effect)) {
            Native.noir(bitmap,strength,Noir.uniforms(effect),frame)
            return
        }
        if (SixteenMm.modern(effect)) {
            Native.sixteenMm(bitmap,strength,FilmColor.uniforms(effect),frame)
            return
        }
        FilmColor.definitions[effect.kind]?.let {
            if (effect.kind == "Halation") Native.halation(bitmap, strength, FilmColor.uniforms(effect))
            else Native.filmColor(bitmap, it.kernel, strength, FilmColor.uniforms(effect))
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

    fun pixelId(effect: Effect) = if (ExistingEffects.modern(effect) || FrameEffects.modern(effect) || Noir.modern(effect) || SixteenMm.modern(effect)) 0 else pixelId(effect.kind)

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

class Frames(private val cancelled: () -> Boolean = { false }, private val onAnalysis: (Float?) -> Unit = {}) : Closeable {
    private var placementMembers=emptySet<String>()
    private val backgroundDelegate=lazy { BackgroundRenderer() }
    private val backgrounds by backgroundDelegate
    private data class CadenceEntry(val sourceIn: Double,val sourceOut: Double,val rate: Double,val effects: List<Effect>,val clock: EffectCadence)
    private val cadences=linkedMapOf<String,CadenceEntry>()
    private fun cadence(clip: Clip): EffectCadence {
        val effects=clip.effects.filter { it.kind in setOf("Super 8","Undercranking","Step Printing") }
        val previous=cadences[clip.id]
        if (previous!=null && previous.sourceIn==clip.sourceIn && previous.sourceOut==clip.sourceOut && previous.rate==clip.rate && previous.effects==effects) return previous.clock
        val clock=ExistingEffects.cadence(clip)
        if (cadences.size>=8) cadences.remove(cadences.keys.first())
        cadences[clip.id]=CadenceEntry(clip.sourceIn,clip.sourceOut,clip.rate,effects.map { it.copyEffect() },clock)
        return clock
    }
    private var fps = 30
    private var extended = false
    private var tenBit = false
    private var presentationFrame = 0L
    private var originalStabilization = false
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

    fun render(project: Project, at: Double, width: Int, height: Int, keepLinear: Boolean = false,
        originalStabilization: Boolean = false, frameRate: Int = project.fps, preview: Boolean = false): Bitmap {
        this.originalStabilization = originalStabilization
        fps = project.fps.coerceAtLeast(1)
        tenBit = keepLinear
        preserveHdr = keepLinear
        val t = at.coerceIn(0.0, max(0.0, project.duration - 0.001))
        val active=project.active(t)
        // An ungraded cut can tone-map directly into the display bitmap in the decoder.
        val directPreview=preview && !keepLinear && active.size==1 && active.single().effects.none { it.enabled } &&
            project.transitions.none { t>=it.begin && t<=it.end } && project.subtitleCues(t).isEmpty()
        val hasHdr = !directPreview && project.clips.any { it.video && WorkingColor.hdr(it.path) }
        extended = keepLinear || hasHdr || !directPreview && project.clips.any { it.video && it.effects.any { fx -> fx.enabled &&
            (ExistingEffects.modern(fx) && (fx.kind in ExistingEffects.noIntensity || fx.number("intensity",1f)>0 || fx.parameter("intensity")?.keyframes?.any { (it.value as? EffectValue.Number)?.value?.let { it>0 }==true }==true) || fx.kind == "Step Printing" || fx.kind == "Color LUTs" && fx.values["source"] in listOf("Apple Log", "Apple Log 2", "O-Log")) } }
        val output = if (extended) WorkingColor.bitmap(width, height) else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        placementMembers=active.map(ArchiveGeometry::owner).toSet()
        presentationFrame = floor(t * fps + .000001).toLong()
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
        active
            .filter { it.id !in connected }
            .forEach { layers.add(Layer(it.track, clip = it)) }
        for (layer in layers.sortedBy { layer -> layer.clip?.let { c ->
            c.effects.lastOrNull { it.kind=="Placement" && it.values["geometry"]=="canvas" && it.active(t-c.start) && ArchiveGeometry.matches(it,placementMembers) }?.number("order",layer.track.toFloat())?.toInt()
        } ?: layer.track }) {
            val clip = layer.clip
            if (clip != null) drawClip(canvas, clip, t, width, height)
            else drawTransition(output, layer.transition!!, layer.before, layer.after, t, frameRate.coerceAtLeast(1))
        }
        for (cue in project.subtitleCues(t))
            TitleStyle.draw(canvas,cue.effect.copyEffect().apply { values["text"]=cue.text },width,height,t-cue.start,cue.end-cue.start)
        return if (extended && !keepLinear) WorkingColor.display(output, toneMap = false).also { output.recycle() } else output
        } catch (error: Throwable) { if (!output.isRecycled) output.recycle();throw error }
    }

    private fun drawTransition(
        output: Bitmap,
        transition: Transition,
        before: Clip?,
        after: Clip?,
        t: Double,
        frameRate: Int,
    ) {
        val settings=transition.controls
        val split=settings!=null && transition.kind=="Split screen"
        val overlays=if (split) mutableListOf<(Canvas)->Unit>() else null
        fun picture(): Bitmap = when {
            !split -> output.copy(output.config!!,true)
            extended -> WorkingColor.bitmap(output.width,output.height)
            else -> Bitmap.createBitmap(output.width,output.height,Bitmap.Config.ARGB_8888)
        }
        val a = picture()
        val b = picture()
        try {
            val edgeFade=settings!=null && transition.kind=="Fade through color" && (before==null || after==null)
            val fill=if (edgeFade) settings.color.toInt() else Color.BLACK
            if (before==null && (edgeFade || split)) a.eraseColor(fill)
            if (after==null && (edgeFade || split)) b.eraseColor(fill)
            if (before != null)
                drawClip(
                    Canvas(a),
                    before,
                    (if (transition.kind=="Still" && transition.controls!=null) max(before.start,(ceil(transition.pictureOrigin*frameRate-.000001)-1)/frameRate) else t).coerceIn(before.start, before.end - .001),
                    output.width,
                    output.height,
                    transitionOverlays=overlays?.let { if (before.contains(t)) it else mutableListOf() },
                )
            if (after != null)
                drawClip(
                    Canvas(b),
                    after,
                    t.coerceIn(after.start, after.end - .001),
                    output.width,
                    output.height,
                    transitionOverlays=overlays?.let { if (after.contains(t)) it else mutableListOf() },
                )
            val progress = transition.progress(t)
            if (settings!=null) {
                val duration=TransitionClock(transition).videoDuration
                val flashing=transition.kind=="Flash Cut" &&
                    floor((t-transition.pictureOrigin)*frameRate+.000001).toInt() in settings.flashes(duration,frameRate)
                val color=settings.color.toInt()
                val p=floatArrayOf(if (settings.vertical) 1f else 0f,settings.divider.toFloat(),if (settings.reverse) 1f else 0f,
                    settings.first.x.toFloat(),settings.first.y.toFloat(),settings.first.zoom.toFloat(),settings.second.x.toFloat(),settings.second.y.toFloat(),settings.second.zoom.toFloat(),
                    Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f,if (flashing) 1f else 0f,0f)
                for (i in 9..11) p[i]=if (p[i]<=.04045f) p[i]/12.92f else ((p[i]+.055f)/1.055f).pow(2.4f)
                Native.transition(a,b,output,if (edgeFade) 0 else Effects.transitionId(transition.kind),progress,transition.amount,
                    transition.angle+(if (settings.reverse) 180f else 0f),transition.soft,p)
                if (overlays!=null) Canvas(output).let { canvas -> overlays.forEach { it(canvas) } }
            } else Native.blend(
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

    private fun drawClip(canvas: Canvas, c: Clip, at: Double, width: Int, height: Int, decorate: Boolean=true,
                         transitionOverlays: MutableList<(Canvas)->Unit>?=null) {
        val local = at - c.start
        val cadence=if (c.photo || c.frozen!=null) null else cadence(c).hold(c.sourceTime(at))
        val heldTime=cadence?.boundary ?: c.sourceTime(at)
        val effectFrame=if (cadence!=null) floor(heldTime*fps+.000001).toLong() else presentationFrame
        val fx = EffectOrder.ordered(c.effects)
            .filter { it.active(local) && (it.kind!="Placement" || ArchiveGeometry.matches(it,placementMembers)) }.map { it.sampled(c.frozen?.let { hold -> hold.sourceTime+hold.offset+local } ?: heldTime) }
            .filter { it.kind !in setOf("Freeze","Strobe","Echo","Zoom","Mirror","Rotation","Crop","Letterbox","Blur","Widescreen 1953") || it.strength(local,c.duration)>0 }
        fun overlays() {
            if (transitionOverlays==null) annotations(canvas,c,fx,local,at,width,height)
            else transitionOverlays.add { annotations(it,c,fx,local,at,width,height) }
        }
        fx.lastOrNull { it.kind=="Placement" && it.values["geometry"]=="canvas" }?.let { placement ->
            val layer=WorkingColor.bitmap(width,height)
            try {
                drawClip(Canvas(layer),c.copy(effects=c.effects.filter { it.id!=placement.id }.toMutableList()),at,width,height,decorate,transitionOverlays)
                val (transform,clip)=ArchiveGeometry.placement(placement,width.toDouble(),height.toDouble())
                val saved=canvas.save()
                try {
                    canvas.clipRect(clip.x.toFloat(),clip.y.toFloat(),(clip.x+clip.width).toFloat(),(clip.y+clip.height).toFloat())
                    canvas.drawBitmap(layer,Matrix().apply { setValues(transform.values()) },paint.apply { alpha=255;colorFilter=null })
                } finally { canvas.restoreToCount(saved) }
            } finally { layer.recycle() }
            return
        }
        val frames=fx.filter { FrameEffects.modern(it) && it.kind!="Blur" }
        val print=fx.firstOrNull { c.frozen!=null && it.kind=="Freeze Frame" && it.values["style"]=="Print" }
        if (decorate && (frames.isNotEmpty() || print!=null)) {
            val layer=WorkingColor.bitmap(width,height)
            try {
                drawClip(Canvas(layer),c,at,width,height,false)
                if (print!=null) Native.timeFilm(layer,2,1f,floatArrayOf(1f),effectFrame,heldTime)
                for (effect in frames) FrameEffects.decorate(layer,effect,effect.strength(local,c.duration))
                canvas.drawBitmap(layer,0f,0f,paint.apply { alpha=255;colorFilter=null })
                overlays()
            } finally { layer.recycle() }
            return
        }
        var sourceTime = heldTime
        if (cadence!=null) {
            val index=VideoFrameIndex.get(c.path,onAnalysis)
            sourceTime=index.times[index.first(max(c.sourceIn,heldTime)).coerceAtMost(index.first(c.sourceOut)-1).coerceIn(0,index.times.lastIndex)]
        }
        fx.firstOrNull { it.kind == "Freeze" }
            ?.let { sourceTime = c.sourceIn + it.number("time").toDouble() }
        fx.firstOrNull { it.kind == "Strobe" }
            ?.let {
                val hz = it.number("frequency", 8f)
                sourceTime = floor(sourceTime * hz) / hz
            }
        val stabilization = fx.lastOrNull { it.kind == "Stabilize" }
        val sourceStabilization = stabilization?.takeIf { StabilizationSettings.modern(it) &&
            StabilizationSettings.level(it) != StabilizationLevel.Off && !c.photo }
        if (sourceStabilization != null) sourceTime = if (originalStabilization)
            StabilizationRenderer.originalTime(c.path,sourceTime,cancelled,onAnalysis)
        else StabilizationRenderer.frameTime(c,sourceStabilization,sourceTime,cancelled,onAnalysis)
        val grade = fx.lastOrNull { it.kind == "Color LUTs" }
        val rawLog = grade?.values?.get("source") in listOf("Apple Log", "Apple Log 2", "O-Log")
        val raw = sequenceFrame(c, fx, sourceTime, width, height, rawLog) ?: return
        val bitmap =
            if (raw.isMutable && raw.config == (if (extended) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888)) raw
            else raw.copy(if (extended) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888, true).also { raw.recycle() }
        try {
        fx.filter { it.kind=="Night fix" && ExistingEffects.modern(it) }.forEach { effect ->
            NightRestore.apply(bitmap,c,effect,sourceTime,rawLog,cancelled,onAnalysis) { time ->
                frame(c.path,time,width,height,"${c.path}:night-source",rawColor=rawLog)
            }
        }
        grade?.let { Effects.correct(bitmap, it, it.strength(local, c.duration)) }
        val mapped = extended && WorkingColor.hdr(c.path) && (!preserveHdr || fx.any { it.kind in Effects.video &&
            it.kind !in setOf("Kaleidoscope", "Zoom", "Crop", "Mirror", "Rotation", "Stabilize", "Placement", "Night fix", "Dolly Zoom", "Crash Zoom") || it.kind=="Night fix" && !ExistingEffects.modern(it) || it.kind == "Freeze Frame" })
        if (mapped) Native.workingToneMap(bitmap)
        val person =
            if (
                fx.any {
                    (it.kind == "Dolly Zoom" && !ExistingEffects.modern(it) || it.kind == "Color Isolation" && it.values["mode"] == "Person") &&
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
        for (e in fx.filter { it.kind != "Color LUTs" && !(it.kind=="Night fix" && ExistingEffects.modern(it)) }) {
            if (e.kind == "Negative Strobe") {
                val original = c.effects.first { it.id == e.id }
                if (TimeEffects.negative(original, sourceTime, fps.toDouble(), c.frozen?.let { it.offset + local }))
                    Native.workingNegative(bitmap, e.strength(local, c.duration))
            }
            if (e.kind in BackgroundEffect.kinds) applyBackground(bitmap,c,e,sourceTime,
                c.frozen?.let { it.sourceTime+it.offset+at-c.start } ?: if (cadence!=null) heldTime else c.sourceTime(at))
            else if (e.kind=="Dolly Zoom" && ExistingEffects.modern(e) && e.values["captured"]!="1") {
                val index=if (c.photo || c.frozen!=null) null else VideoFrameIndex.get(c.path,onAnalysis)
                val point=index?.last(sourceTime) ?: 0
                val previous=if (index==null) emptyList() else ((point-1) downTo max(0,point-7)).map { index.times[it] }
                val subject=backgrounds.subject(bitmap,sourceTime,"${c.path}:${c.effects.hashCode()}:dolly",previous) { time,w,h ->
                    val raw=frame(c.path,time,w,h,"${c.path}:dolly-subject",rawColor=rawLog)
                    raw?.let {
                        val input=if (it.config==bitmap.config && it.isMutable) it else it.copy(bitmap.config!!,true).also { _ -> it.recycle() }
                        try {
                            val prefix=EffectOrder.ordered(c.effects).takeWhile { it.id!=e.id }.filter { it.active(c.timelineOffset(time)) }.map { it.sampled(time) }
                            for (night in prefix.filter { it.kind=="Night fix" && ExistingEffects.modern(it) })
                                NightRestore.apply(input,c,night,time,rawLog,cancelled,onAnalysis) { source -> frame(c.path,source,w,h,"${c.path}:night-source",rawColor=rawLog) }
                            grade?.let { Effects.correct(input,it.sampled(time),it.strength(local,c.duration)) }
                            if (mapped) Native.workingToneMap(input)
                            for (prior in prefix) if (prior.kind in BackgroundEffect.kinds) applyBackground(input,c,prior,time)
                            input
                        } catch (error: Throwable) { input.recycle();throw error }
                    }
                }
                Effects.correct(bitmap,e,1f,subject.alpha,sourceTime-ExistingEffects.start(e,c),effectFrame,false,subject.width,subject.height)
            }
            else Effects.correct(bitmap, e, e.strength(local, c.duration), person,
                (if (cadence!=null) heldTime else sourceTime) - (e.values["origin"]?.toDoubleOrNull() ?: c.sourceTime(c.start + e.from)),frame = effectFrame,decorate=false)
            var kind = e.kind
            val id = Effects.pixelId(e)
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

        }
        fx.lastOrNull { it.kind=="Mirror" }?.let { effect ->
            val amount=effect.strength(local,c.duration)
            if (amount in .000001f.. .999999f) {
                val mirrored=bitmap.copy(bitmap.config!!,true)
                try { FrameEffects.liveGeometry(mirrored,effect,1f);Native.workingAverage(bitmap,mirrored,1-amount) }
                finally { mirrored.recycle() }
            }
        }
        canvas.save()
        val nativeCrop=fx.lastOrNull { it.kind=="Crop" && it.values["geometry"]=="source" }
        if (nativeCrop!=null) {
            val mirror=fx.lastOrNull { it.kind=="Mirror" }
            val transform=ArchiveGeometry.crop(nativeCrop,bitmap.width.toDouble(),bitmap.height.toDouble(),width.toDouble(),height.toDouble(),
                mirror?.number("horizontal",if (mirror.number("vertical")==1f) 0f else 1f)==1f,mirror?.number("vertical")==1f)
            val matrix=Matrix().apply { setValues(transform.values()) }
            c.frozen?.let { hold ->
                fx.firstOrNull { it.kind=="Freeze Frame" }?.let { settings ->
                    val scale=1+settings.number("pushIn")*((hold.offset+local)/settings.number("hold",(hold.offset+hold.duration).toFloat())).toFloat().coerceIn(0f,1f)
                    matrix.postScale(scale,scale,width/2f,height/2f)
                }
            }
            if (sourceStabilization!=null && !originalStabilization) {
                val fit=min(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
                val dest=RectF((width-bitmap.width*fit)/2,(height-bitmap.height*fit)/2,(width+bitmap.width*fit)/2,(height+bitmap.height*fit)/2)
                val correction=StabilizationRenderer.matrix(c,sourceStabilization,sourceTime,dest,cancelled,onAnalysis)
                val normal=Matrix().apply { setScale(fit,fit);postTranslate(dest.left,dest.top) };val inverse=Matrix()
                check(normal.invert(inverse));correction.postConcat(inverse);correction.preConcat(normal)
                matrix.preConcat(correction)
            }
            canvas.drawBitmap(bitmap,matrix,paint.apply { alpha=255;colorFilter=null })
        } else {
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
            crop?.let { (it.number("rotation") + it.number("quarter") * 90)*it.strength(local,c.duration) }
                ?: fx.lastOrNull { it.kind == "Rotation" }?.let { it.number("rotation")*it.strength(local,c.duration) }
                ?: 0f
        val radians = Math.toRadians(angle.toDouble())
        val cover =
            max(
                    abs(cos(radians)) + abs(sin(radians)) * rect.height() / rect.width(),
                    abs(cos(radians)) + abs(sin(radians)) * rect.width() / rect.height(),
                )
                .toFloat()
        val zoom = (crop?.let { 1+(it.number("zoom",1f)-1)*it.strength(local,c.duration) } ?: 1f) * if (crop != null) cover else 1f
        val dx = crop?.let { it.number("x")*it.strength(local,c.duration) } ?: 0f
        val dy = crop?.let { it.number("y")*it.strength(local,c.duration) } ?: 0f
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
                if (mirror.number("horizontal",if (mirror.number("vertical")==1f) 0f else 1f)==1f) -1f else 1f,
                if (mirror.number("vertical") == 1f) -1f else 1f,
                rect.centerX(),
                rect.centerY(),
            )
        if (sourceStabilization != null && !originalStabilization) canvas.concat(StabilizationRenderer.matrix(c,sourceStabilization,sourceTime,dest,cancelled,onAnalysis))
        else if (stabilization != null && !StabilizationSettings.modern(stabilization) && stabilization.number("strength", 0.5f) > 0f) {
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
                paint.alpha = (fx.last { it.kind == "Echo" }.strength(local,c.duration,.4f) * 160).toInt()
                canvas.drawBitmap(previous, null, dest, paint)
                paint.alpha = 255
                previous.recycle()
            }
        }
        }
        canvas.restore()
        } finally { bitmap.recycle() }
        for (e in fx.filter { it.kind=="Letterbox" && !FrameEffects.modern(it) }) {
            paint.color=Color.BLACK
            val margin=height*e.number("strength",.12f).coerceIn(0f,.4f)
            canvas.drawRect(0f,0f,width.toFloat(),margin,paint)
            canvas.drawRect(0f,height-margin,width.toFloat(),height.toFloat(),paint)
        }
        if (decorate) overlays()
    }

    private fun annotations(canvas: Canvas,c: Clip,fx: List<Effect>,local: Double,at: Double,width: Int,height: Int) {
        for (e in fx) {
            if (e.subtitle!=null && e.values["subtitleOwner"]=="audio") continue
            if (e.kind == "Text" && e.values["design"] == "1") {
                val phase = e.phase
                val titleTime=c.frozen?.let { it.sourceTime+it.offset+local } ?: c.sourceTime(at)
                val elapsed = if (phase == null) local - e.from else phase.offset + phase.elapsed(titleTime) -
                    (e.values["titleOrigin"]?.toDoubleOrNull() ?: 0.0)
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
                val path=e.values["path"] ?: continue
                val overlay=if (e.values["geometry"]=="center") PhotoSource.frame(path,width,height) else BitmapFactory.decodeFile(path) ?: continue
                try {
                    val r=ArchiveGeometry.image(e,overlay.width.toDouble(),overlay.height.toDouble(),width.toDouble(),height.toDouble())
                    paint.alpha=(255*e.number("opacity",1f).coerceIn(0f,1f)).roundToInt()
                    canvas.drawBitmap(overlay,null,RectF(r.x.toFloat(),r.y.toFloat(),(r.x+r.width).toFloat(),(r.y+r.height).toFloat()),paint)
                } finally { overlay.recycle();paint.alpha=255 }
            }
        }
    }

    private fun applyBackground(bitmap: Bitmap,clip: Clip,effect: Effect,time: Double,backgroundTime: Double=time) {
        val original=clip.effects.first { it.id==effect.id }
        val origin=original.values["origin"]?.toDoubleOrNull() ?: clip.sourceTime(clip.start+original.from)
        val times=if (clip.photo || clip.frozen!=null) emptyList() else {
            val index=VideoFrameIndex.get(clip.path,onAnalysis)
            val at=index.first(time).coerceAtMost(index.times.lastIndex)
            ((at-1) downTo max(0,at-7)).map { index.times[it] }.filter { it>=origin-.000001 }
        }
        val prefix=EffectOrder.ordered(clip.effects).takeWhile { it.id!=effect.id }+
            clip.effects.filter { it.kind in setOf("Color LUTs","Step Printing","Freeze","Strobe") }
        val source=java.io.File(clip.path)
        val identity="${clip.path}:${source.length()}:${source.lastModified()}:${prefix.hashCode()}:${bitmap.config}:$preserveHdr"
        val journal=backgrounds.journal(effect,source)
        val matteTime=if (journal==null || clip.photo) time else VideoFrameIndex.get(clip.path,onAnalysis).let { index ->
            index.times[index.first(max(0.0,time-.001)).coerceAtMost(index.times.lastIndex)]
        }
        val captured=journal?.at(effect.values["matteTrack"] ?: effect.id,matteTime)?.captureTime ?: time
        val inputs=BackgroundEffect.prefix(clip.effects.filter { it.active(clip.timelineOffset(time)) },effect).map { it.sampled(time) }
        val signature=if (journal==null) "" else backgrounds.signature(original.sampled(captured),
            BackgroundEffect.prefix(clip.effects.filter { it.active(clip.timelineOffset(time)) },effect).map { it.sampled(captured) })
        val inputSignature=if (journal==null) "" else backgrounds.inputSignature(inputs)
        backgrounds.render(bitmap,effect,matteTime,origin,identity,times,backgroundTime,original,journal,signature,inputSignature) { at,w,h ->
            val local=clip.timelineOffset(at)
            val active=EffectOrder.ordered(clip.effects).filter { it.active(local) }.map { it.sampled(at) }
            val grade=active.lastOrNull { it.kind=="Color LUTs" }
            val rawLog=grade?.values?.get("source") in listOf("Apple Log","Apple Log 2","O-Log")
            val raw=sequenceFrame(clip,active,at,w,h,rawLog) ?: return@render null
            val input=if (raw.isMutable && raw.config==bitmap.config) raw else raw.copy(bitmap.config!!,true).also { raw.recycle() }
            try {
                active.filter { it.kind=="Night fix" && ExistingEffects.modern(it) }.forEach { night ->
                    NightRestore.apply(input,clip,night,at,rawLog,cancelled,onAnalysis) { time -> frame(clip.path,time,w,h,"${clip.path}:night-source",rawColor=rawLog) }
                }
                grade?.let { Effects.correct(input,it,it.strength(local,clip.duration)) }
                val mapped=extended && WorkingColor.hdr(clip.path)
                if (mapped) Native.workingToneMap(input)
                active.firstOrNull { it.kind=="Night fix" && it.number("motion")==1f }?.let { night ->
                    val previous=max(clip.sourceIn,at-1.0/fps)
                    if (previous<at-.001) frame(clip.path,previous,w,h,"${clip.path}:background-night",rawLog)?.let { frame ->
                        try {
                            grade?.let { Effects.correct(frame,it,it.strength(local,clip.duration)) }
                            if (mapped) Native.workingToneMap(frame)
                            if (frame.width==input.width && frame.height==input.height)
                                Native.temporal(input,frame,Native.similarity(frame,input),night.strength(local,clip.duration))
                        } finally { frame.recycle() }
                    }
                }
                for (prior in active) {
                    if (prior.id==effect.id) break
                    if (prior.kind in BackgroundEffect.kinds) applyBackground(input,clip,prior,at)
                    else if (prior.kind=="Night fix" && !ExistingEffects.modern(prior)) Effects.correct(input,prior,prior.strength(local,clip.duration))
                }
                input
            } catch (error: Throwable) { input.recycle();throw error }
        }
    }

    override fun close() {
        cadences.clear()
        if (backgroundDelegate.isInitialized()) backgrounds.close()
        photos.values.forEach { it.recycle() }
        photos.clear(); photoPaths.clear()
        decoders.values.forEach { it.close() }
        decoders.clear()
        readers.values.forEach { it.release() }
        readers.clear()
    }
}
