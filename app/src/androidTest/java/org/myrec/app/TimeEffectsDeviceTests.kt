package org.myrec.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Half
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object TimeEffectsDeviceTests {
    private fun pixel(bitmap: Bitmap): FloatArray {
        val bytes = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(bytes)
        return FloatArray(3) { Half.toFloat(bytes.getShort(bitmap.height / 2 * bitmap.rowBytes + bitmap.width / 2 * 8 + it * 2)) }
    }
    fun run(instrumentation: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit) {
        val root = File(a.cacheDir, "time-${uid()}").apply { mkdirs() }
        val file = File(root, "vfr.mp4")
        instrumentation.context.assets.open("time-vfr.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val times = doubleArrayOf(0.0, .04, .11, .16, .30, .38, .50, .70)
        fun duration(movie: File, expected: Double) {
            // Android 10's retriever reports AAC media before the MP4 edit list.
            // Check the presented movie and the decoded timeline instead.
            check(abs((Mp4Audio.presentedDuration(movie) ?: error("No movie duration")) - expected) < .002)
            check(abs(a.audioCache.get(movie.path).length()/8.0/48000 - expected) < .002)
            VideoDecoder(movie.path).use { decoder ->
                (decoder.frame(expected-1.0/30,64,64) ?: error("No final video frame")).recycle()
            }
        }
        try {
            test("animated Step Printing keeps stereo pitch across speed keys and exports exact timeline duration") {
                val count = 48000 * 4
                val wav = ByteBuffer.allocate(44 + count * 4).order(ByteOrder.LITTLE_ENDIAN)
                wav.put("RIFF".toByteArray()).putInt(36 + count * 4).put("WAVEfmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16).put("data".toByteArray()).putInt(count * 4)
                repeat(count) { i -> repeat(2) { c -> wav.putShort((.2 * sin(2 * PI * (if (c == 0) 440 else 733) * i / 48000) * 32767).roundToInt().toShort()) } }
                val source = File(root, "speed.wav").apply { writeBytes(wav.array()) }
                val clip = ProjectStore.inspect(source)
                val effect = Effect(kind = "Step Printing", to = 6.0)
                effect.parameter("speed")!!.apply {
                    setKeyframe(0.0, EffectValue.Number(1.0)); setKeyframe(2.0, EffectValue.Number(.5))
                }
                clip.effects.add(effect)
                val project = Project(width = 160, height = 96, clips = mutableListOf(clip))
                check(project.duration == 6.0)
                fun pitch(pcm: FloatArray, channel: Int): Double {
                    var crossings = 0
                    for (i in 1 until pcm.size / 2) if (pcm[(i - 1) * 2 + channel] <= 0 && pcm[i * 2 + channel] > 0) crossings++
                    return crossings * 48000.0 / (pcm.size / 2 - 1)
                }
                AudioMixer(project, a.audioCache).use { mixer ->
                    mixer.prepare()
                    for (time in listOf(1.0, 3.0, 5.0)) {
                        mixer.seek(time); val pcm = mixer.read(9600)
                        check(abs(pitch(pcm, 0) - 440) < 12 && abs(pitch(pcm, 1) - 733) < 12)
                    }
                    mixer.seek(1.99); check(mixer.read(1920).all { it.isFinite() })
                }
                val saved = a.store.decode(a.store.encode(project))
                check(saved.clips.single().playbackSpans == clip.playbackSpans)
                val movie = File(root, "animated.mp4")
                Export(a.audioCache).run(saved, movie, 160, 96) {}
                duration(movie,6.0)
            }
            test("pause analysis padding and exported duration follow animated speed after trim and reopen") {
                val count = 48000 * 4
                val wav = ByteBuffer.allocate(44 + count * 4).order(ByteOrder.LITTLE_ENDIAN)
                wav.put("RIFF".toByteArray()).putInt(36 + count * 4).put("WAVEfmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16).put("data".toByteArray()).putInt(count * 4)
                repeat(count) { i -> repeat(2) { c -> wav.putShort((if (i in 115200 until 134400) 0 else
                    (.2 * sin(2 * PI * (if (c == 0) 440 else 733) * i / 48000) * 32767).roundToInt()).toShort()) } }
                val source = File(root, "pauses.wav").apply { writeBytes(wav.array()) }
                val clip = ProjectStore.inspect(source)
                val effect = Effect(kind = "Step Printing", to = 6.0)
                effect.parameter("speed")!!.apply {
                    setKeyframe(0.0, EffectValue.Number(1.0)); setKeyframe(2.0, EffectValue.Number(.5))
                }
                clip.effects.add(effect)
                val editor = Editor(Project(width = 160, height = 96, clips = mutableListOf(clip)))
                val work = PausesDraft(editor)
                work.reviews[clip.id] = PauseReview(automatic = false, minimum = .6, before = .1, after = .1,
                    analyzed = SourceInterval(0.0, 4.0))
                work.analyze(a.audioCache) {}
                check(work.reviews.getValue(clip.id).candidates.size == 1)
                work.commit(editor)
                check(abs(editor.project.duration - 5.4) < 1e-6)
                check(abs(editor.project.clips.single().sourceTime(3.0) - 2.8) < 1e-6)
                a.store.save(editor, false)
                val saved = a.store.editor(editor.project)
                check(abs(saved.project.duration - 5.4) < 1e-6)
                val movie = File(root, "paused-speed.mp4")
                Export(a.audioCache).run(saved.project, movie, 160, 96) {}
                duration(movie,5.4)
                saved.undo(); check(saved.project.duration == 6.0)
                saved.redo(); check(abs(saved.project.duration - 5.4) < 1e-6)
            }
            test("decoded B frame index retains all variable frame rate presentation timestamps") {
                val index = VideoFrameIndex.get(file.path) {}
                check(index.times.size == times.size) { index.times.contentToString() }
                check(times.indices.all { abs(times[it] - index.times[it]) < .000002 })
            }
            test("reverse and repeated source seeks return exact requested presentation frames") {
                VideoDecoder(file.path, linear = true).use { decoder ->
                    val expected = times.map { decoder.frame(it, 96, 64)!!.let { b -> pixel(b).also { b.recycle() } } }
                    check(expected.zipWithNext().all { pair -> pair.first.indices.any { abs(pair.first[it] - pair.second[it]) > .1 } })
                    for (i in listOf(2, 0, 1, 7, 5, 2, 2, 4, 3, 0)) {
                        val bitmap = decoder.frame(times[i], 96, 64)!!
                        check(pixel(bitmap).indices.all { abs(pixel(bitmap)[it] - expected[i][it]) < .002 }) { "Wrong frame at ${times[i]}" }
                        bitmap.recycle()
                    }
                }
            }
            test("Step Printing holds actual source frame groups and averages smear in linear light") {
                val clip = ProjectStore.inspect(file).apply { sourceOut = .74; audio = false }
                val effect = Effect(kind = "Step Printing", to = 1.0, phase = EffectPhase(0.0))
                effect.setNumber("step", 3f); effect.setNumber("smear", 1f); clip.effects.add(effect)
                val expected = VideoDecoder(file.path, linear = true).use { decoder ->
                    (0..2).map { decoder.frame(times[it], 96, 64)!!.let { b -> pixel(b).also { b.recycle() } } }
                }
                Frames().use { frames ->
                    val project = Project(width = 96, height = 64, clips = mutableListOf(clip))
                    val first = frames.render(project, .01, 96, 64, keepLinear = true)
                    val held = frames.render(project, .115, 96, 64, keepLinear = true)
                    check(first.sameAs(held))
                    val result = pixel(first)
                    for (c in 0..2) check(abs(result[c] - expected.sumOf { it[c].toDouble() } / 3) < .003)
                    first.recycle(); held.recycle()
                    effect.setNumber("smear", 0f)
                    val plain = frames.render(project, .05, 96, 64, keepLinear = true)
                    check(pixel(plain).indices.all { abs(pixel(plain)[it] - expected.first()[it]) < .002 }); plain.recycle()
                }
            }
            test("source phase frequency keys and half speed survive project persistence and editor undo") {
                val editor = Editor(Project(clips = mutableListOf(ProjectStore.inspect(file))))
                editor.applyEffect(Effect(kind = "Negative Strobe"))
                val original = editor.project.clips.single().effects.single()
                original.parameter("frequency")!!.setKeyframe(.11, EffectValue.Number(6.0))
                val step = Effect(kind = "Step Printing"); step.setNumber("speed", .5f)
                editor.applyEffect(step)
                val project = a.store.decode(a.store.encode(editor.project))
                val copy = project.clips.single().effects.first { it.kind == original.kind }
                check(copy.phase == original.phase && copy.parameter("frequency")!!.keyframes == original.parameter("frequency")!!.keyframes)
                check(project.clips.single().effectiveRate == .5)
                editor.undo(); check(editor.project.clips.single().effectiveRate == 1.0)
            }
            test("negative conversion uses recording display code inversion with linear intensity") {
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(64, 128, 192)) }
                Native.workingNegative(bitmap, 1f)
                val rgb = bitmap.getPixel(4, 4)
                check(abs(Color.red(rgb) - 191) <= 1 && abs(Color.green(rgb) - 127) <= 1 && abs(Color.blue(rgb) - 63) <= 1)
                bitmap.recycle()
            }
        } finally { root.deleteRecursively() }
    }
}
