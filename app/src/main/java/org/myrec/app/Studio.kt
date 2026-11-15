package org.myrec.app

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
    private const val VERSION = "source-audio-v9-aac-clock-1"
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
    private var engine = 2
    private var verified = false
    @Volatile var downloadCancelled = false
    val ready
        get() =
            ::directory.isInitialized &&
                File(directory, "ready").isFile &&
                models.all { (name, spec) -> File(directory, name).length() == spec.first }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun hash(value: String) =
        hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    fun verify(cancelled: () -> Boolean) {
        if (verified) return
        check(ready) { "Download Speech detail first" }
        for ((name, spec) in models) check(SourceAudio.hash(File(directory, name), cancelled) == spec.second) {
            "Studio audio model did not verify; download it again"
        }
        verified = true
    }

    fun download(progress: (Float) -> Unit) {
        verified = false
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
                    (it.kind in AudioModels.neural || it.kind == "AI voice isolation") && it.enabled && it.number("strength", 1f) > 0
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
                "${clip.path}:${clip.sourceIn}:${clip.sourceOut}:${clip.start}:${clip.playbackSpans}:${clip.gainDb}:${clip.pauses}:${clip.frozen}:" +
                    clip.effects
                        .filter { it.kind in Effects.audio }
                        .joinToString("|") { parameters(it) }
            } +
            project.transitions.joinToString { transition ->
                "${transition.at}:${transition.duration}:${transition.hold}:${transition.overlap}:${transition.phaseFrom}:${transition.phaseTo}:" +
                    "${part(transition.leftId)}:${part(transition.rightId)}"
            }
    }

    @Synchronized
    fun prepare(
        clip: Clip,
        cache: AudioCache,
        cancelled: () -> Boolean,
        progress: (Float) -> Unit,
    ): File? {
        if (!has(Project(clips = mutableListOf(clip)))) return null
        check(::cacheDirectory.isInitialized) { "Studio audio is not configured" }
        cacheDirectory.mkdirs()
        var source = cache.get(clip.path, cancelled)
        if (source.length() == 0L) return null
        fun clock(source: Double) = when {
            source < clip.sourceIn -> (source - clip.sourceIn) / clip.effectiveRate
            source > clip.sourceOut -> clip.duration + (source - clip.sourceOut) / clip.rateAt(clip.sourceOut)
            else -> clip.timelineOffset(source)
        }
        val effects = clip.effects.filter { it.kind in Effects.audio && it.enabled }
        for ((index, effect) in effects.withIndex()) {
            if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
            val noise = effect.kind == "AI voice isolation"
            val neural = effect.kind in AudioModels.neural || noise
            if (effect.number("strength", 1f) <= 0) continue
            val full = effect.from <= 0 && effect.to >= clip.duration && effect.number("animate") == 0f
            val scope = if (full) "source" else "${clip.sourceIn}:${clip.playbackSpans}:${clip.duration}"
            val model = if (noise) "rnnoise-0.1-stereo-1" else if (neural) AudioModels.version(effect.kind) else if (effect.values["dsp"] == "2") "dsp-2" else "dsp-1"
            val recipe = if (full) "${effect.kind}:${effect.values.toSortedMap()}" else parameters(effect)
            val identity = hash("$VERSION:$model:${SourceAudio.hash(source, cancelled)}:$recipe:$scope")
            val target = File(cacheDirectory, "$identity.f32")
            if (!target.isFile || target.length() != source.length()) {
                var wet: File? = null
                if (noise) {
                    check(Native.rnnoiseAvailable()) { "Noise reduction is unavailable in this build" }
                    val key = hash("$VERSION:$model:${SourceAudio.hash(source, cancelled)}")
                    wet = File(cacheDirectory, "$key.wet.f32")
                    if (wet.length() != source.length()) {
                        wet.delete()
                        SourceAudio.denoise(source, wet, cancelled) { p -> progress((index + p) / effects.size) }
                    }
                } else if (neural) {
                    check(AudioModels.ready(effect.kind)) { "Open Enhance audio to download ${effect.kind} first" }
                    check(Native.studioAvailable()) { "Studio audio is unavailable in this build" }
                    val kind = AudioModels.kind(effect.kind)
                    val window = when (kind) { 0 -> SourceAudio.reduction; 1 -> SourceAudio.restoration; else -> SourceAudio.detail }
                    val wetKey = hash("$VERSION:${AudioModels.version(effect.kind)}:${SourceAudio.hash(source, cancelled)}:$window")
                    wet = File(cacheDirectory, "$wetKey.wet.f32")
                    if (wet.length() != source.length()) {
                        wet.delete()
                        if (handle != 0L && engine != kind) {
                            if (engine == 2) Native.studioFree(handle) else Native.neuralFree(handle)
                            handle = 0L
                        }
                        engine = kind
                        AudioModels.verify(effect.kind, cancelled)
                        if (handle == 0L) handle = if (kind == 2) Native.studioLoad(directory.absolutePath, 2)
                            else Native.neuralLoad(AudioModels.directory(effect.kind).absolutePath, kind, 2)
                        check(handle != 0L) { "Cannot prepare ${effect.kind}" }
                        val inferenceInput = if (kind == 1) {
                            val down = File(cacheDirectory, "$wetKey.input441.f32")
                            SourceAudio.resample(source, down, 48000, 44100, cancelled = cancelled, progress = {})
                            down
                        } else source
                        val rendered = if (kind == 1) File(cacheDirectory, "$wetKey.output441.f32") else wet
                        if (rendered.length() != inferenceInput.length()) {
                            rendered.delete()
                            SourceAudio.render(inferenceInput, rendered, File(cacheDirectory, "$wetKey.chunks"), window,
                                cancelled, { p -> progress((index + p) / effects.size) }) {
                                if (engine == 2) Native.studioProcess(handle, it) else Native.neuralProcess(handle, it)
                            }
                        }
                        if (kind == 1) SourceAudio.resample(rendered, wet, 44100, 48000, source.length() / 8, cancelled, {})
                    }
                }
                val partial = File(cacheDirectory, "$identity-${uid()}.partial")
                val state = if (neural) 0L else Native.audioCreate(48000)
                try {
                    java.io.RandomAccessFile(source, "r").use { dry ->
                        val restored = wet?.let { java.io.RandomAccessFile(it, "r") }
                        try {
                            FileOutputStream(partial).buffered().use { output ->
                                var position = 0L
                                while (position < source.length() / 8) {
                                    if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                                    val frames = min(1024L, source.length() / 8 - position).toInt()
                                    val bytes = ByteArray(frames * 8); dry.readFully(bytes)
                                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                                    val original = FloatArray(frames * 2).also { buffer.asFloatBuffer().get(it) }
                                    val processed = if (restored != null) {
                                        restored.readFully(bytes)
                                        FloatArray(frames * 2).also { buffer.asFloatBuffer().get(it) }
                                    } else original.copyOf().also {
                                        AudioFilter.process(state, it, effect,
                                            effect.strength(if (full) 0.0 else clock(position / 48000.0), clip.duration))
                                    }
                                    for (i in processed.indices) {
                                        val local = (position + i / 2) / 48000.0
                                        val clock = if (full) 0.0 else clock(local)
                                        val strength = if (full || effect.active(clock)) {
                                            if (neural) effect.strength(clock, clip.duration) else 1f
                                        } else 0f
                                        val value = original[i] + strength * (processed[i] - original[i])
                                        check(value.isFinite()) { "Invalid processed audio" }
                                        buffer.putFloat(i * 4, value)
                                    }
                                    output.write(bytes)
                                    position += frames
                                }
                            }
                        } finally { restored?.close() }
                    }
                    check(partial.length() == source.length() && partial.renameTo(target)) { "Cannot save source audio" }
                } finally {
                    if (state != 0L) Native.audioDestroy(state)
                    partial.delete()
                }
            }
            source = target
            progress((index + 1f) / effects.size)
        }
        val entries = cacheDirectory.listFiles().orEmpty().filter { it.extension != "partial" }
        fun bytes(file: File) = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()
        var total = entries.sumOf(::bytes)
        for (file in entries.sortedBy { it.lastModified() }) {
            if (total <= 384L * 1024 * 1024) break
            if (file == source) continue
            val size = bytes(file)
            if (file.deleteRecursively()) total -= size
        }
        return source
    }
}
