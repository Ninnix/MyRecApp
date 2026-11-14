package org.myrec.app

import kotlin.math.*

class SubtitlesDraft(source: Editor, initial: Project = source.project) {
    private val original = source.project.duplicate()
    val project = initial.duplicate()
    data class Row(val id: String, val time: Double, val text: String)

    private fun entries(id: String? = null) = project.clips.flatMap { clip -> clip.effects
        .filter { it.subtitle != null && (id == null || it.subtitle!!.id == id) }.map { clip to it } }

    private fun words(id: String): List<SubtitleWord> = entries(id).flatMap { (clip, effect) ->
        effect.subtitle!!.words.mapNotNull { word ->
            val from = max(clip.sourceIn, word.start); val to = min(clip.sourceOut, word.end)
            if (to <= from) null else word.copy(start = clip.start + clip.timelineOffset(from),
                end = clip.start + clip.timelineOffset(to))
        }
    }.groupBy { it.id }.map { (_, parts) -> parts.first().copy(start = parts.minOf { it.start }, end = parts.maxOf { it.end }) }
        .sortedBy { it.start }

    fun rows(): List<Row> = entries().map { it.second.subtitle!!.id }.distinct().mapNotNull { id ->
        val words = words(id)
        if (words.isEmpty()) null else Row(id, words.first().start, words.joinToString(" ") { it.text })
    }.sortedBy { it.time }

    fun transcript() = rows().joinToString("\n") { it.text }

    fun correct(id: String, text: String) {
        val next = SubtitleTiming.corrected(words(id), text)
        for ((clip, effect) in entries(id)) {
            val local = next.filter { it.start < clip.end && it.end > clip.start }.map { it.copy(
                start = clip.sourceTime(max(clip.start, it.start)), end = clip.sourceTime(min(clip.end, it.end))) }
            if (local.isEmpty()) clip.effects.remove(effect)
            else {
                effect.subtitle = effect.subtitle!!.copy(words = local)
                effect.from = clip.timelineOffset(local.first().start)
                effect.to = clip.timelineOffset(local.last().end)
                effect.values["text"] = next.joinToString(" ") { it.text }
            }
        }
    }

    fun regroup(maxWords: Int) {
        val series = entries().map { it.second.subtitle!!.series }.distinct()
        for (group in series) {
            val old = entries().filter { it.second.subtitle!!.series == group }
            val ids = old.map { it.second.subtitle!!.id }.distinct()
            val all = ids.flatMap { words(it) }.distinctBy { it.id }.sortedBy { it.start }
            old.forEach { (clip, effect) -> clip.effects.remove(effect) }
            for (words in SubtitleTiming.group(all, maxWords)) attach(project, group, words,
                old.map { it.first.id }.toSet(), old.firstOrNull()?.second)
        }
    }

    fun placement(key: String, value: String) { entries().forEach { it.second.values[key] = value } }
    fun remove(id: String) { entries(id).forEach { (clip, effect) -> clip.effects.remove(effect) } }
    fun commit(editor: Editor) = editor.commitDraft(original, project)

    companion object {
        fun attach(project: Project, series: String, words: List<SubtitleWord>, targets: Set<String>, style: Effect? = null) {
            if (words.isEmpty()) return
            val phrase = uid(); val from = words.first().start; val to = words.last().end
            for (clip in project.clips.filter { it.id in targets && it.video && it.start < to && it.end > from }) {
                val source = words.filter { it.start < clip.end && it.end > clip.start }.map { it.copy(
                    start = clip.sourceTime(max(clip.start, it.start)), end = clip.sourceTime(min(clip.end, it.end))) }
                    .filter { it.end > it.start }
                if (source.isEmpty()) continue
                val effect = style?.copyEffect(true) ?: Effect(kind = "Text", values = mutableMapOf(
                    "x" to "0.05", "y" to "0.9", "size" to "0.045", "style" to "Bold"))
                effect.from = max(0.0, from - clip.start); effect.to = min(clip.duration, to - clip.start)
                effect.values["text"] = words.joinToString(" ") { it.text }
                effect.subtitle = SubtitlePhrase(series, phrase, source)
                clip.effects.add(effect)
            }
        }
    }
}
