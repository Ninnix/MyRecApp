package org.myrec.app

import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.*

class Captions(private val cache: AudioCache, val directory: File) {
    companion object {
        private val inferenceLock = Any()
    }

    @Volatile var cancelled = false
    private val revision = "5eb144179a02acc5e5ba31e748d22b0cf3e303b0"
    private val names =
        listOf(
            "config.json",
            "generation_config.json",
            "vocab.json",
            "merges.txt",
            "model.safetensors",
        )
    val ready
        get() = names.all { File(directory, it).length() > 0 }

    fun download(progress: (Float) -> Unit) {
        directory.mkdirs()
        for ((i, name) in names.withIndex()) {
            if (cancelled) throw InterruptedIOException("Download cancelled")
            val file = File(directory, name)
            if (file.length() > 0) continue
            val partial = File(directory, "$name.partial")
            val offset = partial.length()
            val connection =
                URI("https://huggingface.co/Qwen/Qwen3-ASR-0.6B/resolve/$revision/$name")
                    .toURL()
                    .openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            try {
                val status = connection.responseCode
                check(status in 200..299) { "Model download failed ($status)" }
                val append = status == 206 && offset > 0
                val total = connection.contentLengthLong + (if (append) offset else 0)
                var written = if (append) offset else 0L
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (cancelled) throw InterruptedIOException("Download cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            written += n
                            progress(
                                (i + (if (total > 0) written.toFloat() / total else 0f)) /
                                    names.size
                            )
                        }
                    }
                }
                check(partial.renameTo(file)) { "Cannot save model" }
            } finally {
                connection.disconnect()
            }
        }
    }

    fun generate(project: Project, language: String, progress: (Float) -> Unit): List<Caption> =
        synchronized(inferenceLock) {
            if (cancelled) throw InterruptedIOException("Captions cancelled")
            check(ready) { "Download the caption model first" }
            check(Native.asrAvailable()) { "The caption runtime is not included in this build" }
            val handle =
                Native.asrLoad(
                    directory.absolutePath,
                    min(6, Runtime.getRuntime().availableProcessors()),
                )
            check(handle != 0L) { "Cannot load the caption model" }
            val captions = mutableListOf<Caption>()
            try {
                AudioMixer(project, cache).use { mixer ->
                    mixer.prepare()
                    mixer.seek(0.0)
                    var position = 0.0
                    while (position < project.duration) {
                        if (cancelled) throw InterruptedIOException("Captions cancelled")
                        val length = min(6.0, project.duration - position)
                        val stereo = mixer.read((length * 48000).toInt())
                        val mono = FloatArray(stereo.size / 6)
                        var peak = 0f
                        for (i in mono.indices) {
                            val offset = i * 6
                            mono[i] =
                                (stereo[offset] +
                                    stereo[offset + 1] +
                                    stereo[offset + 2] +
                                    stereo[offset + 3] +
                                    stereo[offset + 4] +
                                    stereo[offset + 5]) / 6
                            peak = max(peak, abs(mono[i]))
                        }
                        if (peak > 0.003f) {
                            val bytes = Native.asrTranscribe(handle, mono, language)
                            val text =
                                bytes
                                    ?.toString(Charsets.UTF_8)
                                    ?.substringAfter("<asr_text>")
                                    ?.trim()
                                    .orEmpty()
                            if (text.isNotBlank())
                                captions.add(Caption(position, position + length, text))
                        }
                        position += length
                        progress((position / project.duration).toFloat())
                    }
                }
            } finally {
                Native.asrFree(handle)
            }
            captions
        }
}

data class Caption(val start: Double, val end: Double, val text: String)
