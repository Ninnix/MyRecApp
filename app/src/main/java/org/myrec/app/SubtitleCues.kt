package org.myrec.app

import kotlin.math.*

data class SubtitleCue(val clip: Clip,val effect: Effect,val words: List<SubtitleWord>,val start: Double,val end: Double) {
    val id get()="${clip.id}:${effect.subtitle!!.id}:${words.first().id}:${start.toBits()}"
    val text get()=words.joinToString(" ") { it.text }
}
fun Clip.subtitleMuted(time: Double): Boolean {
    if (gainDb<= -60 || frozen!=null) return true
    val source=sourceTime(time)
    return effects.any { fx -> fx.enabled && fx.kind=="Undercranking" && ExistingEffects.modern(fx) && fx.active(time-start) && (fx.parameter("mute")?.at(source) as? EffectValue.Toggle)?.value==true }
}
fun Project.subtitleCues(at: Double?=null): List<SubtitleCue> = clips.filter { it.audio && it.gainDb> -60 && it.frozen==null && (at==null || it.contains(at) && !it.subtitleMuted(at)) }.flatMap { clip ->
    val source=at?.let { clip.sourceTime(it) }
    clip.effects.filter { it.enabled && it.subtitle!=null && it.values["subtitleOwner"]=="audio" &&
        (source==null || source>=it.subtitle!!.words.first().start && source<it.subtitle!!.words.last().end) }.flatMap { effect ->
        clip.keptIntervals().flatMap { interval ->
            val words=effect.subtitle!!.words.filter { it.start<interval.end && it.end>interval.start }
            val groups=mutableListOf<MutableList<SubtitleWord>>()
            for (word in words) {
                if (groups.isEmpty() || word.start-groups.last().last().end>.4) groups.add(mutableListOf(word)) else groups.last().add(word)
            }
            groups.mapNotNull { group ->
                val start=clip.start+clip.timelineOffset(max(interval.start,group.first().start))
                val end=clip.start+clip.timelineOffset(min(interval.end,group.last().end))
                if (end>start) SubtitleCue(clip,effect,group,start,end) else null
            }
        }
    }
}.filter { at==null || at>=it.start && at<it.end }.sortedWith(compareBy({ it.start },{ it.clip.track },{ it.id }))

fun SubtitlePhrase.independent()=copy(id=uid(),words=words.map { it.copy(id=uid()) })

fun Project.upgradeSubtitles() {
    for (clip in clips.toList()) for (effect in clip.effects.filter { it.subtitle!=null && it.values["subtitleOwner"]!="audio" }.toList()) {
        val owner=if (clip.audio) clip else clips.firstOrNull { it.audio && samePart(it,clip) }
        if (owner!=null) {
            if (owner!==clip) {
                clip.effects.remove(effect)
                if (owner.effects.any { it.subtitle?.id==effect.subtitle!!.id }) continue
                owner.effects.add(effect)
            }
            effect.values["subtitleOwner"]="audio";TitleStyle.initialize(effect,false)
        }
    }
}

fun Project.subtitleTimingFingerprint(): String {
    val data=clips.joinToString(";") { clip ->
        listOf(clip.id,clip.occurrence,clip.video,clip.audio,clip.start,clip.sourceIn,clip.sourceOut,
            clip.frozen,clip.audio && clip.gainDb<= -60,clip.playbackSpans).joinToString("|")
    }
    return java.security.MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString("") { "%02x".format(it) }
}
