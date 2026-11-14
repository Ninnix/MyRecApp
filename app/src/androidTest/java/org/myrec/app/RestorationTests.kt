package org.myrec.app

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object RestorationTests {
    private fun write(file: File, count: Int, sample: (Int, Int) -> Float) {
        val bytes = ByteBuffer.allocate(count * 8).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { i -> repeat(2) { c -> bytes.putFloat(sample(i, c)) } }
        file.writeBytes(bytes.array())
    }
    private fun read(file: File): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
    fun run(instrumentation: Instrumentation, a: MainActivity, test: (String, () -> Unit) -> Unit) {
        val root = File(a.cacheDir, "restoration-${uid()}").apply { mkdirs() }
        try {
            test("rational resampling preserves stereo phase pitch amplitude and exact round trip duration") {
                val input = File(root, "input.f32"); val down = File(root, "down.f32"); val up = File(root, "up.f32")
                write(input, 48017) { i, c -> (.3 * sin(2 * PI * 733 * i / 48000) * if (c == 0) 1 else -1).toFloat() }
                val frames = (48017.0 * 44100 / 48000).roundToLong()
                check(Native.audioResample(input.path, down.path, 48000, 44100, frames) { _, _ -> true } == frames)
                check(Native.audioResample(down.path, up.path, 44100, 48000, 48017) { _, _ -> true } == 48017L)
                val result = read(up); val source = read(input)
                var error = 0.0
                for (i in 100 until 47917) {
                    check(abs(result[i * 2] + result[i * 2 + 1]) < 1e-7)
                    error += (result[i * 2] - source[i * 2]).pow(2)
                }
                check(sqrt(error / 47817) < .0002) { "Resampling RMS error: ${sqrt(error / 47817)}" }
            }
            test("resampling reflects one frame and rejects cancellation malformed samples and output collisions") {
                val input = File(root, "one.f32"); val output = File(root, "one-out.f32")
                write(input, 1) { _, c -> if (c == 0) .375f else -.25f }
                check(Native.audioResample(input.path, output.path, 48000, 44100, 1) { _, _ -> true } == 1L)
                val samples = read(output)
                check(abs(samples[0] - .375f) < 1e-6 && abs(samples[1] + .25f) < 1e-6)
                val original = output.readBytes()
                check(Native.audioResample(input.path, output.path, 48000, 44100, 1) { _, _ -> true } == -1L)
                check(output.readBytes().contentEquals(original)); output.delete()
                check(Native.audioResample(input.path, output.path, 48000, 44100, 1) { _, _ -> false } == -1L && !output.exists())
                write(input, 1) { _, _ -> Float.NaN }
                check(Native.audioResample(input.path, output.path, 48000, 44100, 1) { _, _ -> true } == -1L && !output.exists())
            }
            test("speech resampling is band limited mono with exact duration and silence") {
                val input = File(root, "mono.f32"); val output = File(root, "mono16.f32")
                val bytes = ByteBuffer.allocate(48017 * 4).order(ByteOrder.LITTLE_ENDIAN)
                repeat(48017) { i -> bytes.putFloat((.3 * sin(2 * PI * 1000 * i / 48000) + .3 * sin(2 * PI * 12000 * i / 48000)).toFloat()) }
                input.writeBytes(bytes.array())
                val count = (48017 / 3.0).roundToLong()
                check(Native.audioResample(input.path, output.path, 48000, 16000, count, 1) { _, _ -> true } == count)
                val result = read(output)
                check(result.size == count.toInt())
                val error = sqrt((100 until result.size - 100).sumOf { i ->
                    (result[i] - .3 * sin(2 * PI * 1000 * i / 16000)).pow(2)
                } / (result.size - 200))
                check(error < .0005) { "Speech resampling RMS error $error" }
            }
            test("downsampling removes frequencies beyond the new Nyquist limit") {
                val input = File(root, "high.f32"); val output = File(root, "high-down.f32")
                write(input, 48000) { i, _ -> (.3 * sin(2 * PI * 23500 * i / 48000)).toFloat() }
                check(Native.audioResample(input.path, output.path, 48000, 44100, 44100) { _, _ -> true } == 44100L)
                val result = read(output)
                val rms = sqrt((100 until 44000).sumOf { result[it * 2].toDouble().pow(2) } / 43900)
                check(rms < .002) { "Aliased high-frequency RMS: $rms" }
            }
            test("transcription checkpoints resume exact source words and reject stale or corrupt progress") {
                val file = File(root, "transcript.json")
                val checkpoint = CaptionCheckpoint(file, "source-recipe", 3)
                check(checkpoint.load().completed == 0)
                val words = listOf(SubtitleWord(text = "hello", start = .1, end = .5))
                checkpoint.save(CaptionCheckpoint.State(1, words))
                check(checkpoint.load() == CaptionCheckpoint.State(1, words))
                check(CaptionCheckpoint(file, "changed-source", 3).load().completed == 0)
                check(CaptionCheckpoint(file, "source-recipe", 0).load().completed == 0)
                file.writeText("{"); check(checkpoint.load().completed == 0)
                check(root.listFiles()!!.none { it.extension == "partial" })
            }
            test("verified VoiceFixer runs the reference 256 frame context with centered finite output") {
                AudioModels.verify("Studio restoration") { false }
                val handle = Native.neuralLoad(AudioModels.directory("Studio restoration").path, 1, 2)
                check(handle != 0L)
                try {
                    val source = File(root, "speech.wav")
                    instrumentation.context.assets.open("speech.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
                    val decoded = a.audioCache.get(source.path) { false }
                    val resampled = File(root, "speech441.f32")
                    SourceAudio.resample(decoded, resampled, 48000, 44100, cancelled = { false }, progress = {})
                    val pcm = read(resampled)
                    val window = SourceAudio.restoration
                    check(window.frames == 112455 && window.overlap == 3087 && window.stride == 52920)
                    val input = FloatArray(window.frames) { pcm[SourceAudio.reflected(it.toLong() - window.margin, pcm.size / 2L).toInt() * 2] }
                    val output = Native.neuralProcess(handle, input)
                    check(output.size == input.size && output.all { it.isFinite() })
                    check(sqrt(output.sumOf { it.toDouble().pow(2) } / output.size) > .0001)
                    check(output.indices.any { abs(output[it] - input[it]) > .001f })
                } finally { Native.neuralFree(handle) }
            }
        } finally { root.deleteRecursively() }
    }
}
