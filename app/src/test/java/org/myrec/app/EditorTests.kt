package org.myrec.app

import kotlin.math.abs
import kotlin.random.Random

private fun near(a: Double, b: Double) = check(abs(a - b) < 0.0001) { "$a != $b" }

private fun fixture() =
    Editor(Project(clips = mutableListOf(Clip(path = "fixture.mp4", sourceOut = 10.0))))

fun main() {
    var passed = 0
    fun test(name: String, block: () -> Unit) {
        block()
        passed++
        println("PASS $name")
    }
    TitleModelTests.run(::test)
    TimeEffectsTests.run(::test)
    ProcessingTests.run(::test)
    EffectModelTests.run(::test)
    VideoEffectsTests.run(::test)
    AudioEditTests.run(::test)
    PreviewModelTests.run(::test)
    TimelineMoveTests.run(::test)
    TimelineSnapTests.run(::test)
    TimelineRulesTests.run(::test)
    test("new recordings remain through undo redo and restored editing sessions") {
        val e = Editor()
        val first = Clip(path = "first.mp4", sourceOut = 5.0)
        e.appendRecording(first)
        check(!e.canUndo)
        e.setGain(-6f)
        val second = Clip(path = "second.mp4", sourceOut = 3.0)
        e.appendRecording(second)
        e.undo()
        check(e.project.clips.map { it.path } == listOf("first.mp4", "second.mp4"))
        check(e.project.clips.first().gainDb == 0f)
        near(e.project.duration, 8.0)
        val saved = e.session()
        val restored = Editor(saved.project).apply { load(saved.project, saved) }
        restored.appendRecording(Clip(path = "third.mp4", sourceOut = 2.0))
        check(restored.canRedo)
        restored.redo()
        check(restored.project.clips.size == 3)
        check(restored.project.clips.first().gainDb == -6f)
        near(restored.project.duration, 10.0)
        restored.select(restored.project.clips.last())
        restored.deleteSelection()
        check(restored.project.clips.size == 2)
        check(restored.session().originals.size == 3)
        restored.undo()
        check(restored.project.clips.size == 3)
    }
    test("persistent session state retains more than sixty independent edits") {
        val e = fixture()
        repeat(100) { e.setGain(if (it % 2 == 0) -3f else -9f) }
        val saved = e.session()
        val restored = Editor(saved.project).apply { load(saved.project, saved) }
        repeat(100) { check(restored.canUndo); restored.undo() }
        check(!restored.canUndo && restored.project.clips.single().gainDb == 0f)
        repeat(100) { check(restored.canRedo); restored.redo() }
        check(restored.project.clips.single().gainDb == -9f)
        check(!restored.canRedo)
    }
    test("draft sessions persist committed state and failed edits leave no undo step") {
        val e = fixture()
        e.beginBatch()
        e.setGain(-12f)
        check(e.session().project.clips.single().gainDb == 0f)
        check(e.session().undo.isEmpty())
        e.endBatch(false)
        check(e.session().project.clips.single().gainDb == -12f)
        check(e.session().undo.size == 1)
        val before = e.project.duplicate()
        try { e.change { e.project.clips.clear(); error("Injected edit failure") } }
        catch (_: IllegalStateException) {}
        check(e.project == before && e.session().undo.size == 1)
        e.setGain(-12f)
        check(e.session().undo.size == 1)
    }
    test("recording rotation follows each sensor and the locked display orientation") {
        for (display in listOf(0, 90, 180, 270)) {
            val rear = CameraOrientation.recording(90, display, false)
            val front = CameraOrientation.recording(270, display, true)
            check(CameraOrientation.correction(rear, rear) == 0)
            check(CameraOrientation.correction(rear, front) == if (display % 180 == 0) 180 else 0)
            check(CameraOrientation.correction(front, rear) == if (display % 180 == 0) 180 else 0)
        }
        check(CameraOrientation.recording(0, 90, false) == 270)
        check(CameraOrientation.recording(180, 90, true) == 270)
        check(CameraOrientation.correction(270, 0) == 90)
        check(CameraOrientation.correction(0, 270) == 270)
    }
    test("microphone locations have distinct labels and durable priority keys") {
        val bottom = MicrophoneInput(24, 15, "Example phone", "bottom")
        val rear = MicrophoneInput(25, 15, "Example phone", "back")
        check(bottom.usable && rear.usable)
        check(bottom.label == "Phone · Bottom mic" && rear.label == "Phone · Rear mic")
        check(bottom.key != rear.key && bottom.key == bottom.copy(id = 200).key)
        check(!MicrophoneInput(28, 28, "Example phone", "").usable)
        val usb =
            listOf(
                MicrophoneInput(80, 11, "USB mic, stereo", "usb:1"),
                MicrophoneInput(81, 11, "USB mic, stereo", "usb:2"),
            )
        check(MicrophoneInput.labels(usb).distinct().size == 2)
        check(usb.none { ',' in it.key })
        check(MicrophoneInput.labels(usb.map { it.copy(address = "") }).distinct().size == 2)
        val unknown = listOf(bottom.copy(address = ""), rear.copy(address = ""))
        check(MicrophoneInput.labels(unknown).distinct().size == 2)
        check(unknown.all { "Bottom" !in it.label && "Rear" !in it.label })
    }
    test("camera focus adapts to video, photo, auto and fixed-focus hardware modes") {
        check(CameraFocus.mode(intArrayOf(0, 1, 3, 4)) == 3)
        check(CameraFocus.mode(intArrayOf(0, 1, 4)) == 4)
        check(CameraFocus.mode(intArrayOf(0, 1)) == 1)
        check(CameraFocus.mode(intArrayOf(0)) == 0)
        check(!CameraFocus.canLock(0) && !CameraFocus.canLock(5))
        check(CameraFocus.canLock(3) && CameraFocus.canLock(1))
    }
    test("split effects own independent state") {
        val e = fixture()
        e.applyEffect(Effect(kind = "Color LUTs", values = mutableMapOf("lut" to "Mono")))
        e.cursor = 4.0
        e.split()
        val clips = e.project.clips.sortedBy { it.start }
        check(clips.size == 2)
        near(clips[0].duration, 4.0)
        near(clips[1].duration, 6.0)
        check(clips[0].effects.single().id == clips[1].effects.single().id)
        clips[1].effects[0].values["lut"] = "Warm"
        check(clips[0].effects[0].values["lut"] == "Mono")
        e.removeEffect(clips[1].effects[0].id)
        check(clips[0].effects.size == 1)
        check(e.project.clips[1].effects.isEmpty())
    }
    test("range effect affects exactly selected interval") {
        val e = fixture()
        e.range = 2.0..5.0
        e.applyEffect(Effect(kind = "Noir"))
        check(e.project.clips.size == 3)
        val effected = e.project.clips.single { it.effects.isNotEmpty() }
        near(effected.start, 2.0)
        near(effected.end, 5.0)
        near(e.project.duration, 10.0)
    }
    test("undo and redo restore deep snapshots") {
        val e = fixture()
        e.cursor = 5.0
        e.split()
        e.undo()
        check(e.project.clips.size == 1)
        e.redo()
        check(e.project.clips.size == 2)
        e.project.clips[0].effects.add(Effect(kind = "Noir"))
        e.undo()
        check(e.project.clips[0].effects.isEmpty())
    }
    test("range copy clips source bounds and paste ripples") {
        val e = fixture()
        e.range = 2.0..4.0
        e.copySelection()
        near(e.clipboard.single().sourceIn, 2.0)
        near(e.clipboard.single().sourceOut, 4.0)
        e.clearSelection()
        e.cursor = 8.0
        e.paste()
        near(e.project.duration, 12.0)
        val paste = e.targets().single()
        near(paste.start, 8.0)
        near(paste.end, 10.0)
    }
    test("range delete closes gap") {
        val e = fixture()
        e.range = 2.0..4.0
        e.deleteSelection()
        near(e.project.duration, 8.0)
        val clips = e.project.clips.sortedBy { it.start }
        near(clips[0].end, 2.0)
        near(clips[1].start, 2.0)
        near(clips[1].sourceIn, 4.0)
    }
    test("unlink creates independently editable audio") {
        val e = fixture()
        e.select(e.project.clips.single())
        e.unlink()
        check(e.project.clips.size == 2)
        check(e.project.clips.count { it.audio } == 1)
        check(e.project.clips.count { it.video } == 1)
        val audio = e.project.clips.single { it.audio }
        e.clearSelection()
        e.select(audio)
        e.deleteSelection(false)
        check(e.project.clips.single().video)
        check(!e.project.clips.single().audio)
        near(e.project.duration, 10.0)
    }
    test("speed edits ripple following clips") {
        val e = fixture()
        e.cursor = 5.0
        e.split()
        e.select(e.project.clips.first())
        e.setRate(2.0)
        near(e.project.duration, 7.5)
        near(e.project.clips.single { it.sourceIn == 5.0 }.start, 2.5)
    }
    test("multitrack move preserves source and other clips") {
        val e = fixture()
        e.cursor = 5.0
        e.split()
        val c = e.project.clips[1]
        e.select(c)
        e.moveSelection(1.0, 1)
        near(c.start, 1.0)
        near(c.sourceIn, 5.0)
        check(c.track == 1)
        check(e.project.active(2.0).size == 2)
    }
    test("stacked copies of the same source select and relink independently") {
        val e = fixture()
        val original = e.project.clips.single()
        val copy = original.duplicate(true)
        e.addLayer(copy, 0.0)
        e.clearSelection()
        e.select(copy)
        check(e.targets() == listOf(copy))
        e.unlink()
        val audio = e.project.clips.last()
        e.clearSelection()
        e.select(copy)
        e.toggleSelection(audio)
        e.linkSelection()
        check(e.targets().toSet() == setOf(copy, audio))
        check(original.audio && original.video)
        e.clearSelection()
        e.select(copy)
        check(e.targets().toSet() == setOf(copy, audio))
    }
    test("move batch cancels and commits as one undo step") {
        val e = fixture()
        val c = e.project.clips.single()
        e.select(c)
        e.beginBatch()
        e.moveSelection(2.0, 1)
        e.moveSelection(4.0, 2)
        e.endBatch(true)
        near(e.project.clips.single().start, 0.0)
        check(!e.canUndo)
        e.beginBatch()
        e.moveSelection(2.0, 1)
        e.moveSelection(4.0, 2)
        e.endBatch(false)
        e.undo()
        near(e.project.clips.single().start, 0.0)
        check(e.project.clips.single().track == 0)
    }
    test("snap and event navigation") {
        val e = fixture()
        e.cursor = 4.0
        e.split()
        near(e.snapTime(4.05), 4.0)
        e.cursor = 1.0
        e.jump(true)
        near(e.cursor, 4.0)
        e.jump(false)
        near(e.cursor, 0.0)
    }
    test("random splits preserve total duration and media bounds") {
        val e = fixture()
        val rng = Random(42)
        repeat(100) {
            e.cursor = rng.nextDouble(0.01, 9.99)
            e.split()
        }
        val clips = e.project.clips.sortedBy { it.start }
        near(clips.sumOf { it.duration }, 10.0)
        for (i in 1 until clips.size) {
            near(clips[i - 1].end, clips[i].start)
            near(clips[i - 1].sourceOut, clips[i].sourceIn)
        }
    }
    test("fixed timeline stays centered at project edges") {
        near(TimelineLayout.start(0.0, 10.0, false), -5.0)
        near(TimelineLayout.start(14.0, 10.0, false), 9.0)
        near(TimelineLayout.start(14.0, 14.0, true), 0.0)
    }
    test("partly clipped thumbnail tiles retain their clip origin when panning") {
        near(TimelineLayout.thumbnailStart(20f, 0f, 50f).toDouble(), 20.0)
        near(TimelineLayout.thumbnailStart(-120f, 0f, 50f).toDouble(), -20.0)
        near(TimelineLayout.thumbnailStart(-152f, 0f, 50f).toDouble(), -2.0)
        near(TimelineLayout.thumbnailStart(-120f, 46f, 50f).toDouble(), 30.0)
        for (pan in -300..300 step 7) {
            val left = 97f - pan
            val first = TimelineLayout.thumbnailStart(left, 46f, 83.25f)
            val tile = (first - left) / 83.25f
            check(abs(tile - kotlin.math.round(tile)) < .0001f)
            check(first <= maxOf(left, 46f) && first + 83.25f > 46f)
        }
    }
    test("video lanes precede audio lanes and separate overlapping clips") {
        val e = fixture()
        val overlay = Clip(path = "other.mp4", sourceOut = 5.0, track = 2)
        e.project.clips.add(overlay)
        val lanes = TimelineLayout.lanes(e.project, 109f)
        check(lanes.map { it.audio } == listOf(false, false, true, true))
        check(lanes.first().ids == setOf(overlay.id))
        check(lanes.zipWithNext().all { (a, b) -> a.bottom < b.top })
        e.select(e.project.clips.first())
        e.unlink()
        check(e.project.clips.last().track == 0)
    }
    test("layer imports keep existing clips and transitions in place") {
        val e = fixture()
        e.cursor = 5.0
        e.split()
        e.putTransition(Transition(at = 5.0, duration = 1.0))
        val before = e.project.duplicate()
        val video = Clip(path = "layer.mp4", sourceOut = 3.0)
        e.addLayer(video, 2.0)
        val audio = Clip(path = "music.wav", sourceOut = 20.0, video = false, linked = false)
        e.addLayer(audio, 2.0)
        check(video.track == 1 && audio.track == 2)
        near(video.start, 2.0)
        near(audio.start, 2.0)
        check(e.project.clips.take(2) == before.clips)
        check(e.project.transitions == before.transitions)
        check(e.project.active(3.0).map { it.path } == listOf("fixture.mp4", "layer.mp4"))
        e.undo()
        check(e.project.clips.none { it.path == "music.wav" })
        e.redo()
        check(e.project.clips.last().path == "music.wav")
    }
    test("recording markers follow trims, splits, speed, copy and undo") {
        val e = fixture()
        e.project.clips.single().marks.addAll(listOf(1.0, 4.0, 8.0))
        e.cursor = 4.0
        e.split()
        check(e.project.markers() == listOf(1.0, 4.0, 8.0))
        check(4.0 in e.project.events())
        e.range = 3.0..6.0
        e.copySelection()
        e.cursor = 10.0
        e.paste()
        check(e.project.markers() == listOf(1.0, 4.0, 8.0, 11.0))
        e.undo()
        check(e.project.markers() == listOf(1.0, 4.0, 8.0))
        val clone = e.project.duplicate()
        clone.clips.first().marks.clear()
        check(e.project.clips.first().marks.size == 3)
        e.clearSelection()
        e.select(e.project.clips.last())
        e.setRate(2.0)
        check(e.project.markers() == listOf(1.0, 4.0, 6.0))
    }
    test("moving a group preserves relative tracks and offsets") {
        val e = fixture()
        val other = Clip(path = "other.mp4", sourceOut = 5.0, track = 2, start = 1.0)
        e.project.clips.add(other)
        e.project.clips.forEach { e.selected.add(it.id) }
        e.moveSelection(3.0, 1)
        check(e.project.clips.map { it.track } == listOf(1, 3))
        near(e.project.clips.first().start, 3.0)
        near(other.start, 4.0)
    }
    test("placement owns each overlapping clip and commits as one edit") {
        val e = fixture()
        val other = Clip(path = "other.mp4", sourceOut = 10.0, track = 1)
        e.project.clips.add(other)
        e.project.clips.forEach { e.selected.add(it.id) }
        val effects =
            e.project.clips.associate {
                it.id to
                    Effect(
                        kind = "Placement",
                        values = mutableMapOf("x" to if (it === other) "0.6" else "0.1"),
                    )
            }
        e.applyPlacements(effects, mapOf(other.id to 2))
        check(e.project.clips.map { it.effects.single().values["x"] } == listOf("0.1", "0.6"))
        check(other.track == 2)
        e.undo()
        check(e.project.clips.all { it.effects.isEmpty() })
        check(e.project.clips.last().track == 1)
    }
    test("range placement changes framing and layer only inside the range") {
        val e = fixture()
        val id = e.project.clips.single().id
        e.range = 2.0..5.0
        e.applyPlacements(
            mapOf(id to Effect(kind = "Placement", values = mutableMapOf("w" to "0.5"))),
            mapOf(id to 2),
        )
        check(e.project.clips.size == 3)
        val changed = e.project.clips.single { it.effects.isNotEmpty() }
        near(changed.start, 2.0)
        near(changed.end, 5.0)
        check(changed.track == 2)
        check(e.project.clips.filter { it !== changed }.all { it.track == 0 })
    }
    test("opening another project cannot undo into the previous project") {
        val e = fixture()
        e.cursor = 3.0
        e.split()
        val other = Project(name = "Other")
        e.load(other)
        check(!e.canUndo && !e.canRedo)
        e.undo()
        check(e.project === other)
    }
    test("undercranking retimes video audio effects and following clips") {
        val e = fixture()
        e.cursor = 5.0
        e.split()
        e.select(e.project.clips.first())
        e.replaceChain(listOf(Effect(kind = "Undercranking")), listOf("Undercranking"))
        val clip = e.project.clips.first()
        near(clip.duration, 4.0)
        near(clip.sourceTime(2.0), 2.5)
        near(clip.effects.single().to, 4.0)
        near(e.project.clips.last().start, 4.0)
        near(e.project.duration, 9.0)
        e.removeEffect(clip.effects.single().id)
        near(clip.duration, 5.0)
        near(e.project.clips.last().start, 5.0)
        near(e.project.duration, 10.0)
        e.undo()
        near(e.project.duration, 9.0)
    }
    test("transition overlap shortens the project and editing or removal restores handles") {
        val e = fixture()
        e.cursor = 5.0
        e.split()
        e.putTransition(Transition(at = 5.0, kind = "Wipe", duration = 1.0))
        near(e.project.duration, 9.0)
        near(e.project.clips.last().start, 4.0)
        near(e.project.transitions.single().at, 4.5)
        val value = e.project.transitions.single().copy(duration = 2.0, kind = "Defocus")
        e.putTransition(value)
        near(e.project.duration, 8.0)
        near(e.project.clips.last().start, 3.0)
        e.removeTransition(value.id)
        near(e.project.duration, 10.0)
        near(e.project.clips.last().start, 5.0)
        e.undo()
        near(e.project.duration, 8.0)
    }
    test("deleting either side of a transition leaves no overlap gap") {
        for (index in 0..1) {
            val e = fixture()
            e.cursor = 5.0
            e.split()
            e.putTransition(Transition(at = 5.0, duration = 1.0))
            e.select(e.project.clips[index])
            e.deleteSelection()
            check(e.project.clips.size == 1 && e.project.transitions.isEmpty())
            near(e.project.clips.single().start, 0.0)
            near(e.project.duration, 5.0)
        }
    }
    test("copy and paste retain a transition and remap its clip references") {
        val e = fixture()
        e.cursor = 5.0
        e.split()
        e.putTransition(Transition(at = 5.0, duration = 1.0))
        e.project.clips.forEach { e.selected.add(it.id) }
        e.copySelection()
        e.clearSelection()
        e.cursor = e.project.duration
        e.paste()
        near(e.project.duration, 18.0)
        check(e.project.transitions.size == 2)
        val pasted = e.project.transitions.last()
        near(pasted.at, 13.5)
        check(pasted.leftId in e.selected && pasted.rightId in e.selected)
    }
    test("speed changes keep an outgoing transition attached to both clips") {
        val e = fixture()
        e.cursor = 5.0
        e.split()
        e.putTransition(Transition(at = 5.0, duration = 1.0))
        e.select(e.project.clips.first())
        e.setRate(2.0)
        near(e.project.clips.last().start, 1.5)
        near(e.project.transitions.single().at, 2.0)
        near(e.project.duration, 6.5)
    }
    test("import inserts at the playhead and preserves both sides of the source") {
        val e = fixture()
        e.cursor = 4.0
        e.insert(Clip(path = "inserted.mp4", sourceOut = 2.0))
        near(e.project.duration, 12.0)
        val clips = e.project.clips.sortedBy { it.start }
        near(clips[0].end, 4.0)
        check(clips[1].path == "inserted.mp4")
        near(clips[1].start, 4.0)
        near(clips[2].start, 6.0)
        near(clips[2].sourceIn, 4.0)
        e.undo()
        near(e.project.duration, 10.0)
    }
    test("retiming coincident video and audio ripples the following cut once") {
        for (effect in listOf(false, true)) {
            val e = fixture()
            e.cursor = 5.0
            e.split()
            e.select(e.project.clips.first())
            e.unlink()
            val pair = e.targets()
            check(pair.size == 2)
            if (effect)
                e.replaceChain(listOf(Effect(kind = "Undercranking")), listOf("Undercranking"))
            else e.setRate(1.25)
            check(pair.all { abs(it.duration - 4) < .001 })
            near(e.project.clips.single { it.sourceIn == 5.0 }.start, 4.0)
            near(e.project.duration, 9.0)
        }
    }
    test("moving both sides carries their transition while moving one detaches it") {
        val e = fixture()
        e.cursor = 5.0
        e.split()
        e.putTransition(Transition(at = 5.0, duration = 1.0))
        e.project.clips.forEach { e.selected.add(it.id) }
        e.moveSelection(2.0, 1)
        near(e.project.transitions.single().at, 6.5)
        check(e.project.clips.all { it.track == 1 })
        e.clearSelection()
        e.select(e.project.clips.last())
        e.moveSelection(0.0, 2)
        check(e.project.transitions.isEmpty())
        near(e.targets().single().start, 0.0)
        e.undo()
        near(e.project.transitions.single().at, 6.5)
    }
    test("relinking separate matching parts restores joint selection") {
        val e = fixture()
        e.select(e.project.clips.single())
        e.unlink()
        val video = e.project.clips.single { it.video }
        e.clearSelection()
        e.select(video)
        e.toggleSelection(e.project.clips.single { !it.video })
        e.linkSelection()
        check(e.project.clips.all { it.linked })
        e.clearSelection()
        e.select(video)
        check(e.targets().size == 2)
        e.undo()
        check(e.project.clips.none { it.linked })
    }
    test("timeline shows an overlapping transition as one shared cut") {
        val e = fixture()
        e.cursor = 5.0
        e.split()
        e.putTransition(Transition(at = 5.0, duration = 1.0))
        val left = TimelineLayout.interval(e.project, e.project.clips.first())
        val right = TimelineLayout.interval(e.project, e.project.clips.last())
        near(left.endInclusive, 4.5)
        near(right.start, 4.5)
        near(right.endInclusive, 9.0)
        e.select(e.project.clips.last())
        e.unlink()
        near(TimelineLayout.interval(e.project, e.project.clips.last()).start, 4.0)
    }
    fun blend(): Editor =
        fixture().apply {
            cursor = 5.0
            split()
            putTransition(Transition(at = 5.0, duration = 1.0))
        }
    fun sample(project: Project, time: Double): List<Double> {
        val tr = project.transitions.first { time >= it.begin && time <= it.end }
        return listOf(
            project.transitionSource(tr.leftId, time)!!.sourceTime(time),
            project.transitionSource(tr.rightId, time)!!.sourceTime(time),
            tr.progress(time).toDouble(),
        )
    }
    test("splitting an active blend retains both source clocks and phase") {
        val e = blend()
        val before = e.project.duplicate()
        e.cursor = 4.4
        e.split()
        for (time in listOf(4.1, 4.3, 4.5, 4.9)) sample(before, time)
            .zip(sample(e.project, time))
            .forEach { (a, b) -> near(a, b) }
        near(e.project.duration, 9.0)
    }
    test("insertion inside a blend restores source handles and opens picture time as one undo") {
        val e = blend()
        val before = e.project.duplicate()
        e.clipboard = listOf(Clip(path = "insert.mp4", sourceOut = 2.0))
        e.cursor = 4.25
        e.paste()
        near(e.project.duration, 12.0)
        check(e.project.transitions.isEmpty())
        near(e.project.clips.filter { it.path == "fixture.mp4" }.sumOf { it.duration }, 10.0)
        val inserted = e.project.clips.single { it.path == "insert.mp4" }
        near(inserted.start, 4.25)
        e.undo()
        check(e.project == before)
    }
    test("deleting the middle of a blend preserves its surviving phase ranges") {
        val e = blend()
        val before = e.project.duplicate()
        e.range = 4.2..4.8
        e.deleteSelection()
        near(e.project.duration, 8.4)
        for ((a, b) in listOf(4.1 to 4.1, 4.9 to 4.3)) sample(before, a)
            .zip(sample(e.project, b))
            .forEach { (x, y) -> near(x, y) }
    }
    test("copying a range through a blend clips its source handles and phase") {
        val e = blend()
        val before = e.project.duplicate()
        e.range = 4.2..4.8
        e.copySelection()
        e.clearSelection()
        e.cursor = e.project.duration
        e.paste()
        near(e.project.duration, 9.6)
        sample(before, 4.3).zip(sample(e.project, 9.1)).forEach { (a, b) -> near(a, b) }
    }
    test("slicing a held blend retains its piecewise phase") {
        val tr = Transition(at = 5.0, duration = 2.0, hold = 1.0)
        for (cut in listOf(4.0, 5.0, 6.0)) {
            val a = tr.slice(tr.begin, cut)!!
            val b = tr.slice(cut, tr.end)!!
            for (i in 0..30) {
                val time = tr.begin + i * .1
                near(
                    tr.progress(time).toDouble(),
                    (if (time <= cut) a else b).progress(time).toDouble(),
                )
            }
        }
    }
    test("large speed changes shrink transitions to available source handles") {
        for (index in 0..1) {
            val e = blend()
            e.select(e.project.clips[index])
            e.setRate(8.0)
            val tr = e.project.transitions.single()
            check(tr.overlap <= e.project.clips.minOf { it.duration })
            check(e.project.clips.all { it.start >= 0 })
            near(e.project.duration, 5.0 + 1.0 / 30)
        }
    }
    test("a short middle clip cannot retain overlapping handles longer than itself") {
        val e = fixture()
        e.cursor = 4.0
        e.split()
        e.cursor = 5.0
        e.split()
        e.putTransition(Transition(at = 4.0, duration = .6))
        val middle = e.project.clips.single { it.sourceIn == 4.0 }
        e.putTransition(Transition(at = middle.end, duration = .6))
        e.select(middle)
        e.setRate(4.0)
        val handles =
            e.project.transitions
                .filter { it.leftId == middle.id || it.rightId == middle.id }
                .sumOf { it.overlap }
        check(handles <= middle.duration + .000001)
        check(e.project.clips.all { it.start >= 0 })
        for (tr in e.project.transitions.filter { it.overlap > 0 }) {
            val left = e.project.clips.first { it.id == tr.leftId }
            val right = e.project.clips.first { it.id == tr.rightId }
            near(left.end - right.start, tr.overlap)
        }
    }
    println("$passed editor tests passed")
}
