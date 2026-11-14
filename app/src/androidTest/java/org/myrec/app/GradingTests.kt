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

object GradingTests {
    fun run(instrumentation: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit) {
        fun channels(pixel: Int) = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        test("primary correction matches upstream source-equation SDR goldens within one code value") {
            val data = instrumentation.context.assets.open("grade-reference.json").bufferedReader().use { JSONObject(it.readText()) }
            val fixtures = data.getJSONArray("fixtures")
            for (i in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(i)
                val fx = Effect(kind = "Color Correction")
                val parameters = fixture.getJSONArray("parameters")
                EffectContracts.correction.forEachIndexed { k, p -> fx.setNumber(p.id, parameters.getDouble(k).toFloat()) }
                val input = fixture.getJSONArray("input")
                val expected = fixture.getJSONArray("output")
                val image = Bitmap.createBitmap(input.length(), 1, Bitmap.Config.ARGB_8888)
                for (x in 0 until input.length()) {
                    val rgb = input.getJSONArray(x)
                    image.setPixel(x, 0, Color.rgb(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2)))
                }
                Effects.correct(image, fx, fixture.getDouble("intensity").toFloat())
                for (x in 0 until input.length()) for ((c, value) in channels(image.getPixel(x, 0)).withIndex())
                    check(abs(value - expected.getJSONArray(x).getInt(c)) <= 1) { "Grade fixture $i color $x channel $c: $value" }
                image.recycle()
            }
        }
        test("primary correction keeps neutral identity pivot bypass gray ramps and monochrome stable") {
            val ramp = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888)
            val color = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            for (x in 0 until 256) ramp.setPixel(x, 0, Color.rgb(x, x, x))
            for (y in 0 until 64) for (x in 0 until 64) color.setPixel(x, y, Color.rgb(x * 255 / 63, y * 255 / 63, 38))
            val identity = color.copy(Bitmap.Config.ARGB_8888, true)
            val neutral = Effect(kind = "Color Correction")
            Effects.correct(identity, neutral, 1f); check(identity.sameAs(color))
            neutral.setNumber("pivot", .2f)
            Effects.correct(identity, neutral, 1f); check(identity.sameAs(color))
            neutral.setNumber("exposure", 1f)
            Effects.correct(identity, neutral, 0f); check(identity.sameAs(color))
            identity.recycle()
            for (control in EffectContracts.correction) for (edge in listOf(control.min, control.max)) {
                val fx = Effect(kind = "Color Correction").apply { setNumber(control.id, edge.toFloat()) }
                val output = color.copy(Bitmap.Config.ARGB_8888, true)
                Effects.correct(output, fx, 1f)
                if (control.id != "pivot") check(!output.sameAs(color)) { "No response to ${control.id}=$edge" }
                output.recycle()
                if (control.id in listOf("exposure", "contrast", "shadows", "highlights", "blacks", "whites", "pivot")) {
                    val gray = ramp.copy(Bitmap.Config.ARGB_8888, true)
                    Effects.correct(gray, fx, 1f)
                    var previous = 0
                    for (x in 0 until 256) {
                        val c = channels(gray.getPixel(x, 0))
                        check(c.max() - c.min() <= 1 && c[0] >= previous) { "Tone ramp reverses or tints: ${control.id}=$edge at $x" }
                        previous = c[0]
                    }
                    gray.recycle()
                }
            }
            for (saturation in listOf(0f, 1f, 2f)) {
                val fx = Effect(kind = "Color Correction").apply {
                    setNumber("saturation", saturation); setNumber("vibrance", 1f); setNumber("hue", 75f)
                }
                val gray = ramp.copy(Bitmap.Config.ARGB_8888, true)
                Effects.correct(gray, fx, 1f)
                for (x in 0 until 256) check(channels(gray.getPixel(x, 0)).all { abs(it - x) <= 1 })
                gray.recycle()
            }
            val bw = color.copy(Bitmap.Config.ARGB_8888, true)
            Effects.correct(bw, Effect(kind = "Color Correction").apply { setNumber("saturation", 0f) }, 1f)
            for (y in 0 until 64) for (x in 0 until 64) channels(bw.getPixel(x, y)).let { check(it.max() - it.min() <= 1) }
            bw.recycle(); color.recycle(); ramp.recycle()
        }
        fun find(root: View, name: String, bar: Boolean = false): View? {
            if ((!bar || root is SeekBar) && (root.contentDescription?.toString() == name || root is TextView && root.text.toString() == name)) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), name, bar)?.let { return it }
            return null
        }
        test("correction inspector exposes Light Color advanced pivot and reset controls in both orientations") {
            onMain { a.editor.applyEffect(Effect(kind = "Color Correction")) }
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                onMain { a.requestedOrientation = orientation }
                Thread.sleep(650)
                onMain { a.panels.videoEffects(a.editor.project.clips.single().effects.single { it.kind == "Color Correction" }) }
                Thread.sleep(200)
                onMain {
                    check(find(a.root, "Exposure", true) != null && find(a.root, "Shadows", true) != null)
                    find(a.root, "› Advanced light")!!.performClick()
                    val pivot = find(a.root, "Contrast pivot", true)!!
                    check(!pivot.isEnabled)
                    find(a.root, "Contrast", true)!!.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                    check(pivot.isEnabled)
                    val exposure = find(a.root, "Exposure", true)!!
                    check(exposure.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                    val parent = exposure.parent as ViewGroup
                    val heading = parent.getChildAt(parent.indexOfChild(exposure) - 1) as ViewGroup
                    heading.getChildAt(1).performClick()
                    find(a.root, "Color")!!.performClick()
                    check(find(a.root, "Vibrance", true) != null && find(a.root, "Hue", true) != null)
                    check(find(a.root, "Reset correction") != null)
                    find(a.root, "Apply")!!.performClick()
                    val correction = a.editor.project.clips.single().effects.single { it.kind == "Color Correction" }
                    check(correction.number("exposure") == 0f && correction.number("contrast") > 1f)
                    a.editor.undo()
                }
            }
        }
    }
}
