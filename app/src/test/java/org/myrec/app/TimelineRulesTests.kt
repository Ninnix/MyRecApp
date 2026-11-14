package org.myrec.app

import kotlin.math.abs

object TimelineRulesTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        fun near(a: Double, b: Double) = check(abs(a - b) < .00001) { "$a != $b" }
        fun sequence() = Editor(Project(clips = (0..3).map {
            Clip(path = "$it.mp4", sourceOut = 2.0, start = it * 2.0)
        }.toMutableList()))
        test("independent picture and sound pastes overlay without inserting time") {
            for (video in listOf(true, false)) {
                val e = sequence()
                val source = Clip(path = "independent", sourceOut = 1.0, video = video, audio = !video, linked = false)
                e.project.clips.add(source)
                e.select(source); e.copySelection()
                check(!e.clipboardInsertsTime)
                val before = e.project.duplicate()
                e.clearSelection(); e.cursor = .5; e.paste()
                check(e.project.clips.dropLast(1) == before.clips)
                near(e.project.clips.last().start, .5)
                if (video) check(e.project.active(.75).last().id == e.project.clips.last().id)
            }
        }
        test("linked AV paste opens picture time while independent music continues unsplit") {
            val e = sequence()
            val music = Clip(path = "music.wav", sourceOut = 12.0, video = false, linked = false)
            e.project.clips.add(music)
            e.select(e.project.clips[0]); e.copySelection()
            check(e.clipboardInsertsTime)
            e.clearSelection(); e.cursor = 3.0; e.paste()
            check(e.project.clips.single { it.id == music.id } == music)
            near(e.project.clips.single { it.path == "2.mp4" }.start, 6.0)
            near(e.project.clips.last().start, 3.0)
        }
        test("clipboard compacts unselected gaps but ranges retain their internal timing") {
            val e = sequence()
            e.selected.addAll(listOf(e.project.clips[0].id, e.project.clips[2].id))
            e.copySelection()
            check(e.clipboard.map { it.start } == listOf(0.0, 2.0))
            e.clearSelection(); e.project.clips.removeAt(1); e.range = 0.0..6.0; e.copySelection()
            check(e.clipboard.map { it.start } == listOf(0.0, 4.0))
        }
        test("picture-gap insertion fills from the left edge and moves only overflow") {
            val e = sequence()
            e.project.clips[1].start = 4.0; e.project.clips[2].start = 6.0; e.project.clips[3].start = 8.0
            val music = Clip(path = "score.wav", sourceOut = 15.0, video = false, linked = false)
            e.project.clips.add(music)
            e.cursor = 3.0; e.insert(Clip(path = "short.mp4", sourceOut = 1.0))
            near(e.project.clips.last().start, 2.0)
            near(e.project.clips[1].start, 4.0)
            check(e.project.clips.single { it.id == music.id } == music)
            e.undo(); e.cursor = 3.0; e.insert(Clip(path = "long.mp4", sourceOut = 3.0))
            near(e.project.clips.last().start, 2.0); near(e.project.clips[1].start, 5.0)
            check(e.project.clips.single { it.id == music.id } == music)
        }
        test("deleting linked production footage ripples production groups but leaves overlays and score") {
            val e = sequence()
            val overlay = Clip(path = "overlay", sourceOut = 8.0, audio = false, linked = false, track = 10)
            val music = Clip(path = "score", sourceOut = 12.0, video = false, linked = false)
            e.project.clips.addAll(listOf(overlay, music))
            e.select(e.project.clips[1]); e.deleteSelection()
            near(e.project.clips.single { it.path == "2.mp4" }.start, 2.0)
            check(e.project.clips.first { it.id == overlay.id } == overlay)
            check(e.project.clips.first { it.id == music.id } == music)
        }
        test("deleting detached picture or sound leaves a gap and closing gaps considers every layer") {
            val e = sequence()
            val first = e.project.clips[0]
            e.select(first); e.unlink(); e.clearSelection(); e.select(first); e.deleteSelection()
            near(e.project.clips.single { it.path == "1.mp4" }.start, 2.0)
            check(TimelineRules.gap(e.project, 1.0) == null)
            val sound = e.project.clips.last()
            e.select(sound); e.deleteSelection(); e.cursor = 1.0
            check(TimelineRules.gap(e.project, 1.0) == TimelineSpan(0.0, 2.0))
            e.closeGap()
            near(e.project.clips.first().start, 0.0)
            e.undo(); near(e.project.clips.first().start, 2.0)
        }
        test("Link joins different contiguous takes and one-sided chains survive move and reopening") {
            val e = sequence()
            e.selected.addAll(e.project.clips.take(2).map { it.id })
            e.linkSelection()
            val first = e.project.clips[0].id
            e.clearSelection(); e.select(e.project.clips[0])
            check(e.targets().size == 2 && e.selectionLinked)
            e.moveSelection(1.0)
            near(e.project.clips[0].start, 1.0); near(e.project.clips[1].start, 3.0)
            val saved = e.session()
            val reopened = Editor(saved.project).apply { load(saved.project, saved) }
            reopened.select(reopened.project.clips.single { it.id == first })
            check(reopened.targets().size == 2 && reopened.selectionLinked)
            reopened.unlink(); reopened.clearSelection(); reopened.select(reopened.project.clips[0])
            check(reopened.targets().size == 1)
        }
        test("Link refuses gaps, overlaps and a single part without changing the edit") {
            for (start in listOf(1.0, 3.0)) {
                val e = sequence()
                e.project.clips[1].start = start
                e.selected.addAll(e.project.clips.take(2).map { it.id })
                val before = e.project.duplicate()
                check(runCatching { e.linkSelection() }.isFailure)
                check(e.project == before && !e.canUndo)
            }
            val e = sequence(); e.select(e.project.clips[0])
            check(runCatching { e.linkSelection() }.isFailure)
        }
        test("linking an intersection keeps outside fragments separate and Cancel is a pure plan") {
            val picture = Clip(path = "video", sourceOut = 4.0, audio = false, linked = false)
            val sound = Clip(path = "sound", sourceOut = 4.0, start = 2.0, video = false, linked = false)
            val e = Editor(Project(clips = mutableListOf(picture, sound)))
            e.selected.addAll(listOf(picture.id, sound.id))
            val before = e.project.duplicate()
            val plan = e.linkPlan()
            check(plan.needsIntersection && plan.intersection == TimelineSpan(2.0, 4.0))
            check(e.project == before && !e.canUndo)
            check(runCatching { e.linkSelection() }.isFailure)
            e.linkSelection(true, before)
            check(e.targets().size == 2 && e.targets().all { it.start == 2.0 && it.end == 4.0 })
            check(e.project.clips.filter { it.id !in e.selected }.all { it.link == null })
            e.moveSelection(3.0)
            check(e.project.clips.any { it.start == 0.0 && it.end == 2.0 })
            check(e.project.clips.any { it.start == 4.0 && it.end == 6.0 })
            e.undo(); e.undo(); check(e.project == before)
        }
        test("splitting a horizontal linked group creates independently selectable left and right links") {
            val e = sequence()
            e.selected.addAll(e.project.clips.take(2).map { it.id }); e.linkSelection()
            e.cursor = 1.0; e.split(); e.clearSelection(); e.select(e.project.clips[0])
            check(e.targets().all { it.end <= 1.0 })
            e.clearSelection(); e.select(e.project.clips.last())
            check(e.targets().size == 2 && e.targets().all { it.start >= 1.0 })
        }
        test("source occurrence survives split and restores detached retimed audio without moving picture") {
            val e = Editor(Project(clips = mutableListOf(Clip(path = "take", sourceOut = 8.0, rate = 2.0))))
            e.select(e.project.clips[0]); e.unlink()
            val sound = e.project.clips.last()
            e.clearSelection(); e.select(sound); e.moveSelection(1.0)
            near(e.syncOffset()!!, 1.0)
            val picture = e.project.clips.first().duplicate()
            e.restoreSync()
            near(e.project.clips.last().start, 0.0)
            check(e.project.clips.first() == picture)
            e.undo(); near(e.project.clips.last().start, 1.0)
        }
        test("cut and repeated paste give fresh links and occurrences with one deletion undo") {
            val e = sequence()
            val before = e.project.duplicate()
            e.selected.addAll(e.project.clips.take(2).map { it.id }); e.linkSelection()
            e.cutSelection(); check(e.clipboard.size == 2 && e.project.clips.size == 2)
            e.cursor = e.project.duration; e.paste(); val pasted = e.targets().mapNotNull { it.link }.toSet()
            e.cursor = e.project.duration; e.paste()
            check(e.targets().mapNotNull { it.link }.none { it in pasted })
            check(e.project.clips.map { it.id }.distinct().size == e.project.clips.size)
            repeat(4) { e.undo() }
            check(e.project == before)
        }
        test("mixed photo and video batches keep order, canvas and originals through one undo") {
            val e = Editor()
            val before = e.project.duplicate()
            val first = Clip(path = "photo.jpg", sourceOut = 3.0, photo = true, audio = false, linked = false)
            val second = Clip(path = "video.mp4", sourceOut = 2.0)
            e.insertBatch(listOf(first, second), 0.0, shape = 800 to 1200)
            check(e.project.clips.map { it.path } == listOf("photo.jpg", "video.mp4"))
            check(e.project.clips.map { it.start } == listOf(0.0, 3.0))
            check(e.project.width == 800 && e.project.height == 1200)
            val session = e.session()
            e.undo(); check(e.project == before && !e.canUndo)
            check(e.session().originals.size == 2)
            e.redo(); check(e.project == session.project)
        }
        test("picture and sound clipboard destinations remain distinct at coincident cuts") {
            val e = sequence()
            val audio = (0..1).map { Clip(path = "sound$it", sourceOut = 2.0, start = it * 2.0, video = false, linked = false) }
            e.project.clips.addAll(audio)
            e.select(audio[0]); e.copySelection(); e.clearSelection(); e.cursor = 2.0
            val targets = e.pasteTargets()
            check(targets.any { it.kind == PasteKind.PICTURE } && targets.any { it.kind == PasteKind.SOUND })
            val before = e.project.clips.filter { it.video }.map { it.duplicate() }
            e.paste(targets.single { it.kind == PasteKind.SOUND })
            check(e.project.clips.filter { it.video } == before)
            near(e.project.clips.single { it.id == audio[1].id }.start, 4.0)
        }
        test("PCM wave duration retains fractional milliseconds and skips padded metadata chunks") {
            val file = java.io.File.createTempFile("myrec-wave-", ".wav")
            try {
                val frames = 12345
                val header = java.nio.ByteBuffer.allocate(56).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt(48 + frames * 2).put("WAVEJUNK".toByteArray())
                header.putInt(3).put(byteArrayOf(1, 2, 3, 0)).put("fmt ".toByteArray()).putInt(16)
                header.putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16)
                header.put("data".toByteArray()).putInt(frames * 2)
                file.outputStream().use { it.write(header.array()); it.write(ByteArray(frames * 2)) }
                near(PcmWave.duration(file)!!, frames / 48000.0)
                java.io.RandomAccessFile(file, "rw").use { it.setLength(file.length() - 1) }
                check(PcmWave.duration(file) == null)
            } finally { file.delete() }
        }
    }
}
