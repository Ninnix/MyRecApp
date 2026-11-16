package org.myrec.app

import kotlin.math.*

data class PlaybackSpan(val start: Double, val end: Double, val rate: Double) {
    val duration get() = (end - start) / rate
}

internal data class RateState(val rate: Double, val sourceIn: Double, val sourceOut: Double,
    val pauses: PauseReview?, val effects: List<Effect>)

fun Clip.rateAt(source: Double): Double = rate * effects.filter { it.enabled }.fold(1.0) { value,fx ->
    val active=source>=(fx.values["rangeStart"]?.toDoubleOrNull() ?: fx.values["origin"]?.toDoubleOrNull() ?: sourceIn) && source<(fx.values["rangeEnd"]?.toDoubleOrNull() ?: fx.values["clockEnd"]?.toDoubleOrNull() ?: sourceOut)
    value * when {
        fx.kind=="Step Printing" -> fx.numberAt("speed",source,1f).toDouble()
        fx.kind=="Undercranking" && ExistingEffects.modern(fx) -> if (active) fx.numberAt("speed",source,1.25f).toDouble() else 1.0
        fx.kind=="Undercranking" -> (1+(fx.number("rate",1.25f)-1)*fx.number("strength",1f)).toDouble()
        else -> 1.0
    }
}

internal fun Clip.computePlaybackSpans(): List<PlaybackSpan> {
    val effects=effects.filter { it.enabled && it.kind in setOf("Step Printing","Undercranking") }
    val bounds=effects.flatMap { fx ->
        listOf(fx.values["origin"]?.toDoubleOrNull() ?: fx.phase?.origin ?: sourceIn,
            fx.values["clockEnd"]?.toDoubleOrNull() ?: sourceOut,
            fx.values["rangeStart"]?.toDoubleOrNull() ?: fx.values["origin"]?.toDoubleOrNull() ?: sourceIn,fx.values["rangeEnd"]?.toDoubleOrNull() ?: fx.values["clockEnd"]?.toDoubleOrNull() ?: sourceOut)+
            fx.parameters.filter { it.id in setOf("speed","mute") }.flatMap { it.keyframes.map { k -> k.time } }
    }
    val boundaries=(if (bounds.isEmpty()) listOf(sourceIn,sourceOut) else bounds+min(sourceIn,bounds.min())+max(sourceOut,bounds.max())).distinct().sorted()
    val kept=keptIntervals();val spans=mutableListOf<PlaybackSpan>()
    fun add(a: Double,b: Double,depth: Int=0) {
        if (kept.none { it.start<b && it.end>a }) return
        val r0=rateAt(a+.0000001);val r1=rateAt(b-.0000001)
        require(r0.isFinite() && r1.isFinite() && r0>0 && r1>0 && r0<=128 && r1<=128) { "Invalid playback speed" }
        if (depth<12 && b-a>1.0/30 && abs(ln(r1/r0))>.01) {
            val middle=(a+b)/2;add(a,middle,depth+1);add(middle,b,depth+1)
        } else {
            val speed=rateAt((a+b)/2)
            for (interval in kept) {
                val start=max(a,interval.start);val end=min(b,interval.end)
                if (end>start) { require(spans.size<100000) { "Too many playback rate intervals" };spans.add(PlaybackSpan(start,end,speed)) }
            }
        }
    }
    for ((a,b) in boundaries.zipWithNext()) if (b>a) add(a,b)
    return spans
}

fun Clip.timelineOffset(source: Double): Double = if (frozen != null) 0.0 else
    playbackSpans.sumOf { max(0.0, min(it.end, source) - it.start) / it.rate }

fun Clip.playbackPieces(): List<Clip> {
    if (frozen != null || playbackSpans.size == 1 && pauses == null) return listOf(this)
    val total = duration
    var offset = 0.0
    return playbackSpans.map { span ->
        val length = span.duration
        duplicate().also { part ->
            part.pauses = null; part.sourceIn = span.start; part.sourceOut = span.end; part.start = start + offset
            part.rate = span.rate
            part.effects.removeAll { it.kind=="Step Printing" || it.kind=="Undercranking" && !ExistingEffects.modern(it) }
            part.effects.filter { it.kind=="Undercranking" && ExistingEffects.modern(it) }.forEach {
                it.parameter("speed")!!.apply { value=EffectValue.Number(1.0);keyframes.clear() }
            }
            part.sliceAudio(offset, offset + length, total)
            part.effects.forEach { it.from = max(0.0, it.from - offset); it.to = min(length, it.to - offset) }
            part.effects.removeAll { it.to <= it.from }
            offset += length
        }
    }
}
