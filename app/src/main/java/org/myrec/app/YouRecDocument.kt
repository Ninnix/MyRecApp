package org.myrec.app

import java.util.UUID
import org.json.*
import kotlin.math.*

/* The archive envelope is native schema 12. Refuse an untranslatable edit before
 * publishing media; a conform that drops processing is not a project restore. */
object YouRecDocument {
    fun uuid(value: String)=runCatching { UUID.fromString(value).toString() }.getOrElse { UUID.nameUUIDFromBytes(value.toByteArray()).toString() }
    private fun array(o: JSONObject,key: String)=o.optJSONArray(key) ?: JSONArray()
    private fun objects(a: JSONArray)=(0 until a.length()).map { a.getJSONObject(it) }
    private fun time(seconds: Double)=JSONObject().put("ticks",(seconds*240000).roundToLong())
    private fun seconds(o: JSONObject)=o.getLong("ticks")/240000.0
    private fun unsupported(name: String): Nothing=error("YouRec interchange cannot preserve $name yet. The original project has been kept.")
    private val names=linkedMapOf("RGB Offset" to "rgbOffset","Negative Strobe" to "negativeStrobe","Night fix" to "nightFix",
        "Color Correction" to "colorCorrection","Silent Tint" to "silentTint","Three-Strip" to "threeStrip","Bleach Bypass" to "bleachBypass",
        "Day for Night" to "dayForNight","Color Isolation" to "colorIsolation","Noir" to "noir","Expressionism" to "expressionism",
        "Quantization" to "quantization","Dolly Zoom" to "dollyZoom","Crash Zoom" to "crashZoom","Kaleidoscope" to "kaleidoscope",
        "Widescreen 1953" to "widescreen","Freeze Frame" to "freezeFrame","Step Printing" to "stepPrinting","Undercranking" to "undercranking",
        "Halation" to "halation","16mm" to "sixteenMM","Super 8" to "superEight","Chroma Key" to "chromaKey","Auto Background" to "autoBackground")
    private fun nativeValue(value: EffectValue): JSONObject {
        val (key,data)=when (value) {
            is EffectValue.Number -> "number" to value.value
            is EffectValue.Toggle -> "toggle" to value.value
            is EffectValue.Choice -> "choice" to value.value
            is EffectValue.Color -> "color" to JSONObject().put("red",value.red).put("green",value.green).put("blue",value.blue)
            is EffectValue.Media -> "media" to (value.path ?: JSONObject.NULL)
        }
        return JSONObject().put(key,JSONObject().put("_0",data))
    }
    private fun value(o: JSONObject): EffectValue {
        val key=o.keys().asSequence().single();val data=o.getJSONObject(key).opt("_0")
        return when (key) {
            "number" -> EffectValue.Number((data as Number).toDouble())
            "toggle" -> EffectValue.Toggle(data as Boolean)
            "choice" -> EffectValue.Choice(data as String)
            "color" -> (data as JSONObject).let { EffectValue.Color(it.getDouble("red"),it.getDouble("green"),it.getDouble("blue")) }
            "media" -> EffectValue.Media(if (data==null || data==JSONObject.NULL) null else data as String)
            else -> unsupported("effect value $key")
        }
    }
    private fun parameter(p: EffectParameter,initial: EffectValue,animatable: Boolean): JSONObject {
        if (!animatable && p.keyframes.isNotEmpty()) unsupported("animation of ${p.id}")
        val kind=when (val k=p.kind) {
            is ParameterKind.Slider -> JSONObject().put("slider",JSONObject().put("min",k.min).put("max",k.max).put("defaultValue",(initial as EffectValue.Number).value).put("step",k.step))
            is ParameterKind.Stepper -> JSONObject().put("stepper",JSONObject().put("values",JSONArray(k.values)).put("defaultValue",(initial as EffectValue.Number).value))
            is ParameterKind.Picker -> JSONObject().put("picker",JSONObject().put("options",JSONArray(k.values)).put("defaultValue",(initial as EffectValue.Choice).value))
            ParameterKind.Toggle -> JSONObject().put("toggle",JSONObject().put("defaultValue",(initial as EffectValue.Toggle).value))
            ParameterKind.Color -> JSONObject().put("color",JSONObject().put("defaultValue",nativeValue(initial).getJSONObject("color").getJSONObject("_0")))
            ParameterKind.Media -> JSONObject().put("media",JSONObject().put("image",true).put("video",true))
        }
        return JSONObject().put("id",p.id).put("title",p.id).put("kind",kind).put("value",nativeValue(p.value)).put("animatable",animatable)
            .put("keyframes",JSONArray(p.keyframes.map { JSONObject().put("id",uuid(it.id)).put("time",it.time).put("value",nativeValue(it.value)) }))
    }
    private fun nativeEffect(fx: Effect,clip: Clip): JSONObject {
        val kind=names[fx.kind] ?: unsupported(fx.kind)
        if (fx.parameters.isEmpty()) unsupported("legacy ${fx.kind} processing")
        if (fx.motion!=null && fx.kind!="Color Correction") unsupported("${fx.kind} pose animation")
        val start=clip.sourceTime(clip.start+fx.from)
        val origin=fx.phase?.origin ?: fx.values["origin"]?.toDoubleOrNull() ?: start
        if (origin<start && fx.from>0) unsupported("scoped ${fx.kind} phase")
        val defaults=Effect.create(fx.kind).parameters.associateBy { it.id }
        val out=JSONObject().put("id",uuid(fx.id)).put("kind",kind).put("enabled",fx.enabled).put("version",1)
            .put("start",min(origin,start)).put("end",clip.sourceTime(clip.start+min(fx.to,clip.duration)))
            .put("parameters",JSONArray(fx.parameters.map { p -> parameter(p,defaults.getValue(p.id).value,
                ExistingEffects.animatable(fx.kind,p.id) && !(fx.kind=="Negative Strobe" && p.id in listOf("timedBurst","burstLength"))) }))
        if (fx.to>=clip.duration) fx.values["clockEnd"]?.toDoubleOrNull()?.let { out.put("end",max(it,out.getDouble("end"))) }
        val phase=fx.phase
        if (fx.kind=="Negative Strobe") {
            val actual=phase ?: EffectPhase(origin)
            val points=if (actual.clock.isNotEmpty()) actual.clock else listOf(EffectTimePoint(origin,0.0),EffectTimePoint(clip.sourceOut,max(0.0,clip.sourceOut-origin)))
            if (points.size>1) out.put("strobeTimeMap",JSONArray(points.map { JSONObject().put("source",it.source).put("elapsed",it.elapsed) }))
            out.put("phaseOffset",actual.offset)
        }
        fx.values["release"]?.toDoubleOrNull()?.let {
            if (fx.kind!="Crash Zoom" || origin+it>=out.getDouble("end")) unsupported("scoped manual zoom release")
            out.put("releaseTime",origin+it)
        }
        fx.motion?.let { out.put("motion",motion(it)) }
        out.put("algorithm",if (fx.kind in listOf("Color Isolation","Chroma Key")) 2 else 1)
        return out
    }
    private fun effect(o: JSONObject,clip: Clip): Effect {
        val kind=names.entries.firstOrNull { it.value==o.getString("kind") }?.key ?: unsupported(o.getString("kind"))
        if (o.getInt("version")!=1 || o.optInt("algorithm",0)!=(if (kind in listOf("Color Isolation","Chroma Key")) 2 else 1)) unsupported("$kind algorithm version")
        if (o.has("analysisFile") || o.has("analysisDataFile") || o.has("palette") || o.has("lockedPalette")) unsupported("$kind saved analysis")
        val result=Effect.create(kind).copy(id=o.getString("id"),enabled=o.getBoolean("enabled"))
        val parameters=objects(o.getJSONArray("parameters"))
        for (stored in parameters) {
            if (result.parameter(stored.getString("id"))==null && array(stored,"keyframes").length()==0) {
                val v=value(stored.getJSONObject("value"))
                if (stored.getString("id")=="intensity" && v==EffectValue.Number(1.0) || kind=="Widescreen 1953" && stored.getString("id")=="ovalBokeh" && v==EffectValue.Toggle(false)) continue
            }
            val p=result.parameter(stored.getString("id")) ?: unsupported("$kind parameter ${stored.getString("id")}")
            p.value=value(stored.getJSONObject("value"));p.keyframes.clear()
            p.keyframes.addAll(objects(array(stored,"keyframes")).map { EffectKeyframe(it.getDouble("time"),value(it.getJSONObject("value")),it.getString("id")) })
            require(p.valid()) { "Invalid $kind parameter" }
        }
        result.from=clip.timelineOffset(max(clip.sourceIn,o.getDouble("start"))).coerceAtLeast(0.0)
        result.to=clip.timelineOffset(min(clip.sourceOut,o.getDouble("end"))).coerceAtMost(clip.duration)
        val origin=o.getDouble("start")-if (kind=="Crash Zoom") o.optDouble("phaseOffset",0.0) else 0.0
        result.values["origin"]=origin.toString()
        result.phase=EffectPhase(o.getDouble("start"),o.optDouble("phaseOffset",0.0),objects(array(o,"strobeTimeMap")).map { EffectTimePoint(it.getDouble("source"),it.getDouble("elapsed")) })
        if (o.has("releaseTime")) result.values["release"]=(o.getDouble("releaseTime")-origin).toString()
        result.motion=o.optJSONObject("motion")?.let(::motion)
        return result
    }
    private fun motion(m: EffectMotion): JSONObject {
        fun pose(p: Map<String,Double>)=JSONObject().put("values",JSONObject(p))
        return JSONObject().put("a",pose(m.a)).put("b",pose(m.b)).put("duration",m.duration).put("frame",m.frame)
            .put("timing",JSONObject().put("curve",m.timing.curve.name.replaceFirstChar { it.lowercase() }).put("rise",m.timing.rise)
                .put("release",m.timing.release).put("ramp",m.timing.ramp.name.replaceFirstChar { it.lowercase() }))
            .put("clock",JSONArray(m.clock.map { JSONObject().put("source",it.source).put("elapsed",it.elapsed) }))
    }
    private fun motion(o: JSONObject): EffectMotion {
        fun pose(key: String): Map<String,Double> {
            val p=o.getJSONObject(key);if (!p.isNull("lut") || !p.isNull("look")) unsupported("animated LUT selection")
            val v=p.getJSONObject("values");return v.keys().asSequence().associateWith { v.getDouble(it) }
        }
        val t=o.getJSONObject("timing")
        fun curve(key: String)=EffectCurve.entries.firstOrNull { it.name.equals(t.getString(key),true) } ?: unsupported("animation curve")
        return EffectMotion(pose("a"),pose("b"),EffectTiming(curve("curve"),t.getDouble("rise"),t.getDouble("release"),curve("ramp")),o.getDouble("duration"),o.getDouble("frame"),
            objects(array(o,"clock")).map { EffectTimePoint(it.getDouble("source"),it.getDouble("elapsed")) })
    }
    private fun content(clip: Clip,source: String): JSONObject {
        if (clip.frozen!=null) unsupported("held-frame audio and styling")
        val out=JSONObject().put("id",uuid(clip.id)).put("sourceID",source).put("start",clip.sourceIn).put("end",clip.sourceOut)
        val effects=JSONArray()
        for (fx in clip.effects) when (fx.kind) {
            in Effects.audio -> Unit
            in names -> effects.put(nativeEffect(fx,clip))
            "Text" -> {
                if (fx.subtitle!=null) continue
                if (!fx.enabled) unsupported("disabled text layers")
                val texts=out.optJSONArray("texts") ?: JSONArray().also { out.put("texts",it) }
                texts.put(nativeText(fx,clip))
            }
            "Color LUTs" -> {
                if (!fx.enabled || out.has("color") || fx.from>0 || fx.to<clip.duration) unsupported("scoped or stacked color grades")
                if (fx.values["source"] !in listOf(null,"Recorded")) unsupported("manual Log interpretation")
                val look=fx.values["lut"] ?: "Natural"
                val looks=listOf("natural","warm","cool","cinema","mono","portrait","daylight","goldenHour","mutedFilm","richContrast","pastel","night","silver","ink")
                val index=CubeLut.looks.indexOf(look);require(index>=0)
                val color=JSONObject().put("id",uuid(fx.id)).put("look",looks[index]).put("amount",fx.number("strength",1f))
                for (key in listOf("exposure","highlights","localRecovery","warmth","tint")) color.put(key,fx.number(key))
                fx.values["path"]?.let { color.put("imported",JSONObject().put("filename",it).put("name",fx.values["name"] ?: "Imported LUT").put("input",when (fx.values["input"]) { "Apple Log" -> "appleLog";"Apple Log 2" -> "appleLog2";"O-Log" -> unsupported("O-Log LUTs");else -> "rec709" })) }
                fx.motion?.let { color.put("motion",motion(it)) }
                out.put("color",color)
            }
            "Mirror" -> {
                if (!fx.enabled || fx.from>0 || fx.to<clip.duration) unsupported("scoped mirror transforms")
                out.put("mirror",JSONObject().put("id",uuid(fx.id)).put("horizontal",fx.number("horizontal",if (fx.number("vertical")==0f) 1f else 0f)>0).put("vertical",fx.number("vertical")>0))
            }
            "Stabilize" -> {
                if (!fx.enabled || !StabilizationSettings.modern(fx) || fx.from>0 || fx.to<clip.duration) unsupported("scoped or legacy stabilization")
                val (start,end)=StabilizationSettings.range(fx,clip)
                out.put("stabilization",JSONObject().put("id",uuid(fx.id)).put("level",StabilizationSettings.level(fx).name.lowercase())
                    .put("version",1).put("referenceStart",start).put("referenceEnd",end))
            }
            else -> unsupported(fx.kind)
        }
        if (effects.length()>0) out.put("videoEffects",effects)
        require(clip.rate in .25..4.0) { "YouRec cannot preserve this speed setting" }
        if (abs(clip.rate-1)>.000001) out.put("speed",JSONObject().put("id",uuid(clip.id+":speed")).put("rate",clip.rate))
        clip.frozen?.let { out.put("frozenFrame",JSONObject().put("sourceTime",it.sourceTime).put("duration",it.duration).put("offset",0)) }
        return out
    }
    private fun ink(value: String): JSONObject {
        val hex=value.removePrefix("#");val n=hex.toLong(16)
        return JSONObject().put("rgba",if (hex.length==6) n*256+255 else (n and 0xffffff)*256+(n ushr 24))
    }
    private fun ink(o: JSONObject): String {
        val n=o.getLong("rgba");return "#%08x".format(java.util.Locale.US,((n and 255) shl 24) or (n ushr 8))
    }
    private fun nativeText(fx: Effect,clip: Clip): JSONObject {
        if (fx.subtitle!=null) unsupported("subtitle ownership")
        if (fx.values["design"]!="1") unsupported("legacy title layout")
        val d=TitleStyle.defaults+fx.values
        if (TitleStyle.families.none { it.id==d["face"] } && FontLibrary.Reference.parse(d.getValue("face"))==null) unsupported("device-specific fonts")
        val design=JSONObject().put("face",d.getValue("face")).put("weight",d.getValue("weight").toInt()).put("italic",d.getValue("italic").toBoolean())
            .put("alignment",d.getValue("align").lowercase()).put("fill",ink(d.getValue("color"))).put("border",ink(d.getValue("border")))
            .put("shade",ink(d.getValue("shade"))).put("backing",ink(d.getValue("backing"))).put("outlined",d.getValue("outlined").toBoolean()).put("shadow",d.getValue("shadow").toBoolean())
            .put("plate",when (d["plate"]) { "One box" -> "block";"Each line" -> "lines";else -> "off" })
        for (key in listOf("tracking","lineSpacing","borderWidth","shadowBlur","shadowOffset","padding","radius")) design.put(key,d.getValue(key).toDouble())
        d["gradientFirst"]?.let { design.put("gradient",JSONObject().put("first",ink(it)).put("last",ink(d.getValue("gradientLast"))).put("angle",fx.number("gradientAngle"))) }
        val out=JSONObject().put("id",uuid(fx.id)).put("text",d["text"] ?: "Your text").put("start",clip.sourceTime(clip.start+fx.from))
            .put("end",clip.sourceTime(clip.start+min(fx.to,clip.duration))).put("x",fx.number("x",.5f)).put("y",fx.number("y",.5f)).put("size",fx.number("size",.065f))
            .put("style","bold").put("color","white").put("appearance",design)
        if (FontLibrary.Reference.parse(d.getValue("face"))!=null && d["path"]!=null)
            require(java.io.File(d.getValue("path")).name=="font-${FontLibrary.Reference.parse(d.getValue("face"))!!.digest}.sfnt") { "Invalid imported font asset" }
        val m=JSONObject()
        for (key in listOf("entrance","exit","inDirection","outDirection")) m.put(key,(d[key] ?: if (key.endsWith("Direction")) "Up" else "None").lowercase())
        for (key in listOf("inSeconds","outSeconds")) m.put(key,fx.number(key,.35f))
        out.put("motion",m)
        return out
    }
    private fun text(o: JSONObject,clip: Clip,paths: Map<String,String>): Effect {
        val fx=Effect(kind="Text",id=o.getString("id"),from=clip.timelineOffset(max(clip.sourceIn,o.getDouble("start"))),to=clip.timelineOffset(min(clip.sourceOut,o.getDouble("end"))))
        fx.values.putAll(TitleStyle.defaults);fx.values["text"]=o.getString("text");fx.values["x"]=o.getDouble("x").toString();fx.values["y"]=o.getDouble("y").toString();fx.values["size"]=o.getDouble("size").toString()
        val d=o.optJSONObject("appearance") ?: unsupported("legacy title design")
        fx.values["face"]=d.getString("face");fx.values["weight"]=d.getInt("weight").toString();fx.values["italic"]=d.getBoolean("italic").toString()
        fx.values["align"]=d.getString("alignment").replaceFirstChar { it.uppercase() }
        for ((key,name) in mapOf("color" to "fill","border" to "border","shade" to "shade","backing" to "backing")) fx.values[key]=ink(d.getJSONObject(name))
        for (key in listOf("tracking","lineSpacing","borderWidth","shadowBlur","shadowOffset","padding","radius")) fx.values[key]=d.getDouble(key).toString()
        for (key in listOf("outlined","shadow")) fx.values[key]=d.getBoolean(key).toString()
        fx.values["plate"]=when (d.getString("plate")) { "block" -> "One box";"lines" -> "Each line";else -> "None" }
        FontLibrary.Reference.parse(fx.values.getValue("face"))?.let { fx.values["path"]=paths.getValue("font-${it.digest}.sfnt");fx.values["fontIndex"]=it.index.toString();fx.values["fontName"]=it.postScript }
        o.optJSONObject("motion")?.let { m ->
            for (key in listOf("entrance","exit","inDirection","outDirection")) if (m.has(key)) fx.values[key]=m.getString(key).replaceFirstChar { it.uppercase() }
            for (key in listOf("inSeconds","outSeconds")) fx.values[key]=m.getDouble(key).toString()
        }
        d.optJSONObject("gradient")?.let { fx.values["gradientFirst"]=ink(it.getJSONObject("first"));fx.values["gradientLast"]=ink(it.getJSONObject("last"));fx.values["gradientAngle"]=it.getDouble("angle").toString() }
        return fx
    }
    private fun mapping(clip: Clip)=JSONObject().put("runs",JSONArray().apply {
        val hold=clip.frozen
        if (hold!=null) put(JSONObject().put("duration",time(hold.duration)).put("sourceStart",hold.sourceTime).put("sourceEnd",hold.sourceTime).put("silent",true))
        else {
            var total=0.0;var ticks=0L
            val under=clip.effects.filter { it.kind=="Undercranking" && it.enabled }
            for (span in clip.playbackSpans) {
                val boundaries=(listOf(span.start,span.end)+under.flatMap { fx ->
                    fx.parameter("mute")?.keyframes.orEmpty().map { it.time }+listOf(clip.sourceTime(clip.start+fx.from),clip.sourceTime(clip.start+min(fx.to,clip.duration)))
                }.filter { it>span.start && it<span.end }).distinct().sorted()
                for (i in 1 until boundaries.size) {
                    val start=boundaries[i-1];val end=boundaries[i];val mid=(start+end)/2
                    total+=span.duration*(end-start)/(span.end-span.start)
                    val boundary=(total*240000).roundToLong();val length=boundary-ticks;ticks=boundary
                    if (length<=0) continue
                    val muted=under.any { it.active(clip.timelineOffset(mid)) && (it.parameter("mute")?.at(mid) as? EffectValue.Toggle)?.value==true }
                    put(JSONObject().put("duration",JSONObject().put("ticks",length)).put("sourceStart",start).put("sourceEnd",end).put("silent",!clip.audio || muted))
                }
            }
        }
    })
    fun snapshot(project: Project,sources: Map<String,String>,captureCount: Int): JSONObject {
        require(project.duration<=604800 && project.width%2==0 && project.height%2==0 && project.fps in listOf(24,25,30,50,60)) { "YouRec requires an even canvas, a supported frame rate and a timeline under seven days" }
        val picture=JSONArray();val sound=JSONArray();val links=linkedMapOf<String,MutableList<String>>()
        var subtitles: JSONObject?=null
        for (clip in project.clips) {
            if (clip.effects.any { it.subtitle!=null } && !clip.audio) unsupported("video-owned legacy captions")
            if (!clip.video && clip.effects.any { it.kind !in Effects.audio && it.subtitle==null }) unsupported("audio-owned video processing")
            val map=mapping(clip)
            if (clip.video) picture.put(JSONObject().put("occurrenceID",uuid(clip.occurrence)).put("start",time(clip.start)).put("content",content(clip,sources.getValue(clip.path)))
                .put("layer",clip.track).put("localOrigin",time(0.0)).put("mapping",map))
            if (clip.audio) {
                val audioId=if (clip.video) uuid(clip.id+":audio") else uuid(clip.id)
                val item=JSONObject().put("id",audioId).put("occurrenceID",uuid(clip.occurrence)).put("sourceID",sources.getValue(clip.path))
                    .put("start",time(clip.start)).put("mapping",map).put("localOrigin",time(0.0)).put("volumeDB",max(-60f,clip.gainDb)).put("muted",clip.gainDb<=-60).put("enhancements",JSONArray())
                if (clip.video) { item.put("followsVideo",uuid(clip.id));links.getOrPut(clip.link ?: clip.group) { mutableListOf() }.addAll(listOf(uuid(clip.id),audioId)) }
                clip.fadeIn?.let { item.put("fadeIn",JSONObject().put("seconds",it.seconds).put("elapsed",it.elapsed)) }
                clip.fadeOut?.let { item.put("fadeOut",JSONObject().put("seconds",it.seconds).put("elapsed",it.elapsed)) }
                item.put("enhancements",JSONArray(clip.effects.filter { it.kind in Effects.audio }.map { fx ->
                    val kinds=mapOf("Low-cut filter" to "highPass","Voice EQ" to "equalizer","Output gain" to "gain")
                    val kind=kinds[fx.kind] ?: unsupported("${fx.kind} inference runtime")
                    require(AudioFilter.modern(fx)) { "YouRec cannot preserve legacy audio processing" }
                    JSONObject().put("id",uuid(fx.id+":region")).put("interval",interval(fx.from,min(clip.duration,fx.to)))
                        .put("effects",JSONArray().put(JSONObject().put("id",uuid(fx.id)).put("kind",kind).put("enabled",fx.enabled).put("implementationVersion",1)
                            .put("frequency",fx.number("frequency",if (fx.kind=="Low-cut filter") 80f else 1800f)).put("amount",fx.number("gain"))))
                }))
                item.put("censorRegions",JSONArray(clip.censors.map { r -> JSONObject().put("id",uuid(r.id)).put("interval",interval(r.from,r.to)).put("phaseOrigin",time(r.phaseOrigin))
                    .put("envelopeInterval",interval(r.envelopeFrom,r.envelopeTo)).put("settings",JSONObject().put("frequency",r.settings.frequency).put("levelDB",r.settings.levelDb)
                        .put("waveform",r.settings.waveform.name.lowercase()).put("envelope",r.settings.envelope.name.lowercase())) }))
                val words=JSONArray();val seen=mutableSetOf<String>()
                for (fx in clip.effects.filter { it.subtitle!=null }) {
                    if (!fx.enabled) unsupported("disabled caption phrases")
                    val phrase=fx.subtitle!!;val settings=nativeText(fx.copy(subtitle=null),clip)
                    for (key in listOf("text","start","end")) settings.remove(key)
                    settings.put("id",uuid(phrase.series)).put("maxWords",6).put("language","Auto").put("timingFingerprint","")
                    if (subtitles==null) subtitles=settings else if (subtitles.toString()!=settings.toString()) unsupported("independent caption designs")
                    for (w in phrase.words) if (seen.add(w.id)) words.put(JSONObject().put("id",uuid(w.id)).put("phraseID",uuid(phrase.id)).put("text",w.text).put("start",w.start).put("end",w.end))
                }
                if (words.length()>0) item.put("subtitleWords",words)
                sound.put(item)
            }
            if (clip.link!=null && !(clip.video && clip.audio)) links.getOrPut(clip.link!!) { mutableListOf() }.add(uuid(clip.id))
        }
        val timeline=JSONObject().put("video",picture).put("audio",sound).put("links",JSONArray(links.filter { it.value.distinct().size>1 }.map { (key,members) -> JSONObject().put("id",uuid(key)).put("members",JSONArray(members.distinct())) }))
            .put("bookmarks",JSONArray()).put("placements",JSONArray()).put("transitions",JSONArray()).put("generated",JSONArray())
        val bookmarks=JSONArray();val seen=mutableSetOf<String>()
        for (mark in project.bookmarks) bookmarks.put(JSONObject().put("id",uuid(mark.id)).put("label",mark.label).put("anchor",JSONObject().put("project",JSONObject().put("_0",time(mark.time)))))
        for (clip in project.clips) for (mark in (clip.bookmarks+clip.marks.map(::recordingBookmark)).distinctBy { it.id }) {
            val id=uuid("${clip.occurrence}:${mark.id}");if (!seen.add(id)) continue
            val anchor=if (mark.local) JSONObject().put("local",JSONObject().put("occurrence",uuid(clip.occurrence)).put("offset",time(mark.time)))
                else JSONObject().put("source",JSONObject().put("occurrence",uuid(clip.occurrence)).put("source",sources.getValue(clip.path)).put("time",mark.time))
            bookmarks.put(JSONObject().put("id",id).put("label",mark.label).put("anchor",anchor))
        }
        timeline.put("bookmarks",bookmarks)
        subtitles?.let { timeline.put("subtitles",it) }
        timeline.put("transitions",JSONArray(project.transitions.map(::transition)))
        return JSONObject().put("captureCursor",captureCount).put("state",JSONObject().put("timeline",timeline).put("outputSize",JSONArray(listOf(project.width,project.height))))
    }
    fun project(snapshot: JSONObject,header: JSONObject,paths: Map<String,String>): Project {
        val state=snapshot.getJSONObject("state");val timeline=state.getJSONObject("timeline")
        for (key in listOf("placements","generated")) if (array(timeline,key).length()>0) unsupported(key)
        val subtitles=timeline.optJSONObject("subtitles")
        val sources=objects(header.getJSONArray("sources")).associateBy { it.getString("id") }
        val size=state.optJSONArray("outputSize")
        val result=Project(id=header.getString("id"),name=header.getString("title"),width=size?.getInt(0) ?: 1920,height=size?.getInt(1) ?: 1080,
            fps=header.optJSONObject("exportSettings")?.optInt("frameRate",30) ?: 30,captureCount=0)
        val membership=objects(array(timeline,"links")).flatMap { link -> (0 until link.getJSONArray("members").length()).map { link.getJSONArray("members").getString(it) to link.getString("id") } }.toMap()
        fun add(item: JSONObject,video: Boolean) {
            val body=if (video) item.getJSONObject("content") else item
            val source=sources.getValue(body.getString("sourceID"));val filename=source.getString("filename")
            var cursor=seconds(item.getJSONObject("start"));val owner=body.getString("id")
            if (source.optJSONArray("videoEffects")?.length()?.let { it>0 }==true) unsupported("captured source processing")
            for ((index,run) in objects(item.getJSONObject("mapping").getJSONArray("runs")).withIndex()) {
                val from=run.getDouble("sourceStart");val to=run.getDouble("sourceEnd");val duration=seconds(run.getJSONObject("duration"))
                require(duration>0 && from>=0 && to>=from && to<=source.getDouble("duration")+.001)
                val hold=from==to
                if (hold) unsupported("held-frame source processing")
                val c=Clip(id=if (index==0) owner else uuid("$owner:$index"),path=paths.getValue(filename),sourceIn=from,sourceOut=if (hold) min(source.getDouble("duration"),from+.000001) else to,
                    start=cursor,track=if (video) item.optInt("layer",0) else 0,rate=if (hold) 1.0 else (to-from)/duration,
                    video=video,audio=!video,linked=membership.containsKey(owner),group=owner,
                    occurrence=item.getString("occurrenceID"),link=membership[owner],photo=source.optBoolean("isStillPhoto",false),
                    gainDb=if (item.optBoolean("muted",false) || !video && run.optBoolean("silent",false)) -60f else item.optDouble("volumeDB",0.0).toFloat(),frozen=if (hold) FrozenFrame(from,duration) else null)
                if (video) {
                    val allowed=setOf("id","sourceID","start","end","speed","frozenFrame","videoEffects","texts","color","mirror","stabilization")
                    for (key in body.keys()) if (key !in allowed && body.opt(key)!=JSONObject.NULL) unsupported(key)
                    c.effects.addAll(objects(array(body,"videoEffects")).filter { it.getDouble("start")<to && it.getDouble("end")>from }.map { effect(it,c).apply {
                        if (kind in listOf("Undercranking","Step Printing")) {
                            parameter("speed")?.let { p -> p.value=EffectValue.Number(1.0);p.keyframes.clear() }
                            motion=motion?.let { m -> m.copy(a=m.a-"speed",b=m.b-"speed").takeIf { it.a.isNotEmpty() } }
                        }
                    } })
                    c.effects.addAll(objects(array(body,"texts")).filter { it.getDouble("start")<to && it.getDouble("end")>from }.map { text(it,c,paths) })
                    body.optJSONObject("stabilization")?.let {
                        require(it.optInt("version",1)==1)
                        val fx=Effect.create("Stabilize").copy(id=it.optString("id",uuid(owner+":stabilization")),to=c.duration)
                        fx.values["level"]=it.getString("level").replaceFirstChar { it.uppercase() }
                        fx.values["referenceStart"]=it.optDouble("referenceStart",from).toString();fx.values["referenceEnd"]=it.optDouble("referenceEnd",to).toString()
                        require(StabilizationSettings.valid(fx,c));c.effects.add(fx)
                    }
                    body.optJSONObject("mirror")?.let { c.effects.add(Effect(id=it.getString("id"),kind="Mirror",to=c.duration,values=mutableMapOf("horizontal" to (if (it.getBoolean("horizontal")) "1" else "0"),"vertical" to (if (it.getBoolean("vertical")) "1" else "0")))) }
                    body.optJSONObject("color")?.let {
                        val looks=listOf("natural","warm","cool","cinema","mono","portrait","daylight","goldenHour","mutedFilm","richContrast","pastel","night","silver","ink")
                        val index=looks.indexOf(it.getString("look"));require(index>=0)
                        val fx=Effect(id=it.getString("id"),kind="Color LUTs",to=c.duration,values=mutableMapOf("lut" to CubeLut.looks[index],"strength" to it.getDouble("amount").toString(),"balanceModel" to "2"))
                        for (key in listOf("exposure","highlights","localRecovery","warmth","tint")) fx.values[key]=it.optDouble(key,0.0).toString()
                        fx.motion=it.optJSONObject("motion")?.let(::motion)
                        it.optJSONObject("imported")?.let { lut -> fx.values["path"]=paths.getValue(lut.getString("filename"));fx.values["name"]=lut.getString("name");fx.values["input"]=when (lut.getString("input")) { "appleLog" -> "Apple Log";"appleLog2" -> "Apple Log 2";else -> "Rec.709" } }
                        c.effects.add(fx)
                    }
                } else {
                    for ((phrase,words) in objects(array(item,"subtitleWords")).groupBy { it.getString("phraseID") }) {
                        val settings=subtitles ?: unsupported("caption words without a design")
                        val kept=words.filter { it.getDouble("start")<to && it.getDouble("end")>from }.map { SubtitleWord(it.getString("id"),it.getString("text"),it.getDouble("start"),it.getDouble("end")) }
                        if (kept.isEmpty()) continue
                        val title=JSONObject(settings.toString()).put("id",uuid("$owner:$phrase")).put("text",kept.joinToString(" ") { it.text }).put("start",from).put("end",to)
                        val fx=text(title,c,paths);fx.subtitle=SubtitlePhrase(settings.getString("id"),phrase,kept)
                        fx.values["subtitleOwner"]="audio";c.effects.add(fx)
                    }
                    val offset=cursor-seconds(item.getJSONObject("start"))
                    for (region in objects(array(item,"enhancements"))) {
                        val span=region.getJSONObject("interval");val lo=max(0.0,seconds(span.getJSONObject("start"))-offset);val hi=min(duration,seconds(span.getJSONObject("end"))-offset)
                        if (hi<=lo) continue
                        for (fx in objects(region.getJSONArray("effects"))) {
                            require(fx.optInt("implementationVersion",1)==1)
                            val kind=mapOf("highPass" to "Low-cut filter","equalizer" to "Voice EQ","gain" to "Output gain")[fx.getString("kind")] ?: unsupported("${fx.getString("kind")} inference runtime")
                            c.effects.add(AudioFilter.effect(kind).copy(id=fx.getString("id"),from=lo,to=hi,enabled=fx.getBoolean("enabled")).apply {
                                values["frequency"]=fx.getDouble("frequency").toString();values["gain"]=fx.getDouble("amount").toString()
                            })
                        }
                    }
                    for (r in objects(array(item,"censorRegions"))) {
                        val span=r.getJSONObject("interval");val settings=r.getJSONObject("settings");val envelope=r.optJSONObject("envelopeInterval") ?: span
                        val region=AudioCensorRegion(r.getString("id"),seconds(span.getJSONObject("start")),seconds(span.getJSONObject("end")),seconds(r.getJSONObject("phaseOrigin")),
                            AudioCensorSettings(settings.getDouble("frequency"),settings.getDouble("levelDB"),CensorWaveform.entries.first { it.name.equals(settings.getString("waveform"),true) },CensorEnvelope.entries.first { it.name.equals(settings.getString("envelope"),true) }),
                            seconds(envelope.getJSONObject("start")),seconds(envelope.getJSONObject("end")))
                        region.sliced(offset,offset+duration)?.let(c.censors::add)
                    }
                    item.optJSONObject("fadeIn")?.let { c.fadeIn=AudioEdgeFade(it.getDouble("seconds"),it.optDouble("elapsed",0.0)+cursor-seconds(item.getJSONObject("start"))).takeIf { fade -> fade.valid() } }
                    item.optJSONObject("fadeOut")?.let {
                        val length=objects(item.getJSONObject("mapping").getJSONArray("runs")).sumOf { r -> seconds(r.getJSONObject("duration")) }
                        c.fadeOut=AudioEdgeFade(it.getDouble("seconds"),it.optDouble("elapsed",0.0)+seconds(item.getJSONObject("start"))+length-cursor-duration).takeIf { fade -> fade.valid() }
                    }
                }
                result.clips.add(c);cursor+=duration
            }
        }
        objects(array(timeline,"video")).forEach { add(it,true) };objects(array(timeline,"audio")).forEach { add(it,false) }
        for (o in objects(array(timeline,"bookmarks"))) {
            val anchor=o.getJSONObject("anchor");val id=o.getString("id");val label=o.getString("label")
            anchor.optJSONObject("project")?.let { result.bookmarks.add(SourceBookmark(id,seconds(it.getJSONObject("_0")),label)) }
                ?: run {
                    val local=anchor.has("local");val body=anchor.getJSONObject(if (local) "local" else "source")
                    val mark=SourceBookmark(id,if (local) seconds(body.getJSONObject("offset")) else body.getDouble("time"),label,local)
                    for (clip in result.clips.filter { it.occurrence==body.getString("occurrence") }) clip.bookmarks.add(mark)
                }
        }
        for (clip in result.clips) {
            val source=sources.values.first { paths.getValue(it.getString("filename"))==clip.path }
            for (event in objects(array(source,"events")).filter { it.getString("kind")=="bookmark" }) {
                val at=event.getDouble("time")
                if (clip.bookmarks.none { !it.local && abs(it.time-at)<.000001 }) clip.bookmarks.add(SourceBookmark(event.getString("id"),at,event.optString("detail")))
            }
        }
        for (o in objects(array(timeline,"transitions"))) result.transitions.add(transition(o,result))
        val timing=result.subtitleTimingFingerprint()
        for (fx in result.clips.flatMap { it.effects }.filter { it.subtitle!=null }) fx.values["subtitleTiming"]=timing
        return result
    }
    private fun interval(start: Double,end: Double)=JSONObject().put("start",time(start)).put("end",time(end))
    private val joins=mapOf("Dissolve" to "dissolve","Fade through color" to "fadeBlack","Wipe" to "wipe","Split screen" to "split","Hitchcock" to "hitchcock","Whip pan" to "whip","Flash Cut" to "flashCut","Still" to "still","Defocus" to "defocus")
    private fun transition(t: Transition): JSONObject {
        if (t.kind=="Still") unsupported("generated still transitions")
        val c=t.controls ?: unsupported("legacy transition sound curves")
        if (t.windowFrom!=null || t.windowTo!=null || t.phaseFrom!=0f || t.phaseTo!=1f) unsupported("sliced transitions")
        fun framing(f: TransitionFraming)=JSONObject().put("x",f.x).put("y",f.y).put("zoom",f.zoom)
        val settings=JSONObject().put("id",uuid(t.id)).put("style",joins[t.kind] ?: unsupported(t.kind)).put("duration",t.duration).put("axis",if (c.vertical) "vertical" else "horizontal")
            .put("divider",c.divider).put("reverse",c.reverse).put("audio",when (c.audio) { "First clip" -> "first";"Second clip" -> "second";else -> "crossfade" })
            .put("first",framing(c.first)).put("second",framing(c.second)).put("hold",t.hold).put("wipeAngle",t.angle).put("hardEdge",!t.soft)
            .put("flashCount",c.flashCount).put("flashAcceleration",c.flashAcceleration).put("stillDissolve",c.stillDissolve).put("blurAmount",t.amount)
            .put("fadeColor",JSONObject().put("red",((c.color shr 16) and 255)/255.0).put("green",((c.color shr 8) and 255)/255.0).put("blue",(c.color and 255)/255.0))
        c.audioOffset?.let { settings.put("audioOffset",it) };c.audioBlend?.let { settings.put("audioBlend",it) }
        return JSONObject().put("settings",settings).put("factor",1).put("origin",time(t.pictureOrigin)).apply {
            t.leftId?.let { put("outgoing",uuid(it)) };t.rightId?.let { put("incoming",uuid(it)) }
        }
    }
    private fun transition(o: JSONObject,p: Project): Transition {
        val s=o.getJSONObject("settings");val factor=o.optDouble("factor",1.0);require(factor.isFinite() && factor>0)
        fun framing(key: String)=s.getJSONObject(key).let { TransitionFraming(it.getDouble("x"),it.getDouble("y"),it.getDouble("zoom")) }
        val color=s.optJSONObject("fadeColor");val argb=if (color==null) 0xff000000 else 0xff000000L or ((color.getDouble("red")*255).roundToLong() shl 16) or ((color.getDouble("green")*255).roundToLong() shl 8) or (color.getDouble("blue")*255).roundToLong()
        val c=TransitionControls(s.getString("axis")=="vertical",s.getDouble("divider"),s.getBoolean("reverse"),when (s.getString("audio")) { "first" -> "First clip";"second" -> "Second clip";else -> "Crossfade" },
            framing("first"),framing("second"),argb,if (s.has("audioOffset")) s.getDouble("audioOffset")*factor else null,if (s.has("audioBlend")) s.getDouble("audioBlend")*factor else null,
            s.optInt("flashCount",3),s.optDouble("flashAcceleration",2.0),s.optDouble("stillDissolve",0.0)*factor)
        require(c.valid());if (s.optBoolean("highlightsFirst",false)) unsupported("highlight-ordered transition")
        fun owner(key: String,last: Boolean)=o.optString(key).takeIf { it.isNotEmpty() }?.let { id ->
            val clips=p.clips.filter { it.video && it.group==id };(if (last) clips.maxByOrNull { it.end } else clips.minByOrNull { it.start })?.id ?: unsupported("transition owner")
        }
        val t=Transition(id=s.getString("id"),at=0.0,kind=joins.entries.firstOrNull { it.value==s.getString("style") }?.key ?: unsupported("transition style"),
            duration=s.getDouble("duration")*factor,hold=s.optDouble("hold",0.0)*factor,amount=s.optDouble("blurAmount",.75).toFloat(),angle=s.optDouble("wipeAngle",if (c.vertical) 90.0 else 0.0).toFloat(),soft=!s.optBoolean("hardEdge",false),controls=c,leftId=owner("outgoing",true),rightId=owner("incoming",false))
        val clock=TransitionClock(t);t.at=seconds(o.getJSONObject("origin"))+(clock.lower+clock.upper)/2
        t.overlap=clock.overlap;return t
    }
    fun capture(clip: Clip,source: String)=content(clip,source)
    fun originals(header: JSONObject,paths: Map<String,String>): List<Clip> = objects(header.getJSONArray("sources")).map {
        if (array(it,"videoEffects").length()>0) unsupported("captured source processing")
        val duration=it.getDouble("duration");require(duration.isFinite() && duration in .000001..604800.0)
        Clip(id=uuid(it.getString("id")+":original"),path=paths.getValue(it.getString("filename")),sourceOut=duration,
            video=!it.optBoolean("audioOnly",false),audio=!it.optBoolean("isStillPhoto",false),photo=it.optBoolean("isStillPhoto",false),
            bookmarks=objects(array(it,"events")).filter { e -> e.getString("kind")=="bookmark" }.map { e -> SourceBookmark(e.getString("id"),e.getDouble("time"),e.optString("detail")) }.toMutableList())
    }
    fun materialized(snapshot: JSONObject,header: JSONObject): JSONObject {
        val result=JSONObject(snapshot.toString());val timeline=result.getJSONObject("state").getJSONObject("timeline")
        val ledger=objects(array(header,"captureLedger"));val cursor=result.optInt("captureCursor",0);require(cursor in 0..ledger.size)
        var time=objects(array(timeline,"video")).plus(objects(array(timeline,"audio"))).maxOfOrNull { item -> seconds(item.getJSONObject("start"))+objects(item.getJSONObject("mapping").getJSONArray("runs")).sumOf { seconds(it.getJSONObject("duration")) } } ?: 0.0
        var layer=objects(array(timeline,"video")).maxOfOrNull { it.optInt("layer",0) } ?: -1
        val sources=objects(header.getJSONArray("sources")).associateBy { it.getString("id") }
        for (entry in ledger.drop(cursor).filter { !it.getBoolean("insertion") }) {
            val body=entry.getJSONObject("clip");val source=sources.getValue(body.getString("sourceID"))
            val clip=Clip(id=body.getString("id"),path=source.getString("filename"),sourceIn=body.getDouble("start"),sourceOut=body.getDouble("end"),start=time,
                rate=body.optJSONObject("speed")?.getDouble("rate") ?: 1.0,video=!source.optBoolean("audioOnly",false),audio=!source.optBoolean("isStillPhoto",false))
            if (body.has("pauses") || body.has("frozenFrame")) unsupported("capture-ledger retiming")
            clip.effects.addAll(objects(array(body,"videoEffects")).map { effect(it,clip) })
            val map=mapping(clip);val occurrence=uuid(clip.id+":capture")
            if (clip.video) timeline.getJSONArray("video").put(JSONObject().put("content",body).put("start",time(time)).put("layer",++layer).put("occurrenceID",occurrence).put("mapping",map).put("localOrigin",time(0.0)))
            if (clip.audio) timeline.getJSONArray("audio").put(JSONObject().put("id",uuid(clip.id+":audio")).put("sourceID",body.getString("sourceID")).put("start",time(time))
                .put("occurrenceID",occurrence).put("mapping",map).put("localOrigin",time(0.0)).put("volumeDB",0).put("muted",false).put("enhancements",JSONArray()))
            time+=clip.duration
        }
        result.put("captureCursor",ledger.size);return result
    }
}
