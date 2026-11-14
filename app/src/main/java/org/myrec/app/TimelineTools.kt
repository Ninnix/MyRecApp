package org.myrec.app

import kotlin.math.*

data class FrozenFrame(val sourceTime: Double, val duration: Double, val offset: Double = 0.0) {
    fun valid() = sourceTime.isFinite() && sourceTime >= 0 && duration.isFinite() && duration > 0 && duration <= 10 &&
        offset.isFinite() && offset >= 0 && offset + duration <= 10.000001
}

fun Editor.insertFreeze(seconds: Double, pushIn: Float = 0f, continueAudio: Boolean = false): String? {
    require(seconds.isFinite() && seconds in (1.0 / project.fps)..10.0 && pushIn in 0f..0.3f)
    val position = if (cursor >= project.duration - .5 / project.fps) project.duration
        else ((cursor * project.fps).roundToLong() / project.fps.toDouble()).coerceIn(0.0, project.duration)
    val source = project.active(min(position, max(0.0, project.duration - 1.0 / project.fps))).lastOrNull() ?: return null
    require(project.transitions.none { position > it.begin && position < it.end }) { "Choose a frame outside a transition" }
    val frozenTime = source.sourceTime(position.coerceAtMost(source.end - 1.0 / project.fps))
    val hold = source.duplicate(true).apply {
        sourceIn = frozenTime; sourceOut = min(source.sourceOut, frozenTime + 1.0 / project.fps)
        start = position; rate = 1.0; pauses = null; audio = source.audio && continueAudio
        frozen = FrozenFrame(frozenTime, seconds)
        effects.removeAll { it.kind in setOf("Undercranking", "Freeze", "Strobe", "Echo", "Step Printing", "Freeze Frame") }
        effects.forEach { it.from = 0.0; it.to = seconds }
        effects.add(Effect(kind = "Freeze Frame", to = seconds, values = mutableMapOf(
            "sourceAudio" to source.audio.toString(), "pushIn" to pushIn.toString(), "hold" to seconds.toString(), "audio" to if (continueAudio) "Continue" else "Silence")))
        fadeIn = null; fadeOut = null; censors.clear(); marks.clear()
    }
    cursor = position
    insert(hold)
    cursor = position
    return hold.id
}

fun Editor.setPauseReviews(reviews: Map<String, PauseReview?>) {
    change {
        val rippled = mutableSetOf<Pair<Double, Double>>()
        for (clip in project.clips.filter { it.id in reviews }.sortedByDescending { it.start }) {
            require(clip.frozen == null) { "A held frame has no pauses" }
            val old = clip.duplicate(); val oldEnd = clip.end
            val effects = clip.effects.associate { it.id to (old.sourceTime(old.start + it.from) to old.sourceTime(old.start + min(it.to, old.duration))) }
            val regions = clip.censors.map { it to (old.sourceTime(old.start + it.from) to old.sourceTime(old.start + it.to)) }
            clip.pauses = reviews[clip.id]
            require(clip.pauses?.valid() != false)
            clip.fadeIn = clip.fadeIn?.fitted(old.duration, clip.duration)
            clip.fadeOut = clip.fadeOut?.fitted(old.duration, clip.duration)
            clip.effects.forEach {
                val span = effects.getValue(it.id)
                it.from = clip.timelineOffset(span.first); it.to = clip.timelineOffset(span.second)
            }
            clip.censors.clear()
            for ((region, span) in regions) {
                val from = clip.timelineOffset(span.first); val to = clip.timelineOffset(span.second)
                if (to > from) clip.censors.add(region.copy(from = from, to = to, phaseOrigin = from, envelopeFrom = from, envelopeTo = to))
            }
            if (rippled.add(old.start to oldEnd)) {
                val delta = clip.duration - old.duration
                project.clips.filter { it !== clip && it.start >= oldEnd - .000001 }.forEach { it.start += delta }
                project.transitions.filter { it.at >= oldEnd - .000001 }.forEach { it.at += delta }
            }
        }
    }
}

fun Editor.resizeFreeze(id: String, seconds: Double, pushIn: Float, continueAudio: Boolean) {
    val clip = project.clips.firstOrNull { it.id == id && it.frozen != null } ?: return
    val hold = clip.frozen!!
    require(seconds.isFinite() && seconds > 0 && seconds <= 10 - hold.offset && pushIn in 0f..0.15f)
    val sourceAudio = session().originals.any { it.path == clip.path && it.audio }
    change {
        val old = clip.duration; val end = clip.end
        clip.frozen = hold.copy(duration = seconds)
        clip.retimeAudio(old)
        for (effect in clip.effects) {
            effect.from *= seconds / old; effect.to = min(seconds, effect.to * seconds / old)
            if (effect.kind == "Freeze Frame") {
                effect.values["pushIn"] = pushIn.toString(); effect.values["hold"] = (hold.offset + seconds).toString()
                effect.values["audio"] = if (continueAudio) "Continue" else "Silence"
                clip.audio = continueAudio && (sourceAudio || effect.values["sourceAudio"] == "true")
            }
        }
        val delta = seconds - old
        project.clips.filter { it !== clip && it.start >= end - .000001 }.forEach { it.start += delta }
        project.transitions.filter { it.at >= end - .000001 }.forEach { it.at += delta }
    }
}
