package org.myrec.app

import kotlin.math.*

data class SourceInterval(val start: Double, val end: Double) {
    val duration get() = max(0.0, end - start)
    fun valid() = start.isFinite() && end.isFinite() && start >= 0 && end > start
}
data class PauseCandidate(val id: String = uid(), val raw: SourceInterval, val enabled: Boolean = true)
data class PauseReview(val automatic: Boolean = true, val thresholdDb: Double = -38.0,
    val minimum: Double = .6, val before: Double = .12, val after: Double = .18,
    val analyzed: SourceInterval, val candidates: List<PauseCandidate> = emptyList()) {
    fun valid() = thresholdDb.isFinite() && thresholdDb in -80.0..0.0 && minimum.isFinite() && minimum in .05..30.0 &&
        before.isFinite() && before in 0.0..5.0 && after.isFinite() && after in 0.0..5.0 && analyzed.valid() &&
        candidates.size <= 10000 && candidates.map { it.id }.distinct().size == candidates.size &&
        candidates.all { it.id.isNotBlank() && it.raw.valid() && it.raw.start >= analyzed.start && it.raw.end <= analyzed.end }

    fun cuts(clip: Clip): List<SourceInterval> {
        val clock = clip.copy(pauses = null, frozen = null,
            sourceIn = min(clip.sourceIn, candidates.minOfOrNull { it.raw.start } ?: clip.sourceIn),
            sourceOut = max(clip.sourceOut, candidates.maxOfOrNull { it.raw.end } ?: clip.sourceOut))
        val frame = 1.0 / 30
        fun length(span: SourceInterval) = clock.timelineOffset(span.end) - clock.timelineOffset(span.start)
        val proposed = candidates.filter { it.enabled }.map { c ->
            val a = max(clip.sourceIn, min(c.raw.end, clock.sourceTime(clock.start + clock.timelineOffset(c.raw.start) + before)))
            val b = clock.sourceTime(clock.start + clock.timelineOffset(c.raw.end) - after)
            SourceInterval(a, max(a, min(clip.sourceOut, b)))
        }.filter { length(it) >= frame - .000001 }.sortedBy { it.start }
        val merged = mutableListOf<SourceInterval>()
        for (cut in proposed) {
            val last = merged.lastOrNull()
            if (last != null && cut.start <= last.end) merged[merged.lastIndex] = SourceInterval(last.start, max(last.end, cut.end))
            else merged.add(cut)
        }
        val visible = clock.timelineOffset(clip.sourceOut) - clock.timelineOffset(clip.sourceIn)
        val excess = merged.sumOf(::length) - max(0.0, visible - frame)
        if (excess > 0 && merged.isNotEmpty()) merged[merged.lastIndex] = merged.last().let {
            it.copy(end = clock.sourceTime(clock.start + clock.timelineOffset(it.end) - excess))
        }
        return merged.filter { length(it) >= frame - .000001 }
    }
}

object PauseDetector {
    fun threshold(levels: List<Float>, step: Double, settings: PauseReview): Double {
        if (!settings.automatic || levels.isEmpty()) return settings.thresholdDb
        val sorted = levels.sorted()
        fun db(value: Float) = 20 * log10(max(1e-8, value.toDouble()))
        fun percentile(p: Int) = db(sorted[(sorted.size - 1) * p / 100])
        val floor = percentile(10); var speech = percentile(90)
        if (speech - floor < 10 && percentile(50) - floor < 5) {
            val block = max(1, ceil(.2 / step).toInt())
            var loudest = 0f
            for (i in 0..(levels.size - block) step block) loudest = max(loudest, levels.subList(i, i + block).min())
            speech = max(speech, db(loudest))
        }
        return max(-56.0, min(-27.0, min(floor + 8, speech - 18))).roundToInt().toDouble()
    }

    fun detect(levels: List<Float>, step: Double, clip: Clip, settings: PauseReview): PauseReview {
        require(step > 0 && step.isFinite() && levels.all { it.isFinite() && it >= 0 })
        val db = threshold(levels, step, settings)
        val threshold = 10.0.pow(db / 20)
        val first = max(0, ceil(settings.analyzed.start / step).toInt())
        val last = min(levels.size, floor(settings.analyzed.end / step).toInt())
        val result = mutableListOf<PauseCandidate>()
        val clock = clip.copy(pauses = null, frozen = null,
            sourceIn = min(clip.sourceIn, settings.analyzed.start), sourceOut = max(clip.sourceOut, settings.analyzed.end))
        var begin: Int? = null
        fun close(end: Int) {
            val start = begin ?: return
            begin = null
            if (clock.timelineOffset(end * step) - clock.timelineOffset(start * step) >= settings.minimum - .000001)
                result.add(PauseCandidate(raw = SourceInterval(start * step, min(settings.analyzed.end, end * step))))
        }
        for (i in first until last) {
            if (levels[i] <= threshold) { if (begin == null) begin = i } else close(i)
        }
        close(last)
        return settings.copy(thresholdDb = db, candidates = result)
    }
}

fun Clip.keptIntervals(): List<SourceInterval> {
    val result = mutableListOf<SourceInterval>()
    var cursor = sourceIn
    for (cut in pauses?.cuts(this).orEmpty()) {
        if (cut.start > cursor) result.add(SourceInterval(cursor, cut.start))
        cursor = max(cursor, cut.end)
    }
    if (cursor < sourceOut) result.add(SourceInterval(cursor, sourceOut))
    return result
}
