package org.myrec.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TimelineSpan(val start: Double, val end: Double) {
    val duration get() = end - start
    fun contains(time: Double) = time >= start && time < end
    fun intersection(other: TimelineSpan): TimelineSpan? =
        TimelineSpan(max(start, other.start), min(end, other.end)).takeIf { it.duration > .000001 }
}

data class LinkPlan(val members: Set<String>, val video: TimelineSpan?, val audio: TimelineSpan?) {
    val needsIntersection get() = video != null && audio != null && video != audio
    val intersection get() = video?.let { a -> audio?.let(a::intersection) }
}

enum class PasteKind { PICTURE, SOUND, PROJECT }
data class PasteTarget(val at: Double, val kind: PasteKind, val label: String,
                       val right: String? = null, val gap: TimelineSpan? = null)

object TimelineRules {
    fun union(spans: List<TimelineSpan>): List<TimelineSpan> {
        val result = mutableListOf<TimelineSpan>()
        for (span in spans.sortedBy { it.start }.filter { it.duration > .000001 }) {
            val last = result.lastOrNull()
            if (last != null && TimelineMove.time(span.start) <= TimelineMove.time(last.end))
                result[result.lastIndex] = TimelineSpan(last.start, max(last.end, span.end))
            else result.add(span)
        }
        return result
    }

    fun groups(project: Project): List<Set<String>> {
        val remaining = project.clips.map { it.id }.toMutableSet()
        val result = mutableListOf<Set<String>>()
        while (remaining.isNotEmpty()) {
            val group = TimelineMove.linked(project, setOf(remaining.first()))
            remaining.removeAll(group)
            result.add(group)
        }
        return result
    }

    fun productionGroup(project: Project, members: Set<String>): Boolean {
        val parts = project.clips.filter { it.id in members }
        return parts.any { it.video } && parts.any { it.audio } &&
            (parts.size > 1 || parts.singleOrNull()?.linked == true)
    }

    fun linkPlan(project: Project, selected: Set<String>): LinkPlan {
        val members = TimelineMove.linked(project, selected)
        require(members.size >= 2) { "Select a part, then long-press other video or audio parts to add them." }
        fun span(parts: List<Clip>, audio: Boolean): TimelineSpan? {
            val sorted = parts.sortedBy { it.start }
            if (sorted.isEmpty()) return null
            for ((a, b) in sorted.zipWithNext()) {
                fun picture(c: Clip): String? = if (c.video) c.id else project.clips.firstOrNull {
                    it.video && it.occurrence == c.occurrence && TimelineMove.time(it.start) == TimelineMove.time(c.start) &&
                        TimelineMove.time(it.end) == TimelineMove.time(c.end)
                }?.id
                val joined = project.transitions.any { it.leftId == picture(a) && it.rightId == picture(b) }
                require(TimelineMove.time(a.end) == TimelineMove.time(b.start) || joined) {
                    "Each video or audio sequence must meet edge to edge, without gaps or overlaps. Move the parts together with Snap to cuts, then try Link again."
                }
            }
            return TimelineSpan(TimelineMove.time(sorted.first().start), TimelineMove.time(sorted.maxOf { it.end }))
        }
        val plan = LinkPlan(members, span(project.clips.filter { it.id in members && it.video }, false),
            span(project.clips.filter { it.id in members && it.audio }, true))
        if (plan.needsIntersection) {
            val shared = requireNotNull(plan.intersection) { "The selected video and audio do not overlap in time." }
            require(project.transitions.none { transition ->
                transition.leftId in members && transition.rightId in members &&
                    listOf(shared.start, shared.end).any { it > transition.begin && it < transition.end }
            }) { "The shared interval crosses a transition. Shorten or remove the transition before linking." }
        }
        return plan
    }

    fun gap(project: Project, time: Double, pictureOnly: Boolean = false): TimelineSpan? {
        if (time < 0 || time >= project.duration) return null
        val occupied = union(project.clips.filter { !pictureOnly || it.video }.map { TimelineSpan(it.start, it.end) })
        if (occupied.any { it.contains(time) }) return null
        return TimelineSpan(occupied.filter { it.end <= time }.maxOfOrNull { it.end } ?: 0.0,
            occupied.firstOrNull { it.start > time }?.start ?: project.duration)
            .takeIf { it.duration > .000001 }
    }

    fun syncOffset(project: Project, sound: Clip): Double? {
        if (!sound.audio || sound.video || sound.frozen != null) return null
        for (picture in project.clips.filter { it.video && it.frozen == null &&
            it.path == sound.path && it.occurrence == sound.occurrence }) {
            val source = max(picture.sourceIn, sound.sourceIn)
            if (source >= min(picture.sourceOut, sound.sourceOut)) continue
            if (picture.keptIntervals().none { source >= it.start && source < it.end } ||
                sound.keptIntervals().none { source >= it.start && source < it.end }) continue
            return sound.start + sound.timelineOffset(source) - picture.start - picture.timelineOffset(source)
        }
        return null
    }
}
