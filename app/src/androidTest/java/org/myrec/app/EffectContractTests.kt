package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import org.json.JSONObject
import kotlin.math.*

object EffectContractTests {
    fun run(instrumentation: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit) {
        ProcessingDeviceTests.run(instrumentation, a, onMain, test)
        fun find(view: View, name: String): View? {
            if (view.contentDescription?.toString() == name || view is TextView && view.text.toString() == name) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), name)?.let { return it }
            return null
        }
        val directory = File(a.getExternalFilesDir(null), "effect-contracts").apply { mkdirs() }
        fun snapshot(name: String) = onMain {
            val image = Bitmap.createBitmap(a.root.width, a.root.height, Bitmap.Config.ARGB_8888)
            a.root.draw(Canvas(image))
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        fun linear(v: Double) = if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
        fun encoded(v: Double) = ((if (v <= .0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - .055) * 255).roundToInt().coerceIn(0, 255)
        fun channel(color: Int, channel: Int) = (color ushr (16 - channel * 8)) and 255
        test("independent RGB offsets match linear sampling and intensity within one code value") {
            for ((width, height) in listOf(81 to 53, 53 to 81, 1 to 7)) {
                val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                for (y in 0 until height) for (x in 0 until width)
                    input.setPixel(x, y, Color.rgb((x * 13 + y * 19) % 256, (x * 37 + y * 7) % 256, (x * 3 + y * 23) % 256))
                val shifts = listOf(.17 to -.31, -.43 to .71, .82 to -.23)
                fun sample(x: Double, y: Double, c: Int): Double {
                    val xx = x.coerceIn(0.0, width - 1.0); val yy = y.coerceIn(0.0, height - 1.0)
                    val ix = floor(xx).toInt(); val iy = floor(yy).toInt()
                    val dx = xx - ix; val dy = yy - iy
                    fun v(x: Int, y: Int) = linear(channel(input.getPixel(x.coerceAtMost(width - 1), y.coerceAtMost(height - 1)), c) / 255.0)
                    return (1 - dy) * ((1 - dx) * v(ix, iy) + dx * v(ix + 1, iy)) +
                        dy * ((1 - dx) * v(ix, iy + 1) + dx * v(ix + 1, iy + 1))
                }
                val fx = Effect(kind = "RGB Offset")
                for ((i, name) in listOf("red", "green", "blue").withIndex()) {
                    fx.setNumber(name + "X", shifts[i].first.toFloat()); fx.setNumber(name + "Y", shifts[i].second.toFloat())
                }
                for (strength in listOf(0f, .25f, 1f)) {
                    val output = input.copy(Bitmap.Config.ARGB_8888, true)
                    Effects.correct(output, fx, strength)
                    for (y in 0 until height) for (x in 0 until width) for (c in 0..2) {
                        val original = linear(channel(input.getPixel(x, y), c) / 255.0)
                        val reach = .12 * min(width, height)
                        val shifted = sample(x - shifts[c].first * reach, y - shifts[c].second * reach, c)
                        val expected = encoded(original + strength * (shifted - original))
                        check(abs(channel(output.getPixel(x, y), c) - expected) <= 1) { "RGB $width/$height $x,$y channel $c strength $strength" }
                    }
                    output.recycle()
                }
                val identity = input.copy(Bitmap.Config.ARGB_8888, true)
                Effects.correct(identity, Effect(kind = "RGB Offset"), 1f)
                check(identity.sameAs(input))
                identity.recycle(); input.recycle()
            }
        }
        test("subpixel registration interpolates linear light instead of dark display values") {
            val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
            for (y in 0 until 20) for (x in 0 until 20) image.setPixel(x, y, if (x >= 11) Color.WHITE else Color.BLACK)
            val fx = Effect(kind = "RGB Offset")
            fx.setNumber("redX", (.5 / (20 * .12)).toFloat())
            Effects.correct(image, fx, 1f)
            check(abs(Color.red(image.getPixel(11, 10)) - 188) <= 1)
            check(Color.green(image.getPixel(11, 10)) == 255 && Color.blue(image.getPixel(11, 10)) == 255)
            image.recycle()
        }
        val source = File(a.store.media, "effect-source.mp4")
        instrumentation.context.assets.open("fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val p = Project(name = "Effect contracts", clips = mutableListOf(ProjectStore.inspect(source)))
        val fx = Effect(kind = "RGB Offset")
        fx.editNumber("redX", .8f, 2.0, 0.0, true)
        p.clips.single().effects.add(fx)
        test("render families use one fixed order and old projects load into the same model") {
            val color = Effect(kind = "Color Correction").apply { setNumber("exposure", 2f) }
            val channels = Effect(kind = "RGB Offset").apply { setNumber("redX", .173f) }
            fun project(reversed: Boolean) = p.duplicate().also {
                val clip = it.clips.single()
                clip.effects.clear()
                clip.effects.addAll(if (reversed) listOf(channels, color) else listOf(color, channels))
            }
            Frames().use { renderer ->
                val first = renderer.render(project(false), .5, 160, 90)
                val second = renderer.render(project(true), .5, 160, 90)
                check(first.sameAs(second))
                first.recycle(); second.recycle()
            }
            val legacy = a.store.encode(project(true))
            legacy.getJSONArray("clips").getJSONObject(0).put("renderVersion", 0)
            check(a.store.decode(legacy) == project(true))
            check(!a.store.encode(a.store.decode(legacy)).getJSONArray("clips").getJSONObject(0).has("renderVersion"))
        }
        test("versioned parameters and source keyframes survive save redo duplicate and archives") {
            val e = Editor(p.duplicate())
            e.setGain(-3f); e.undo(); a.store.save(e, false)
            val opened = a.store.editor(e.project)
            check(opened.canRedo && opened.project.clips.single().effects.single().parameters.size == 7)
            check(abs(opened.project.clips.single().effects.single().numberAt("redX", 1.0) - .4f) < .00001f)
            val copy = a.store.duplicate(e.project)
            check(a.store.editor(copy).project.clips.single().effects.single().parameters == fx.parameters)
            val archive = File(a.cacheDir, "effects-${uid()}.zip")
            ProjectArchive.export(a.store, e.project, archive)
            val restored = ProjectArchive.restore(a.store, archive)
            val transferred = a.store.editor(restored.project)
            check(transferred.canRedo)
            check(transferred.project.clips.single().effects.single().parameters == fx.parameters)
            archive.delete()
        }
        test("malformed parameter types ranges keys and future algorithms cannot replace a valid edit") {
            val encoded = a.store.encode(p)
            fun rejected(change: (JSONObject) -> Unit) {
                val data = JSONObject(encoded.toString())
                change(data.getJSONArray("clips").getJSONObject(0).getJSONArray("effects").getJSONObject(0))
                check(runCatching { a.store.decode(data) }.isFailure)
            }
            rejected { it.getJSONArray("parameters").getJSONObject(1).getJSONObject("value").put("value", 4.0) }
            rejected { it.getJSONArray("parameters").getJSONObject(1).getJSONObject("value").put("value", "0.5") }
            rejected { it.getJSONArray("parameters").getJSONObject(1).getJSONObject("value").put("type", "toggle").put("value", true) }
            rejected { it.getJSONArray("parameters").getJSONObject(1).put("id", "unknown") }
            rejected { it.put("algorithm", "1") }
            rejected { it.put("algorithm", 1.5) }
            rejected { it.getJSONArray("parameters").getJSONObject(1).getJSONArray("keyframes").getJSONObject(0).put("time", -1.0) }
            val future = JSONObject(encoded.toString())
            future.getJSONArray("clips").getJSONObject(0).getJSONArray("effects").getJSONObject(0).put("algorithm", 999)
            check(runCatching { a.store.decode(future) }.exceptionOrNull() is NewerProject)
            val old = JSONObject(encoded.toString())
            old.put("version", 2)
            old.getJSONArray("clips").getJSONObject(0).getJSONArray("effects").getJSONObject(0).apply {
                remove("algorithm"); remove("parameters"); getJSONObject("values").put("distance", .025).put("angle", 90)
            }
            val imported = a.store.decode(old).clips.single().effects.single()
            check(imported.values.isEmpty() && abs(imported.number("redY") - .025f / .12f) < .00001)
            check(!a.store.encode(a.store.decode(old)).getJSONArray("clips").getJSONObject(0).getJSONArray("effects").getJSONObject(0).has("algorithm"))
        }
        onMain {
            val clean = p.duplicate()
            clean.clips.single().effects.clear()
            a.editor.load(clean)
            a.editor.applyEffect(Effect(kind = "RGB Offset"))
        }
        test("RGB pads apply cancel center and expose accessible axes in both orientations") {
            for ((name, orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                onMain { a.requestedOrientation = orientation }
                Thread.sleep(600)
                onMain { a.panels.videoEffects(a.editor.project.clips.single().effects.single()) }
                Thread.sleep(250)
                onMain {
                    for (channel in listOf("Red", "Green", "Blue")) {
                        val pad = find(a.root, "$channel offset") ?: error("Missing $channel pad")
                        check(pad.width > 0 && pad.height > 0)
                    }
                    val red = find(a.root, "Red offset")!!
                    check(red.performAccessibilityAction(0x02000002, null))
                    check(a.editor.project.clips.single().effects.single().number("redX") == 0f)
                }
                snapshot("rgb-$name")
                onMain {
                    check(find(a.root, "Apply")!!.performClick())
                    check(abs(a.editor.project.clips.single().effects.single().number("redX") - .03f) < .00001)
                    a.panels.videoEffects(a.editor.project.clips.single().effects.single())
                }
                Thread.sleep(150)
                onMain {
                    check(find(a.root, "Red offset")!!.performAccessibilityAction(0x02000005, null))
                    a.panels.close()
                    check(abs(a.editor.project.clips.single().effects.single().number("redX") - .03f) < .00001)
                    a.editor.undo()
                    check(a.editor.project.clips.single().effects.single().number("redX") == 0f)
                }
            }
        }
        test("Animate authors source keys while subsequent edits keep their animation") {
            onMain { a.editor.cursor = 1.0; a.panels.videoEffects(a.editor.project.clips.single().effects.single()) }
            Thread.sleep(150)
            onMain {
                find(a.root, "Animate")!!.performClick()
                find(a.root, "Red offset")!!.performAccessibilityAction(0x02000002, null)
                find(a.root, "Apply")!!.performClick()
                val effect = a.editor.project.clips.single().effects.single()
                check(effect.parameter("redX")!!.keyframes.map { it.time } == listOf(0.0, 1.0))
                a.editor.cursor = 2.0
                a.panels.videoEffects(effect)
                find(a.root, "Red offset")!!.performAccessibilityAction(0x02000002, null)
                find(a.root, "Apply")!!.performClick()
                check(a.editor.project.clips.single().effects.single().parameter("redX")!!.keyframes.map { it.time } == listOf(0.0, 1.0, 2.0))
            }
        }
        test("live RGB controls keep independent channels and disable interaction with the effect") {
            var live = emptyList<Effect>()
            onMain {
                a.panels.captureEffects(listOf(Effect(kind = "RGB Offset"))) { live = it }
                (find(a.root, "RGB Offset")!!.parent as View).performClick()
            }
            Thread.sleep(150)
            onMain {
                check(find(a.root, "Animate")!!.visibility == View.GONE)
                check(find(a.root, "Green offset")!!.performAccessibilityAction(0x02000002, null))
                find(a.root, "Effect enabled")!!.performClick()
                check(!find(a.root, "Green offset")!!.performAccessibilityAction(0x02000002, null))
                a.panels.close()
                check(!live.single().enabled && abs(live.single().number("greenX") - .03f) < .00001)
                check(live.single().number("redX") == 0f && live.single().number("blueX") == 0f)
            }
        }
        test("touch dragging clamps each channel to its ring and double tapping centers it") {
            lateinit var pad: View
            onMain {
                a.panels.videoEffects(a.editor.project.clips.single().effects.single())
            }
            Thread.sleep(150)
            fun event(action: Int, time: Long, x: Float, y: Float) {
                val e = android.view.MotionEvent.obtain(time, time, action, x, y, 0)
                try { check(pad.dispatchTouchEvent(e)) } finally { e.recycle() }
            }
            onMain {
                pad = find(a.root, "Red offset")!!
                val now = android.os.SystemClock.uptimeMillis()
                event(android.view.MotionEvent.ACTION_DOWN, now, pad.width / 2f, pad.height / 2f)
                event(android.view.MotionEvent.ACTION_MOVE, now + 10, pad.width * 3f, pad.height * 3f)
                event(android.view.MotionEvent.ACTION_UP, now + 20, pad.width * 3f, pad.height * 3f)
                find(a.root, "Apply")!!.performClick()
                val fx = a.editor.project.clips.single().effects.single()
                val x = fx.numberAt("redX", a.editor.cursor)
                val y = fx.numberAt("redY", a.editor.cursor)
                check(abs(hypot(x, y) - 1f) < .0001)
                a.panels.videoEffects(fx)
            }
            Thread.sleep(150)
            repeat(2) {
                onMain {
                    pad = find(a.root, "Red offset")!!
                    val now = android.os.SystemClock.uptimeMillis()
                    event(android.view.MotionEvent.ACTION_DOWN, now, pad.width / 2f, pad.height / 2f)
                    event(android.view.MotionEvent.ACTION_UP, now + 10, pad.width / 2f, pad.height / 2f)
                }
                Thread.sleep(100)
            }
            onMain {
                find(a.root, "Apply")!!.performClick()
                val fx = a.editor.project.clips.single().effects.single()
                check(fx.numberAt("redX", a.editor.cursor) == 0f && fx.numberAt("redY", a.editor.cursor) == 0f)
            }
        }
        GradingTests.run(instrumentation, a, onMain, test)
        MotionTests.run(instrumentation, a, onMain, test)
        FilmColorTests.run(instrumentation, a, onMain, test)
        KaleidoscopeTests.run(instrumentation, a, onMain, test)
    }
}
