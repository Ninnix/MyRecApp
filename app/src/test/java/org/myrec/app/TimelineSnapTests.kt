package org.myrec.app

import kotlin.math.abs

object TimelineSnapTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        fun editor() = Editor(Project(clips = mutableListOf(
            Clip(path = "a.mp4", sourceOut = 2.0), Clip(path = "b.mp4", sourceOut = 2.0, start = 2.0))))
        fun near(a: Double, b: Double) = check(abs(a - b) < .000001) { "$a != $b" }
        test("playhead snaps to source edges and bookmarks but not effect or transition boundaries") {
            val e = editor()
            e.project.clips.first().effects.add(Effect(kind = "Noir", from = .7, to = 1.2))
            e.project.clips.first().marks.add(1.5)
            e.project.transitions.add(Transition(at = 2.0, duration = .6))
            check(TimelineSnap.targets(e.project) == listOf(0.0, 1.5, 2.0, 4.0))
            near(e.snapTime(.72), .72)
            near(e.snapTime(1.22), 1.22)
            near(e.snapTime(1.72), 1.72)
            near(e.snapTime(1.52), 1.5)
            near(e.snapTime(2.05), 2.0)
        }
        test("playhead snap uses the reference eight-point radius at every zoom and respects Off") {
            val e = editor()
            near(e.snapTime(1.875, 64.0), 2.0)
            near(e.snapTime(1.919, 100.0), 1.919)
            near(e.snapTime(1.9, 40.0), 2.0)
            near(e.snapTime(1.9, 200.0), 1.9)
            e.snap = false
            near(e.snapTime(1.99, 100.0), 1.99)
            near(e.snapTime(-1.0), 0.0)
            near(e.snapTime(5.0), 4.0)
        }
        test("Move and playhead share source-owned bookmark targets and exclude held source marks") {
            val e = editor()
            e.project.clips.first().marks.add(1.0)
            val second = e.project.clips[1]
            second.marks.add(.5)
            check(TimelineSnap.targets(e.project, setOf(second.id)) == listOf(0.0, 1.0, 2.0))
            second.frozen = FrozenFrame(sourceTime = .5, duration = 2.0)
            check(2.5 !in TimelineSnap.targets(e.project))
        }
        test("Mark prefers a nearby picture cut and quantizes to the reference 30 fps grid with Off") {
            val e = editor()
            e.cursor = 1.98; e.markRange(100.0)
            near(e.mark!!, 2.0)
            e.clearSelection(); e.snap = false
            e.cursor = 1.98; e.markRange(100.0)
            near(e.mark!!, 59.0 / 30)
            e.clearSelection(); e.cursor = 1.0 / 60; e.markRange()
            near(e.mark!!, 1.0 / 30)
            near(TimelineMove.time(1.0 / 480000), 1.0 / 240000)
            near(TimelineMove.time(-1.0 / 480000), -1.0 / 240000)
        }
        test("Mark uses quiet original source samples before frame rounding even with snapping off") {
            val e = editor()
            e.snap = false
            e.cursor = .509
            e.markRange(100.0) { _, source -> if (abs(source - .529) < .000001) .005f else .5f }
            near(e.mark!!, 16.0 / 30)
            e.clearSelection(); e.cursor = .509
            e.markRange(100.0) { _, _ -> .02f }
            near(e.mark!!, .5)
        }
        test("Mark quiet-point sampling follows trimmed and retimed source clocks") {
            val clip = Clip(path = "trim.mp4", sourceIn = 3.0, sourceOut = 7.0, rate = 2.0)
            val project = Project(clips = mutableListOf(clip))
            val samples = mutableListOf<Double>()
            val at = TimelineSnap.mark(project, .509, 100.0, false) { _, source ->
                samples.add(source)
                if (abs(source - 4.058) < .000001) 0f else .5f
            }
            check(samples.size == 5)
            near(samples.first(), 3.978); near(samples.last(), 4.058)
            near(at, 16.0 / 30)
        }
        test("Mark aligns any position inside a transition to the join with snapping on or off") {
            val e = editor()
            e.putTransition(Transition(at = 2.0, duration = .5))
            val cut = e.project.transitions.single().cut
            for (enabled in listOf(false, true)) {
                e.clearSelection(); e.snap = enabled; e.cursor = cut - .1
                e.markRange(200.0)
                near(e.mark!!, cut)
                near(e.cursor, cut)
            }
        }
        test("Mark rejects subframe ranges, supports reverse selection and moves the existing end") {
            val e = editor()
            e.snap = false; e.cursor = 1.0; e.markRange()
            e.cursor = 1.005
            check(e.markRange() == "Choose an end at least one frame away")
            near(e.mark!!, 1.0); check(e.range == null)
            e.cursor = .5; check(e.markRange() == null)
            check(e.range == .5..1.0)
            e.cursor = 1.5; check(e.markRange() == null)
            check(e.range == .5..1.5 && e.mark == null)
            e.cursor = .5; check(e.markRange() == "Place the end after the start")
            check(e.range == .5..1.5)
            check(!e.canUndo)
        }
        test("changing Snap clears a Move latch, notifies persistence once and survives project switches") {
            val e = editor()
            var saved: Boolean? = null
            var calls = 0
            e.onSnapChange = { saved = it; calls++ }
            e.select(e.project.clips.first()); e.beginMove()
            e.move!!.snap = MoveSnap(2.0, 0.0)
            e.snap = false
            check(saved == false && calls == 1 && e.move!!.snap == null)
            e.snap = false; check(calls == 1)
            e.finishMove(true); e.load(Project())
            check(!e.snap && calls == 1)
            e.snap = true; check(saved == true && calls == 2)
        }
    }
}
