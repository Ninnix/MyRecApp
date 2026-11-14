package org.myrec.app

import android.app.Instrumentation
import java.io.File
import org.json.JSONObject

object DurabilityTests {
    fun run(instrumentation: Instrumentation, test: (String, () -> Unit) -> Unit) {
        val root = File(instrumentation.targetContext.cacheDir, "durability-${uid()}")
        val store = ProjectStore(root)
        fun source(name: String): Clip {
            val file = File(store.media, name)
            instrumentation.context.assets.open("fixture.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            return ProjectStore.inspect(file)
        }
        fun reopen(e: Editor) = ProjectStore(root).editor(e.project)
        test("history and its redo branch survive reopening beyond sixty edits") {
            val e = Editor(Project(clips = mutableListOf(source("history.mp4"))))
            repeat(80) { e.setGain(if (it % 2 == 0) -3f else -9f) }
            e.undo()
            store.save(e)
            val opened = reopen(e)
            check(opened.canRedo && opened.project.clips.single().gainDb == -3f)
            opened.redo()
            check(opened.project.clips.single().gainDb == -9f)
            repeat(80) { check(opened.canUndo); opened.undo() }
            check(!opened.canUndo && opened.project.clips.single().gainDb == 0f)
            store.save(opened)
            val again = reopen(opened)
            check(again.canRedo)
            again.setGain(-4f)
            check(!again.canRedo)
            store.save(again)
            check(!reopen(again).canRedo)
        }
        test("recordings survive old edits and preserve immutable history payloads") {
            val e = Editor()
            e.appendRecording(source("take1.mp4"))
            e.setGain(-6f)
            store.save(e)
            val folder = File(root, "revisions/${e.project.id}")
            val old = folder.listFiles()!!.associate { it.name to it.readBytes() }
            e.appendRecording(source("take2.mp4"))
            store.save(e)
            for ((name, data) in old) check(File(folder, name).readBytes().contentEquals(data))
            val opened = reopen(e)
            opened.undo()
            check(opened.project.clips.size == 2 && opened.project.clips.first().gainDb == 0f)
            check(opened.session().captures.size == 2)
            opened.appendRecording(source("take3.mp4"))
            check(opened.canRedo)
            opened.redo()
            check(opened.project.clips.size == 3)
            opened.select(opened.project.clips.last())
            opened.deleteSelection()
            store.save(opened)
            val deleted = reopen(opened)
            check(deleted.project.clips.size == 2 && deleted.session().originals.size == 3)
            deleted.undo()
            check(deleted.project.clips.size == 3)
        }
        test("legacy project upgrades without losing its edit or source bytes") {
            val clip = source("legacy.mp4")
            val original = File(clip.path).readBytes()
            val p = Project(name = "Legacy", clips = mutableListOf(clip))
            val legacy = store.encode(p).put("version", 1)
            legacy.remove("captureCount")
            File(root, "${p.id}.json").writeText(legacy.toString())
            val opened = store.editor(p)
            check(!opened.canUndo && store.encode(opened.project).getString("name") == "Legacy")
            store.save(opened)
            check(JSONObject(File(root, "${p.id}.json").readText()).has("state"))
            check(File(clip.path).readBytes().contentEquals(original))
        }
        test("five recoverable indexes retain damaged evidence and permit resaving") {
            val e = Editor(Project(clips = mutableListOf(source("recovery.mp4"))))
            store.save(e)
            repeat(8) { e.setGain(-it.toFloat() - 1); store.save(e) }
            val indexes = File(root, "backups/${e.project.id}").listFiles()!!
            check(indexes.size == 5)
            indexes.forEach { check(it.setLastModified(100000L)) }
            val target = File(root, "${e.project.id}.json")
            target.writeText("damaged index")
            val recoveredStore = ProjectStore(root)
            val recovered = recoveredStore.editor(e.project)
            check(recoveredStore.problems.isNotEmpty() && recovered.canUndo)
            recoveredStore.save(recovered)
            check(File(root, "damaged/${e.project.id}").listFiles()!!.any {
                it.readText() == "damaged index"
            })
            check(reopen(recovered).canUndo)
            target.writeText("")
            val emptyStore = ProjectStore(root)
            val emptyRecovered = emptyStore.editor(e.project)
            emptyStore.save(emptyRecovered)
            check(emptyStore.open(e.project.id).project.clips.isNotEmpty())
        }
        test("legacy hash-only recovery filenames remain readable") {
            val e = Editor(Project(clips = mutableListOf(source("legacy-backup.mp4"))))
            store.save(e); e.setGain(-2f); store.save(e)
            val copy = File(root, "backups/${e.project.id}").listFiles()!!.single()
            val legacy = File(copy.parentFile, copy.name.substringAfter('-'))
            check(copy.renameTo(legacy))
            File(root, "${e.project.id}.json").writeText("damaged index")
            val recovered = ProjectStore(root).editor(e.project)
            check(recovered.project.clips.single().gainDb == 0f)
        }
        test("future schemas are refused without replacing them with an older backup") {
            val e = Editor(Project(clips = mutableListOf(source("future.mp4"))))
            store.save(e)
            e.setGain(-2f); store.save(e)
            val target = File(root, "${e.project.id}.json")
            val future = JSONObject(target.readText()).put("version", 999).toString()
            target.writeText(future)
            check(runCatching { ProjectStore(root).open(e.project.id) }.exceptionOrNull() is NewerProject)
            check(runCatching { store.save(e) }.exceptionOrNull() is NewerProject)
            check(target.readText() == future)
        }
        test("draft and failed publication preserve committed editing state until retry") {
            val e = Editor(Project(clips = mutableListOf(source("retry.mp4"))))
            store.save(e)
            e.beginBatch(); e.setGain(-12f); store.save(e)
            check(store.open(e.project.id).project.clips.single().gainDb == 0f)
            e.endBatch(false)
            val pointer = File(root, "current")
            check(pointer.delete() && pointer.mkdir())
            check(runCatching { store.save(e) }.isFailure)
            check(e.canUndo && e.project.clips.single().gainDb == -12f)
            check(pointer.delete())
            store.save(e)
            check(reopen(e).project.clips.single().gainDb == -12f)
            check(reopen(e).canUndo)
        }
        test("duplicate owns originals sidecars overlay assets and its complete history") {
            val clip = source("duplicate.mp4")
            val original = File(clip.path).readBytes()
            File(clip.path + ".mirror").writeText("horizontal")
            File(clip.path + ".primaries").writeText("1")
            val image = File(store.media, "overlay.png").apply { writeText("overlay asset") }
            val e = Editor(Project(name = "Copy me", clips = mutableListOf(clip)))
            e.applyEffect(Effect(kind = "Image", values = mutableMapOf("path" to image.absolutePath)))
            e.undo()
            store.save(e)
            val copy = store.duplicate(e.project)
            check(copy.id != e.project.id && copy.clips.single().path != clip.path)
            val copied = store.editor(copy)
            check(copied.canRedo)
            copied.redo()
            val overlay = copied.project.clips.single().effects.single().values.getValue("path")
            check(overlay != image.absolutePath && File(overlay).readText() == "overlay asset")
            val copiedPath = copied.project.clips.single().path
            check(File(copiedPath).readBytes().contentEquals(original))
            check(File(copiedPath + ".mirror").readText() == "horizontal")
            check(File(copiedPath + ".primaries").readText() == "1")
            store.rename(copy, "New copy name")
            val renamed = store.editor(copy)
            renamed.redo()
            check(renamed.project.name == "New copy name")
            check(File(clip.path).readBytes().contentEquals(original))
        }
        test("deleted and erased projects cannot reappear through recovery indexes") {
            val e = Editor(Project(clips = mutableListOf(source("deleted.mp4"))))
            store.save(e)
            e.setGain(-2f); store.save(e)
            store.delete(e.project)
            check(store.current().id != e.project.id)
            check(runCatching { store.open(e.project.id) }.isFailure)
            store.restore(e.project)
            check(store.editor(e.project).canUndo)
            store.delete(e.project)
            store.erase(e.project)
            check(store.current().id != e.project.id)
            check(runCatching { store.open(e.project.id) }.isFailure)
        }
        test("interrupted playable takes recover once into their original project") {
            val e = Editor(Project(name = "Recovery target"))
            store.save(e)
            val file = File(store.media, "interrupted.mp4")
            val effect = Effect(kind = "Exposure", values = mutableMapOf("strength" to "0.4"))
            val receipt = store.beginCapture(e.project, file, listOf(effect))
            instrumentation.context.assets.open("fixture.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val original = file.readBytes()
            store.checkpoint(receipt, listOf(.5), listOf(effect))
            val recovery = ProjectStore(root)
            check(recovery.recoverCaptures() == 1)
            val opened = recovery.editor(e.project)
            check(opened.project.clips.single().id == receipt.id)
            check(opened.project.clips.single().marks == listOf(.5))
            check(opened.project.clips.single().effects.single().kind == "Exposure")
            check(!opened.canUndo)
            check(recovery.recoverCaptures() == 0)
            check(file.readBytes().contentEquals(original))
        }
        test("a leftover receipt never resurrects intentionally removed footage") {
            val e = Editor(Project())
            store.save(e)
            val file = File(store.media, "already-indexed.mp4")
            val receipt = store.beginCapture(e.project, file, emptyList())
            instrumentation.context.assets.open("fixture.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val clip = ProjectStore.inspect(file).copy(id = receipt.id, group = receipt.id)
            store.finishCapture(receipt, clip)
            e.appendRecording(clip)
            e.select(clip); e.deleteSelection()
            store.save(e)
            val recovery = ProjectStore(root)
            check(recovery.recoverCaptures() == 0)
            check(recovery.open(e.project.id).project.clips.isEmpty())
            check(recovery.open(e.project.id).originals.single().id == receipt.id)
        }
        test("interrupted Record here and voice layers recover at their pinned time as undoable edits") {
            for (layer in listOf(false, true)) {
                val e = Editor(Project(clips = mutableListOf(source("base-$layer.mp4")), width = 720, height = 1280))
                val before = e.project.duplicate()
                store.save(e)
                val file = File(store.media, "inserted-$layer.mp4")
                val receipt = store.beginCapture(e.project, file, emptyList(), .5, layer)
                instrumentation.context.assets.open("fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
                val clip = ProjectStore.inspect(file).copy(id = receipt.id, group = receipt.id,
                    occurrence = receipt.id, video = !layer, linked = !layer)
                store.finishCapture(receipt, clip)
                val recovery = ProjectStore(root)
                check(recovery.recoverCaptures() == 1)
                val opened = recovery.editor(e.project)
                val inserted = opened.project.clips.single { it.id == receipt.id }
                check(kotlin.math.abs(inserted.start - .5) < .00001 && inserted.video == !layer)
                check(opened.project.width == 720 && opened.project.height == 1280)
                check(opened.canUndo && opened.session().captures.isEmpty())
                opened.undo(); check(opened.project == before && File(inserted.path).isFile)
                check(opened.session().originals.any { it.id == receipt.id })
                check(recovery.recoverCaptures() == 0)
            }
        }
        test("relink verifies unchanged bytes and rewrites recordings originals undo and redo") {
            val clip = source("relink.mp4")
            val original = File(clip.path)
            File(clip.path + ".mirror").writeText("horizontal")
            val e = Editor()
            e.appendRecording(clip); e.setGain(-6f); e.setGain(-9f); e.undo()
            store.save(e)
            val expected = store.registerMedia(e.project.id, clip.path)
            val replacement = File(root, "found.mp4")
            check(original.renameTo(replacement))
            val before = File(root, "${e.project.id}.json").readBytes()
            val wrong = File(root, "wrong.mp4").apply { writeText("a different recording") }
            check(runCatching { store.relink(e.project, clip.path, wrong) }.isFailure)
            check(File(root, "${e.project.id}.json").readBytes().contentEquals(before))
            // Simulate another committed edit while the replacement was selected.
            e.setGain(-11f); e.undo(); store.save(e)
            val path = store.relink(e.project, clip.path, replacement)
            check(path != clip.path && File(path).readBytes().contentEquals(replacement.readBytes()))
            check(File(path + ".mirror").readText() == "horizontal")
            check(store.trustedHash(e.project.id, path) == expected)
            val saved = store.open(e.project.id)
            check(ProjectAssets.paths(saved) == listOf(path))
            check(saved.captures.single().path == path && saved.originals.single().path == path)
            val repaired = store.editor(e.project)
            check(repaired.project.clips.single().gainDb == -6f && repaired.canRedo)
            repaired.redo(); check(repaired.project.clips.single().gainDb == -11f)
            repeat(2) { repaired.undo() }
            check(repaired.project.clips.single().gainDb == 0f && repaired.project.clips.single().path == path)
            check(!original.exists() && replacement.isFile)
            // The live editor has not received its UI callback yet. Its next
            // save must retain the repair as well as the new edit.
            e.setGain(-14f); store.save(e)
            val latest = ProjectStore(root).editor(e.project)
            check(latest.project.clips.single().gainDb == -14f)
            check(ProjectAssets.paths(latest.session()) == listOf(path))
            latest.undo()
            check(latest.project.clips.single().gainDb == -6f && latest.project.clips.single().path == path)
        }
        test("relink cancellation and unknown identity leave project media untouched") {
            val clip = source("repair-cancel.mp4")
            val e = Editor(Project(clips = mutableListOf(clip)))
            store.save(e)
            val source = File(clip.path)
            check(runCatching { store.relink(e.project, clip.path, source) }.isFailure)
            store.registerMedia(e.project.id, clip.path)
            val before = File(root, "${e.project.id}.json").readBytes()
            var checks = 0
            check(runCatching { store.relink(e.project, clip.path, source) { ++checks > 20 } }.isFailure)
            check(File(root, "${e.project.id}.json").readBytes().contentEquals(before))
            check(source.isFile)
            check(store.media.listFiles()!!.none { it.name.startsWith(".repair-") })
        }
        test("startup only removes abandoned marked transfers and keeps published history assets") {
            val isolated = ProjectStore(File(root, "cleanup"))
            fun folder(name: String, owner: String, marker: String = ".myrec-copy") =
                File(isolated.media, name).apply { mkdirs(); File(this, marker).writeText(owner) }
            val unmarked = File(isolated.media, "unmarked").apply { mkdirs(); File(this, "keep").writeText("user file") }
            val abandoned = folder(".copy-abandoned", "missing-project")
            val active = folder(".restore-active", "active-project", ".myrec-restore")
            val activeTarget = File(isolated.media, "active-project")
            ProjectAssets.begin(active, activeTarget)
            val published = folder("published", "published-project", ".myrec-repair")
            val source = File(published, "take.mp4").apply { writeText("published original") }
            val clip = Clip(path = source.absolutePath, sourceOut = 2.0)
            val e = Editor(Project(id = "published-project", clips = mutableListOf(clip)))
            e.selected.add(clip.id); e.deleteSelection(false)
            isolated.save(e)
            val future = folder(".restore-future", "future-project", ".myrec-restore")
            File(isolated.root, "future-project.json").writeText("{\"version\":999,\"id\":\"future-project\"}")
            check(isolated.cleanTransfers() == 1)
            check(!abandoned.exists() && active.isDirectory && future.isDirectory && unmarked.isDirectory)
            check(source.isFile && !File(published, ".myrec-repair").exists())
            check(isolated.problems.isNotEmpty())
            ProjectAssets.end(active, activeTarget)
            check(isolated.cleanTransfers() == 1 && !active.exists())
            check(source.isFile && unmarked.isDirectory && future.isDirectory)
        }
        test("saving a live edit repairs damaged cached revisions and retains the evidence") {
            val e = Editor(Project(clips = mutableListOf(source("live-checksum.mp4"))))
            store.save(e)
            e.setGain(-3f); store.save(e)
            e.setGain(-6f); store.save(e)
            val index = JSONObject(File(root, "${e.project.id}.json").readText())
            val hash = index.getJSONArray("undo").getString(0)
            val revision = File(root, "revisions/${e.project.id}/$hash.json")
            revision.writeText("")
            e.setGain(-9f); store.save(e)
            val reopened = ProjectStore(root).editor(e.project)
            repeat(3) { reopened.undo() }
            check(reopened.project.clips.single().gainDb == 0f)
            check(File(root, "damaged/${e.project.id}").listFiles()!!.any { it.length() == 0L })
        }
        test("project versions cannot silently coerce malformed types to legacy schemas") {
            val encoded = store.encode(Project())
            for (version in listOf("2", "invalid", 1.5, JSONObject.NULL))
                check(runCatching { store.decode(JSONObject(encoded.toString()).put("version", version)) }.isFailure)
            check(store.decode(encoded).id == encoded.getString("id"))
        }
        test("damaged revision is recovered without silently accepting unchecked history") {
            val e = Editor(Project(clips = mutableListOf(source("checksum.mp4"))))
            store.save(e)
            e.setGain(-3f); store.save(e)
            val index = JSONObject(File(root, "${e.project.id}.json").readText())
            val damaged = File(root, "revisions/${e.project.id}/${index.getString("state")}.json")
            damaged.writeText("bad revision")
            val recoveredStore = ProjectStore(root)
            val restored = recoveredStore.editor(e.project)
            check(restored.project.clips.single().gainDb == 0f)
            check(recoveredStore.problems.isNotEmpty())
            check(damaged.readText() == "bad revision")
        }
    }
}
