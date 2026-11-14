package org.myrec.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.floor

object TimelineSnap {
    const val FRAME = 1.0 / 30

    fun targets(project: Project, excluding: Set<String> = emptySet(), extra: List<Double> = emptyList()): List<Double> =
        (project.clips.filter { it.id !in excluding }.flatMap { clip ->
            listOf(clip.start, clip.end) + if (clip.frozen != null) emptyList() else
                clip.marks.filter { mark -> clip.keptIntervals().any { mark >= it.start && mark < it.end } }
                    .map { clip.start + clip.timelineOffset(it) }
        } + extra + 0.0).map(TimelineMove::time).distinct().sorted()

    fun snap(project: Project, time: Double, pointsPerSecond: Double): Double {
        val nearest = targets(project).minByOrNull { abs(it - time) } ?: return time
        return if (abs(nearest - time) * max(.000001, pointsPerSecond) <= 8) nearest else time
    }

    fun mark(project: Project, raw: Double, pointsPerSecond: Double, enabled: Boolean,
             peak: (Clip, Double) -> Float?): Double {
        val pictures = project.clips.filter { it.video }.sortedBy { it.start }
        val boundaries = (pictures.flatMap { clip ->
            TimelineLayout.interval(project, clip).let { listOf(it.start, it.endInclusive) }
        } + project.transitions.map { it.cut }).map(TimelineMove::time).distinct().sorted()
        val nearest = boundaries.minByOrNull { abs(it - raw) }
        var time = if (enabled && nearest != null && abs(nearest - raw) * pointsPerSecond <= 8) nearest else {
            var quiet = raw
            val clip = pictures.firstOrNull { clip ->
                TimelineLayout.interval(project, clip).let { raw >= it.start && raw <= it.endInclusive }
            }
            if (clip != null) {
                val candidates = (-2..2).map { (raw + it * .01).coerceIn(clip.start, clip.end) }
                val samples = candidates.mapNotNull { at -> peak(clip, clip.sourceTime(at))?.let { at to it } }
                val minimum = samples.minByOrNull { it.second }
                if (minimum != null && minimum.second < .01f) quiet = minimum.first
            }
            floor(quiet * 30 + .5) / 30
        }
        project.transitions.firstOrNull { time >= it.begin && time < it.end }?.let { time = it.cut }
        return time.coerceIn(0.0, project.duration)
    }
}
