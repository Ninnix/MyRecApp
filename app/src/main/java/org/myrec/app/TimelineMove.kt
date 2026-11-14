package org.myrec.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.floor
import kotlin.math.sign

enum class MoveScope(val title: String) {
    SELECTED("Selected only"), LEFT("With left"), RIGHT("With right"), BOTH("Both sides")
}

data class MoveSnap(val target: Double, val edge: Double)
data class MoveResult(val offset: Double, val snap: MoveSnap? = null)

class TimelineMove(val base: Project, val picked: Set<String>, val cursor: Double, val scale: Double) {
    val choices = companions(base, picked)
    var scope = MoveScope.SELECTED
    val members get() = choices.getValue(scope)
    val anchor = base.clips.filter { it.id in picked }.minOf { it.start }
    var offset = 0.0
    var requested = 0.0
    var snap: MoveSnap? = null
    val position get() = time(anchor + offset)
    val boundaries: List<Double> get() {
        val group = base.clips.filter { it.id in members }
        val earliest = time(anchor - group.minOf { it.start })
        val latest = time(anchor + 604800 - group.maxOf { it.end })
        return (listOf(0.0, anchor, base.duration) + base.clips.filter { it.id !in members }
            .flatMap { TimelineLayout.interval(base, it).let { r -> listOf(r.start, r.endInclusive) } } +
            base.transitions.filter { it.leftId !in members || it.rightId !in members }.map { it.cut })
            .map(::time).filter { it in earliest..latest }.distinct().sorted()
    }

    fun available(scope: MoveScope): Boolean = when (scope) {
        MoveScope.SELECTED -> true
        MoveScope.BOTH -> choices.getValue(scope) != choices.getValue(MoveScope.LEFT) &&
            choices.getValue(scope) != choices.getValue(MoveScope.RIGHT)
        else -> choices.getValue(scope) != choices.getValue(MoveScope.SELECTED)
    }

    fun resolve(requested: Double, pointsPerSecond: Double, snapping: Boolean): MoveResult =
        snapped(base, members, requested, pointsPerSecond, if (snapping) snap else null,
            if (snapping) listOf(cursor) else emptyList(), snapping)

    companion object {
        // This is the reference edit clock, separate from original source PTS.
        fun time(seconds: Double): Double = sign(seconds) * floor(abs(seconds) * 240000 + .5) / 240000

        fun linked(project: Project, ids: Set<String>): Set<String> {
            val result = ids.toMutableSet()
            val pending = ArrayDeque(ids)
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                val clip = project.clips.firstOrNull { it.id == id } ?: continue
                for (other in project.clips) if (other.id !in result &&
                    (clip.link != null && clip.link == other.link ||
                        clip.linked && other.linked && clip.path == other.path &&
                        clip.occurrence == other.occurrence && time(clip.start) == time(other.start) &&
                        clip.sourceIn == other.sourceIn && clip.sourceOut == other.sourceOut &&
                        clip.frozen == other.frozen && clip.playbackSpans == other.playbackSpans &&
                        time(clip.duration) == time(other.duration))) {
                    result.add(other.id); pending.addLast(other.id)
                }
            }
            return result
        }

        fun companions(project: Project, picked: Set<String>): Map<MoveScope, Set<String>> {
            val seed = linked(project, picked)
            val previous = mutableMapOf<String, String>()
            val next = mutableMapOf<String, String>()
            fun join(a: String, b: String) { previous[b] = a; next[a] = b }
            for (transition in project.transitions) {
                val a = project.clips.firstOrNull { it.id == transition.leftId && it.video }
                val b = project.clips.firstOrNull { it.id == transition.rightId && it.video }
                if (a != null && b != null) join(a.id, b.id)
            }
            fun chain(parts: List<Clip>) {
                val starts = parts.filter { it.id !in previous }.groupBy { time(it.start) }
                for ((cut, outgoing) in parts.filter { it.id !in next }.groupBy { time(it.end) }) {
                    val incoming = starts[cut]?.toMutableList() ?: continue
                    val unmatched = mutableListOf<Clip>()
                    for (a in outgoing) {
                        val b = incoming.firstOrNull { it.occurrence == a.occurrence }
                        if (b == null) unmatched.add(a)
                        else { join(a.id, b.id); incoming.remove(b) }
                    }
                    unmatched.zip(incoming).forEach { (a, b) -> join(a.id, b.id) }
                }
            }
            chain(project.clips.filter { it.video }.sortedBy { it.track })
            val pictureLinks = linked(project, project.clips.filter { it.video }.map { it.id }.toSet())
            chain(project.clips.filter { it.audio && it.id !in pictureLinks })
            fun following(neighbors: Map<String, String>): Set<String> {
                val members = seed.toMutableSet()
                val pending = ArrayDeque(seed)
                while (pending.isNotEmpty()) {
                    val id = pending.removeFirst()
                    for (part in linked(project, setOf(id)) + listOfNotNull(neighbors[id]))
                        if (members.add(part)) pending.addLast(part)
                }
                return members
            }
            val left = following(previous)
            val right = following(next)
            return mapOf(MoveScope.SELECTED to seed, MoveScope.LEFT to left,
                MoveScope.RIGHT to right, MoveScope.BOTH to (left + right))
        }

        fun snapped(project: Project, selected: Set<String>, requested: Double, scale: Double,
                    latch: MoveSnap? = null, extra: List<Double> = emptyList(), enabled: Boolean = true): MoveResult {
            val parts = project.clips.filter { it.id in selected }
            if (parts.isEmpty()) return MoveResult(0.0)
            val minimum = -parts.minOf { it.start }
            val maximum = 604800 - parts.maxOf { it.end }
            val delta = time(requested.coerceIn(minimum, maximum))
            if (!enabled) return MoveResult(delta)
            val edges = parts.flatMap { listOf(time(it.start), time(it.end)) }.distinct().sorted()
            val targets = TimelineSnap.targets(project, selected, extra)
            var best: MoveSnap? = null
            var distance = Double.POSITIVE_INFINITY
            val points = max(.000001, scale)
            for (edge in edges) {
                val at = targets.binarySearch(time(edge + delta)).let { if (it < 0) -it - 1 else it }
                for (index in listOf(at - 1, at)) {
                    val target = targets.getOrNull(index) ?: continue
                    if (time(target - edge) !in time(minimum)..time(maximum)) continue
                    val gap = abs(edge + delta - target) * points
                    if (gap <= 10 && gap < distance) { best = MoveSnap(target, edge); distance = gap }
                }
            }
            if (latch != null && latch.edge in edges && latch.target in targets &&
                time(latch.target - latch.edge) in time(minimum)..time(maximum)) {
                val held = abs(latch.edge + delta - latch.target) * points
                if (held <= 18 && (best == null || held <= distance + 1))
                    return MoveResult(time(latch.target - latch.edge), latch)
            }
            return MoveResult(time(best?.let { it.target - it.edge } ?: delta), best)
        }
    }
}
