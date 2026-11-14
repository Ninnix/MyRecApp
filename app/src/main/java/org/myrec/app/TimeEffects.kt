package org.myrec.app

import kotlin.math.*

data class EffectPhase(val origin: Double, val offset: Double = 0.0, val clock: List<EffectTimePoint> = emptyList()) {
    fun valid() = origin.isFinite() && origin in 0.0..604800.0 && offset.isFinite() && offset in 0.0..604800.0 &&
        clock.size <= 100000 && clock.all { it.source.isFinite() && it.source >= 0 && it.elapsed.isFinite() && it.elapsed in 0.0..604800.0 } &&
        clock.zipWithNext().all { it.first.source < it.second.source && it.first.elapsed <= it.second.elapsed }
    fun elapsed(source: Double): Double {
        if (clock.isEmpty()) return max(0.0, source - origin)
        if (source <= clock.first().source) return clock.first().elapsed
        if (source >= clock.last().source) return clock.last().elapsed
        var a = 0; var b = clock.lastIndex
        while (b - a > 1) { val mid = (a + b) / 2; if (clock[mid].source <= source) a = mid else b = mid }
        val l = clock[a]; val r = clock[b]
        return l.elapsed + (r.elapsed - l.elapsed) * (source - l.source) / (r.source - l.source)
    }
    companion object {
        fun anchor(clip: Clip, offset: Double): EffectPhase {
            val points = mutableListOf<EffectTimePoint>()
            var elapsed = 0.0
            for (span in clip.playbackSpans) {
                if (points.lastOrNull()?.source != span.start) points.add(EffectTimePoint(span.start, elapsed))
                elapsed += span.duration
                points.add(EffectTimePoint(span.end, elapsed))
            }
            return EffectPhase(clip.sourceIn, offset, points)
        }
    }
}

object TimeEffects {
    val kinds = setOf("Negative Strobe", "Step Printing")
    fun parameters(kind: String): MutableList<EffectParameter> = when (kind) {
        "Negative Strobe" -> mutableListOf(
            EffectParameter("frequency", ParameterKind.Stepper(listOf(1.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 15.0)), EffectValue.Number(8.0)),
            EffectParameter("timedBurst", ParameterKind.Toggle, EffectValue.Toggle(false)),
            EffectParameter("burstLength", ParameterKind.Slider(.1, 10.0, .05), EffectValue.Number(1.0)))
        "Step Printing" -> mutableListOf(
            EffectParameter("step", ParameterKind.Stepper((2..6).map { it.toDouble() }), EffectValue.Number(3.0)),
            EffectParameter("smear", ParameterKind.Slider(0.0, 1.0, .01), EffectValue.Number(.7)),
            EffectParameter("speed", ParameterKind.Stepper(listOf(.5, 1.0)), EffectValue.Number(1.0)))
        else -> mutableListOf()
    }

    fun negative(effect: Effect, source: Double, fps: Double, hold: Double? = null): Boolean {
        val phase = effect.phase ?: EffectPhase(0.0)
        val clock = phase.offset + (hold ?: phase.elapsed(source))
        if ((effect.parameter("timedBurst")?.value as? EffectValue.Toggle)?.value == true && clock >= effect.number("burstLength", 1f) - 1e-8) return false
        val frequency = effect.parameter("frequency") ?: return false
        fun hz(v: EffectValue) = min(max(.5, fps * .5), (v as? EffectValue.Number)?.value ?: 8.0)
        var rate = hz(frequency.at(phase.origin)); var last = 0.0; var cycles = 0.0
        for (key in frequency.keyframes) {
            if (key.time <= phase.origin) continue
            if (key.time > source) break
            val next = phase.offset + phase.elapsed(key.time)
            cycles += (next - last) * rate; last = next; rate = hz(key.value)
        }
        cycles += (clock - last) * rate
        return floor(cycles * 2 + 1e-7).toLong() % 2 == 1L
    }
}

class FrameIndex(val times: DoubleArray) {
    init { require(times.isNotEmpty() && times.size <= 3000000 && times.all { it.isFinite() && it in 0.0..604800.0 } &&
        (1 until times.size).all { times[it - 1] < times[it] }) }
    fun first(time: Double): Int {
        var a = 0; var b = times.size
        while (a < b) { val m = (a + b) / 2; if (times[m] < time - .0000001) a = m + 1 else b = m }
        return a
    }
    fun last(time: Double): Int {
        var a = 0; var b = times.size
        while (a < b) { val m = (a + b) / 2; if (times[m] <= time + .0000001) a = m + 1 else b = m }
        return max(0, a - 1)
    }
    fun block(time: Double, origin: Double, count: Int, lower: Double, upper: Double): DoubleArray {
        require(count in 2..6)
        val start = last(origin); val index = last(time)
        val block = start + (max(start, min(times.lastIndex, index)) - start) / count * count
        val minimum = min(times.lastIndex, first(lower)); val before = first(upper) - 1
        if (before < minimum) return DoubleArray(count) { times[last(lower)] }
        val maximum = min(times.lastIndex, before)
        return DoubleArray(count) { times[(block + it).coerceIn(minimum, maximum)] }
    }
}
