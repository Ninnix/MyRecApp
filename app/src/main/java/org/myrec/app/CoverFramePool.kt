package org.myrec.app

import kotlin.math.*
import kotlin.random.Random

class CoverFramePool(picture: List<ClosedFloatingPointRange<Double>>, scope: List<ClosedFloatingPointRange<Double>>) {
    private data class Span(val first: Long, val count: Int)
    private val spans: List<Span>
    val count: Int
    private val scale = 240000L
    private val step = scale / 30

    private fun ticks(seconds: Double) = if (seconds.isFinite()) (seconds.coerceIn(-604800.0,604800.0) * scale).roundToLong() else 0L
    private fun merged(ranges: List<ClosedFloatingPointRange<Double>>): List<Pair<Long,Long>> {
        val result = mutableListOf<Pair<Long,Long>>()
        for ((start,end) in ranges.map { ticks(it.start) to ticks(it.endInclusive) }.sortedBy { it.first }) {
            if (end <= start) continue
            val last = result.lastOrNull()
            if (last != null && start <= last.second) result[result.lastIndex] = last.first to max(last.second,end)
            else result.add(start to end)
        }
        return result
    }
    init {
        val pictures = merged(picture); val scopes = merged(scope)
        val result = mutableListOf<Span>()
        var i = 0; var j = 0
        while (i < pictures.size && j < scopes.size) {
            val start = max(pictures[i].first,scopes[j].first)
            val end = min(pictures[i].second,scopes[j].second)
            if (start < end) {
                val grid = (start + step - 1) / step * step
                val first = if (grid < end) grid else start
                result.add(Span(first,(1 + (end - 1 - first) / step).toInt()))
            }
            if (pictures[i].second < scopes[j].second) i++ else j++
        }
        spans = result
        count = spans.sumOf { it.count }
    }
    fun picks(previous: List<Int> = emptyList(), random: Random = Random.Default): List<Int> {
        val bands = min(count,9)
        return (0 until bands).map { band ->
            val start = band.toLong() * count / bands
            val end = (band + 1L) * count / bands
            val old = previous.getOrNull(band)?.takeIf { it >= start && it < end }
            if (end - start > 1 && old != null) {
                val next = random.nextInt(start.toInt(),end.toInt() - 1)
                if (next < old) next else next + 1
            } else random.nextInt(start.toInt(),end.toInt())
        }
    }
    fun time(index: Int): Double {
        require(index in 0 until count)
        var offset = index
        for (span in spans) {
            if (offset < span.count) return (span.first + offset.toLong() * step).toDouble() / scale
            offset -= span.count
        }
        error("Cover index outside the sampled range")
    }
}

class CoverSource(project: Project, selected: Set<String> = emptySet(), range: ClosedFloatingPointRange<Double>? = null) {
    val project = project.duplicate()
    val scopeLabel = when { range != null -> "Selected range"; selected.isNotEmpty() -> "Selected clips"; else -> "Whole project" }
    private val scope = when {
        range != null -> listOf(range)
        selected.isNotEmpty() -> project.clips.filter { it.id in selected }.map { it.start..it.end }
        else -> listOf(0.0..project.duration)
    }
    val rangeLabel = "${timeLabel(scope.minOfOrNull { it.start } ?: 0.0)} – ${timeLabel(scope.maxOfOrNull { it.endInclusive } ?: 0.0)}"
    val pool = CoverFramePool(project.clips.filter { it.video }.map { it.start..it.end } +
        project.transitions.filter { it.leftId != null && (it.kind == "Still" || it.hold > 0) }
            .map { it.begin..min(project.duration,it.end) },scope)

    fun size(maximum: Int): Pair<Int,Int> = if (project.width >= project.height)
        maximum to max(1,(maximum.toDouble() * project.height / project.width).roundToInt())
        else max(1,(maximum.toDouble() * project.width / project.height).roundToInt()) to maximum

    fun filename(index: Int): String {
        val title = project.name.split(Regex("[/\\\\:\\p{Cntrl}]+")).filter { it.isNotEmpty() }.joinToString("-").trim().take(40).ifEmpty { "MyRec" }
        return "$title - ${timeLabel(pool.time(index)).replace(':','-')}.jpg"
    }
}
