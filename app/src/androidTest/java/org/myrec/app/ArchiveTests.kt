package org.myrec.app

import android.app.Instrumentation
import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

object ArchiveTests {
    fun run(instrumentation: Instrumentation, test: (String, () -> Unit) -> Unit) {
        val root = File(instrumentation.targetContext.cacheDir, "archives-${uid()}")
        val store = ProjectStore(root)
        fun fixture(name: String): Clip {
            val file = File(store.media, name)
            instrumentation.context.assets.open("fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
            return ProjectStore.inspect(file)
        }
        fun digest(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        fun rewrite(source: File, name: String, change: (MutableMap<String, ByteArray>) -> Unit): File {
            val entries = linkedMapOf<String, ByteArray>()
            ZipFile(source).use { zip -> for (entry in zip.entries())
                entries[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
            }
            change(entries)
            return File(root, name).also { file -> ZipOutputStream(file.outputStream()).use { zip ->
                for ((key, data) in entries) { zip.putNextEntry(ZipEntry(key)); zip.write(data); zip.closeEntry() }
            } }
        }
        val first = fixture("first.mp4")
        val second = fixture("same-bytes-separate-take.mp4")
        File(first.path + ".mirror").writeText("horizontal")
        File(first.path + ".primaries").writeText("1")
        val picture = File(store.media, "overlay.png").apply { writeText("image used only by redo") }
        val editor = Editor(Project(name = "Portable project"))
        editor.appendRecording(first)
        editor.appendRecording(second)
        editor.applyEffect(Effect(kind = "Image", values = mutableMapOf("path" to picture.absolutePath)))
        editor.undo()
        store.save(editor)
        val originalBytes = File(first.path).readBytes()
        val archive = File(root, "project.zip")
        test("project archive preserves all sources history redo assets and sidecars") {
            ProjectArchive.export(store, editor.project, archive)
            ZipFile(archive).use { zip ->
                check(zip.getEntry("Timeline.otio") != null)
                val integrity = JSONObject(zip.getInputStream(zip.getEntry("integrity.json")).bufferedReader().readText()).getJSONObject("files")
                for (name in integrity.keys()) {
                    val data = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                    check(digest(data) == integrity.getJSONObject(name).getString("sha256"))
                }
                val project = zip.getInputStream(zip.getEntry("project.json")).bufferedReader().readText()
                check(!project.contains(store.root.absolutePath))
            }
            val restored = ProjectArchive.restore(store, archive)
            check(restored.missing.isEmpty() && restored.project.id != editor.project.id)
            val reopened = store.editor(restored.project)
            check(reopened.project.clips.size == 2 && reopened.session().captures.size == 2)
            check(reopened.session().originals.size == 2)
            check(reopened.project.clips.map { it.path }.distinct().size == 2)
            val path = reopened.project.clips.first().path
            check(path != first.path && File(path).readBytes().contentEquals(originalBytes))
            check(File(path + ".mirror").readText() == "horizontal")
            check(File(path + ".primaries").readText() == "1")
            reopened.redo()
            val image = reopened.project.clips.flatMap { it.effects }.single().values.getValue("path")
            check(image != picture.absolutePath && File(image).readText() == picture.readText())
            reopened.undo()
            check(reopened.project.clips.all { it.effects.isEmpty() })
            check(File(first.path).readBytes().contentEquals(originalBytes))
        }
        test("missing archived originals retain the complete editable project and history") {
            var missingName = ""
            val broken = rewrite(archive, "missing.zip") { files ->
                missingName = files.keys.first { it.startsWith("Media/") && it.endsWith(".mp4") }
                files.remove(missingName)
            }
            val restored = ProjectArchive.restore(store, broken)
            check(missingName in restored.missing)
            val reopened = store.editor(restored.project)
            check(reopened.project.clips.size == 2 && reopened.canRedo)
            check(reopened.project.clips.any { !File(it.path).isFile })
            reopened.redo()
            check(reopened.project.clips.flatMap { it.effects }.single().kind == "Image")
            val missing = reopened.project.clips.single { !File(it.path).isFile }.path
            val repaired = store.relink(restored.project, missing, File(first.path))
            val opened = store.editor(restored.project)
            check(opened.canRedo && File(repaired).readBytes().contentEquals(originalBytes))
            check(ProjectAssets.paths(opened.session()).none { it == missing })
        }
        test("damaged archived originals are quarantined without discarding history") {
            val broken = rewrite(archive, "damaged-source.zip") { files ->
                val name = files.keys.first { it.startsWith("Media/") && it.endsWith(".mp4") }
                val data = files.getValue(name).clone()
                data[data.size / 2] = (data[data.size / 2].toInt() xor 1).toByte()
                files[name] = data
            }
            val restored = ProjectArchive.restore(store, broken)
            check(restored.missing.size == 1)
            check(store.editor(restored.project).canRedo)
            check(File(store.media, "${restored.project.id}/damaged").listFiles()!!.size == 1)
        }
        test("archive source identity cannot disagree with its integrity checksum") {
            val broken = rewrite(archive, "wrong-identity.zip") { files ->
                val name = files.keys.first { it.startsWith("Media/") && it.endsWith(".mp4") }
                val changed = files.getValue(name).clone()
                changed[changed.size / 2] = (changed[changed.size / 2].toInt() xor 1).toByte()
                files[name] = changed
                val integrity = JSONObject(String(files.getValue("integrity.json"), Charsets.UTF_8))
                integrity.getJSONObject("files").getJSONObject(name).put("sha256", digest(changed))
                files["integrity.json"] = integrity.toString().toByteArray()
            }
            val before = store.list().map { it.id }.toSet()
            check(runCatching { ProjectArchive.restore(store, broken) }.isFailure)
            check(store.list().map { it.id }.toSet() == before)
            check(store.media.listFiles()!!.none { it.name.startsWith(".restore-") })
        }
        test("archive metadata corruption is rejected without publishing a partial project") {
            val before = store.list().map { it.id }.toSet()
            val broken = rewrite(archive, "bad-metadata.zip") { files -> files["history.json"] = "corrupt".toByteArray() }
            check(runCatching { ProjectArchive.restore(store, broken) }.isFailure)
            check(store.list().map { it.id }.toSet() == before)
            check(store.media.listFiles()!!.none { it.name.startsWith(".restore-") })
        }
        test("unsafe zip paths are rejected without extracting outside owned staging") {
            val malicious = rewrite(archive, "unsafe.zip") { files -> files["../escaped.txt"] = "bad".toByteArray() }
            check(runCatching { ProjectArchive.restore(store, malicious) }.isFailure)
            check(!File(root, "escaped.txt").exists() && !File(store.media, "escaped.txt").exists())
        }
        test("future archived schemas are refused even with matching integrity hashes") {
            val future = rewrite(archive, "future.zip") { files ->
                val changed = JSONObject(String(files.getValue("project.json"))).put("version", 999).toString().toByteArray()
                files["project.json"] = changed
                val integrity = JSONObject(String(files.getValue("integrity.json")))
                integrity.getJSONObject("files").put("project.json", JSONObject().put("bytes", changed.size).put("sha256", digest(changed)))
                files["integrity.json"] = integrity.toString().toByteArray()
            }
            check(runCatching { ProjectArchive.restore(store, future) }.exceptionOrNull() is NewerProject)
        }
        test("cancelled archive transfers clean only their own unpublished staging") {
            val output = File(root, "cancelled.zip")
            check(runCatching { ProjectArchive.export(store, editor.project, output, { true }) }.exceptionOrNull() is InterruptedIOException)
            check(!output.exists())
            check(runCatching { ProjectArchive.restore(store, archive, { true }) }.exceptionOrNull() is InterruptedIOException)
            check(store.media.listFiles()!!.none { it.name.startsWith(".restore-") })
            check(File(first.path).readBytes().contentEquals(originalBytes))
        }
        test("archive export refuses overwriting an existing completed file") {
            val before = archive.readBytes()
            check(runCatching { ProjectArchive.export(store, editor.project, archive) }.isFailure)
            check(archive.readBytes().contentEquals(before))
        }
        test("OTIO source conform keeps rates gaps marks and simple dissolves") {
            val a = Clip(path = "Media/a.mp4", sourceOut = 4.0, marks = mutableListOf(.5))
            val b = Clip(path = "Media/b.mp4", sourceOut = 4.0, start = 3.0)
            val p = Project(clips = mutableListOf(a, b), transitions = mutableListOf(
                Transition(at = 3.5, duration = 1.0, leftId = a.id, rightId = b.id, overlap = 1.0)))
            val otio = EditorialTimeline.encode(p)
            check(otio.getString("OTIO_SCHEMA") == "Timeline.1")
            val stack = otio.getJSONObject("tracks")
            val tracks = stack.getJSONArray("children")
            val video = (0 until tracks.length()).map { tracks.getJSONObject(it) }.single { it.getString("kind") == "Video" }
            val clips = video.getJSONArray("children")
            check(clips.length() == 3)
            check(clips.getJSONObject(1).getString("OTIO_SCHEMA") == "Transition.1")
            check(clips.getJSONObject(1).getJSONObject("in_offset").getDouble("value") == 15.0)
            check(clips.getJSONObject(2).getJSONObject("source_range").getJSONObject("start_time").getDouble("value") == 15.0)
            check(stack.getJSONArray("markers").length() == 1)
            val fast = Project(clips = mutableListOf(Clip(path = "Media/a.mp4", sourceOut = 4.0, start = 1.0, rate = 2.0)))
            val lanes = EditorialTimeline.encode(fast).getJSONObject("tracks").getJSONArray("children")
            val lane = (0 until lanes.length()).map { lanes.getJSONObject(it) }.single { it.getString("kind") == "Video" }
            val runs = lane.getJSONArray("children")
            check(runs.getJSONObject(0).getString("OTIO_SCHEMA") == "Gap.1")
            val clip = runs.getJSONObject(1)
            check(clip.getJSONArray("effects").getJSONObject(0).getDouble("time_scalar") == 2.0)
            check(clip.getJSONObject("source_range").getJSONObject("duration").getDouble("value") == 60.0)
        }
    }
}
