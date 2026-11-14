package org.myrec.app

import kotlin.math.*

data class PlaybackSpan(val start: Double, val end: Double, val rate: Double) {
    val duration get() = (end - start) / rate
}

internal data class RateState(val rate: Double, val sourceIn: Double, val sourceOut: Double,
    val pauses: PauseReview?, val effects: List<Effect>)

fun Clip.rateAt(source: Double): Double = rate * effects.filter { it.enabled }.fold(1.0) { value, fx ->
    value * when (fx.kind) {
        "Step Printing" -> fx.numberAt("speed", source, 1f).toDouble()
        "Undercranking" -> (1 + (fx.number("rate", 1.25f) - 1) * fx.number("strength", 1f)).toDouble()
        else -> 1.0
    }
}

internal fun Clip.computePlaybackSpans(): List<PlaybackSpan> {
    val keys = effects.filter { it.enabled && it.kind == "Step Printing" }
        .flatMap { it.parameter("speed")?.keyframes.orEmpty() }.map { it.time }
    val boundaries = (keys + sourceIn + sourceOut).filter { it in sourceIn..sourceOut }.distinct().sorted()
    val kept = keptIntervals()
    val spans = mutableListOf<PlaybackSpan>()
    for ((a, b) in boundaries.zipWithNext()) {
        val speed = rateAt((a + b) / 2)
        require(speed.isFinite() && speed > 0 && speed <= 128) { "Invalid playback speed" }
        for (interval in kept) {
            val start = max(a, interval.start); val end = min(b, interval.end)
            if (end > start) spans.add(PlaybackSpan(start, end, speed))
        }
    }
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
            part.effects.removeAll { it.kind == "Step Printing" || it.kind == "Undercranking" }
            part.sliceAudio(offset, offset + length, total)
            part.effects.forEach { it.from = max(0.0, it.from - offset); it.to = min(length, it.to - offset) }
            part.effects.removeAll { it.to <= it.from }
            offset += length
        }
    }
}
