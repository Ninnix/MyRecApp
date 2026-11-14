package org.myrec.app

import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.*

class Captions(private val cache: AudioCache, val directory: File) {
    companion object {
        private val inferenceLock = Any()
        private val verifiedModels = mutableSetOf<String>()
    }

    @Volatile var cancelled = false
    private val revision = "5eb144179a02acc5e5ba31e748d22b0cf3e303b0"
    private val files = linkedMapOf(
        "config.json" to (6193L to "76d3ae4601ce939830b2517f4a6cadb86cc51316c3900af6b020b051c21a478c"),
        "generation_config.json" to (142L to "1da527824d81e07118facff437e03f2e24a23311e3bdeb2368973fe77e5f275c"),
        "vocab.json" to (2776833L to "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        "merges.txt" to (1671853L to "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
        "model.safetensors" to (1876091704L to "79d6cbd4c98c7bbffe9db2edac07f56cd6637d0d5944b27f6c2b8353840323ea"),
    )
    val ready get() = files.all { (name, spec) -> File(directory, name).isFile && File(directory, name).length() == spec.first }

    private fun verify() {
        val key = directory.path + files.keys.joinToString { "${File(directory, it).length()}:${File(directory, it).lastModified()}" }
        if (key in verifiedModels) return
        check(ready) { "Download the caption model first" }
        for ((name, spec) in files) check(SourceAudio.hash(File(directory, name)) { cancelled } == spec.second) {
            "The caption model did not verify. Download it again."
        }
        verifiedModels.add(key)
    }

    fun download(progress: (Float) -> Unit) {
        directory.mkdirs()
        val total = files.values.sumOf { it.first }
        var complete = 0L
        for ((name, spec) in files) {
            if (cancelled) throw InterruptedIOException("Download cancelled")
            val file = File(directory, name)
            if (file.length() == spec.first && SourceAudio.hash(file) { cancelled } == spec.second) {
                complete += spec.first; progress(complete.toFloat() / total); continue
            }
            val partial = File(directory, "$name.partial")
            if (partial.length() > spec.first) partial.delete()
            if (partial.length() < spec.first) {
                val offset = partial.length()
                val connection = URI("https://huggingface.co/Qwen/Qwen3-ASR-0.6B/resolve/$revision/$name").toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 30000; connection.readTimeout = 60000
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                try {
                    val status = connection.responseCode
                    check(status in 200..299) { "Model download failed ($status)" }
                    val append = status == 206 && offset > 0
                    var written = if (append) offset else 0L
                    connection.inputStream.use { input -> java.io.FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(131072)
                        while (true) {
                            if (cancelled) throw InterruptedIOException("Download cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n; check(written <= spec.first) { "Invalid caption model size" }
                            output.write(buffer, 0, n)
                            progress((complete + written).toFloat() / total)
                        }
                    } }
                } finally { connection.disconnect() }
            }
            if (partial.length() != spec.first || SourceAudio.hash(partial) { cancelled } != spec.second) {
                partial.delete(); error("The caption download did not verify. Please retry.")
            }
            check(partial.renameTo(file)) { "Cannot publish the caption model" }
            complete += spec.first; progress(complete.toFloat() / total)
        }
        File(directory, "ready").writeText(revision)
    }

    fun generate(project: Project, language: String, scope: ClosedFloatingPointRange<Double> = 0.0..project.duration,
        progress: (Float) -> Unit): List<Caption> = synchronized(inferenceLock) {
        if (cancelled) throw InterruptedIOException("Captions cancelled")
        check(ready) { "Download the caption model first" }
        check(Native.asrAvailable()) { "The caption runtime is not included in this build" }
        verify()
        directory.mkdirs()
        val audio = File(cache.root, "transcript-${uid()}.partial")
        val mix = File(cache.root, "mix-${uid()}.partial")
        val start = max(0.0, scope.start); val end = min(project.duration, scope.endInclusive)
        require(end > start) { "Select some audio first" }
        var handle = 0L
        try {
            val total = ((end - start) * 48000).roundToLong()
            var mixed = 0L
            AudioMixer(project, cache).use { mixer ->
                mixer.prepare({ cancelled }); mixer.seek(start)
                mix.outputStream().buffered().use { output ->
                    while (mixed < total) {
                        if (cancelled) throw InterruptedIOException("Captions cancelled")
                        val frames = min(960L, total - mixed).toInt()
                        val stereo = mixer.read(frames)
                        val buffer = java.nio.ByteBuffer.allocate(frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until frames) buffer.putFloat((stereo[i * 2] + stereo[i * 2 + 1]) * .5f)
                        output.write(buffer.array()); mixed += frames
                        progress(.1f * mixed / total)
                    }
                }
            }
            val samples = ((end - start) * 16000).roundToLong()
            check(Native.audioResample(mix.path, audio.path, 48000, 16000, samples, 1) { done, count ->
                progress(.1f + .05f * done / count); !cancelled
            } == samples) { "Cannot prepare speech audio" }
            val levels = mutableListOf<Float>()
            java.io.RandomAccessFile(audio, "r").use { input ->
                val bytes = ByteArray(320 * 4)
                while (input.filePointer < input.length()) {
                    val size = min(bytes.size.toLong(), input.length() - input.filePointer).toInt()
                    input.readFully(bytes, 0, size)
                    if (cancelled) throw InterruptedIOException("Captions cancelled")
                    val floats = java.nio.ByteBuffer.wrap(bytes, 0, size).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    var energy = 0.0
                    while (floats.hasRemaining()) { val value = floats.get(); energy += value * value }
                    levels.add(sqrt(energy / (size / 4)).toFloat())
                }
            }
            val plan = SubtitleTiming.plan(levels, samples / 16000.0)
            check(plan.isNotEmpty()) { "No speech was detected in this range. Try a range with audible speech." }
            val signature = "$revision:qwen-asr-92469425-bf16:speech-sinc-v1:$language:$start:$end:${SourceAudio.hash(audio) { cancelled }}:$plan"
            val identity = java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val checkpoints = File(cache.root, "transcriptions").apply { mkdirs() }
            val checkpoint = CaptionCheckpoint(File(checkpoints, "$identity.json"), identity, plan.size)
            val saved = checkpoint.load()
            val words = saved.words.toMutableList()
            if (saved.completed < plan.size) {
                handle = Native.asrLoad(directory.absolutePath, min(6, Runtime.getRuntime().availableProcessors()))
                check(handle != 0L) { "Cannot load the caption model" }
                java.io.RandomAccessFile(audio, "r").use { input ->
                    for ((i, chunk) in plan.withIndex().drop(saved.completed)) {
                        if (cancelled) throw InterruptedIOException("Captions cancelled")
                        val first = (chunk.range.start * 16000).roundToLong()
                        val count = min(samples - first, ((chunk.range.endInclusive - chunk.range.start) * 16000).roundToLong()).toInt()
                        val bytes = ByteArray(count * 4)
                        input.seek(first * 4); input.readFully(bytes)
                        val mono = FloatArray(count).also { java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
                        val text = Native.asrTranscribe(handle, mono, language)?.toString(Charsets.UTF_8)
                            ?.substringAfter("<asr_text>")?.trim().orEmpty()
                        val speech = chunk.speech.map { (start + it.start)..(start + it.endInclusive) }
                        words.addAll(SubtitleTiming.estimate(text, speech))
                        checkpoint.save(CaptionCheckpoint.State(i + 1, words))
                        progress(.15f + .85f * (i + 1) / plan.size)
                    }
                }
            }
            if (cancelled) throw InterruptedIOException("Captions cancelled")
            check(words.isNotEmpty()) { "The model did not find spoken words. Your previous subtitles are unchanged." }
            checkpoints.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }
                .drop(8).forEach { it.delete() }
            progress(1f)
            SubtitleTiming.group(words, 6).map { group ->
                Caption(group.first().start, group.last().end, group.joinToString(" ") { it.text }, group)
            }
        } finally {
            if (handle != 0L) Native.asrFree(handle)
            audio.delete(); mix.delete()
        }
    }
}

data class Caption(val start: Double, val end: Double, val text: String, val words: List<SubtitleWord>)
