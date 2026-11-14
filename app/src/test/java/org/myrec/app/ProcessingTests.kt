package org.myrec.app

import java.io.File
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object ProcessingTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        fun cube(text: String) = CubeLut.parse(text.trimIndent().toByteArray())
        fun near(a: Float, b: Float) = check(abs(a - b) < .00001f) { "$a != $b" }
        test("speech planning bounds continuous speech and word timing avoids silence") {
            val plan = SubtitleTiming.plan(List(1500) { .1f }, 30.0)
            check(plan.isNotEmpty() && plan.all { it.range.endInclusive - it.range.start <= 12.000001 })
            val words = SubtitleTiming.estimate("one two three four five six", listOf(1.0..2.0, 4.0..5.0))
            check(words.size == 6 && words.all { it.start >= 1 && it.end <= 5 && (it.end <= 2 || it.start >= 4) })
            val corrected = SubtitleTiming.corrected(words, "one, two changed four five six!")
            check(corrected.first().id == words.first().id && corrected.last().id == words.last().id)
            check(corrected.first().start == words.first().start && corrected.last().end == words.last().end)
            check(SubtitleTiming.plan(List(500) { 0f }, 10.0).isEmpty())
            val insertion = SubtitleTiming.corrected(words, "one extra two three four five six")
            check(insertion.size == 7 && insertion.zipWithNext().all { it.first.end <= it.second.start + .000001 })
        }
        test("subtitle review correction regroup and cancel preserve source anchors") {
            val editor = Editor(Project(clips = mutableListOf(Clip(path = "test", sourceOut = 10.0))))
            val words = SubtitleTiming.estimate("one two three four", listOf(1.0..3.0))
            val result = editor.project.duplicate()
            SubtitlesDraft.attach(result, "series", words, result.clips.map { it.id }.toSet())
            val draft = SubtitlesDraft(editor, result)
            val row = draft.rows().single()
            draft.correct(row.id, "one, two changed four!")
            check(editor.project.clips.single().effects.isEmpty())
            val removedFirst = SubtitlesDraft(editor, result)
            removedFirst.correct(removedFirst.rows().single().id, "two three four")
            val shortened = removedFirst.project.clips.single().effects.single()
            near(shortened.from.toFloat(), shortened.subtitle!!.words.first().start.toFloat())
            draft.regroup(1)
            check(draft.rows().size == 4 && draft.transcript().contains("changed"))
            draft.commit(editor); check(editor.canUndo)
            editor.undo(); check(editor.project.clips.single().effects.isEmpty())
        }
        test("source pause cuts preserve one frame and share seeking duration and markers") {
            val editor = Editor(Project(clips = mutableListOf(Clip(path = "a", sourceOut = 4.0,
                marks = mutableListOf(3.0)), Clip(path = "b", sourceOut = 2.0, start = 4.0))))
            val clip = editor.project.clips.first()
            val review = PauseReview(before = 0.0, after = 0.0, analyzed = SourceInterval(0.0, 4.0),
                candidates = listOf(PauseCandidate(raw = SourceInterval(1.0, 2.0))))
            editor.setPauseReviews(mapOf(clip.id to review))
            near(clip.duration.toFloat(), 3f); near(clip.sourceTime(1.0).toFloat(), 2f)
            near(editor.project.clips.last().start.toFloat(), 3f)
            near(editor.project.markers().single().toFloat(), 2f)
            val pieces = clip.playbackPieces(); check(pieces.size == 2)
            near(pieces.sumOf { it.duration }.toFloat(), clip.duration.toFloat())
            editor.cursor = 1.5; editor.split()
            check(editor.project.clips.filter { it.path == "a" }.size == 2)
            near(editor.project.duration.toFloat(), 5f)
            editor.undo(); editor.setPauseReviews(mapOf(clip.id to null))
            near(editor.project.duration.toFloat(), 6f)
            val all = review.copy(candidates = listOf(PauseCandidate(raw = SourceInterval(0.0, 4.0))))
            check(all.cuts(clip).sumOf { it.duration } <= 4 - 1.0 / 30 + .000001)
        }
        test("pause review preserves linked detached video and audio as one timeline cut") {
            val video = Clip(path = "a", sourceOut = 4.0, audio = false, group = "linked")
            val audio = Clip(path = "a", sourceOut = 4.0, video = false, group = "linked")
            val editor = Editor(Project(clips = mutableListOf(video, audio)))
            editor.selected.add(audio.id)
            val draft = PausesDraft(editor)
            check(draft.clips.size == 2)
            val candidate = PauseCandidate(raw = SourceInterval(1.0, 2.0))
            draft.reviews.replaceAll { _, s -> s.copy(before = 0.0, after = 0.0, candidates = listOf(candidate)) }
            val next = draft.snapshot()
            check(next.clips.all { abs(it.duration - 3) < .000001 } && editor.project.clips.all { it.duration == 4.0 })
            draft.commit(editor); editor.undo(); check(editor.project.clips.all { it.duration == 4.0 })
        }
        test("pause detector uses RMS with reference defaults and adaptive floor") {
            val clip = Clip(path = "a", sourceOut = 4.0)
            val settings = PauseReview(analyzed = SourceInterval(0.0, 4.0))
            val levels = List(400) { if (it in 100..199) .1f else .001f }
            val found = PauseDetector.detect(levels, .01, clip, settings)
            check(found.candidates.size == 2 && found.thresholdDb in -56.0..-27.0)
            check(found.before == .12 && found.after == .18 && found.minimum == .6)
            val cuts = found.cuts(clip)
            near(cuts.first().start.toFloat(), .12f); near(cuts.first().end.toFloat(), .82f)
        }
        test("freeze insertion adds time resumes footage and survives splitting copying undo") {
            val editor = Editor(Project(clips = mutableListOf(Clip(path = "a", sourceOut = 5.0))))
            editor.cursor = 2.0
            val id = editor.insertFreeze(3.0)!!
            near(editor.project.duration.toFloat(), 8f)
            val hold = editor.project.clips.single { it.id == id }
            check(!hold.audio && hold.frozen!!.valid())
            near(hold.sourceTime(3.0).toFloat(), 2f)
            val following = editor.project.clips.single { it.start == 5.0 }
            near(following.sourceTime(5.0).toFloat(), 2f)
            editor.cursor = 3.0; editor.split()
            check(editor.project.clips.count { it.frozen != null } == 2)
            near(editor.project.duration.toFloat(), 8f)
            editor.range = 2.5..4.5; editor.copySelection(); editor.range = null; editor.cursor = 8.0; editor.paste()
            near(editor.project.duration.toFloat(), 10f)
            check(editor.project.clips.filter { it.frozen != null }.all { it.frozen!!.valid() && it.sourceOut > it.sourceIn })
            editor.undo(); editor.undo(); editor.undo()
            near(editor.project.duration.toFloat(), 5f)
        }
        test("held frame duration audio and push settings can be edited as one undo step") {
            val editor = Editor(Project(clips = mutableListOf(Clip(path = "a", sourceOut = 5.0))))
            editor.cursor = 2.0; val id = editor.insertFreeze(2.0)!!
            editor.resizeFreeze(id, 4.0, .1f, true)
            val hold = editor.project.clips.single { it.id == id }
            check(hold.audio && hold.duration == 4.0 && editor.project.duration == 9.0)
            editor.undo(); check(editor.project.clips.single { it.id == id }.duration == 2.0)
            editor.undo(); check(editor.project.duration == 5.0)
        }
        test("CUBE preserves 1D samples, domains, interpolation and extended outputs") {
            val c = cube("""
                TITLE "Curve"
                DOMAIN_MIN -1 0 1
                DOMAIN_MAX 1 2 3
                LUT_1D_SIZE 3
                -0.5 0 0
                0.25 0.5 1
                2 3 4
            """)
            check(c.title == "Curve" && c.samples.size == 9 && c.oneDimensional)
            val p = c.sample(floatArrayOf(-.5f, 1f, 3f))
            near(p[0], -.125f); near(p[1], .5f); near(p[2], 4f)
        }
        test("CUBE 3D interpolation uses red as the fastest axis") {
            val c = cube("""
                LUT_3D_SIZE 2
                0 0 0
                1 0 0
                0 1 0
                1 1 0
                0 0 1
                1 0 1
                0 1 1
                1 1 1
            """)
            c.sample(floatArrayOf(.2f, .4f, .8f)).zip(listOf(.2f, .4f, .8f)).forEach { near(it.first, it.second) }
        }
        test("CUBE rejects malformed headers, samples, shapers, domains and UTF8") {
            for (s in listOf("LUT_3D_SIZE 1", "LUT_1D_SIZE 2\n0 0 0", "LUT_1D_SIZE 2\nLUT_3D_SIZE 2",
                "LUT_1D_SIZE 2\nNaN 0 0\n1 1 1", "LUT_1D_SIZE 2\n0 0 0\n1 1 1\n2 2 2",
                "DOMAIN_MIN 1 0 0\nLUT_1D_SIZE 2\n0 0 0\n1 1 1",
                "TITLE A\nTITLE B\nLUT_1D_SIZE 2\n0 0 0\n1 1 1")) {
                check(runCatching { cube(s) }.isFailure) { s }
            }
            check(runCatching { CubeLut.parse(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }.isFailure)
        }
        test("all reference authored looks build finite red-fastest cubes") {
            for (name in CubeLut.looks) {
                val c = CubeLut.builtin(name)
                check(c.samples.size == 33 * 33 * 33 * 3 && c.samples.all { it.isFinite() && it in 0f..1f })
            }
            val input = floatArrayOf(.125f, .5f, .875f)
            CubeLut.builtin("Natural").sample(input).zip(input.toList()).forEach { near(it.first, it.second) }
            val warm = CubeLut.builtin("Warm").sample(input)
            check(warm[0] > input[0] && warm[2] < input[2])
        }
        test("source windows preserve independent and opposite stereo channels") {
            val root = kotlin.io.path.createTempDirectory("source-audio").toFile()
            try {
                val bytes = ByteBuffer.allocate(85 * 8).order(ByteOrder.LITTLE_ENDIAN)
                repeat(85) { bytes.putFloat((it + 1) / 100f); bytes.putFloat(-(it + 1) / 100f) }
                val input = File(root, "source.f32").apply { writeBytes(bytes.array()) }
                var calls = 0
                val output = File(root, "output.f32")
                SourceAudio.render(input, output, File(root, "chunks"), SourceAudio.Window(32, 8), { false }, {}) {
                    calls++; FloatArray(it.size) { i -> it[i] * .5f }
                }
                check(calls == 12 && output.length() == input.length())
                val result = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                repeat(85) { near(result.get(it * 2), (it + 1) / 200f); near(result.get(it * 2 + 1), -(it + 1) / 200f) }
                output.delete()
                SourceAudio.render(input, output, File(root, "chunks"), SourceAudio.Window(32, 8), { false }, {}) { error("Checkpoint was lost") }
                output.delete()
                val corrupt = File(root, "chunks/0.f32"); corrupt.writeBytes(ByteArray(corrupt.length().toInt()))
                var repaired = 0
                SourceAudio.render(input, output, File(root, "chunks"), SourceAudio.Window(32, 8), { false }, {}) {
                    repaired++; FloatArray(it.size) { i -> it[i] * .5f }
                }
                check(repaired == 2)
            } finally { root.deleteRecursively() }
        }
        test("overlapping source windows crossfade independent predictions and resume with their retained tails") {
            val root = kotlin.io.path.createTempDirectory("source-overlap").toFile()
            try {
                val input = File(root, "source.f32").apply {
                    val buffer = ByteBuffer.allocate(37 * 8).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(37) { buffer.putFloat(.2f); buffer.putFloat(.2f) }; writeBytes(buffer.array())
                }
                val output = File(root, "output.f32"); val chunks = File(root, "chunks")
                val window = SourceAudio.Window(24, 4, 12)
                var calls = 0
                SourceAudio.render(input, output, chunks, window, { false }, {}) { samples ->
                    calls++; FloatArray(samples.size) { calls.toFloat() }
                }
                check(calls == 4 && output.length() == input.length())
                val result = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until 37) {
                    val block = i / 12; val at = i % 12
                    val expected = if (block > 0 && at < 4) block + (at + 1f) / 5 else block + 1f
                    repeat(2) { check(abs(result.get(i * 2 + it) - expected) < 1e-6) }
                }
                val original = output.readBytes(); output.delete()
                SourceAudio.render(input, output, chunks, window, { false }, {}) { error("Inference repeated") }
                check(output.readBytes().contentEquals(original))
            } finally { root.deleteRecursively() }
        }
        test("tiny source reflection, dual mono reuse and cancellation leave resumable chunks") {
            val root = kotlin.io.path.createTempDirectory("source-audio").toFile()
            try {
                val input = File(root, "source.f32").apply {
                    writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(.5f).putFloat(.5f).array())
                }
                val output = File(root, "output.f32")
                var calls = 0
                SourceAudio.render(input, output, File(root, "chunks"), SourceAudio.Window(32, 8), { false }, {}) {
                    calls++; check(it.all { it == .5f }); it
                }
                check(calls == 1 && output.readBytes().contentEquals(input.readBytes()))
                output.delete()
                check(runCatching {
                    SourceAudio.render(input, output, File(root, "chunks"), SourceAudio.Window(32, 8), { true }, {}) { it }
                }.exceptionOrNull() is InterruptedIOException)
                check(!output.exists() && root.listFiles()!!.none { it.extension == "partial" })
                check(SourceAudio.reflected(-999, 1) == 0L && SourceAudio.reflected(-1, 5) == 1L && SourceAudio.reflected(5, 5) == 3L)
            } finally { root.deleteRecursively() }
        }
    }
}
