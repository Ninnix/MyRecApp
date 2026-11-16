package org.myrec.app

import kotlin.math.*

data class TransitionFraming(val x: Double=.5,val y: Double=.5,val zoom: Double=1.0) {
    fun valid()=x.isFinite() && x in 0.0..1.0 && y.isFinite() && y in 0.0..1.0 && zoom.isFinite() && zoom in 1.0..4.0
}
data class TransitionControls(val vertical: Boolean=false,val divider: Double=.5,val reverse: Boolean=false,
    val audio: String="Crossfade",val first: TransitionFraming=TransitionFraming(),val second: TransitionFraming=TransitionFraming(),
    val color: Long=0xff000000,val audioOffset: Double?=null,val audioBlend: Double?=null,
    val flashCount: Int=3,val flashAcceleration: Double=2.0,val stillDissolve: Double=0.0) {
    fun valid()=divider.isFinite() && divider in .15.. .85 && audio in listOf("Crossfade","First clip","Second clip") &&
        first.valid() && second.valid() && color in 0..0xffffffffL && listOfNotNull(audioOffset,audioBlend,stillDissolve).all { it.isFinite() && abs(it)<=604800 } &&
        (audioBlend==null || audioBlend>=0) && stillDissolve>=0 && flashCount in 1..20 && flashAcceleration.isFinite() && flashAcceleration in .25..4.0
    fun flashes(duration: Double,fps: Int): List<Int> {
        val frames=max(1,(duration*fps).roundToInt());val count=min(flashCount,max(0,(frames-1)/2))
        val result=mutableListOf<Int>()
        for (i in 1..count) {
            val desired=((1-(1-i.toDouble()/(count+1)).pow(flashAcceleration))*(frames-1)).roundToInt()
            result.add(min(frames-2-2*(count-i),max((result.lastOrNull() ?: -1)+2,desired)))
        }
        return result
    }
}

fun Transition.audioGain(time: Double,outgoing: Boolean): Double {
    if (controls==null) return if (outgoing) cos(progress(time)*PI/2) else sin(progress(time)*PI/2)
    val clock=TransitionClock(this)
    val range=if (outgoing) clock.audioOut else clock.audioIn
    val elapsed=time-pictureOrigin
    val length=range.endInclusive-range.start
    val position=if (length<=0) { if (elapsed>=range.start) 1.0 else 0.0 } else ((elapsed-range.start)/length).coerceIn(0.0,1.0)
    return if (outgoing) 1-position else position
}

class TransitionClock(t: Transition) {
    private val still=t.kind=="Still"
    val motion: Double
    val hold: Double
    val videoDuration: Double
    val audioIn: ClosedFloatingPointRange<Double>
    val audioOut: ClosedFloatingPointRange<Double>
    val leftEnd: Double
    val rightStart: Double
    val lower: Double
    val upper: Double
    val cut: Double
    val overlap get()=leftEnd-rightStart
    val leftCost get()=leftEnd-lower
    val rightCost get()=upper-rightStart
    init {
        val c=t.controls ?: TransitionControls()
        val still=t.kind=="Still";val solid=t.kind=="Fade through color"
        val edge=if (t.leftId==null && t.rightId!=null) -1 else if (t.rightId==null && t.leftId!=null) 1 else 0
        motion=if (still) c.stillDissolve else t.duration
        hold=if (still) t.duration else if (t.kind=="Flash Cut") 0.0 else t.hold
        videoDuration=motion+hold
        val aNeed=if (still) 0.0 else if (solid && edge==0) motion/2 else videoDuration
        val bNeed=if (still) hold else if (solid && edge==0) motion/2+hold else 0.0
        var offset=c.audioOffset ?: 0.0
        var blend=c.audioBlend ?: if (still) 0.0 else t.duration+if (solid && edge==0) 0.0 else hold
        val custom=c.audioOffset!=null || c.audioBlend!=null
        if (t.kind=="Split screen" && !custom) {
            if (c.audio=="First clip") { offset=max(0.0,videoDuration-.008);blend=min(.008,videoDuration) }
            if (c.audio=="Second clip") blend=min(.008,videoDuration)
        }
        if (edge==-1) {
            val begin=offset+if (still && !custom) hold else 0.0
            audioIn=begin..(begin+blend);audioOut=0.0..0.0
            leftEnd=0.0;rightStart=min(bNeed,audioIn.start);lower=min(0.0,rightStart);upper=max(videoDuration,audioIn.endInclusive)
        } else if (edge==1) {
            audioOut=offset..(offset+blend);audioIn=0.0..0.0
            leftEnd=max(aNeed,audioOut.endInclusive);rightStart=0.0;lower=min(0.0,audioOut.start);upper=max(videoDuration,leftEnd)
        } else {
            if ((solid || still) && !custom) {
                audioOut=offset..(offset+blend/2);audioIn=(offset+blend/2+hold)..(offset+blend+hold)
            } else { audioIn=offset..(offset+blend);audioOut=audioIn }
            leftEnd=max(aNeed,audioOut.endInclusive);rightStart=min(bNeed,audioIn.start)
            lower=minOf(0.0,rightStart,audioOut.start);upper=maxOf(videoDuration,leftEnd,audioIn.endInclusive)
        }
        cut=if (still) hold else videoDuration/2
    }
    fun progress(elapsed: Double): Float {
        if (motion<=0) return if (elapsed<cut) 0f else 1f
        if (still) return ((elapsed-hold)/motion).toFloat().coerceIn(0f,1f)
        return ((if (elapsed<motion/2) elapsed else if (elapsed<=motion/2+hold) motion/2 else elapsed-hold)/motion).toFloat().coerceIn(0f,1f)
    }
}

fun Transition.handleCosts(): Pair<Double,Double> {
    if (controls==null) return overlap to overlap
    val clock=TransitionClock(this);val lower=windowFrom ?: clock.lower;val upper=windowTo ?: clock.upper
    return max(0.0,min(upper,clock.leftEnd)-lower) to max(0.0,upper-max(lower,clock.rightStart))
}
