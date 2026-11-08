package org.yourec.app

import android.app.Instrumentation
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

object AudioPrecisionTest {
    fun run(test: Instrumentation, precision: String): JSONObject {
        require(precision in listOf("fp32", "fp16", "fp16-storage"))
        val context = test.targetContext
        val output =
            File(context.getExternalFilesDir(null), "voicefixer-precision/$precision").apply {
                mkdirs()
            }
        val folder = if (precision == "fp32") "voicefixer-v1" else "voicefixer-$precision"
        val models = File(context.getExternalFilesDir(null), "models/$folder")
        val result = JSONObject().put("precision", precision).put("threads", 2)
        val cases = JSONArray()
        result.put("cases", cases)
        var handle = 0L
        try {
            val speech = File(context.cacheDir, "precision-speech.wav")
            test.context.assets.open("speech.wav").use { input ->
                speech.outputStream().use { input.copyTo(it) }
            }
            val cache = AudioCache(File(context.cacheDir, "precision-pcm"))
            val pcm =
                ByteBuffer.wrap(cache.get(speech.path).readBytes())
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
            val source =
                FloatArray(min(96000, pcm.remaining() / 2)) { (pcm.get() + pcm.get()) * .5f }
            val start = System.nanoTime()
            handle = Native.neuralLoad(models.path, 1, 2)
            result.put("load_seconds", (System.nanoTime() - start) / 1e9)
            check(handle != 0L)
            for (name in listOf("normal", "quiet", "noise", "silence")) {
                val random = Random(11)
                val input =
                    FloatArray(source.size) { i ->
                        when (name) {
                            "quiet" -> source[i] * .01f
                            "noise" -> source[i] + (random.nextGaussian() * .025).toFloat()
                            "silence" -> 0f
                            else -> source[i]
                        }
                    }
                val begin = System.nanoTime()
                val samples = Native.neuralProcess(handle, input)
                val seconds = (System.nanoTime() - begin) / 1e9
                check(samples.size == input.size && samples.all { it.isFinite() }) {
                    "Invalid $precision output on $name"
                }
                val row =
                    JSONObject()
                        .put("case", name)
                        .put("seconds", seconds)
                        .put("samples", samples.size)
                        .put("peak", samples.maxOf { abs(it) })
                        .put("rms", sqrt(samples.sumOf { it.toDouble() * it } / samples.size))
                cases.put(row)
                val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(samples)
                File(output, "$name.f32").writeBytes(bytes.array())
                File(output, "result.json").writeText(result.toString(2))
            }
            result.put("passed", true)
        } catch (e: Exception) {
            result.put("passed", false).put("error", e.toString())
        } finally {
            if (handle != 0L) Native.neuralFree(handle)
            File(output, "result.json").writeText(result.toString(2))
        }
        return result
    }
}
