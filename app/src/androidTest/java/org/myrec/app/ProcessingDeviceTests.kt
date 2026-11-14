package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object ProcessingDeviceTests {
    fun run(instrumentation: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit) {
        val root = File(a.cacheDir, "processing-${uid()}").apply { mkdirs() }
        fun linear(v: Double) = if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
        fun encoded(v: Double) = ((if (v <= .0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - .055) * 255).roundToInt().coerceIn(0, 255)
        test("native 3D and 1D LUTs match red-fastest interpolation in linear strength blending") {
            val one = CubeLut.parse("LUT_1D_SIZE 3\n0 0 0\n0.1 0.7 0.3\n1 1 1".toByteArray())
            for (cube in listOf(one, CubeLut.builtin("Cinema"), CubeLut.builtin("Warm"))) for (strength in listOf(0f, .3f, 1f)) {
                val bitmap = Bitmap.createBitmap(73, 31, Bitmap.Config.ARGB_8888)
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) bitmap.setPixel(x, y,
                    Color.rgb((x * 13 + y * 29) % 256, (x * 31 + y * 3) % 256, (x * 7 + y * 17) % 256))
                val before = IntArray(73 * 31); bitmap.getPixels(before, 0, 73, 0, 0, 73, 31)
                Native.colorLut(bitmap, cube.samples, cube.size, cube.oneDimensional, cube.minimum + cube.maximum, 0, strength, 0f, 0f)
                for (y in 0 until 31) for (x in 0 until 73) {
                    val old = before[y * 73 + x]; val result = bitmap.getPixel(x, y)
                    val rgb = floatArrayOf(Color.red(old) / 255f, Color.green(old) / 255f, Color.blue(old) / 255f)
                    val mapped = cube.sample(rgb)
                    for (c in 0..2) {
                        val target = encoded(linear(rgb[c].toDouble()) * (1 - strength) + linear(mapped[c].toDouble()) * strength)
                        val actual = (result ushr (16 - c * 8)) and 255
                        check(abs(actual - target) <= 1) { "LUT channel $c: $actual != $target" }
                    }
                }
                bitmap.recycle()
            }
        }
        test("zero creative strength preserves technical exposure and highlight adjustment") {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(160, 160, 160))
            val cube = CubeLut.builtin("Warm")
            Native.colorLut(bitmap, cube.samples, cube.size, false, cube.minimum + cube.maximum, 1, 0f, 1f, -.6f)
            val y = linear(160 / 255.0) * 2; val k = 3.6
            val target = encoded(.35 + ln(1 + k * max(0.0, y - .35)) / k)
            check(abs(Color.red(bitmap.getPixel(0, 0)) - target) <= 1)
            bitmap.recycle()
        }
        test("Apple Log viewing remains monotonic with technical interpretation at zero look strength") {
            val cube = CubeLut.builtin("Natural")
            for (profile in 1..2) {
                val bitmap = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888)
                for (i in 0..255) bitmap.setPixel(i, 0, Color.rgb(i, i, i))
                Native.colorLut(bitmap, cube.samples, cube.size, false, cube.minimum + cube.maximum, 0, 0f, 0f, 0f, profile)
                var prior = 0
                for (i in 0..255) {
                    val code = i / 255.0
                    val scene = if (code >= .20855531595) 2.0.pow((code - .69336945) / .08550479) - .00964052
                        else sqrt(code / 47.28711236) - .05641088
                    val y = max(0.0, scene); val excess = max(0.0, y - .6)
                    val viewed = if (y <= .6) y else .6 + .4 * excess / (excess + .4)
                    val value = bitmap.getPixel(i, 0)
                    check(abs(Color.red(value) - encoded(viewed)) <= 1 && Color.red(value) >= prior)
                    check(abs(Color.red(value) - Color.blue(value)) <= 1)
                    prior = Color.red(value)
                }
                bitmap.recycle()
            }
        }
        test("Dolly separation preserves foreground and creates a finite deterministic background move") {
            val original = Bitmap.createBitmap(97, 61, Bitmap.Config.ARGB_8888)
            for (y in 0 until 61) for (x in 0 until 97) original.setPixel(x, y, Color.rgb(x * 255 / 96, y * 255 / 60, (x + y) % 256))
            val mask = FloatArray(192 * 192) { if (it % 192 in 70..120) 1f else 0f }
            val first = original.copy(Bitmap.Config.ARGB_8888, true)
            val second = original.copy(Bitmap.Config.ARGB_8888, true)
            for (output in listOf(first, second)) Native.dolly(output, mask, 1.4f, .5f, .5f, 1f)
            check(first.sameAs(second) && !first.sameAs(original))
            for (y in 0 until 61) for (x in 40..55) check(first.getPixel(x, y) == original.getPixel(x, y))
            val full = original.copy(Bitmap.Config.ARGB_8888, true)
            Native.dolly(full, FloatArray(192 * 192) { 1f }, 1.5f, .5f, .5f, 1f)
            check(full.sameAs(original))
            val zero = original.copy(Bitmap.Config.ARGB_8888, true)
            Native.dolly(zero, mask, 1.5f, .5f, .5f, 0f)
            check(zero.sameAs(original))
            listOf(original, first, second, full, zero).forEach { it.recycle() }
        }
        val wave = File(root, "speech.wav")
        val count = 48000 * 3
        val wav = ByteBuffer.allocate(44 + count * 4).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + count * 4).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16).put("data".toByteArray()).putInt(count * 4)
        for (i in 0 until count) for (channel in 0..1) wav.putShort((if (i in 48000 until 96000) 0 else
            (.2 * sin(2 * PI * (if (channel == 0) 440 else 733) * i / 48000) * 32767).roundToInt()).toShort())
        wave.writeBytes(wav.array())
        val original = Project(name = "Processing", width = 160, height = 90,
            clips = mutableListOf(Clip(path = wave.path, sourceOut = 3.0, video = false)))
        test("RNNoise keeps stereo channels independent flushes partial tails and reuses source stages") {
            val input = File(root, "noise-input.f32")
            val buffer = ByteBuffer.allocate(1001 * 8).order(ByteOrder.LITTLE_ENDIAN)
            val random = java.util.Random(42)
            repeat(1001) { buffer.putFloat((random.nextFloat() - .5f) * .1f); buffer.putFloat(0f) }
            input.writeBytes(buffer.array())
            val output = File(root, "noise-output.f32")
            SourceAudio.denoise(input, output, { false }, {})
            check(output.length() == input.length())
            val pcm = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            check((0 until pcm.limit()).all { pcm[it].isFinite() })
            check((0 until 1001).all { abs(pcm[it * 2 + 1]) < 1e-8f })
            val cancelled = File(root, "noise-cancel.f32")
            check(runCatching { SourceAudio.denoise(input, cancelled, { true }, {}) }.exceptionOrNull() is java.io.InterruptedIOException)
            check(!cancelled.exists() && root.listFiles()!!.none { it.extension == "partial" })
            val clip = original.clips.single().duplicate().apply { effects.add(Effect(kind = "AI voice isolation")) }
            val first = Studio.prepare(clip, a.audioCache, { false }, {})!!
            val second = clip.duplicate(true).apply { sourceIn = .5; sourceOut = 2.5; gainDb = -6f }
            check(Studio.prepare(second, a.audioCache, { false }, {}) == first)
            check(first.length() == count * 8L)
        }
        test("pause review analyzes source RMS preserves decisions and mixer joins skipped source ranges") {
            val editor = Editor(original.duplicate())
            val work = PausesDraft(editor)
            work.analyze(a.audioCache) {}
            check(work.reviews.values.single().candidates.size == 1)
            val preview = work.snapshot()
            check(editor.project == original && preview.duration in 2.29..2.31)
            fun read(project: Project, at: Double, count: Int) = AudioMixer(project, a.audioCache).use {
                it.prepare(); it.seek(at); it.read(count)
            }
            val result = read(preview, 1.0, 48000)
            check(result.any { abs(it) > .1f } && result.all { it.isFinite() })
            work.commit(editor); a.store.save(editor, false)
            val opened = a.store.editor(editor.project)
            check(opened.project == editor.project)
            opened.undo(); check(opened.project.clips.single().pauses == null)
            opened.redo(); check(opened.project.clips.single().pauses != null)
            opened.setPauseReviews(mapOf(opened.project.clips.single().id to null))
            check(abs(opened.project.duration - 3) < .000001)
        }
        test("LUT subtitle pause and held frame metadata survive duplicate and archive undo") {
            val (lut, _) = LutAssets.ingest("LUT_1D_SIZE 2\n0 0 0\n1 1 1".toByteArray(), a.store.media)
            val editor = Editor(original.duplicate())
            editor.project.clips.single().video = true
            editor.project.clips.single().effects.add(Effect(kind = "Color LUTs", values = mutableMapOf("path" to lut.path)))
            SubtitlesDraft.attach(editor.project, "series", SubtitleTiming.estimate("hello world", listOf(.1..1.0)), setOf(editor.project.clips.single().id))
            editor.cursor = 1.0; editor.insertFreeze(1.0)
            a.store.save(editor, false)
            check(a.store.editor(editor.project).project == editor.project)
            val copy = a.store.duplicate(editor.project)
            val owned = copy.clips.flatMap { it.effects }.flatMap { it.assetPaths() }.distinct()
            check(owned.size == 1 && owned.single() != lut.path && File(owned.single()).readBytes().contentEquals(lut.readBytes()))
            val archive = File(root, "project.zip")
            ProjectArchive.export(a.store, editor.project, archive)
            val restored = ProjectArchive.restore(a.store, archive).project
            check(restored.clips.count { it.frozen != null } == 1 && restored.clips.any { c -> c.effects.any { it.subtitle != null } })
        }
        fun find(v: View, name: String): View? {
            if (v.contentDescription?.toString() == name || v is TextView && v.text.toString() == name) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), name)?.let { return it }
            return null
        }
        test("LUT pause subtitle and structural hold workspaces open in both orientations") {
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                onMain { a.requestedOrientation = orientation }
                instrumentation.waitForIdleSync(); Thread.sleep(350)
                onMain {
                    a.panels.close(); a.editor.load(original.duplicate()); a.editor.project.clips.single().video = true
                    a.panels.effect("Color LUTs")
                    check(find(a.root, "Import .cube LUT") != null && find(a.root, "Highlights") != null)
                    a.panels.close(); a.panels.pauses()
                    check(find(a.root, "Find pauses") != null && find(a.root, "Keep all pauses") != null)
                    a.panels.close(); a.editor.cursor = .5; a.panels.freezeFrame()
                    check(find(a.root, "Hold duration") != null && find(a.root, "Push-in") != null)
                    a.panels.close(); check(abs(a.editor.project.duration - 3) < .000001)
                    a.editor.insertFreeze(1.0)
                    val hold = a.editor.project.clips.single { it.frozen != null }
                    a.panels.editExisting(hold.effects.single { it.kind == "Freeze Frame" }.id)
                    check(find(a.root, "Remove hold") != null && find(a.root, "Edit held frame") != null)
                    a.panels.close()
                    val picture = a.editor.project.clips.first { it.video && it.frozen == null }
                    SubtitlesDraft.attach(a.editor.project, "ui-series",
                        SubtitleTiming.estimate("review these words", listOf(.1.. .4)), setOf(picture.id))
                    val subtitle = picture.effects.first { it.subtitle != null }
                    a.panels.editExisting(subtitle.id)
                    check(find(a.root, "Transcript") != null && find(a.root, "Copy transcript") != null)
                    a.panels.close()
                }
            }
        }
        onMain { a.panels.close(); a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER }
        root.deleteRecursively()
    }
}
