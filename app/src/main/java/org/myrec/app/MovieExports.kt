package org.myrec.app

import android.os.Build
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

class MovieExports(private val root: File) {
    data class Movie(val file: File, val reused: Boolean, val editedAudioPeak: Double? = null)
    internal data class Ticket(val project: String, val key: String, val inputs: JSONObject)

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw InterruptedIOException("Export cancelled")
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hash(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    private fun hash(file: File, cancelled: () -> Boolean): String {
        check(file.isFile) { "An export source is missing: ${file.name}" }
        val length = file.length(); val stamp = file.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(131072)
            while (true) {
                checkCancelled(cancelled)
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        check(length == file.length() && stamp == file.lastModified()) { "A file changed during export verification" }
        return hex(digest.digest())
    }
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
    private fun editKey(store: ProjectStore, project: Project) = hash(canonical(store.encode(project).apply {
        remove("name"); remove("captureCount"); remove("movieSettings")
    }))

    internal fun ticket(store: ProjectStore, project: Project, width: Int, height: Int,
                        color: ExportColor, cancelled: () -> Boolean = { false }, fps: Int = project.fps): Ticket {
        require(project.id.matches(projectId) && width > 0 && height > 0)
        val files = JSONArray()
        for (path in ProjectAssets.paths(EditSession(project)).sorted()) {
            val file = File(path)
            val checksum = hash(file, cancelled)
            val trusted = store.trustedHash(project.id, path)
            check(trusted == null || trusted == checksum) { "An original has changed; repair it before exporting" }
            files.put(JSONObject().put("path", path).put("bytes", file.length()).put("sha256", checksum))
            for (suffix in listOf(".mirror", ".primaries")) {
                val sidecar = File(path + suffix)
                files.put(JSONObject().put("path", sidecar.path).put("sha256", if (sidecar.isFile) hash(sidecar, cancelled) else JSONObject.NULL))
            }
        }
        val models = JSONObject()
        for (kind in project.clips.flatMap { it.effects }.filter { it.enabled && it.kind in AudioModels.neural }.map { it.kind }.distinct().sorted())
            models.put(kind, AudioModels.version(kind))
        val inputs = JSONObject().put("version", 1)
            // Bump this when changing render math, bundled fonts or processing runtimes.
            .put("renderer", "myrec-movie-7").put("platform", Build.FINGERPRINT)
            .put("width", width).put("height", height).put("color", color.name)
            .put("edit", editKey(store, project)).put("sources", files).put("models", models)
        if (fps != project.fps) inputs.put("fps", fps)
        if (project.transitions.any { it.controls!=null }) inputs.put("transitionRenderer", 2)
        if (project.clips.any { clip -> clip.effects.any { it.enabled && StabilizationSettings.modern(it) &&
                StabilizationSettings.level(it) != StabilizationLevel.Off } }) inputs.put("stabilization", StabilizationRenderer.VERSION)
        return Ticket(project.id, hash(canonical(inputs)), inputs)
    }
    private fun directory(project: String) = File(root, project).apply {
        check(isDirectory || mkdirs()) { "Cannot create the movie export directory" }
    }
    private fun retire(folder: File, current: String) {
        for (receipt in folder.listFiles().orEmpty()) {
            val key = receipt.name.removeSuffix(".json")
            if (key == current || !receipt.name.endsWith(".json") || !key.matches(Regex("[a-f0-9]{64}")) || receipt.length() > 1048576) continue
            try {
                val info = JSONObject(receipt.readText())
                if (info.getInt("version") == 1 && info.getString("key") == key) {
                    File(folder, "$key.mp4").delete(); receipt.delete()
                }
            } catch (_: Exception) {}
        }
    }
    private fun verify(file: File, project: Project, width: Int, height: Int, fps: Int, cancelled: () -> Boolean) {
        checkCancelled(cancelled)
        val clip = ProjectStore.inspect(file)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            check(format.getInteger(MediaFormat.KEY_WIDTH) == width && format.getInteger(MediaFormat.KEY_HEIGHT) == height) { "The exported movie has the wrong size" }
        } finally { extractor.release() }
        check(clip.video && clip.audio &&
            abs(clip.duration - project.duration) <= max(.12, 2.0 / fps)) { "The exported movie is incomplete" }
        VideoDecoder(file.path).use { decoder ->
            for (time in listOf(0.0, (project.duration - 1.0 / fps).coerceAtLeast(0.0))) {
                checkCancelled(cancelled)
                (decoder.frame(time, 96, 54) ?: error("The exported movie cannot be decoded")).recycle()
            }
        }
    }
    private fun find(ticket: Ticket, project: Project, width: Int, height: Int, fps: Int, cancelled: () -> Boolean): Movie? {
        val folder = directory(ticket.project)
        val receipt = File(folder, "${ticket.key}.json")
        if (!receipt.isFile || receipt.length() !in 1..1048576) return null
        try {
            val info = JSONObject(receipt.readText())
            if (info.getInt("version") != 1 || info.getString("key") != ticket.key) return null
            val file = File(folder, "${ticket.key}.mp4")
            if (!file.isFile || file.length() != info.getLong("bytes") || hash(file, cancelled) != info.getString("sha256")) return null
            verify(file, project, width, height, fps, cancelled)
            return Movie(file,true,info.optDouble("editedAudioPeak").takeIf { it.isFinite() && it>=1.0592537 })
        } catch (e: InterruptedIOException) { throw e }
        catch (_: Exception) { return null }
    }
    fun movie(store: ProjectStore, project: Project, width: Int, height: Int, color: ExportColor,
              cancelled: () -> Boolean = { false }, progress: (Float) -> Unit = {},
              editedAudioPeak: () -> Double? = { null },
              render: (File, (Float) -> Unit) -> Unit): Movie =
        movie(store, project, width, height, color, project.fps, false, cancelled, progress, editedAudioPeak) { file, _, report -> render(file, report) }

    internal fun resumable(store: ProjectStore, project: Project, width: Int, height: Int, color: ExportColor,
                           fps: Int, cancelled: () -> Boolean, progress: (Float) -> Unit,
                           editedAudioPeak: () -> Double? = { null },
                           render: (File, MovieCheckpoint?, (Float) -> Unit) -> Unit): Movie =
        movie(store, project, width, height, color, fps, true, cancelled, progress, editedAudioPeak, render)

    private fun checkpoints(project: String) = File(root, project).listFiles().orEmpty().filter {
        it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}\\.resume"))
    }

    internal fun discard(project: String) = synchronized(publishing) {
        checkpoints(project).forEach { it.deleteRecursively() }
    }

    internal fun hasCheckpoint(store: ProjectStore, project: Project, width: Int, height: Int, color: ExportColor, fps: Int): Boolean {
        val folders = checkpoints(project.id)
        if (folders.isEmpty()) return false
        val edit = editKey(store, project)
        return folders.any { folder ->
            runCatching {
                val file = File(folder, "settings.json")
                if (file.length() !in 1..2048) return@runCatching false
                val marker = JSONObject(file.readText())
                marker.getInt("width") == width && marker.getInt("height") == height && marker.getString("color") == color.name &&
                    marker.getInt("fps") == fps && marker.getString("edit") == edit
            }.getOrDefault(false)
        }
    }

    private fun movie(store: ProjectStore, project: Project, width: Int, height: Int, color: ExportColor,
                      fps: Int, resume: Boolean, cancelled: () -> Boolean, progress: (Float) -> Unit,
                      editedAudioPeak: () -> Double?,
                      render: (File, MovieCheckpoint?, (Float) -> Unit) -> Unit): Movie = synchronized(publishing) {
        checkCancelled(cancelled)
        val ticket = ticket(store, project, width, height, color, cancelled, fps)
        val folder = directory(ticket.project)
        for (file in folder.listFiles().orEmpty()) if (file.isFile && abandoned.matches(file.name)) file.delete()
        find(ticket, project, width, height, fps, cancelled)?.let {
            if (resume) discard(project.id)
            checkCancelled(cancelled); progress(1f); return@synchronized it
        }
        val file = File(folder, "${ticket.key}.mp4")
        val published = File(folder, "${ticket.key}.json")
        if (!published.exists() && file.isFile) file.delete()
        val partial = File(folder, ".${ticket.key}-${uid()}.partial.mp4")
        val receipt = File(folder, ".${ticket.key}-${uid()}.partial.json")
        try {
            val checkpoint = if (resume) {
                val directory = File(folder, "${ticket.key}.resume")
                check(directory.isDirectory || directory.mkdir()) { "Cannot save export progress" }
                val saved = MovieCheckpoint(directory)
                saved.prepare(JSONObject().put("width", width).put("height", height)
                    .put("fps", fps).put("color", color.name).put("edit", ticket.inputs.getString("edit")))
                checkpoints(project.id).filter { it != directory }.forEach { it.deleteRecursively() }
                saved
            } else null
            render(partial, checkpoint) { progress(it * .9f) }
            verify(partial, project, width, height, fps, cancelled)
            check(ticket.key == ticket(store, project, width, height, color, cancelled, fps).key) { "A source changed while the movie was rendering; export again" }
            val checksum = hash(partial, cancelled)
            progress(.95f)
            FileOutputStream(partial, true).use { it.fd.sync() }
            val info = JSONObject().put("version", 1).put("key", ticket.key).put("project", project.id)
                .put("created", System.currentTimeMillis()).put("inputs", ticket.inputs)
                .put("bytes", partial.length()).put("sha256", checksum)
            val peak=editedAudioPeak()
            peak?.let { info.put("editedAudioPeak",it) }
            FileOutputStream(receipt).use { out -> out.write(info.toString().toByteArray(Charsets.UTF_8)); out.fd.sync() }
            checkCancelled(cancelled)
            check(partial.renameTo(file) && receipt.renameTo(published)) { "Cannot publish the completed export" }
            syncDirectory(folder)
            retire(folder, ticket.key)
            if (resume) discard(project.id)
            progress(1f)
            Movie(file,false,peak)
        } finally { partial.delete(); receipt.delete() }
    }

    private companion object {
        val publishing = Any()
        val projectId = Regex("[A-Za-z0-9-]{1,100}")
        val abandoned = Regex("\\.[a-f0-9]{64}-[a-f0-9-]{36}\\.partial\\.(json|mp4(\\.video\\.mp4|\\.audio\\.m4a)?)")
    }
}
