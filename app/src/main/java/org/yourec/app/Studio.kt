package org.yourec.app

import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.*

object Studio {
    private const val VERSION = "lava-v1-c-1"
    private const val MODEL_URL =
        "https://github.com/Topping1/LavaSR-ONNX/releases/download/Alpha-v0.1/"
    private val models =
        linkedMapOf(
            "enhancer_backbone.onnx" to
                (190195L to "841e96d261dffdf1dc974f3d29e2cfcf1b16fd0b358749c1ace0bbfa1d4c8ddd"),
            "enhancer_backbone.onnx.data" to
                (51773440L to "a125a4ede7cfdd1073d906a3cadf2171a30be6a40f296ad28772e0ba258de8c5"),
            "enhancer_spec_head.onnx" to
                (7484L to "f66fd164c55fd1b07e5cea5e687c71522b192f452691128fd7ae4e6b26dbc683"),
            "enhancer_spec_head.onnx.data" to
                (4263936L to "b855e309b027af9aa75285b97b345571b6bd695a30fde434d06c979d83885fd6"),
        )
    lateinit var directory: File
    lateinit var cacheDirectory: File
    private var handle = 0L
    @Volatile var downloadCancelled = false
    val ready
        get() =
            ::directory.isInitialized &&
                File(directory, "ready").isFile &&
                models.all { (name, spec) -> File(directory, name).length() == spec.first }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun hash(value: String) =
        hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    fun download(progress: (Float) -> Unit) {
        directory.mkdirs()
        val total = models.values.sumOf { it.first }
        var complete = 0L
        for ((name, spec) in models) {
            if (downloadCancelled) throw InterruptedIOException("Download cancelled")
            val file = File(directory, name)
            val partial = File(directory, "$name.partial")
            if (file.length() != spec.first) {
                val offset = partial.length()
                val connection = URI(MODEL_URL + name).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                try {
                    val status = connection.responseCode
                    check(status in 200..299) { "Studio audio download failed ($status)" }
                    val append = offset > 0 && status == 206
                    var written = if (append) offset else 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(partial, append).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                if (downloadCancelled)
                                    throw InterruptedIOException("Download cancelled")
                                val size = input.read(buffer)
                                if (size < 0) break
                                output.write(buffer, 0, size)
                                written += size
                                check(written <= spec.first) { "Invalid studio audio download" }
                                progress((complete + written).toFloat() / total)
                            }
                        }
                    }
                    check(partial.length() == spec.first) { "Incomplete studio audio download" }
                    check(partial.renameTo(file)) { "Cannot save studio audio" }
                } finally {
                    connection.disconnect()
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            if (hex(digest.digest()) != spec.second) {
                file.delete()
                error("Studio audio download did not verify; please retry")
            }
            complete += spec.first
            progress(complete.toFloat() / total)
        }
        File(directory, "ready").writeText(VERSION)
    }

    fun has(project: Project) =
        project.clips.any { clip ->
            clip.audio &&
                clip.effects.any {
                    it.kind == "Studio clarity" && it.enabled && it.number("strength", 1f) > 0
                }
        }

    private fun parameters(effect: Effect) =
        "${effect.kind}:${effect.from}:${effect.to}:${effect.enabled}:${effect.values.toSortedMap()}"

    fun signature(project: Project): String {
        fun part(id: String?) =
            project.clips
                .firstOrNull { it.id == id }
                ?.let { "${it.path}:${it.sourceIn}:${it.sourceOut}:${it.start}" } ?: "none"
        return project.clips
            .filter { it.audio }
            .joinToString(";") { clip ->
                "${clip.path}:${clip.sourceIn}:${clip.sourceOut}:${clip.start}:${clip.effectiveRate}:${clip.gainDb}:" +
                    clip.effects
                        .filter { it.kind in Effects.audio }
                        .joinToString("|") { parameters(it) }
            } +
            project.transitions.joinToString { transition ->
                "${transition.at}:${transition.duration}:${transition.hold}:${transition.overlap}:${transition.phaseFrom}:${transition.phaseTo}:" +
                    "${part(transition.leftId)}:${part(transition.rightId)}"
            }
    }

    data class Prepared(val file: File, val index: Int)

    @Synchronized
    fun prepare(
        project: Project,
        clip: Clip,
        cache: AudioCache,
        cancelled: () -> Boolean,
        progress: (Float) -> Unit,
    ): Prepared? {
        val index =
            clip.effects.indexOfLast {
                it.kind == "Studio clarity" &&
                    it.enabled &&
                    it.number("strength", 1f) > 0 &&
                    it.from < clip.duration &&
                    it.to > 0
            }
        if (index < 0) return null
        check(::cacheDirectory.isInitialized) { "Studio audio is not configured" }
        cacheDirectory.mkdirs()
        val prefix = project.duplicate()
        prefix.clips.forEach { it.audio = it.id == clip.id }
        val source = prefix.clips.first { it.id == clip.id }
        val effect = clip.effects[index]
        val before =
            source.effects.filterIndexed { i, fx -> i < index || fx.kind !in Effects.audio }
        source.effects.clear()
        source.effects.addAll(before)
        val media = File(clip.path)
        val key =
            hash(
                "$VERSION:${media.length()}:${media.lastModified()}:${signature(prefix)}:${parameters(effect)}"
            )
        val target = File(cacheDirectory, "$key.f32")
        val count = (clip.duration * 48000).roundToLong()
        if (target.length() == count * 8 && count > 0) {
            target.setLastModified(System.currentTimeMillis())
            return Prepared(target, index)
        }
        if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
        check(ready) { "Open Enhance audio to download Studio clarity first" }
        check(Native.studioAvailable()) { "Studio audio is unavailable in this build" }
        if (handle == 0L) handle = Native.studioLoad(directory.absolutePath, 2)
        check(handle != 0L) { "Cannot prepare studio audio" }
        val partial = File(cacheDirectory, "$key.partial")
        try {
            AudioMixer(prefix, cache).use { mixer ->
                mixer.prepare(cancelled)
                FileOutputStream(partial).buffered().use { output ->
                    var position = 0L
                    while (position < count) {
                        if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                        val end = min(count, position + 48000 * 6)
                        val from = max(0, position - 24000)
                        val to = min(count, end + 24000)
                        val frames = (to - from).toInt()
                        val stereo = FloatArray(frames * 2)
                        mixer.seek(clip.start + from / 48000.0)
                        var read = 0
                        while (read < frames) {
                            val size = min(1024, frames - read)
                            mixer.read(size).copyInto(stereo, read * 2)
                            read += size
                        }
                        val padded = max(2048, (frames + 511) / 512 * 512)
                        val mono = FloatArray(padded)
                        for (i in mono.indices) {
                            var sample = i
                            while (sample >= frames) sample = max(0, frames * 2 - 2 - sample)
                            mono[i] = (stereo[sample * 2] + stereo[sample * 2 + 1]) * .5f
                        }
                        val enhanced =
                            if (mono.all { abs(it) <= .00001f }) mono
                            else Native.studioProcess(handle, mono)
                        if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                        val buffer =
                            ByteBuffer.allocate((end - position).toInt() * 8)
                                .order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until (end - position).toInt()) {
                            val at = (position - from).toInt() + i
                            val local = (position + i) / 48000.0
                            val wet =
                                if (effect.active(local)) effect.strength(local, clip.duration)
                                else 0f
                            val delta = (enhanced[at] - mono[at]) * wet
                            buffer.putFloat(stereo[at * 2] + delta)
                            buffer.putFloat(stereo[at * 2 + 1] + delta)
                        }
                        output.write(buffer.array())
                        position = end
                        progress(position.toFloat() / count)
                    }
                }
            }
            check(partial.renameTo(target)) { "Cannot save prepared studio audio" }
            val cached = cacheDirectory.listFiles()?.filter { it.extension == "f32" } ?: emptyList()
            var size = cached.sumOf { it.length() }
            for (file in cached.sortedBy { it.lastModified() }) {
                if (size <= 384L * 1024 * 1024) break
                if (file == target) continue
                val bytes = file.length()
                if (file.delete()) size -= bytes
            }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
        return Prepared(target, index)
    }
}
