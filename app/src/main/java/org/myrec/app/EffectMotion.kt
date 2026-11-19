package org.myrec.app

import kotlin.math.*

enum class EffectCurve(val title: String) {
    Linear("Linear"), EaseIn("Ease in"), EaseOut("Ease out"), Smooth("Ease in & out"),
    RiseAndHold("Rise & hold"), RiseHoldReturn("Rise, hold & return");
    val hasRise get() = this == RiseAndHold || this == RiseHoldReturn
    fun sample(x: Double) = when (this) {
        EaseIn -> x * x * x
        EaseOut -> 1 - (1 - x).pow(3)
        Smooth -> x * x * (3 - 2 * x)
        else -> x
    }
}

data class EffectTiming(
    val curve: EffectCurve = EffectCurve.Linear,
    val rise: Double = 1.0,
    val release: Double = 1.0,
    val ramp: EffectCurve = EffectCurve.Linear,
) {
    fun endpoint(b: Boolean, duration: Double, frame: Double): Double {
        if (!b) return 0.0
        val last = max(0.0, duration - frame)
        return if (curve.hasRise) min(last, ceil(min(duration, max(0.0, rise)) / frame - .000001) * frame) else last
    }

    fun fraction(elapsed: Double, duration: Double, frame: Double): Double {
        val last = max(0.0, duration - frame)
        val x = (elapsed / max(.000001, last)).coerceIn(0.0, 1.0)
        if (!curve.hasRise) return curve.sample(x)
        val up = min(duration, max(0.0, rise))
        val down = if (curve == EffectCurve.RiseHoldReturn) min(duration - up, max(0.0, release)) else 0.0
        val peak = min(last, up)
        if (elapsed < peak && peak > 0) return ramp.sample(max(0.0, elapsed / peak))
        if (curve == EffectCurve.RiseHoldReturn && down > 0) {
            if (elapsed >= last && peak < last) return 0.0
            val falling = min(last, max(peak, duration - down))
            if (elapsed > falling) return 1 - ramp.sample(min(1.0, (elapsed - falling) / max(.000001, last - falling)))
        }
        return 1.0
    }
}

data class EffectTimePoint(val source: Double, val elapsed: Double)

data class EffectMotion(
    val a: Map<String, Double>,
    val b: Map<String, Double> = a.toMap(),
    val timing: EffectTiming = EffectTiming(),
    val duration: Double,
    val frame: Double = 1.0 / 30,
    val clock: List<EffectTimePoint> = emptyList(),
) {
    fun duplicate() = copy(a = a.toMap(), b = b.toMap(), clock = clock.toList())
    fun anchored(clip: Clip, offset: Double): EffectMotion {
        val hold = clip.frozen
        val points = if (hold != null) {
            val start = hold.sourceTime + hold.offset
            listOf(EffectTimePoint(start, 0.0), EffectTimePoint(start + clip.duration, clip.duration))
        } else EffectPhase.anchor(clip, 0.0).clock
        return copy(clock = points.map { it.copy(elapsed = it.elapsed + offset) })
    }

    fun elapsed(source: Double): Double {
        if (clock.isEmpty()) return source
        if (source <= clock.first().source) return clock.first().elapsed
        if (source >= clock.last().source) return clock.last().elapsed
        var lo = 0; var hi = clock.lastIndex
        while (hi - lo > 1) {
            val mid = (hi + lo) / 2
            if (clock[mid].source < source) lo = mid else hi = mid
        }
        val l = clock[lo]; val r = clock[hi]
        return l.elapsed + (r.elapsed - l.elapsed) * (source - l.source) / (r.source - l.source)
    }

    fun pose(source: Double): Map<String, Double> {
        val f = timing.fraction(elapsed(source), duration, frame)
        if (f <= 0) return a
        if (f >= 1) return b
        return a.mapValues { (key, start) ->
            val end = b.getValue(key)
            when (key) {
                "hue", "rotation" -> wrap(start + f * wrap(end - start))
                "zoom", "contentZoom" -> if (start > 0 && end > 0) start * (end / start).pow(f) else start + (end - start) * f
                else -> start + (end - start) * f
            }
        }
    }

    fun valid(effect: Effect): Boolean {
        if (effect.kind !in listOf("Color Correction", "Color LUTs", "Crop", "Image", "Placement") || !duration.isFinite() || duration <= 0 ||
            !frame.isFinite() || frame <= 0 || !timing.rise.isFinite() || timing.rise < 0 ||
            !timing.release.isFinite() || timing.release < 0 || timing.ramp.hasRise || clock.size > 100000) return false
        if (clock.any { !it.source.isFinite() || !it.elapsed.isFinite() } ||
            clock.zipWithNext().any { (l, r) -> l.source >= r.source || l.elapsed > r.elapsed }) return false
        if (effect.kind == "Color LUTs") return a.keys==b.keys && GradeModel.valid(a) && GradeModel.valid(b)
        if (effect.kind in listOf("Crop", "Image", "Placement")) {
            val keys=when (effect.kind) {
                "Crop" -> setOf("x","y","zoom","rotation")
                "Image" -> setOf("x","y","size","opacity")
                else -> setOf("x","y","w","h","frameX","frameY","contentZoom","left","right","top","bottom")
            }
            return a.keys==keys && b.keys==keys && listOf(a,b).all { pose ->
                pose.values.all { it.isFinite() } && ArchiveGeometry.valid(effect.copy(motion=null,
                    values=effect.values.toMutableMap().apply { for ((key,value) in pose) put(key,value.toString()) }))
            }
        }
        val expected = effect.parameters.associateBy { it.id }
        return listOf(a, b).all { pose -> pose.keys == expected.keys && pose.all { (id, value) ->
            expected.getValue(id).kind.accepts(EffectValue.Number(value))
        } }
    }

    private fun wrap(value: Double) = value - 360 * floor((value + 180) / 360)
}

internal object TransformMotion {
    fun pose(effect: Effect): Map<String, Double> {
        val defaults = when (effect.kind) {
            "Crop" -> mapOf("x" to .5, "y" to .5, "zoom" to 1.0, "rotation" to 0.0)
            "Image" -> mapOf("x" to .5, "y" to .5, "size" to .3, "opacity" to 1.0)
            else -> mapOf("x" to 0.0, "y" to 0.0, "w" to 1.0, "h" to 1.0,
                "frameX" to .5, "frameY" to .5, "contentZoom" to 1.0,
                "left" to 0.0, "right" to 1.0, "top" to 0.0, "bottom" to 1.0)
        }
        return defaults.mapValues { (key, value) -> effect.values[key]?.toDoubleOrNull() ?: value }
    }

    fun show(effect: Effect, pose: Map<String, Double>) {
        for ((key, value) in pose) effect.values[key] = value.toString()
    }

    fun capture(effect: Effect, second: Boolean) {
        val motion = effect.motion ?: return
        val pose = pose(effect)
        val updated = if (second) motion.copy(b = pose) else motion.copy(a = pose)
        if (updated.valid(effect)) effect.motion = updated
        else show(effect, if (second) motion.b else motion.a)
    }
}
