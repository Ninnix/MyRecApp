package org.myrec.app

import kotlin.math.*

object TimeEffectsTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        fun animated(): Clip {
            val effect = Effect(kind = "Step Printing")
            effect.parameter("speed")!!.apply {
                setKeyframe(0.0, EffectValue.Number(1.0)); setKeyframe(2.0, EffectValue.Number(.5))
            }
            return Clip(path = "a", sourceOut = 4.0, effects = mutableListOf(effect))
        }
        test("animated step speed has one inverse source clock and constant-rate audio pieces") {
            val clip = animated()
            check(clip.duration == 6.0)
            check(clip.sourceTime(1.0) == 1.0 && clip.sourceTime(4.0) == 3.0)
            for (t in listOf(0.0, .3, 1.9, 2.0, 2.1, 4.0, 5.9, 6.0))
                check(abs(clip.timelineOffset(clip.sourceTime(t)) - t) < 1e-6)
            val parts = clip.playbackPieces()
            check(parts.size == 2 && parts[0].duration == 2.0 && parts[1].duration == 4.0 && parts[1].start == 2.0)
            check(parts[0].effectiveRate == 1.0 && parts[1].effectiveRate == .5)
            check(parts.sumOf { it.duration } == clip.duration)
        }
        test("animated speed keeps its source grid across splits pauses copy and key edits") {
            val clip = animated(); val editor = Editor(Project(clips = mutableListOf(clip)))
            editor.cursor = 3.0; editor.split()
            check(abs(editor.project.duration - 6) < 1e-6)
            check(editor.project.clips[0].sourceOut == 2.5 && editor.project.clips[1].sourceIn == 2.5)
            check(editor.project.clips[1].sourceTime(4.0) == 3.0)
            editor.undo()
            val source = editor.project.clips.single()
            source.pauses = PauseReview(before = 0.0, after = 0.0, analyzed = SourceInterval(0.0, 4.0),
                candidates = listOf(PauseCandidate(raw = SourceInterval(1.0, 2.5))))
            check(source.duration == 4.0 && source.sourceTime(1.0) == 2.5)
            val copy = source.duplicate(); check(copy.duration == 4.0)
            copy.effects.single().parameter("speed")!!.setKeyframe(3.0, EffectValue.Number(1.0))
            check(copy.duration == 3.0 && source.duration == 4.0)
        }
        test("draft speed keys ripple following clips once and cancel or commit as one edit") {
            val first = Clip(path = "a", sourceOut = 4.0, effects = mutableListOf(Effect(kind = "Step Printing", to = 4.0)))
            val editor = Editor(Project(clips = mutableListOf(first, Clip(path = "b", sourceOut = 2.0, start = 4.0))))
            val draft = VideoEffectsDraft(editor, setOf("Step Printing"))
            draft.editNumber(draft.chain.single(), "speed", .5f, 2.0, true)
            check(draft.project.clips.first().duration == 6.0 && draft.project.clips.last().start == 6.0)
            check(editor.project.duration == 6.0)
            draft.commit(editor); check(editor.project.duration == 8.0)
            editor.undo(); check(editor.project.duration == 6.0)
            editor.redo(); check(editor.project.duration == 8.0)
        }
        test("pause minimum padding and the retained frame follow animated speed instead of the initial rate") {
            val clip = animated()
            val settings = PauseReview(automatic = false, minimum = .6, before = .1, after = .1,
                analyzed = SourceInterval(0.0, 4.0))
            val levels = List(400) { if (it in 240 until 280) 0f else .1f }
            val review = PauseDetector.detect(levels, .01, clip, settings)
            check(review.candidates.size == 1)
            val cut = review.cuts(clip).single()
            check(abs(cut.start - 2.45) < 1e-6 && abs(cut.end - 2.75) < 1e-6)
            clip.pauses = review
            check(abs(clip.duration - 5.4) < 1e-6)
            check(PauseDetector.detect(levels, .01, clip, settings).candidates.size == 1)
            clip.pauses = review.copy(before = 0.0, after = 0.0,
                candidates = listOf(PauseCandidate(raw = SourceInterval(0.0, 4.0))))
            check(abs(clip.duration - 1.0 / 30) < 1e-6)
            clip.sourceIn = 2.3
            clip.pauses = review.copy(before = .2, after = .1,
                candidates = listOf(PauseCandidate(raw = SourceInterval(2.0, 3.5))))
            check(abs(clip.duration - 1.1) < 1e-6) { "Trimming reapplied pause padding" }
        }
        test("strobe integrates held frequency keys and limits cycles to output Nyquist") {
            val effect = Effect(kind = "Negative Strobe", phase = EffectPhase(0.0))
            effect.setNumber("frequency", 4f)
            check(!TimeEffects.negative(effect, 0.0, 30.0))
            check(TimeEffects.negative(effect, .13, 30.0))
            val frequency = effect.parameter("frequency")!!
            frequency.setKeyframe(0.0, EffectValue.Number(4.0)); frequency.setKeyframe(.17, EffectValue.Number(6.0))
            check(TimeEffects.negative(effect, .2, 30.0))
            frequency.keyframes.clear(); frequency.value = EffectValue.Number(15.0)
            check(!TimeEffects.negative(effect, 0.0, 10.0) && TimeEffects.negative(effect, .1, 10.0) && !TimeEffects.negative(effect, .2, 10.0))
            effect.parameter("timedBurst")!!.value = EffectValue.Toggle(true)
            effect.setNumber("burstLength", .1f)
            check(!TimeEffects.negative(effect, .1, 10.0))
        }
        test("source phase and pause-aware clocks survive split copy and speed changes") {
            val editor = Editor(Project(clips = mutableListOf(Clip(path = "a", sourceOut = 5.0, rate = 2.0))))
            editor.applyEffect(Effect(kind = "Negative Strobe"))
            val effect = editor.project.clips.single().effects.single()
            check(effect.phase!!.valid() && abs(effect.phase!!.elapsed(2.0) - 1) < .000001)
            val before = TimeEffects.negative(effect, .25, 30.0)
            editor.cursor = 1.0; editor.split()
            check(editor.project.clips.all { it.effects.single().phase == effect.phase })
            check(TimeEffects.negative(editor.project.clips.last().effects.single(), .25, 30.0) == before)
            editor.undo(); editor.setRate(.5)
            check(editor.project.clips.single().effects.single().phase == effect.phase)
        }
        test("step printing retimes linked picture sound effects and following clips with undo") {
            val editor = Editor(Project(clips = mutableListOf(Clip(path = "a", sourceOut = 4.0), Clip(path = "b", sourceOut = 2.0, start = 4.0))))
            val effect = Effect(kind = "Step Printing"); effect.setNumber("speed", .5f)
            editor.applyEffect(effect)
            check(editor.project.clips.first().duration == 8.0 && editor.project.clips.last().start == 8.0 && editor.project.duration == 10.0)
            check(editor.project.clips.first().effects.single().to == 8.0)
            editor.undo(); check(editor.project.duration == 6.0)
        }
        test("frame blocks use actual variable-rate timestamps and keep a stable original grid across trims") {
            val index = FrameIndex(doubleArrayOf(0.0, .03, .07, .11, .2, .21, .4, .51))
            check(index.block(.205, 0.0, 3, 0.0, .6).contentEquals(doubleArrayOf(.11, .2, .21)))
            check(index.block(.205, 0.0, 3, .18, .6).contentEquals(doubleArrayOf(.2, .2, .21)))
            check(index.block(.59, 0.0, 3, 0.0, .6).contentEquals(doubleArrayOf(.4, .51, .51)))
            check(runCatching { FrameIndex(doubleArrayOf(.1, .1)) }.isFailure)
            check(runCatching { FrameIndex(doubleArrayOf(Double.NaN)) }.isFailure)
        }
    }
}
