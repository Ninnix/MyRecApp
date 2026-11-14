package org.myrec.app

import kotlin.math.abs

object VideoEffectsTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        val kinds = setOf("RGB Offset", "Color Correction", "Undercranking")
        fun near(a: Double, b: Double) = check(abs(a - b) < .00001) { "$a != $b" }
        fun fixture(): Editor {
            val first = Clip(path = "a.mp4", sourceIn = 2.0, sourceOut = 6.0)
            val second = Clip(path = "b.mp4", sourceIn = 10.0, sourceOut = 14.0, start = 4.0)
            return Editor(Project(clips = mutableListOf(first, second))).apply { selected.addAll(project.clips.map { it.id }) }
        }
        test("effects draft keeps distinct looks and duplicate effect kinds on different clips") {
            val e = fixture()
            val a = Effect(kind = "RGB Offset").apply { setNumber("redX", .2f) }
            val b = Effect(kind = "RGB Offset").apply { setNumber("redX", -.3f) }
            e.project.clips[0].effects.add(a); e.project.clips[1].effects.add(b)
            val original = e.project.duplicate()
            val work = VideoEffectsDraft(e, kinds)
            check(work.chain.size == 2 && work.visible(1.0) == setOf(a.id) && work.visible(5.0) == setOf(b.id))
            work.editNumber(work.chain[0], "redX", .7f, 1.0, false)
            work.commit(e)
            near(e.project.clips[0].effects.single().number("redX").toDouble(), .7)
            near(e.project.clips[1].effects.single().number("redX").toDouble(), -.3)
            e.undo(); check(e.project == original && !e.canUndo)
            e.redo(); check(e.project.clips[0].effects.single().id == a.id)
        }
        test("shared effects retain cut identity but animated edits belong to the current clip") {
            val e = fixture()
            val work = VideoEffectsDraft(e, kinds)
            val fx = Effect(kind = "RGB Offset")
            work.chain.add(fx); work.flush()
            check(work.clips.all { it.effects.single().id == fx.id })
            work.editNumber(fx, "greenX", .6f, 2.0, true)
            check(work.clips[0].effects.single().parameter("greenX")!!.keyframes.size == 2)
            check(work.clips[1].effects.single().parameter("greenX")!!.keyframes.isEmpty())
            work.editNumber(fx, "redY", .4f, 5.0, false)
            check(work.clips.all { abs(it.effects.single().number("redY") - .4f) < .00001f })
            work.commit(e)
            e.clearSelection(); e.cursor = 1.0; e.split()
            val split = e.project.clips.filter { it.path == "a.mp4" }
            check(split.size == 2 && split.map { it.effects.single().id }.distinct() == listOf(fx.id))
            split[1].effects.single().parameter("greenX")!!.keyframes.clear()
            check(split[0].effects.single().parameter("greenX")!!.keyframes.size == 2)
        }
        test("range effects split only video and commit in one undo step") {
            val e = fixture()
            val audio = Clip(path = "voice.wav", sourceOut = 8.0, video = false, track = 1)
            e.project.clips.add(audio)
            e.range = 1.0..6.0
            val original = e.project.duplicate()
            val work = VideoEffectsDraft(e, kinds)
            work.chain.add(Effect(kind = "Color Correction"))
            val draft = work.snapshot()
            check(e.project == original)
            check(draft.clips.single { !it.video } == audio)
            check(draft.clips.filter { it.effects.isNotEmpty() }.map { it.start to it.end } == listOf(4.0 to 6.0, 1.0 to 4.0))
            work.commit(e); e.undo(); check(e.project == original && !e.canUndo)
        }
        test("stale effects draft refuses to replace a newer project or recording") {
            val e = fixture()
            val work = VideoEffectsDraft(e, kinds)
            work.chain.add(Effect(kind = "Color Correction"))
            e.appendRecording(Clip(path = "new.mp4", sourceOut = 2.0))
            val latest = e.project.duplicate()
            check(runCatching { work.commit(e) }.isFailure)
            check(e.project == latest && !e.canUndo)
        }
        test("correction endpoints span shared clips and remain source anchored after speed and cuts") {
            val e = fixture()
            val work = VideoEffectsDraft(e, kinds)
            val fx = Effect(kind = "Color Correction")
            work.chain.add(fx)
            near(work.selectEndpoint(fx, false, 1.0), 0.0)
            work.editNumber(fx, "exposure", -1f, 0.0, false)
            near(work.selectEndpoint(fx, true, 0.0), 8.0 - 1.0 / 30)
            work.editNumber(fx, "exposure", 2f, 8.0 - 1.0 / 30, false)
            check(work.clips.all { it.effects.single().motion!!.valid(it.effects.single()) })
            work.endEndpoint()
            val first = work.clips[0].effects.single()
            val second = work.clips[1].effects.single()
            near(first.numberAt("exposure", 6.0).toDouble(), second.numberAt("exposure", 10.0).toDouble())
            val sampled = second.numberAt("exposure", 12.0)
            work.commit(e)
            e.clearSelection(); e.select(e.project.clips[1]); e.setRate(.5)
            near(e.project.clips[1].effects.single().numberAt("exposure", 12.0).toDouble(), sampled.toDouble())
            e.cursor = 6.0; e.split()
            check(e.project.clips.all { it.effects.single().motion != null })
            for (c in e.project.clips.filter { it.path == "b.mp4" })
                near(c.effects.single().numberAt("exposure", 12.0).toDouble(), sampled.toDouble())
        }
        test("correction timing implements all source curves including one frame and zero ramps") {
            val frame = 1.0 / 30
            for (curve in EffectCurve.entries) {
                val t = EffectTiming(curve, 1.0, 1.0, EffectCurve.Smooth)
                near(t.fraction(0.0, 4.0, frame), 0.0)
                near(t.fraction(4.0 - frame, 4.0, frame), if (curve == EffectCurve.RiseHoldReturn) 0.0 else 1.0)
                check(t.endpoint(true, 4.0, frame) in 0.0..(4.0 - frame))
                check(t.fraction(0.0, frame, frame).isFinite())
            }
            near(EffectTiming(EffectCurve.EaseIn).fraction(.5, 1.0 + frame, frame), .125)
            near(EffectTiming(EffectCurve.EaseOut).fraction(.5, 1.0 + frame, frame), .875)
            near(EffectTiming(EffectCurve.Smooth).fraction(.25, 1.0 + frame, frame), .15625)
            near(EffectTiming(EffectCurve.RiseHoldReturn, 0.0, 0.0).fraction(0.0, 4.0, frame), 1.0)
            near(EffectTiming(EffectCurve.RiseAndHold, .101).endpoint(true, 4.0, frame), 4 * frame)
        }
        test("correction motion interpolates hue on the shortest arc and rejects invalid clocks or poses") {
            val fx = Effect(kind = "Color Correction")
            val pose = fx.parameters.associate { it.id to (it.value as EffectValue.Number).value }
            val m = EffectMotion(pose + ("hue" to 170.0), pose + ("hue" to -170.0), duration = 2.0 + 1.0 / 30)
            near(m.pose(1.0).getValue("hue"), -180.0)
            check(m.valid(fx))
            check(!m.copy(duration = Double.NaN).valid(fx))
            check(!m.copy(a = pose - "hue").valid(fx))
            check(!m.copy(b = pose + ("exposure" to 9.0)).valid(fx))
            check(!m.copy(clock = listOf(EffectTimePoint(1.0, 0.0), EffectTimePoint(1.0, 1.0))).valid(fx))
            check(!m.copy(timing = EffectTiming(ramp = EffectCurve.RiseAndHold)).valid(fx))
        }
        test("removing correction animation bakes the chosen endpoint and reset affects only that endpoint") {
            val e = fixture(); val work = VideoEffectsDraft(e, kinds)
            val fx = Effect(kind = "Color Correction"); work.chain.add(fx)
            work.selectEndpoint(fx, true, 1.0); work.editNumber(fx, "exposure", 2f, 1.0, false)
            work.selectEndpoint(fx, false, 1.0); work.editNumber(fx, "exposure", -1f, 1.0, false)
            work.reset(fx, 1.0)
            check(work.clips.all { it.effects.single().motion!!.a.getValue("exposure") == 0.0 &&
                it.effects.single().motion!!.b.getValue("exposure") == 2.0 })
            work.selectEndpoint(fx, true, 1.0); work.endEndpoint(); work.removeMotion(fx, 1.0)
            check(work.clips.all { it.effects.single().motion == null && it.effects.single().number("exposure") == 2f })
        }
    }
}
