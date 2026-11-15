package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.*

object DirectAudioTests {
    fun run(instrumentation: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
            test: (String, () -> Unit) -> Unit) {
        fun filter(fx: Effect, input: FloatArray, chunk: Int = input.size / 2): FloatArray {
            val state = Native.audioCreate(48000); check(state != 0L)
            try {
                val result = input.copyOf()
                for (start in 0 until input.size / 2 step chunk) {
                    val end = min(input.size / 2, start + chunk)
                    val block = input.copyOfRange(start * 2, end * 2)
                    AudioFilter.process(state, block, fx, 1f); block.copyInto(result, start * 2)
                }
                return result
            } finally { Native.audioDestroy(state) }
        }
        fun response(fx: Effect, frequency: Double): Double {
            val input = FloatArray(96000) { if (it % 2 == 0) (.2 * sin(2 * PI * frequency * (it / 2) / 48000)).toFloat() else 0f }
            val output = filter(fx, input, 137)
            check(output.indices.filter { it % 2 == 1 }.all { output[it] == 0f })
            return sqrt((24000 until 48000).sumOf { output[it * 2].toDouble().pow(2) } /
                (24000 until 48000).sumOf { input[it * 2].toDouble().pow(2) })
        }
        test("reference low cut has a Butterworth corner and twelve decibel octave slope") {
            val fx = AudioFilter.effect("Low-cut filter").apply { values["frequency"] = "240" }
            check(abs(response(fx, 240.0) - sqrt(.5)) < .0001)
            check(response(fx, 60.0) in .061.. .064)
            check(response(fx, 120.0) in .241.. .244)
            check(response(fx, 4000.0) > .999)
        }
        test("reference EQ matches Q one source response and defaults to exact zero gain bypass") {
            val fx = AudioFilter.effect("Voice EQ")
            val input = FloatArray(4096) { (sin(it * .13) * .3).toFloat() }
            check(filter(fx, input).contentEquals(input))
            fx.values["gain"] = "6"
            for ((frequency, magnitude) in listOf(900.0 to 1.240135622163, 1800.0 to 1.995262314969, 3600.0 to 1.233953565177))
                check(abs(response(fx, frequency) - magnitude) < .0001)
            fx.values["gain"] = "-6"
            check(abs(response(fx, 1800.0) - 1 / 1.995262314969) < .0001)
        }
        test("direct audio stages retain independent stereo state across arbitrary decoder blocks") {
            val input = FloatArray(14000).apply { this[0] = 1f; this[35] = .7f }
            for (kind in AudioFilter.direct) {
                val fx = AudioFilter.effect(kind).apply { values["gain"] = "5" }
                val whole = filter(fx, input)
                check(filter(fx, input, 1).contentEquals(whole))
                check(filter(fx, input, 317).contentEquals(whole))
                check((0 until 17).all { whole[2 * it + 1] == 0f })
                fx.enabled = false; check(filter(fx, input).contentEquals(input))
            }
        }
        test("output gain is ordered unclipped decibel gain and its neutral stage is exact") {
            val fx = AudioFilter.effect("Output gain")
            val input = floatArrayOf(.2f, -.1f, -0f, .5f)
            check(filter(fx, input).contentEquals(input))
            fx.values["gain"] = "24"
            val result = filter(fx, input)
            check(result[0] > 3f && result[3] > 7f)
            for (i in input.indices) check(abs(result[i] - input[i] * 10.0.pow(24.0 / 20)) < .000001)
        }
        test("output gain reaches mixer samples and repeated seeks without changing the source") {
            val dryProject = a.editor.project.duplicate()
            val wetProject = dryProject.duplicate()
            wetProject.clips.first { it.audio }.effects.add(AudioFilter.effect("Output gain").apply { values["gain"] = "-6" })
            AudioMixer(dryProject, a.audioCache).use { dry ->
                AudioMixer(wetProject, a.audioCache).use { wet ->
                    dry.prepare(); wet.prepare(); dry.seek(.25); wet.seek(.25)
                    val input = dry.read(4096); val output = wet.read(4096)
                    val scale = 10.0.pow(-6.0 / 20)
                    check(input.indices.all { abs(output[it] - input[it] * scale) < .000001 })
                    wet.seek(.25); check(wet.read(4096).contentEquals(output))
                }
            }
        }
        fun find(view: View, name: String): View? {
            if (view.contentDescription?.toString() == name || view is TextView && view.text.toString() == name) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), name)?.let { return it }
            return null
        }
        test("modern audio defaults reset persist and undo while legacy processing remains unchanged") {
            val before = a.editor.project.duplicate()
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                onMain { a.panels.close(); a.editor.load(before.duplicate()); a.requestedOrientation = orientation }
                Thread.sleep(450)
                onMain {
                    a.panels.audio(initial = AudioFilter.direct.map { AudioFilter.effect(it) })
                    (find(a.root, "Reset") ?: error("No audio reset")).performClick()
                    (find(a.root, "Apply") ?: error("No audio Apply")).performClick()
                    val filters = a.editor.project.clips.first { it.audio }.effects.filter { it.kind in AudioFilter.direct }
                    check(filters.size == 3 && filters.all { it.values["dsp"] == "2" })
                    check(a.store.decode(a.store.encode(a.editor.project)) == a.editor.project)
                    val changed = a.editor.project.duplicate()
                    a.editor.undo(); check(a.editor.project == before)
                    a.editor.redo(); check(a.editor.project == changed)
                }
            }
            val legacy = Effect(kind = "Low-cut filter")
            val input = FloatArray(2000).apply { this[0] = 1f }
            val first = filter(legacy, input)
            check(!filter(AudioFilter.effect("Low-cut filter"), input).contentEquals(first))
            check(filter(legacy.copyEffect(), input).contentEquals(first))
            onMain { a.editor.load(before.duplicate()) }
        }
    }
}
