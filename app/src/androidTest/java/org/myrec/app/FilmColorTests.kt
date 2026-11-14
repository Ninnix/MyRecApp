package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs

object FilmColorTests {
    fun run(instrumentation: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit) {
        test("five film colors match 58 source-equation fixtures within one SDR code value") {
            val data = instrumentation.context.assets.open("film-color-reference.json").bufferedReader().use { JSONObject(it.readText()) }
            val fixtures = data.getJSONArray("fixtures")
            for (i in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(i)
                val definition = FilmColor.definitions.entries.single { it.value.kernel == fixture.getInt("kernel") }
                val fx = Effect(kind = definition.key)
                val values = fixture.getJSONArray("parameters")
                for ((index, control) in definition.value.controls.withIndex()) {
                    fx.parameter(control.id)!!.value = when (val kind = control.kind) {
                        is ParameterKind.Slider -> EffectValue.Number(values.getDouble(index))
                        is ParameterKind.Picker -> EffectValue.Choice(kind.values[values.getInt(index)])
                        ParameterKind.Toggle -> EffectValue.Toggle(values.getDouble(index) > .5)
                        else -> error("Invalid control")
                    }
                }
                fx.setNumber("intensity", fixture.getDouble("intensity").toFloat())
                val width = fixture.getInt("width"); val height = fixture.getInt("height")
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val input = fixture.getJSONArray("input"); val expected = fixture.getJSONArray("output")
                for (index in 0 until input.length()) input.getJSONArray(index).let {
                    image.setPixel(index % width, index / width, Color.rgb(it.getInt(0), it.getInt(1), it.getInt(2)))
                }
                Effects.correct(image, fx, fx.strength(0.0, 1.0))
                for (index in 0 until expected.length()) {
                    val pixel = image.getPixel(index % width, index / width)
                    val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    channels.forEachIndexed { c, value ->
                        check(abs(value - expected.getJSONArray(index).getInt(c)) <= 1) {
                            "${fx.kind} fixture $i pixel $index channel $c: $value != ${expected.getJSONArray(index).getInt(c)}"
                        }
                    }
                }
                image.recycle()
            }
        }
        fun find(root: View, name: String, bar: Boolean = false): View? {
            if ((!bar || root is SeekBar) && (root.contentDescription?.toString() == name || root is TextView && root.text.toString() == name)) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), name, bar)?.let { return it }
            return null
        }
        test("film color inspectors expose all reference controls in both orientations") {
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                onMain { a.panels.close(); a.requestedOrientation = orientation }
                Thread.sleep(550)
                for ((kind, definition) in FilmColor.definitions) {
                    onMain {
                        a.editor.project.clips.forEach { it.effects.clear() }
                        a.editor.clearSelection(); a.editor.cursor = 0.0
                        a.editor.applyEffect(Effect(kind = kind))
                        a.panels.videoEffects(a.editor.project.clips.first().effects.single())
                    }
                    Thread.sleep(150)
                    onMain {
                        val intensity = find(a.root, "Intensity", true)!!
                        check(intensity.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
                        for (control in definition.controls) {
                            val view = find(a.root, control.title, control.kind is ParameterKind.Slider)
                                ?: error("Missing $kind ${control.title}")
                            when (control.kind) {
                                is ParameterKind.Slider -> check(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                                ParameterKind.Toggle -> {
                                    val toggle = if (view is ReferenceToggle) view else (view.parent as ViewGroup).getChildAt(1)
                                    check(toggle.performClick())
                                }
                                else -> check(view.visibility == View.VISIBLE)
                            }
                        }
                        find(a.root, "Apply")!!.performClick()
                        val effect = a.editor.project.clips.first().effects.single()
                        check(effect.kind == kind && effect.number("intensity") < 1f)
                        check(effect.parameters.all { it.valid() })
                        check(a.store.decode(a.store.encode(a.editor.project)) == a.editor.project)
                    }
                }
            }
        }
        test("typed picker toggle and numeric keys persist across cuts and evaluate at source time") {
            val c = a.editor.project.clips.first().duplicate(true).apply { effects.clear() }
            val editor = Editor(Project(clips = mutableListOf(c)))
            val work = VideoEffectsDraft(editor, Effects.video.toSet())
            val fx = Effect(kind = "Day for Night"); work.chain.add(fx)
            work.editValue(fx, "moonlight", EffectValue.Toggle(false), .5, true)
            work.editNumber(fx, "blue", .2f, .5, true)
            work.commit(editor)
            editor.cursor = 1.0; editor.split()
            val reopened = a.store.decode(a.store.encode(editor.project))
            check(reopened.clips.all { it.effects.single().parameter("moonlight")!!.keyframes.size == 2 })
            val effect = reopened.clips.first().effects.single()
            check(effect.sampled(c.sourceIn).parameter("moonlight")!!.value == EffectValue.Toggle(true))
            check(effect.sampled(c.sourceTime(.5)).parameter("moonlight")!!.value == EffectValue.Toggle(false))
        }
    }
}
