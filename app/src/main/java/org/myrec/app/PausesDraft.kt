package org.myrec.app

import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class PausesDraft(source: Editor) {
    private val original = source.project.duplicate()
    private val edit = Editor(original.duplicate()).apply { cursor = source.cursor; range = source.range; selected.addAll(source.selected) }
    private val ids = run {
        val audio = edit.targets().filter { it.audio }
        if (source.linked && edit.range == null) {
            if (edit.selected.isEmpty()) edit.selected.addAll(audio.map { it.id })
            for (sound in audio.filter { it.linked }) edit.project.clips.filter {
                it.linked && it.path == sound.path && abs(it.start - sound.start) < .001 &&
                    abs(it.sourceIn - sound.sourceIn) < .001 && abs(it.sourceOut - sound.sourceOut) < .001
            }.forEach { edit.selected.add(it.id) }
        }
        val video = if (source.linked) edit.isolateVideoScope() else emptySet()
        val sound = edit.isolateAudioScope()
        sound + video.filter { id ->
            val picture = edit.project.clips.first { it.id == id }
            picture.linked && edit.project.clips.any {
                it.id in sound && it.linked && it.path == picture.path && abs(it.start - picture.start) < .001 &&
                    abs(it.sourceIn - picture.sourceIn) < .001 && abs(it.sourceOut - picture.sourceOut) < .001
            }
        }
    }
    private val base = edit.project.duplicate()
    val clips get() = base.clips.filter { it.id in ids && it.frozen == null }
    val reviews = clips.associate { c -> c.id to (c.pauses ?: PauseReview(analyzed = SourceInterval(c.sourceIn, c.sourceOut))) }.toMutableMap()
    @Volatile var cancelled = false

    fun analyze(cache: AudioCache, progress: (Float) -> Unit) {
        val waves = mutableMapOf<String, List<Float>>()
        val analyzed = mutableMapOf<String, PauseReview>()
        for ((index, clip) in clips.withIndex()) {
            if (cancelled) throw InterruptedIOException("Pause analysis cancelled")
            val levels = waves.getOrPut(clip.path) {
                val values = mutableListOf<Float>()
                RandomAccessFile(cache.get(clip.path) { cancelled }, "r").use { input ->
                    val bytes = ByteArray(480 * 8)
                    while (input.filePointer < input.length()) {
                        if (cancelled) throw InterruptedIOException("Pause analysis cancelled")
                        val count = min(bytes.size.toLong(), input.length() - input.filePointer).toInt()
                        input.readFully(bytes, 0, count)
                        val pcm = ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        var sum = 0.0
                        while (pcm.hasRemaining()) { val v = pcm.get().toDouble(); sum += v * v }
                        values.add(sqrt(sum / (count / 4)).toFloat())
                    }
                }
                values
            }
            val settings = reviews.getValue(clip.id)
            val key = "${clip.path}:${clip.sourceIn}:${clip.sourceOut}:${clip.copy(pauses = null, frozen = null).playbackSpans}:${settings.copy(candidates = emptyList())}"
            reviews[clip.id] = analyzed.getOrPut(key) { PauseDetector.detect(levels, .01, clip, settings) }
            progress((index + 1f) / clips.size)
        }
    }

    fun snapshot(): Project = Editor(base.duplicate()).apply { setPauseReviews(reviews) }.project
    fun reset() { reviews.replaceAll { _, value -> value.copy(candidates = value.candidates.map { it.copy(enabled = false) }) } }
    fun commit(editor: Editor) = editor.commitDraft(original, snapshot())
}
