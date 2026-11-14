package org.myrec.app

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.*

data class SubtitleWord(val id: String = uid(), val text: String, val start: Double, val end: Double) {
    fun valid() = id.isNotBlank() && id.length <= 100 && text.isNotBlank() && text.length <= 10000 &&
        start.isFinite() && end.isFinite() && start >= 0 && end > start
}
data class SubtitlePhrase(val series: String, val id: String = uid(), val words: List<SubtitleWord>) {
    fun valid() = series.isNotBlank() && series.length <= 100 && id.isNotBlank() && id.length <= 100 &&
        words.size in 1..128 && words.all { it.valid() } && words.zipWithNext().all { it.first.end <= it.second.start + .000001 }
    val text get() = words.joinToString(" ") { it.text }
}

data class SpeechChunk(val range: ClosedFloatingPointRange<Double>, val speech: List<ClosedFloatingPointRange<Double>>)

object SubtitleTiming {
    fun plan(levels: List<Float>, duration: Double, step: Double = .02): List<SpeechChunk> {
        if (levels.isEmpty() || duration <= 0) return emptyList()
        val db = levels.map { 20 * log10(max(1e-9, it.toDouble())) }
        val sorted = db.sorted(); val floor = sorted[min(sorted.lastIndex, sorted.size / 5)]
        val threshold = max(-52.0, min(-35.0, floor + 9))
        val islands = mutableListOf<ClosedFloatingPointRange<Double>>()
        var begin: Int? = null; var last = 0
        for (i in db.indices) {
            if (db[i] > threshold) { if (begin == null) begin = i; last = i }
            val start = begin
            if (start != null && (i - last > 8 || i == db.lastIndex)) {
                if (last - start >= 2) {
                    val a = max(0.0, start * step - .08); val b = min(duration, (last + 1) * step + .1)
                    val prior = islands.lastOrNull()
                    if (prior != null && a <= prior.endInclusive) islands[islands.lastIndex] = prior.start..b
                    else if (b > a) islands.add(a..b)
                }
                begin = null
            }
        }
        val bounded = mutableListOf<ClosedFloatingPointRange<Double>>()
        for (island in islands) {
            var a = island.start
            while (island.endInclusive - a > 12) {
                val lower = min(db.lastIndex, ((a + 8) / step).toInt())
                val upper = min(db.lastIndex, ((a + 12) / step).toInt())
                val best = (lower..upper).minBy { i -> (max(0, i - 2)..min(db.lastIndex, i + 2)).sumOf { db[it] } }
                val b = max(a + 8, best * step)
                bounded.add(a..b); a = b
            }
            if (island.endInclusive > a) bounded.add(a..island.endInclusive)
        }
        val result = mutableListOf<SpeechChunk>()
        for (island in bounded) {
            val previous = result.lastOrNull()
            val gap = previous?.let { island.start - it.range.endInclusive } ?: Double.POSITIVE_INFINITY
            if (previous != null && island.endInclusive - previous.range.start <= 12 &&
                (gap < .7 || gap < 1.3 && previous.range.endInclusive - previous.range.start < 3))
                result[result.lastIndex] = SpeechChunk(previous.range.start..island.endInclusive, previous.speech + island)
            else result.add(SpeechChunk(island, listOf(island)))
        }
        return result
    }

    fun tokens(text: String): List<String> {
        val value = text.trim()
        if (value.isEmpty()) return emptyList()
        val iterator = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(value) }
        val starts = mutableListOf<Int>()
        var a = iterator.first(); var b = iterator.next()
        while (b != BreakIterator.DONE) {
            if (value.substring(a, b).any { it.isLetterOrDigit() }) starts.add(a)
            a = b; b = iterator.next()
        }
        if (starts.isEmpty()) return listOf(value)
        starts[0] = 0
        return starts.mapIndexed { i, from -> value.substring(from, starts.getOrNull(i + 1) ?: value.length).trim() }
    }

    fun estimate(text: String, speech: List<ClosedFloatingPointRange<Double>>): List<SubtitleWord> {
        val words = tokens(text); val spans = speech.filter { it.endInclusive > it.start }
        if (words.isEmpty() || spans.isEmpty()) return emptyList()
        val weights = words.map { max(1, it.count { c -> c.isLetter() }).toDouble().pow(.65) }
        val total = weights.sum(); val duration = spans.sumOf { it.endInclusive - it.start }
        val grouped = Array(spans.size) { mutableListOf<Int>() }
        var consumed = 0.0
        for (i in words.indices) {
            val midpoint = (consumed + weights[i] / 2) / total * duration
            var cursor = 0.0; var island = spans.lastIndex
            for (j in spans.indices) { cursor += spans[j].endInclusive - spans[j].start; if (midpoint < cursor) { island = j; break } }
            grouped[island].add(i); consumed += weights[i]
        }
        return spans.indices.flatMap { j ->
            var cursor = spans[j].start
            val sum = grouped[j].sumOf { weights[it] }
            grouped[j].map { i ->
                val end = min(spans[j].endInclusive, cursor + (spans[j].endInclusive - spans[j].start) * weights[i] / max(sum, .001))
                SubtitleWord(text = words[i], start = cursor, end = end).also { cursor = end }
            }
        }
    }

    fun group(words: List<SubtitleWord>, maxWords: Int): List<List<SubtitleWord>> {
        require(maxWords in 1..20)
        val result = mutableListOf<MutableList<SubtitleWord>>()
        for (word in words.sortedBy { it.start }) {
            val group = result.lastOrNull(); val prior = group?.lastOrNull()
            if (group == null || group.size >= maxWords || prior != null &&
                (word.start - prior.end > .32 || prior.text.lastOrNull() in listOf('.', '!', '?', '。', '！', '？')))
                result.add(mutableListOf(word))
            else group.add(word)
        }
        return result
    }

    fun corrected(old: List<SubtitleWord>, text: String): List<SubtitleWord> {
        if (old.isEmpty()) return emptyList()
        val next = tokens(text).take(128)
        if (next.isEmpty()) return emptyList()
        fun normalized(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        val a = old.map { normalized(it.text) }; val b = next.map(::normalized)
        if (old.size > 128) return estimate(next.joinToString(" "), listOf(old.first().start..old.last().end))
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed())
            dp[i][j] = if (a[i] == b[j]) 1 + dp[i + 1][j + 1] else max(dp[i + 1][j], dp[i][j + 1])
        val anchors = mutableListOf<Pair<Int, Int>>()
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) { anchors.add(i to j); i++; j++ }
            else if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++
        }
        val result = mutableListOf<SubtitleWord>()
        var priorOld = -1; var priorNew = -1
        for ((oi, ni) in anchors + (old.size to next.size)) {
            if (ni > priorNew + 1) {
                var from = if (priorOld < 0) old.first().start else old[priorOld].end
                var to = if (oi == old.size) old.last().end else old[oi].start
                if (to - from < .04) {
                    if (oi < old.size) to = min(old[oi].end, from + (old[oi].end - old[oi].start) * .45)
                    else result.lastOrNull()?.let { previous ->
                        from = max(previous.start, previous.end - (previous.end - previous.start) * .45)
                        result[result.lastIndex] = previous.copy(end = from)
                    }
                }
                if (to > from) result.addAll(estimate(next.subList(priorNew + 1, ni).joinToString(" "), listOf(from..to)))
            }
            if (oi < old.size) {
                val word = old[oi].copy(text = next[ni], start = max(old[oi].start, result.lastOrNull()?.end ?: old[oi].start))
                if (word.end > word.start) result.add(word)
            }
            priorOld = oi; priorNew = ni
        }
        return result
    }
}
