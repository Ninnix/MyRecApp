package org.myrec.app

import kotlin.math.abs
import kotlin.random.Random

object TimelineMoveTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        fun sequence(): Editor = Editor(Project(clips = (0..3).map {
            Clip(path = "$it.mp4", sourceOut = 2.0, start = it * 2.0)
        }.toMutableList()))
        fun near(a: Double, b: Double) = check(abs(a - b) < .00001) { "$a != $b" }
        fun checkRows(project: Project) {
            val lanes = TimelineLayout.lanes(project, 109f)
            for (lane in lanes) {
                val clips = project.clips.filter { it.id in lane.ids }
                for ((i, a) in clips.withIndex()) for (b in clips.drop(i + 1)) {
                    val x = TimelineLayout.interval(project, a)
                    val y = TimelineLayout.interval(project, b)
                    check(x.start >= y.endInclusive || y.start >= x.endInclusive)
                }
            }
            for (at in project.events().zipWithNext().map { (a, b) -> (a + b) / 2 }) {
                val active = project.active(at)
                val rows = active.map { clip -> lanes.first { !it.audio && clip.id in it.ids }.row }
                check(rows.zipWithNext().all { (a, b) -> a > b })
            }
        }
        test("new overlaps come forward and existing covered nudges retain picture order") {
            val e = sequence()
            e.project.clips.removeAll { it.start >= 4.0 }
            val first = e.project.clips.first()
            val second = e.project.clips[1]
            e.select(first)
            e.moveSelection(2.5)
            check(e.project.active(3.0).last().id == first.id)
            checkRows(e.project)
            e.select(second)
            e.moveSelection(2.0 + 1.0 / 30)
            check(e.project.active(3.0).last().id == first.id)
            checkRows(e.project)
            check(first.sourceIn == 0.0 && first.sourceOut == 2.0)
        }
        test("sequential layers share a row and same-layer overlaps never hide timeline cards") {
            val e = sequence()
            e.project.clips.forEachIndexed { i, c -> c.track = i * 3 }
            check(TimelineLayout.lanes(e.project, 109f).count { !it.audio } == 1)
            e.project.clips.forEach { it.start = 0.0; it.track = 0 }
            check(TimelineLayout.lanes(e.project, 109f).count { !it.audio } == 4)
            checkRows(e.project)
        }
        test("move companions stop at gaps and leave an overlapping overlay and music alone") {
            val e = sequence()
            e.project.clips.last().start += 1.0 / 240000
            val overlay = Clip(path = "overlay.mp4", sourceOut = 1.0, start = 2.5, track = 10)
            val music = Clip(path = "music.wav", sourceOut = 12.0, video = false, linked = false)
            e.project.clips.addAll(listOf(overlay, music))
            val middle = e.project.clips[1]
            e.select(middle)
            e.beginMove()
            val draft = e.move!!
            check(draft.choices.getValue(MoveScope.LEFT) == e.project.clips.take(2).map { it.id }.toSet())
            check(draft.choices.getValue(MoveScope.RIGHT) == e.project.clips.subList(1, 3).map { it.id }.toSet())
            check(draft.choices.getValue(MoveScope.BOTH) == e.project.clips.take(3).map { it.id }.toSet())
            e.changeMoveScope(MoveScope.RIGHT)
            e.updateMove(.75, 60.0, false)
            near(e.project.clips[1].start, 2.75)
            near(e.project.clips[2].start, 4.75)
            near(e.project.clips.first { it.id == overlay.id }.start, 2.5)
            near(e.project.clips.first { it.id == music.id }.start, 0.0)
            e.finishMove(true)
        }
        test("move scope switches retain the requested offset after group clamping") {
            val e = sequence()
            e.select(e.project.clips[1]); e.beginMove()
            e.updateMove(-1.0, 60.0, false)
            e.changeMoveScope(MoveScope.LEFT)
            near(e.move!!.offset, 0.0)
            e.changeMoveScope(MoveScope.SELECTED)
            near(e.move!!.offset, -1.0)
            e.finishMove(true)
        }
        test("move snaps leading and trailing edges, latches and excludes moving source marks") {
            val e = sequence()
            val c = e.project.clips[1]
            e.project.clips[2].start = 8.0
            e.project.clips[3].start = 10.0
            c.marks.add(1.0)
            val ids = setOf(c.id)
            val result = TimelineMove.snapped(e.project, ids, 5.9, 60.0)
            near(result.offset, 6.0)
            val held = TimelineMove.snapped(e.project, ids, 6.25, 60.0, result.snap)
            near(held.offset, 6.0)
            val released = TimelineMove.snapped(e.project, ids, 6.4, 60.0, held.snap)
            near(released.offset, 6.4)
            near(TimelineMove.snapped(e.project, ids, 3.9, 60.0).offset, 4.0)
            near(TimelineMove.snapped(e.project, ids, 5.9, 720.0).offset, 5.9)
            near(TimelineMove.snapped(e.project, ids, -100.0, 60.0).offset, -2.0)
            near(TimelineMove.snapped(e.project, e.project.clips.map { it.id }.toSet(), .5, 60.0).offset, .5)
        }
        test("move boundary destinations use the original picked start and undragged edit") {
            val e = sequence()
            e.select(e.project.clips[1]); e.beginMove()
            e.changeMoveScope(MoveScope.LEFT)
            val stops = e.move!!.boundaries
            check(0.0 !in stops && 2.0 in stops && 4.0 in stops && 8.0 in stops)
            e.placeMove(8.0)
            near(e.project.clips[1].start, 8.0)
            near(e.project.clips[0].start, 6.0)
            check(e.move!!.boundaries == stops)
            e.finishMove(true)
        }
        test("range Move splits only the chosen interval and Cancel restores footage and selection") {
            val e = Editor(Project(clips = mutableListOf(Clip(path = "take.mp4", sourceOut = 10.0))))
            val before = e.project.duplicate()
            e.range = 2.0..4.0
            e.beginMove()
            check(e.move!!.members.size == 1)
            e.updateMove(3.0, 60.0, false)
            val moving = e.project.clips.single { it.id in e.move!!.members }
            near(moving.sourceIn, 2.0); near(moving.sourceOut, 4.0); near(moving.start, 5.0)
            check(e.project.clips.any { it.start == 4.0 && it.sourceOut == 10.0 })
            check(e.session().project == before)
            e.finishMove(true)
            check(e.project == before && e.range == 2.0..4.0 && !e.canUndo)
        }
        test("Move drafts rebuild from the original and Done is one persistent undo") {
            val e = sequence()
            val before = e.project.duplicate()
            e.select(e.project.clips.first()); e.beginMove()
            e.updateMove(2.5, 60.0, false)
            val after = e.project.duplicate()
            repeat(20) { e.updateMove(2.5, 60.0, false) }
            check(e.project == after)
            e.updateMove(0.0, 60.0, false)
            check(e.project == before)
            e.updateMove(2.5, 60.0, false); e.finishMove(false)
            check(e.project == after)
            val saved = e.session()
            val reopened = Editor(saved.project).apply { load(saved.project, saved) }
            reopened.undo(); check(reopened.project == before)
            check(!reopened.canUndo)
            reopened.redo(); check(reopened.project == after)
        }
        test("linked audio keeps sync through scoped split, neighbor move and group clamp") {
            val e = sequence()
            for (c in e.project.clips.toList()) {
                c.audio = false
                e.project.clips.add(c.duplicate(true, keepGroup = true).apply { video = false; audio = true })
            }
            e.range = 2.5..3.5
            e.beginMove()
            check(e.move!!.members.size == 2)
            e.changeMoveScope(MoveScope.BOTH)
            check(e.move!!.members.size == 12)
            e.updateMove(-100.0, 60.0, false)
            for (c in e.project.clips.filter { it.video }) {
                val audio = e.project.clips.single { !it.video && it.group == c.group }
                near(c.start, audio.start)
            }
            e.finishMove(true)
        }
        test("moving rows stay fixed while the group crosses stationary clips") {
            val e = sequence()
            e.select(e.project.clips[1]); e.beginMove()
            val draft = e.move!!
            val rows = TimelineLayout.lanes(draft.base, 109f, draft.members)
            for (offset in listOf(-2.0, 0.0, 1.5, 4.0, 9.0)) {
                e.updateMove(offset, 60.0, false)
                check(TimelineLayout.lanes(draft.base, 109f, draft.members) == rows)
                check(rows.filter { !it.audio }.single { draft.members.any(it.ids::contains) }.ids == draft.members)
            }
            e.finishMove(true)
        }
        test("long crossfade chains use one normal row and two lifted rows per media type") {
            val clips = (0..12).map { Clip(path = "$it.mp4", sourceOut = 2.0, start = it * 1.5) }
            val project = Project(clips = clips.toMutableList(), transitions = clips.zipWithNext().map { (a, b) ->
                Transition(at = (a.end + b.start) / 2, duration = .5, overlap = .5,
                    leftId = a.id, rightId = b.id)
            }.toMutableList())
            val normal = TimelineLayout.lanes(project, 109f)
            check(normal.count { !it.audio } == 1 && normal.count { it.audio } == 1)
            val lifted = TimelineLayout.lanes(project, 109f, clips.map { it.id }.toSet())
            check(lifted.count { !it.audio } == 2 && lifted.count { it.audio } == 2)
        }
        test("transition joins never fold independent identical overlays or detached sound") {
            val e = sequence()
            e.putTransition(Transition(at = 2.0, duration = .5))
            val incoming = e.project.clips[1]
            val overlay = incoming.duplicate(true)
            e.project.clips.add(overlay)
            check(TimelineLayout.interval(e.project, overlay) == overlay.start..overlay.end)
            e.select(incoming); e.unlink()
            val sound = e.project.clips.last()
            check(sound.audio && !sound.video && !sound.linked)
            check(TimelineLayout.interval(e.project, sound) == sound.start..sound.end)
            check(TimelineLayout.lanes(e.project, 109f).count { !it.audio } >= 2)
        }
        test("moving one transition side never ripples stationary footage and zero movement preserves its join") {
            val e = sequence()
            e.putTransition(Transition(at = 2.0, duration = .5))
            e.clearSelection(); e.select(e.project.clips[1])
            val before = e.project.duplicate()
            e.beginMove(); e.updateMove(0.0, 60.0, false)
            check(e.project == before)
            e.updateMove(2.0, 60.0, false)
            check(e.project.transitions.isEmpty())
            for (stationary in before.clips.filter { it.id !in e.move!!.members })
                check(e.project.clips.single { it.id == stationary.id } == stationary)
            e.finishMove(true)
            check(e.project == before)
        }
        test("a shorter transition overlap follows a moved edge without shifting other clips") {
            val e = sequence()
            e.putTransition(Transition(at = 2.0, duration = .5))
            e.clearSelection(); e.select(e.project.clips[1])
            val before = e.project.duplicate()
            e.moveSelection(e.project.clips[1].start + .25)
            near(e.project.transitions.single().overlap, .25)
            near(e.project.transitions.single().duration, .25)
            check(e.project.clips[0] == before.clips[0])
            check(e.project.clips[2] == before.clips[2])
        }
        test("repeated overlaps retain every original source interval and consistent row order") {
            val e = sequence()
            val source = e.project.clips.associate { it.id to Triple(it.path, it.sourceIn, it.sourceOut) }
            val random = Random(81)
            repeat(100) {
                e.clearSelection()
                e.select(e.project.clips[random.nextInt(e.project.clips.size)])
                e.moveSelection(random.nextDouble(0.0, 10.0))
                check(e.project.clips.associate { it.id to Triple(it.path, it.sourceIn, it.sourceOut) } == source)
                checkRows(e.project)
            }
        }
        test("long press selection toggles an entire linked pair without clearing other clips") {
            val e = sequence()
            e.select(e.project.clips.first())
            val c = e.project.clips[1]
            c.audio = false
            val sound = c.duplicate(true, keepGroup = true).apply { video = false; audio = true }
            e.project.clips.add(sound)
            e.toggleSelection(c)
            check(e.selected == setOf(e.project.clips.first().id, c.id, sound.id))
            e.toggleSelection(sound)
            check(e.selected == setOf(e.project.clips.first().id))
        }
        test("identical saved pictures on the same layer remain independent selections and audio links") {
            val first = Clip(path = "same.mp4", sourceOut = 2.0)
            val second = first.duplicate(true)
            val e = Editor(Project(clips = mutableListOf(first, second)))
            e.select(first)
            check(e.targets() == listOf(first))
            e.unlink(); e.clearSelection(); e.select(first); e.toggleSelection(e.project.clips.last()); e.linkSelection()
            check(e.targets().size == 2 && second.id !in e.selected)
            check(second.video && second.audio)
            checkRows(e.project)
        }
    }
}
