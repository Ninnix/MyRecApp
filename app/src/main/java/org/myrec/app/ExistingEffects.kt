package org.myrec.app

import kotlin.math.*

object ExistingEffects {
    val kinds=setOf("Night fix","Dolly Zoom","Crash Zoom","Undercranking","Super 8")
    val noIntensity=setOf("Dolly Zoom","Crash Zoom","Undercranking")
    private fun slider(id: String,title: String,min: Double,max: Double,value: Double,step: Double=.01)=
        FilmColor.Control(id,title,ParameterKind.Slider(min,max,step),EffectValue.Number(value))
    private fun pick(id: String,title: String,options: List<String>,value: String)=
        FilmColor.Control(id,title,ParameterKind.Picker(options),EffectValue.Choice(value))
    private fun toggle(id: String,title: String,value: Boolean)=FilmColor.Control(id,title,ParameterKind.Toggle,EffectValue.Toggle(value))
    private fun steps(id: String,title: String,options: List<Double>,value: Double)=
        FilmColor.Control(id,title,ParameterKind.Stepper(options),EffectValue.Number(value))
    val definitions=mapOf(
        "Night fix" to FilmColor.Definition(-1,"Restore source frames before color and composition. Fast cleans one frame; Medium and Precise align three or nine source observations. Brightness is a fixed exposure curve, so it does not pump between frames.",listOf(
            pick("mode","Mode",listOf("Fast","Medium","Precise"),"Fast"),
            slider("brightness","Shadow brightness",0.0,8.0,2.5,.1),slider("reduction","Noise reduction",0.0,3.0,1.0,.05),slider("detail","Preserve detail",0.0,1.0,.5,.05))),
        "Dolly Zoom" to FilmColor.Definition(-1,"Tap a subject and move slowly toward or away from it. During capture the lens compensates for the subject's changing size; the editor separates the person and background.",listOf(
            pick("direction","Direction",listOf("Stretch","Compress"),"Stretch"),slider("strength","Strength",.1,.5,.3),
            slider("duration","Duration",1.0,5.0,2.5,.1),pick("easing","Easing",listOf("In-out cubic","Linear"),"In-out cubic"),
            slider("targetX","Subject X",0.0,1.0,.5),slider("targetY","Subject Y",0.0,1.0,.5))),
        "Crash Zoom" to FilmColor.Definition(-1,"Tap a target and trigger a fast push with Zoom now or a volume key. Tap Return to release a held zoom. Follow face keeps a visible face centered.",listOf(
            slider("zoom","Zoom amount",1.5,4.0,2.5,.1),slider("speed","Push duration",.1,.5,.18),slider("hold","Hold",0.0,3.0,1.0,.1),
            toggle("return","Return",false),slider("motionBlur","Motion blur",0.0,1.0,.6),slider("targetX","Target X",0.0,1.0,.5),
            slider("targetY","Target Y",0.0,1.0,.5),toggle("trackFace","Follow face",true))),
        "Undercranking" to FilmColor.Definition(-1,"A fractional held-frame cadence with independently controlled speed-up, flicker and gate weave. Mute affects the source audio; background sound stays separate.",listOf(
            steps("fps","Frame rate",listOf(12.0,16.0,18.0),16.0),slider("speed","Speed-up",1.0,1.5,1.25,.05),
            slider("flicker","Flicker",0.0,1.0,.5),slider("weave","Gate weave",0.0,1.0,.4),toggle("mute","Mute audio",false))),
        "Super 8" to FilmColor.Definition(-1,"Warm, faded home-movie color, coarse grain and moving light leaks. The 18/24 fps cadence holds the complete rendered look, including grain.",listOf(
            slider("fade","Fade",0.0,1.0,.5),slider("grain","Grain",0.0,1.0,.7),slider("weave","Weave",0.0,1.0,.5),
            steps("fps","Frame rate",listOf(18.0,24.0),18.0),slider("leaks","Light leaks",0.0,1.0,.2),toggle("border","Frame border",true))))
    fun animatable(kind: String,id: String)=when (kind) {
        "Night fix" -> id!="mode"
        "Dolly Zoom" -> id!="duration"
        "Crash Zoom" -> id !in setOf("speed","hold")
        else -> true
    }
    fun editable(effect: Effect,id: String,live: Boolean): Boolean {
        if (effect.kind=="Dolly Zoom" && live && id in setOf("strength","targetX","targetY","easing")) return false
        if (effect.kind=="Crash Zoom") {
            if (id in setOf("targetX","targetY") && (live || toggle(effect,"trackFace"))) return false
            if (id in setOf("hold","return") && effect.values.containsKey("release")) return false
            if (id=="hold" && !toggle(effect,"return")) return false
        }
        return true
    }
    fun upgrade(effect: Effect) {
        val old=effect.copyEffect();effect.values.clear();effect.parameters.clear();effect.parameters.addAll(parameters(effect.kind))
        for (key in listOf("origin","clockEnd")) old.values[key]?.let { effect.values[key]=it }
        if (effect.kind !in noIntensity) effect.setNumber("intensity",old.number("strength",1f).coerceIn(0f,1f))
        if (effect.kind=="Dolly Zoom") {
            effect.setNumber("strength",old.number("amount",.3f).coerceIn(.1f,.5f))
            for (key in listOf("direction","easing")) old.values[key]?.let { value ->
                effect.parameter(key)?.takeIf { it.kind.accepts(EffectValue.Choice(value)) }?.value=EffectValue.Choice(value)
            }
            for (key in listOf("duration","targetX","targetY")) old.values[key]?.toFloatOrNull()?.let { value ->
                effect.parameter(key)?.takeIf { (it.kind as ParameterKind.Slider).let { r -> value in r.min.toFloat()..r.max.toFloat() } }?.let { effect.setNumber(key,value) }
            }
        }
    }
    fun modern(effect: Effect)=effect.kind in kinds && effect.parameters.isNotEmpty()
    fun parameters(kind: String)=(if (kind in noIntensity) emptyList() else
        listOf(EffectParameter("intensity",ParameterKind.Slider(0.0,1.0,.01),EffectValue.Number(1.0))))
        .plus(definitions.getValue(kind).controls.map { EffectParameter(it.id,it.kind,it.initial) }).toMutableList()
    fun choice(effect: Effect,key: String)=(effect.parameter(key)?.value as? EffectValue.Choice)?.value ?: effect.values[key]
    fun toggle(effect: Effect,key: String)=(effect.parameter(key)?.value as? EffectValue.Toggle)?.value==true
    fun rangeStart(effect: Effect,clip: Clip)=effect.values["rangeStart"]?.toDoubleOrNull() ?: start(effect,clip)
    fun rangeEnd(effect: Effect,clip: Clip)=effect.values["rangeEnd"]?.toDoubleOrNull() ?: end(effect,clip)
    fun start(effect: Effect,clip: Clip)=effect.values["origin"]?.toDoubleOrNull() ?: clip.sourceTime(clip.start+effect.from)
    fun end(effect: Effect,clip: Clip)=effect.values["clockEnd"]?.toDoubleOrNull() ?: clip.sourceTime(clip.start+min(effect.to,clip.duration))
    fun zoom(effect: Effect,time: Double): Pair<Double,Double> {
        val t=max(0.0,time);val push=max(.001,effect.number("speed",.18f).toDouble());val amount=effect.number("zoom",2.5f).toDouble()
        val release=effect.values["release"]?.toDoubleOrNull() ?: (push+.08+effect.number("hold",1f))
        return when {
            t<push -> 1+(amount-1)*(t/push).pow(5) to 5*(t/push).pow(4)/push
            t<push+.08 -> amount*(1+.03*sin((t-push)/.08*PI)) to (.03*PI/.08*cos((t-push)/.08*PI))
            (toggle(effect,"return") || effect.values.containsKey("release")) && t>release -> {
                val f=((t-release)/push).coerceIn(0.0,1.0)
                amount+(1-amount)*f*f*(3-2*f) to -6*f*(1-f)/push
            }
            else -> amount to 0.0
        }
    }
}

class EffectCadence(effects: List<Effect>,rates: List<PlaybackSpan> = emptyList()) {
    data class Segment(val start: Double,val end: Double,val phase: Double,val frequency: Double,val group: Double) {
        val endPhase get()=phase+(end-start)*frequency
    }
    data class Hold(val index: Long,val boundary: Double)
    private val segments: List<Segment>
    init {
        val effects=effects.filter { it.enabled && ExistingEffects.modern(it) && it.kind in setOf("Super 8","Undercranking") }
        val points=(effects.flatMap { listOf(it.from,it.to)+it.parameters.filter { it.id in setOf("fps","intensity") }.flatMap { it.keyframes.map { k -> k.time } } }+
            rates.flatMap { listOf(it.start,it.end) }).distinct().sorted()
        val result=mutableListOf<Segment>();var phase=0.0;var group=0.0;var active=false
        for ((a,b) in points.zipWithNext()) {
            if (b<=a) continue
            val middle=(a+b)/2
            val fps=effects.filter { it.active(middle) && it.numberAt("intensity",middle,1f)>0 }.minOfOrNull { it.numberAt("fps",middle).toDouble() } ?: 0.0
            if (fps<=0) { active=false;continue }
            if (!active) {
                phase=max(ceil(phase-.000001),effects.filter { it.active(middle) }.maxOfOrNull { it.values["phaseOffset"]?.toDoubleOrNull() ?: 0.0 } ?: 0.0)
                group=a
            };active=true
            val rate=rates.firstOrNull { middle>=it.start && middle<it.end }?.rate ?: 1.0
            val segment=Segment(a,b,phase,fps/max(.001,rate),group)
            result.add(segment);phase=segment.endPhase
        }
        segments=result
    }
    fun phase(time: Double): Double {
        val segment=segments.lastOrNull { it.start<time } ?: return 0.0
        return segment.phase+(min(time,segment.end)-segment.start)*segment.frequency
    }
    fun hold(time: Double): Hold? {
        var a=0;var b=segments.size
        while (a<b) { val m=(a+b)/2;if (segments[m].start<=time) a=m+1 else b=m }
        if (a==0) return null
        val current=segments[a-1];if (time>=current.end) return null
        val index=floor(current.phase+(time-current.start)*current.frequency+.000001)
        a=0;b=segments.size
        while (a<b) { val m=(a+b)/2;if (segments[m].endPhase<index-.000001) a=m+1 else b=m }
        val source=segments[min(a,segments.lastIndex)]
        return Hold(index.toLong(),max(current.group,source.start+(index-source.phase)/source.frequency))
    }
}

internal fun ExistingEffects.cadence(clip: Clip): EffectCadence {
    val effects=clip.effects.filter { it.enabled && modern(it) && it.kind in setOf("Super 8","Undercranking") }
    if (effects.isEmpty()) return EffectCadence(emptyList())
    val intervals=effects.map { fx -> fx.copyEffect().apply { from=rangeStart(fx,clip);to=rangeEnd(fx,clip) } }
    val extended=clip.duplicate().apply {
        sourceIn=minOf(sourceIn,intervals.minOf { it.from });sourceOut=maxOf(sourceOut,intervals.maxOf { it.to });pauses=null
    }
    return EffectCadence(intervals,extended.playbackSpans)
}

class DollyZoomFollower(private val referenceHeight: Double,zoom: Double,private val minimum: Double,
    private val maximum: Double,time: Double) {
    var zoom=zoom;private set
    private var rawLogHeight=ln(max(1e-5,referenceHeight/zoom))
    private var measured=time;private var frame=time;private var velocity=0.0;private var stopped: Double?=null
    fun observe(height: Double,appliedZoom: Double,time: Double) {
        if (!height.isFinite() || !appliedZoom.isFinite() || height<=0 || appliedZoom<=0 || time<=measured || stopped!=null) return
        val alpha=1-exp(-(time-measured)/.15)
        rawLogHeight+=(ln(height/appliedZoom)-rawLogHeight)*alpha;measured=time
    }
    fun hasStopped(time: Double)=stopped?.let { time-it>=.3 }==true
    fun stop(time: Double) { if (stopped==null) stopped=time }
    fun value(time: Double): Double {
        val dt=(time-frame).coerceIn(0.0,.1);frame=max(frame,time)
        if (time-measured>.5) stop(time)
        val stopped=stopped
        if (stopped!=null) {
            val remaining=max(0.0,.3-(time-stopped));velocity*=if (remaining>0) remaining/(remaining+dt) else 0.0
        } else {
            val target=(referenceHeight/exp(rawLogHeight)).coerceIn(minimum,maximum)
            val desired=((ln(target)-ln(zoom))/.15).coerceIn(-.6*ln(2.0),.6*ln(2.0))
            velocity+=(desired-velocity)*(1-exp(-dt/.08))
        }
        zoom=(zoom*exp(velocity*dt)).coerceIn(minimum,maximum)
        return zoom
    }
}
