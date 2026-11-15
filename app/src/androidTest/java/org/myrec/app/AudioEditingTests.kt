package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlin.math.*

object AudioEditingTests {
    fun run(instrumentation: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit) {
        val root = File(a.cacheDir, "audio-editing-${uid()}").apply { mkdirs() }
        val raw = File(root, "stereo.f32")
        val n = 96000
        val samples = FloatArray(n * 2) { index ->
            val ch = index % 2; val time = index / 2 / 48000.0
            ((if (ch == 0) .3 else .2) * sin(2 * PI * (if (ch == 0) 440 else 733) * time)).toFloat()
        }
        fun write(file: File, values: FloatArray) {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(values); file.writeBytes(buffer.array())
        }
        fun read(file: File): FloatArray {
            val data = file.readBytes(); val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
        fun pitch(data: FloatArray, channel: Int): Double {
            val frames = data.size / 2; val start = min(4800, frames / 8); val end = frames - start
            var crossings = 0
            for (i in start + 1 until end) if (data[(i - 1) * 2 + channel] <= 0 && data[i * 2 + channel] > 0) crossings++
            return crossings * 48000.0 / (end - start)
        }
        write(raw, samples)
        test("pitch-preserved speeds have exact lengths and retain independent channel frequencies") {
            for (speed in listOf(.1, .5, .75, 1.5, 2.0, 4.0, 8.0)) {
                val output = File(root, "speed-$speed.f32")
                val frames = Native.audioStretch(raw.path, output.path, speed) { _, _ -> true }
                check(frames == (n / speed).roundToLong() && output.length() == frames * 8)
                val data = read(output)
                check(data.all { it.isFinite() && abs(it) < .4 })
                check(abs(pitch(data, 0) - 440) < 8) { "Left pitch ${pitch(data, 0)} at $speed" }
                check(abs(pitch(data, 1) - 733) < 12) { "Right pitch ${pitch(data, 1)} at $speed" }
                check(data.sumOf { it.toDouble() * it } / data.size > .01)
            }
        }
        test("stretch cancellation malformed input and same-path calls never damage originals") {
            val before = raw.readBytes()
            val cancelled = File(root, "cancelled.f32")
            check(Native.audioStretch(raw.path, cancelled.path, .5) { _, _ -> false } < 0 && !cancelled.exists())
            check(Native.audioStretch(raw.path, raw.path, 2.0) { _, _ -> true } < 0)
            check(raw.readBytes().contentEquals(before))
            val existing = File(root, "existing.f32").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            check(Native.audioStretch(raw.path, existing.path, 2.0) { _, _ -> true } < 0)
            check(existing.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
            val broken = File(root, "broken.f32").apply { writeBytes(byteArrayOf(1)) }
            check(Native.audioStretch(broken.path, cancelled.path, 2.0) { _, _ -> true } < 0)
            val tiny = File(root, "tiny.f32"); write(tiny, floatArrayOf(.2f, -.2f))
            check(Native.audioStretch(tiny.path, cancelled.path, .5) { _, _ -> true } == 2L)
            check(read(cancelled).all { abs(abs(it) - .2f) < .000001 })
        }
        test("stereo stretching preserves anti-phase channels and is deterministic") {
            val source = File(root, "anti.f32")
            write(source, FloatArray(samples.size) { i -> if (i % 2 == 0) samples[i] else -samples[i - 1] })
            val first = File(root, "anti-first.f32"); val second = File(root, "anti-second.f32")
            for (file in listOf(first, second)) check(Native.audioStretch(source.path, file.path, .7) { _, _ -> true } > 0)
            val data = read(first)
            check(first.readBytes().contentEquals(second.readBytes()))
            check((0 until data.size / 2).all { abs(data[it * 2] + data[it * 2 + 1]) < .000001 })
        }
        val wave = File(a.store.media, "audio-editing.wav")
        val wav = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(n * 4)
        for (sample in samples) wav.putShort((sample * 32767).roundToInt().toShort())
        wave.writeBytes(wav.array())
        fun fixture(rate: Double = 1.0) = Project(name = "Audio editing", width = 160, height = 90,
            clips = mutableListOf(Clip(path = wave.path, sourceOut = 2.0, video = false, rate = rate)))
        test("mixer seeks and split clips reuse pitch-preserved source audio") {
            val project = fixture(.5)
            val cancelRoot = File(root, "cancelled-cache")
            var checks = 0
            val failed = runCatching { AudioCache(cancelRoot).get(wave.path) { ++checks > 5 } }.exceptionOrNull()
            check(failed is java.io.InterruptedIOException && cancelRoot.listFiles()!!.isEmpty())
            check(a.audioCache.get(wave.path).length() == n * 8L) { "Decoded source lost its final frame" }
            AudioMixer(project, a.audioCache).use { mixer ->
                mixer.prepare(); mixer.seek(.25)
                val first = mixer.read(48000)
                check(abs(pitch(first, 0) - 440) < 3)
                mixer.seek(.25); check(first.contentEquals(mixer.read(48000)))
                val editor = Editor(project.duplicate()); editor.cursor = 1.0; editor.split()
                AudioMixer(editor.project, a.audioCache).use { split ->
                    split.prepare(); split.seek(.25)
                    val second = split.read(48000)
                    check(first.indices.all { abs(first[it] - second[it]) < .00001 })
                }
            }
            check(root.listFiles()!!.none { it.name.endsWith(".partial") })
        }
        test("audio edits survive reopen redo duplicate archive and malformed values are refused") {
            val editor = Editor(fixture())
            val work = AudioEditsDraft(editor); work.fade(false, .3); work.fade(true, .4); work.commit(editor)
            editor.change { censorAudio(editor.project, setOf(editor.project.clips[0].id), .5, 1.5, AudioCensorSettings()) }
            a.store.save(editor, false)
            val opened = a.store.editor(editor.project)
            check(opened.project == editor.project)
            opened.undo(); check(opened.project.clips[0].censors.isEmpty()); opened.redo()
            val copy = a.store.duplicate(opened.project)
            check(a.store.editor(copy).project.clips[0].censors == opened.project.clips[0].censors)
            val archive = File(root, "audio.zip"); ProjectArchive.export(a.store, opened.project, archive)
            val restored = a.store.editor(ProjectArchive.restore(a.store, archive).project)
            check(restored.project.clips[0].fadeIn == AudioEdgeFade(.3) && restored.project.clips[0].censors == opened.project.clips[0].censors)
            val encoded = a.store.encode(opened.project)
            fun rejected(change: (JSONObject) -> Unit) {
                val data = JSONObject(encoded.toString()); change(data.getJSONArray("clips").getJSONObject(0))
                check(runCatching { a.store.decode(data) }.isFailure)
            }
            rejected { it.getJSONObject("fadeIn").put("elapsed", 10) }
            rejected { it.getJSONObject("fadeOut").put("seconds", "0.2") }
            rejected { it.getJSONArray("censors").getJSONObject(0).put("to", 3.0) }
            rejected { it.getJSONArray("censors").getJSONObject(0).put("waveform", "Future") }
            rejected { it.getJSONArray("censors").getJSONObject(0).put("frequency", 99) }
        }
        test("preview and exported audio share fades censoring and pitch-preserved timing") {
            val project = fixture(2.0); val c = project.clips[0]
            c.fadeIn = AudioEdgeFade(.1); c.fadeOut = AudioEdgeFade(.1)
            censorAudio(project, setOf(c.id), .2, .8, AudioCensorSettings(envelope = CensorEnvelope.Pulse))
            AudioMixer(project, a.audioCache).use { mixer ->
                mixer.prepare(); mixer.seek(.4)
                val gap = mixer.read(960)
                check(gap.all { abs(it) < .000001 })
            }
            val movie = File(root, "audio-export.mp4")
            Export(a.audioCache).run(project, movie, 160, 90) {}
            val exported = read(a.audioCache.get(movie.path))
            check(exported.size / 2 / 48000.0 in .98..1.05) { "Decoded export duration: ${exported.size / 2 / 48000.0}" }
            val voice = exported.copyOfRange((.11 * 48000).toInt() * 2, (.19 * 48000).toInt() * 2)
            check(abs(pitch(voice, 0) - 440) < 25 && abs(pitch(voice, 1) - 733) < 25) { "Export changed source pitch" }
            val gapPower = (19500 until 20000).sumOf { exported[it * 2].toDouble().pow(2) } / 500
            check(gapPower < .00001) { "Censored speech leaked in exported pulse gap: $gapPower" }
        }
        onMain { a.editor.load(fixture()) }
        DirectAudioTests.run(instrumentation, a, onMain, test)
        fun find(root: View, name: String, bar: Boolean = false): View? {
            if ((!bar || root is SeekBar) && (root.contentDescription?.toString() == name || root is TextView && root.text.toString() == name)) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), name, bar)?.let { return it }
            return null
        }
        test("volume fades and censor tools apply cancel reopen and undo in both orientations") {
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                onMain { a.panels.close(); a.editor.load(fixture()); a.requestedOrientation = orientation }
                instrumentation.waitForIdleSync()
                Thread.sleep(550)
                onMain { a.panels.volume() }
                Thread.sleep(150)
                onMain {
                    find(a.root, "Fade in duration", true)!!.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                    find(a.root, "Fade out duration", true)!!.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                    find(a.root, "Apply")!!.performClick()
                    check(a.editor.project.clips[0].fadeIn != null && a.editor.project.clips[0].fadeOut != null)
                    a.editor.undo(); check(a.editor.project.clips[0].fadeIn == null)
                    a.panels.censor()
                }
                Thread.sleep(150)
                onMain {
                    check(find(a.root, "Frequency", true) != null && find(a.root, "Pulsed") != null)
                    find(a.root, "Apply")!!.performClick()
                    val id = a.editor.project.clips[0].censors.single().id
                    a.panels.censor(id)
                }
                Thread.sleep(150)
                onMain {
                    find(a.root, "Remove censoring")!!.performClick()
                    check(a.editor.project.clips[0].censors.isEmpty())
                    a.editor.undo(); check(a.editor.project.clips[0].censors.isNotEmpty())
                    a.panels.volume(); a.panels.close()
                }
            }
        }
        test("range volume retains linked picture and sound through move and undo") {
            val file = File(a.store.media, "audio-range.mp4")
            instrumentation.context.assets.open("fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
            val project = Project(clips = mutableListOf(ProjectStore.inspect(file).apply { sourceOut = 2.0 }))
            onMain {
                a.editor.load(project.duplicate()); a.editor.range = .5..1.5; a.panels.volume()
            }
            Thread.sleep(180)
            onMain {
                find(a.root, "Decrease gain by 0.5 dB")!!.performClick()
                find(a.root, "Fade in duration", true)!!.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                find(a.root, "Apply")!!.performClick()
                val clips = a.editor.project.clips.sortedBy { it.start }
                check(clips.size == 3 && clips.all { it.video && it.audio && it.linked })
                check(clips[0].gainDb == 0f && clips[1].gainDb == -.5f && clips[2].gainDb == 0f)
                check(clips[1].fadeIn != null)
                a.editor.select(clips[1]); a.editor.moveSelection(2.0, 0)
                check(a.editor.project.clips.single { it.id == clips[1].id }.let { it.video && it.audio && it.start == 2.0 })
                a.editor.undo(); a.editor.undo()
                check(a.editor.project == project)
            }
        }
    }
}
