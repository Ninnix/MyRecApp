package org.myrec.app

import kotlin.math.*

class SubtitlesDraft(source: Editor, initial: Project = source.project) {
    private val original = source.project.duplicate()
    val project = initial.duplicate()
    data class Row(val id: String, val time: Double, val text: String)

    private fun entries() = project.clips.flatMap { clip -> clip.effects.filter { it.subtitle!=null }.map { clip to it } }

    private data class Slice(val clip: Clip,val effect: Effect,val words: List<SubtitleWord>,val start: Double,val end: Double) {
        val id get()="${clip.id}:${effect.subtitle!!.id}:${words.first().id}"
        fun timeline()=words.mapNotNull { word ->
            val from=max(start,clip.start+clip.timelineOffset(max(clip.sourceIn,word.start)))
            val to=min(end,clip.start+clip.timelineOffset(min(clip.sourceOut,word.end)))
            if (to>from) word.copy(start=from,end=to) else null
        }
    }
    private fun slices(): List<Slice> {
        val owned=project.subtitleCues().map { Slice(it.clip,it.effect,it.words,it.start,it.end) }
        val legacy=entries().filter { it.second.values["subtitleOwner"]!="audio" }.mapNotNull { (clip,effect) ->
            val words=effect.subtitle!!.words.filter { it.start<clip.sourceOut && it.end>clip.sourceIn }
            if (words.isEmpty()) null else Slice(clip,effect,words,clip.start,clip.end)
        }
        return (owned+legacy).sortedWith(compareBy({ it.start },{ it.id }))
    }
    fun rows(): List<Row> = slices().mapNotNull { slice ->
        val words=slice.timeline()
        if (words.isEmpty()) null else Row(slice.id,words.first().start,words.joinToString(" ") { it.text })
    }
    fun transcript() = rows().joinToString("\n") { it.text }

    private fun replace(slice: Slice,words: List<SubtitleWord>) {
        val ids=slice.words.map { it.id }.toSet()
        val all=(slice.effect.subtitle!!.words.filter { it.id !in ids }+words).sortedBy { it.start }
        if (all.isEmpty()) slice.clip.effects.remove(slice.effect)
        else {
            slice.effect.subtitle=slice.effect.subtitle!!.copy(words=all)
            slice.effect.from=slice.clip.timelineOffset(max(slice.clip.sourceIn,all.first().start))
            slice.effect.to=slice.clip.timelineOffset(min(slice.clip.sourceOut,all.last().end))
            slice.effect.values["text"]=all.joinToString(" ") { it.text }
        }
    }
    fun correct(id: String,text: String) {
        val slice=slices().firstOrNull { it.id==id } ?: return
        // The source clock jumps at a removed pause; a cue ends on its own run.
        val source=slice.clip.sourceTime(slice.start)
        val interval=slice.clip.keptIntervals().firstOrNull { source>=it.start && source<it.end }
        val next=SubtitleTiming.corrected(slice.timeline(),text.take(4000)).map { word -> word.copy(
            start=max(interval?.start ?: 0.0,slice.clip.sourceTime(max(slice.start,word.start))),
            end=min(interval?.end ?: slice.clip.sourceOut,slice.clip.sourceTime(min(slice.end,word.end)))) }.filter { it.end>it.start }
        replace(slice,next)
    }

    val groupingAvailable get(): Boolean {
        val fingerprint=project.subtitleTimingFingerprint()
        return entries().all { it.second.values["subtitleTiming"]==fingerprint }
    }
    fun regroup(maxWords: Int) {
        require(maxWords in 1..16)
        if (!groupingAvailable) return
        val timing=project.subtitleTimingFingerprint()
        val groups=entries().groupBy { (clip,fx) -> clip.id to fx.subtitle!!.series }
        for ((_,old) in groups) {
            val clip=old.first().first;val style=old.first().second
            val all=old.flatMap { it.second.subtitle!!.words }.distinctBy { it.id }.filter { it.start<clip.sourceOut && it.end>clip.sourceIn }.sortedBy { it.start }
            old.forEach { clip.effects.remove(it.second) }
            for (words in SubtitleTiming.group(all,maxWords)) clip.effects.add(style.copyEffect(true).apply {
                subtitle=SubtitlePhrase(style.subtitle!!.series,words=words)
                from=clip.timelineOffset(max(clip.sourceIn,words.first().start));to=clip.timelineOffset(min(clip.sourceOut,words.last().end))
                values["text"]=words.joinToString(" ") { it.text };values["subtitleTiming"]=timing
            })
        }
    }

    fun appearance(): Effect?=entries().firstOrNull()?.second?.copyEffect()?.also {
        TitleStyle.initialize(it,false);it.values["text"]=rows().firstOrNull()?.text ?: "Subtitles"
    }
    fun applyAppearance(style: Effect) {
        val keys=TitleStyle.defaults.keys+listOf("x","y","size","gradientFirst","gradientLast","gradientAngle","path","fontName","fontIndex","entrance","exit","inSeconds","outSeconds","inDirection","outDirection")
        for ((_,fx) in entries()) {
            keys.forEach { fx.values.remove(it) };style.values.filterKeys { it in keys }.forEach { (key,value) -> fx.values[key]=value }
        }
    }
    fun placement(key: String, value: String) { entries().forEach { it.second.values[key] = value } }
    fun removeAll() { entries().forEach { (clip,effect) -> clip.effects.remove(effect) } }
    fun remove(id: String) { slices().firstOrNull { it.id==id }?.let { replace(it,emptyList()) } }
    fun commit(editor: Editor) = editor.commitDraft(original, project)

    companion object {
        fun install(project: Project, captions: List<List<SubtitleWord>>, scope: ClosedFloatingPointRange<Double>, targets: Set<String>) {
            val effects=project.clips.flatMap { it.effects }.filter { it.subtitle!=null }
            val fingerprint=project.subtitleTimingFingerprint()
            val current=effects.all { it.values["subtitleTiming"]==fingerprint }
            val covered=project.subtitleCues().all { it.start>=scope.start-.001 && it.end<=scope.endInclusive+.001 }
            val style=effects.firstOrNull()?.copyEffect()
            for (clip in project.clips.filter { it.id in targets }) {
                for (effect in clip.effects.filter { it.subtitle!=null }) {
                    val kept=effect.subtitle!!.words.filter { word ->
                        val time=clip.start+clip.timelineOffset((word.start+word.end)/2)
                        time !in scope
                    }
                    effect.subtitle=effect.subtitle!!.copy(words=kept)
                    effect.values["text"]=kept.joinToString(" ") { it.text }
                    if (kept.isNotEmpty()) {
                        effect.from=clip.timelineOffset(max(clip.sourceIn,kept.first().start))
                        effect.to=clip.timelineOffset(min(clip.sourceOut,kept.last().end))
                    }
                }
                clip.effects.removeAll { it.subtitle?.words?.isEmpty()==true }
            }
            val series=style?.subtitle?.series ?: uid()
            captions.forEach { attach(project,series,it,targets,style,fingerprint) }
            if (current || covered) project.clips.flatMap { it.effects }.filter { it.subtitle!=null }.forEach { it.values["subtitleTiming"]=fingerprint }
        }
        fun attach(project: Project, series: String, words: List<SubtitleWord>, targets: Set<String>, style: Effect? = null,timing: String=project.subtitleTimingFingerprint()) {
            if (words.isEmpty()) return
            val phrase = uid(); val from = words.first().start; val to = words.last().end
            val candidates=project.clips.filter { it.id in targets && (it.audio || it.video) && it.frozen==null && it.gainDb> -60 && it.start<to && it.end>from }
            val owners=words.associate { word ->
                val midpoint=(word.start+word.end)/2
                word.id to candidates.filter { it.contains(midpoint) && !it.subtitleMuted(midpoint) }.sortedWith(compareByDescending<Clip> { it.audio }.thenByDescending { it.gainDb }.thenBy { it.id }).firstOrNull()?.id
            }
            for (clip in candidates) {
                val source = words.filter { owners[it.id]==clip.id }.map { it.copy(
                    start = clip.sourceTime(max(clip.start, it.start)), end = clip.sourceTime(min(clip.end, it.end))) }
                    .filter { it.end > it.start }
                if (source.isEmpty()) continue
                val effect = style?.copyEffect(true) ?: Effect(kind = "Text", values = mutableMapOf(
                    "x" to "0.05", "y" to "0.9", "size" to "0.045", "style" to "Bold"))
                if (style==null) {
                    TitleStyle.initialize(effect,true)
                    effect.values.putAll(mapOf("x" to ".5","y" to ".86","size" to ".052","plate" to "One box","backing" to "#d1050505","shadow" to "false"))
                }
                effect.values["subtitleTiming"]=timing
                if (clip.audio) effect.values["subtitleOwner"]="audio"
                effect.from = max(0.0, from - clip.start); effect.to = min(clip.duration, to - clip.start)
                effect.values["text"] = words.joinToString(" ") { it.text }
                effect.subtitle = SubtitlePhrase(series, phrase, source)
                clip.effects.add(effect)
            }
        }
    }
}
