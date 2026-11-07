package org.yourec.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TimelineLane(val track: Int, val audio: Boolean, val top: Float, val bottom: Float)

object TimelineLayout {
    fun interval(project: Project, clip: Clip): ClosedFloatingPointRange<Double> {
        fun matches(id: String?): Boolean {
            if (id == clip.id) return true
            val other = project.clips.firstOrNull { it.id == id } ?: return false
            if (other.group == clip.group) return true
            return other.path == clip.path &&
                abs(other.start - clip.start) < .001 &&
                abs(other.sourceIn - clip.sourceIn) < .001 &&
                abs(other.sourceOut - clip.sourceOut) < .001
        }
        var from = clip.start
        var to = clip.end
        for (transition in
            project.transitions.filter {
                it.overlap > 0 && clip.start < it.end - .000001 && clip.end > it.begin + .000001
            }) {
            if (matches(transition.rightId)) from = max(from, transition.cut)
            if (matches(transition.leftId)) to = min(to, transition.cut)
        }
        return from..max(from, to)
    }

    fun start(cursor: Double, span: Double, wholeProject: Boolean): Double =
        if (wholeProject) 0.0 else cursor - span / 2

    fun lanes(project: Project, height: Float): List<TimelineLane> {
        val video = project.clips.filter { it.video }.map { it.track }.distinct().sorted()
        val audio = project.clips.filter { it.audio }.map { it.track }.distinct().sorted()
        val result = mutableListOf<TimelineLane>()
        val available = max(0f, height - 16f)
        val videoHeight =
            if (video.isEmpty()) 0f else if (audio.isEmpty()) available else available * 2 / 3
        for ((i, track) in video.withIndex()) {
            result.add(
                TimelineLane(
                    track,
                    false,
                    16f + videoHeight * i / video.size,
                    16f + videoHeight * (i + 1) / video.size - 1f,
                )
            )
        }
        val audioHeight = available - videoHeight
        for ((i, track) in audio.withIndex()) {
            result.add(
                TimelineLane(
                    track,
                    true,
                    16f + videoHeight + audioHeight * i / audio.size,
                    16f + videoHeight + audioHeight * (i + 1) / audio.size - 1f,
                )
            )
        }
        return result
    }
}
