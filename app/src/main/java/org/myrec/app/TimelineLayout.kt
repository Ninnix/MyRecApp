package org.myrec.app

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

data class TimelineLane(val ids: Set<String>, val audio: Boolean, val row: Int,
                        val top: Float, val bottom: Float)

object TimelineLayout {
    fun thumbnailStart(clipLeft: Float, viewportLeft: Float, width: Float): Float =
        clipLeft + floor(max(0f, viewportLeft - clipLeft) / width) * width

    data class FilmstripGrid(val origin: Double, val step: Double) {
        fun slot(time: Double): Double = origin + floor((time - origin) / step + 1e-8) * step
    }

    fun filmstripGrid(clip: Clip, seconds: Double, fps: Int): FilmstripGrid {
        val rate = clip.playbackSpans.singleOrNull()?.rate?.takeIf { clip.frozen == null } ?: return FilmstripGrid(0.0, seconds)
        val frames = 2.0.pow(round(log2(max(1.0, seconds * rate * fps))))
        return FilmstripGrid(clip.start - clip.sourceIn / rate, frames / (fps * rate))
    }

    fun interval(project: Project, clip: Clip, foldTransitions: Boolean = true): ClosedFloatingPointRange<Double> {
        if (!foldTransitions) return clip.start..clip.end
        fun matches(id: String?): Boolean {
            if (id == clip.id) return true
            val other = project.clips.firstOrNull { it.id == id } ?: return false
            return !clip.video && clip.audio && clip.linked && other.group == clip.group &&
                other.path == clip.path &&
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

    fun lanes(project: Project, height: Float, moving: Set<String> = emptySet()): List<TimelineLane> {
        data class Item(val clip: Clip, val span: ClosedFloatingPointRange<Double>)
        val rows = mutableListOf<Pair<Boolean, MutableList<Item>>>()
        fun overlaps(a: Item, b: Item) = TimelineMove.time(a.span.start) < TimelineMove.time(b.span.endInclusive) &&
            TimelineMove.time(b.span.start) < TimelineMove.time(a.span.endInclusive)
        val transitionPairs = project.transitions.map { setOfNotNull(it.leftId, it.rightId) }.toSet()
        fun pack(parts: List<Clip>, audio: Boolean) {
            val base = rows.size
            val placed = mutableListOf<Pair<Item, Int>>()
            for (clip in parts) {
                val item = Item(clip, interval(project, clip, moving.isEmpty()))
                val minimum = if (audio) base else
                    (placed.filter { overlaps(it.first, item) &&
                        setOf(it.first.clip.id, clip.id) !in transitionPairs
                    }.maxOfOrNull { it.second } ?: (base - 1)) + 1
                val row = (minimum until rows.size).firstOrNull { index ->
                    rows[index].second.none { overlaps(it, item) }
                } ?: rows.size
                if (row == rows.size) rows.add(audio to mutableListOf())
                rows[row].second.add(item)
                placed.add(item to row)
            }
        }
        val pictures = project.clips.withIndex().filter { it.value.video }
            .sortedWith(compareByDescending<IndexedValue<Clip>> { it.value.track }.thenByDescending { it.index })
            .map { it.value }
        val sounds = project.clips.filter { it.audio }.sortedWith(
            compareByDescending<Clip> { if (it.linked || it.video) it.track else -1 }.thenBy { it.start }.thenBy { it.id })
        for ((parts, audio) in listOf(pictures to false, sounds to true)) {
            if (moving.isEmpty()) pack(parts, audio)
            else {
                // Keep lifted material on dedicated rows for the whole Move session.
                pack(parts.filter { it.id in moving }, audio)
                pack(parts.filter { it.id !in moving }, audio)
            }
        }
        val videos = rows.count { !it.first }
        val audios = rows.size - videos
        val available = max(0f, height - 16f)
        val videoHeight = if (videos == 0) 0f else if (audios == 0) available else available * 2 / 3
        var top = 16f
        return rows.mapIndexed { index, (audio, items) ->
            val size = if (audio) max(16f, (available - videoHeight) / max(1, audios))
                else max(24f, videoHeight / max(1, videos))
            TimelineLane(items.map { it.clip.id }.toSet(), audio, index, top, top + size - 1).also { top += size }
        }
    }
}
