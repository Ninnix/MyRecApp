package org.myrec.app

import kotlin.math.*

class SpeedRamp(val spans: List<PlaybackSpan>) {
    val duration=spans.sumOf { it.duration }
    val native=spans.flatMap { listOf(it.start,it.end,it.rate) }.toDoubleArray()
    fun outputTime(source: Double)=spans.sumOf { max(0.0,min(it.end,source)-it.start)/it.rate }
    companion object {
        fun forClip(clip: Clip): SpeedRamp? {
            if (clip.frozen!=null || clip.effects.none { it.enabled && it.kind in setOf("Step Printing","Undercranking") && it.parameter("speed")?.keyframes?.isNotEmpty()==true }) return null
            val source=clip.duplicate().apply {
                sourceIn=0.0;sourceOut=max(sourceOut,effects.maxOfOrNull { it.values["clockEnd"]?.toDoubleOrNull() ?: 0.0 } ?: 0.0);pauses=null
            }
            return SpeedRamp(source.playbackSpans)
        }
    }
}
