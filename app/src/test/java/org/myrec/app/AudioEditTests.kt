package org.myrec.app

import kotlin.math.*

object AudioEditTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        fun near(a: Double, b: Double) = check(abs(a - b) < .00001) { "$a != $b" }
        fun fixture() = Editor(Project(clips = mutableListOf(Clip(path = "a", sourceOut = 4.0),
            Clip(path = "b", start = 4.0, sourceOut = 4.0))))
        test("audio fade cuts retain slope and speed fitting keeps visible fades bounded") {
            val e = fixture()
            e.project.clips[0].fadeIn = AudioEdgeFade(3.0)
            e.project.clips[0].fadeOut = AudioEdgeFade(2.0)
            e.cursor = 1.0; e.split()
            val left = e.project.clips.first { it.start == 0.0 }
            val right = e.project.clips.first { it.start == 1.0 }
            near(left.fadeIn!!.gain(1.0), right.fadeIn!!.gain(0.0))
            near(right.fadeIn!!.elapsed, 1.0)
            check(left.fadeOut == null && right.fadeOut == AudioEdgeFade(2.0))
            e.select(right); e.setRate(4.0)
            check(right.fadeIn!!.remaining(right.duration) <= right.duration)
            near(right.fadeIn!!.gain(right.duration), 1.0)
        }
        test("volume draft affects outer audio edges and leaves committed history until apply") {
            val e = fixture(); e.selected.addAll(e.project.clips.map { it.id })
            val before = e.project.duplicate(); val work = AudioEditsDraft(e)
            work.fade(false, 1.0); work.fade(true, 2.0); work.gain(-3f)
            check(e.project == before)
            check(work.clips[0].fadeIn != null && work.clips[0].fadeOut == null)
            check(work.clips[1].fadeIn == null && work.clips[1].fadeOut != null)
            work.commit(e); e.undo(); check(e.project == before && !e.canUndo)
            e.redo(); check(e.project.clips.all { it.gainDb == -3f })
        }
        test("range volume isolates audio while retaining linked picture timing and sources") {
            val e = fixture(); e.range = 1.0..3.0
            val before = e.project.duplicate(); val work = AudioEditsDraft(e)
            work.fade(false, .5); work.gain(-9f); work.commit(e)
            check(e.project.clips.filter { it.video }.sortedBy { it.start }.map { it.start to it.end } ==
                listOf(0.0 to 1.0, 1.0 to 3.0, 3.0 to 4.0, 4.0 to 8.0))
            val pieces = e.project.clips.filter { it.path == "a" }.sortedBy { it.start }
            check(pieces.all { it.video && it.audio && it.linked })
            check(pieces.size == 3 && pieces[1].gainDb == -9f && pieces[0].gainDb == 0f && pieces[2].gainDb == 0f)
            check(pieces[1].sourceIn == 1.0 && pieces[1].sourceOut == 3.0)
            e.undo(); check(e.project == before)
        }
        test("censor waveform and envelopes use the reference equations and stable phase") {
            val sine = AudioCensorSettings()
            near(sine.oscillator(.00025, 48000.0), 1.0)
            val soft = sine.copy(envelope = CensorEnvelope.Soft)
            near(soft.envelope(.03, 1.0), .5)
            val pulse = sine.copy(envelope = CensorEnvelope.Pulse)
            near(pulse.envelope(.22, 1.0), 0.0)
            for (wave in CensorWaveform.entries) {
                val value = sine.copy(waveform = wave)
                near(value.oscillator(.100125, 48000.0), value.oscillator(.001125, 48000.0))
                for (i in 0..1000) check(value.oscillator(i / 48000.0, 48000.0).isFinite())
            }
            check(!sine.copy(frequency = Double.NaN).valid() && !sine.copy(levelDb = -100.0).valid())
        }
        test("censor fades and pulse gaps never expose original speech and overlapping tracks emit once") {
            val a = Clip(path = "a", sourceOut = 1.0)
            val b = Clip(path = "b", sourceOut = 1.0, track = 1)
            val p = Project(clips = mutableListOf(a, b))
            censorAudio(p, setOf(a.id, b.id), .1, .9, AudioCensorSettings(envelope = CensorEnvelope.Pulse))
            val aa = FloatArray(48000 * 2) { .3f }; val bb = aa.copyOf()
            audioFinish(p, a, aa, 0.0); audioFinish(p, b, bb, 0.0)
            for (i in 4800 until 43200) {
                near(bb[i * 2].toDouble(), 0.0)
                val local = i / 48000.0
                near(aa[i * 2].toDouble(), a.censors.single().tone(local, 48000.0))
            }
            near(aa[32000].toDouble(), 0.0)
            near(aa[0].toDouble(), .3)
        }
        test("censor splits copies seeks and block partitioning retain the same waveform") {
            val e = fixture(); val c = e.project.clips[0]
            censorAudio(e.project, setOf(c.id), .1, 3.8, AudioCensorSettings(waveform = CensorWaveform.Triangle))
            val original = c.duplicate()
            e.cursor = 1.0; e.split()
            val right = e.project.clips.single { it.path == "a" && it.start == 1.0 }
            val whole = FloatArray(96000 * 2) { .1f }; audioFinish(Project(clips = mutableListOf(original)), original, whole, 1.0)
            val sliced = FloatArray(whole.size) { .1f }; audioFinish(e.project, right, sliced, 0.0)
            check(whole.indices.all { abs(whole[it] - sliced[it]) < .000001 })
            val parts = FloatArray(whole.size)
            for (offset in 0 until 96000 step 1000) {
                val n = min(1000, 96000 - offset); val block = FloatArray(n * 2) { .1f }
                audioFinish(e.project, right, block, offset / 48000.0)
                block.copyInto(parts, offset * 2)
            }
            check(parts.indices.all { abs(parts[it] - sliced[it]) < .000001 })
        }
        test("censor range replacement preserves outside regions and copying a range retains clocks") {
            val e = fixture(); val c = e.project.clips[0]
            censorAudio(e.project, setOf(c.id), .5, 3.5, AudioCensorSettings())
            val id = c.censors.single().id
            censorAudio(e.project, setOf(c.id), 1.5, 2.5, null)
            check(c.censors.map { it.from to it.to } == listOf(.5 to 1.5, 2.5 to 3.5))
            check(c.censors.all { it.id == id && it.valid(c.duration) })
            e.range = 1.0..3.0; e.copySelection()
            val copy = e.clipboard.single()
            check(copy.censors.map { it.from to it.to } == listOf(0.0 to .5, 1.5 to 2.0))
            check(copy.censors.all { it.valid(copy.duration) })
        }
        test("audio retiming rebases censor intervals and retains edits across undo redo") {
            val e = fixture(); val c = e.project.clips[0]
            censorAudio(e.project, setOf(c.id), 1.0, 3.0, AudioCensorSettings())
            e.setRate(2.0)
            check(c.censors.single().from == .5 && c.censors.single().to == 1.5 && c.censors.single().phaseOrigin == .5)
            e.undo(); check(e.project.clips[0].censors.single().from == 1.0)
            e.redo(); check(e.project.clips[0].censors.single().to == 1.5)
        }
    }
}
