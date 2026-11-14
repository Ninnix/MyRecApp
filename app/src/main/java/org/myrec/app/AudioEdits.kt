package org.myrec.app

import kotlin.math.*

data class AudioEdgeFade(val seconds: Double, val elapsed: Double = 0.0) {
    fun valid() = seconds.isFinite() && seconds > 0 && seconds <= 604800 && elapsed.isFinite() && elapsed >= 0 && elapsed < seconds
    fun remaining(length: Double) = min(length, seconds - elapsed)
    fun trimming(amount: Double) = copy(elapsed = elapsed + amount).takeIf { it.elapsed < seconds }
    fun fitted(old: Double, next: Double): AudioEdgeFade {
        val visible = remaining(old)
        return if (next > 0 && next < visible) copy(seconds = seconds * next / visible, elapsed = elapsed * next / visible) else this
    }
    fun gain(distance: Double) = ((elapsed + distance) / seconds).coerceIn(0.0, 1.0)
}

enum class CensorWaveform { Sine, Triangle, Square }
enum class CensorEnvelope { Short, Soft, Pulse }

data class AudioCensorSettings(val frequency: Double = 1000.0, val levelDb: Double = -18.0,
    val waveform: CensorWaveform = CensorWaveform.Sine, val envelope: CensorEnvelope = CensorEnvelope.Short) {
    fun valid() = frequency.isFinite() && frequency in 100.0..4000.0 && levelDb.isFinite() && levelDb in -36.0..-6.0
    fun oscillator(time: Double, rate: Double): Double {
        val phase = 2 * PI * (time * frequency % 1)
        if (waveform == CensorWaveform.Sine) return sin(phase)
        if (waveform == CensorWaveform.Square) {
            val raw = time * frequency % 1; val t = if (raw < 0) raw + 1 else raw
            val dt = min(.49, frequency / rate)
            fun blep(t: Double): Double {
                if (t < dt) { val x = t / dt; return x + x - x * x - 1 }
                if (t > 1 - dt) { val x = (t - 1) / dt; return x * x + x + x + 1 }
                return 0.0
            }
            return (if (t < .5) 1.0 else -1.0) + blep(t) - blep((t + .5) % 1)
        }
        val maximum = min(17, floor(rate * .45 / frequency).toInt())
        var value = 0.0; var scale = 0.0
        for (harmonic in 1..max(1, maximum) step 2) {
            val coefficient = (if (harmonic / 2 % 2 == 0) 1.0 else -1.0) / (harmonic * harmonic)
            value += coefficient * sin(harmonic * phase); scale += abs(coefficient)
        }
        return value / max(1.0, scale)
    }
    fun envelope(time: Double, duration: Double): Double {
        if (time < 0 || time >= duration) return 0.0
        val fade = min(duration / 2, if (envelope == CensorEnvelope.Soft) .06 else .008)
        fun smooth(v: Double): Double { val x = v.coerceIn(0.0, 1.0); return x * x * (3 - 2 * x) }
        var result = smooth(time / max(.00001, fade)) * smooth((duration - time) / max(.00001, fade))
        if (envelope == CensorEnvelope.Pulse) {
            val t = time % .25
            result *= smooth(t / .008) * smooth((.18 - t) / .008)
        }
        return result
    }
}

data class AudioCensorRegion(val id: String = uid(), val from: Double, val to: Double,
    val phaseOrigin: Double = from, val settings: AudioCensorSettings = AudioCensorSettings(),
    val envelopeFrom: Double = from, val envelopeTo: Double = to) {
    fun valid(length: Double) = id.isNotBlank() && id.length <= 100 && settings.valid() &&
        listOf(from, to, phaseOrigin, envelopeFrom, envelopeTo).all { it.isFinite() && abs(it) <= 604800 } &&
        from >= 0 && to > from && to <= length + .00001 && envelopeFrom <= from && envelopeTo >= to
    fun sliced(start: Double, end: Double): AudioCensorRegion? {
        val a = max(from, start); val b = min(to, end)
        return if (b <= a) null else copy(from = a - start, to = b - start, phaseOrigin = phaseOrigin - start,
            envelopeFrom = envelopeFrom - start, envelopeTo = envelopeTo - start)
    }
    fun originalGain(time: Double) = if (time >= from && time < to) 0.0 else
        (if (time < from) (from - time) / .005 else (time - to) / .005).coerceIn(0.0, 1.0)
    fun tone(time: Double, rate: Double) = if (time < from || time >= to) 0.0 else
        10.0.pow(settings.levelDb / 20) * settings.envelope(time - envelopeFrom, envelopeTo - envelopeFrom) *
            settings.oscillator(time - phaseOrigin, rate)
}

fun Clip.sliceAudio(start: Double, end: Double, oldDuration: Double) {
    fadeIn = fadeIn?.trimming(start)
    fadeOut = fadeOut?.trimming(oldDuration - end)
    val next = censors.mapNotNull { it.sliced(start, end) }
    censors.clear(); censors.addAll(next)
}

fun Clip.retimeAudio(oldDuration: Double) {
    fadeIn = fadeIn?.fitted(oldDuration, duration)
    fadeOut = fadeOut?.fitted(oldDuration, duration)
    val ratio = duration / oldDuration
    for (i in censors.indices) censors[i] = censors[i].let {
        val from = it.from * ratio
        it.copy(from = from, to = it.to * ratio, phaseOrigin = from, envelopeFrom = from, envelopeTo = it.to * ratio)
    }
}

fun audioFinish(project: Project, clip: Clip, pcm: FloatArray, start: Double, rate: Double = 48000.0) {
    if (clip.censors.isEmpty() && clip.fadeIn == null && clip.fadeOut == null) return
    val index = project.clips.indexOfFirst { it.id == clip.id }
    val blockers = clip.censors.map { region -> project.clips.withIndex().filter { (order, other) ->
        other.audio && other.gainDb > -60f && (other.gainDb > clip.gainDb || other.gainDb == clip.gainDb && order < index)
    }.flatMap { (_, other) -> other.censors.filter { it.id == region.id && it.settings == region.settings }
        .map { (other.start + it.from)..(other.start + it.to) } } }
    val volume = if (clip.gainDb <= -60f) 0.0 else 10.0.pow(clip.gainDb.toDouble() / 20)
    for (i in 0 until pcm.size / 2) {
        val local = start + i / rate
        var original = 1.0; var tone = 0.0
        for ((r, region) in clip.censors.withIndex()) {
            original = min(original, region.originalGain(local))
            if (local < region.from || local >= region.to || volume == 0.0) continue
            val absolute = clip.start + local
            val owner = blockers[r].any { absolute >= it.start && absolute < it.endInclusive }
            if (!owner) tone += region.tone(local, rate) * volume
        }
        val fade = (clip.fadeIn?.gain(local) ?: 1.0) * (clip.fadeOut?.gain(clip.duration - local) ?: 1.0)
        for (channel in 0..1) pcm[i * 2 + channel] = ((pcm[i * 2 + channel] * original + tone) * fade).toFloat()
    }
}

class AudioEditsDraft(source: Editor) {
    private val original = source.project.duplicate()
    private val edit = Editor(original.duplicate()).apply { cursor = source.cursor; range = source.range; selected.addAll(source.selected) }
    private val ids = edit.isolateAudioScope()
    val clips get() = edit.project.clips.filter { it.id in ids }
    val project get() = edit.project
    fun target(out: Boolean) = if (out) clips.maxByOrNull { it.end } else clips.minByOrNull { it.start }
    fun gain(db: Float) { clips.forEach { it.gainDb = db.coerceIn(-60f, 24f) } }
    fun fade(out: Boolean, seconds: Double) {
        require(seconds.isFinite() && seconds >= 0)
        val c = target(out) ?: return
        val value = min(c.duration, seconds).takeIf { it > 0 }?.let { AudioEdgeFade(it) }
        if (out) c.fadeOut = value else c.fadeIn = value
    }
    fun snapshot() = project.duplicate()
    fun commit(target: Editor) { target.commitDraft(original, snapshot()) }
}

fun censorAudio(project: Project, ids: Set<String>, start: Double, end: Double,
    settings: AudioCensorSettings?, event: String = uid()) {
    require(start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= project.duration + .00001)
    require(settings == null || settings.valid())
    for (c in project.clips.filter { it.audio && it.id in ids }) {
        val a = max(start, c.start) - c.start; val b = min(end, c.end) - c.start
        if (b <= a) continue
        val regions = mutableListOf<AudioCensorRegion>()
        for (old in c.censors) {
            if (old.to <= a || old.from >= b) { regions.add(old); continue }
            if (old.from < a) regions.add(old.copy(to = a, envelopeTo = a))
            if (old.to > b) regions.add(old.copy(from = b, envelopeFrom = b))
        }
        if (settings != null) regions.add(AudioCensorRegion(event, a, b, start - c.start, settings, start - c.start, end - c.start))
        c.censors.clear(); c.censors.addAll(regions.sortedBy { it.from })
    }
}
