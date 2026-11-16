package org.myrec.app

import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

object ProjectArchive {
    private const val METADATA = 64L * 1024 * 1024
    private const val RESERVE = 64L * 1024 * 1024
    private val mediaName = Regex("Media/[0-9a-f]{64}(?:-[0-9a-f]{16})?\\.[A-Za-z0-9]{1,12}(?:\\.(?:mirror|primaries))?")
    private val historyName = Regex("History/[0-9a-f]{64}\\.json")

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hash(data: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(data))
    private fun hash(file: File, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled(cancelled)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }
    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw InterruptedIOException("Project transfer cancelled")
    }
    private fun allowed(name: String) = name in listOf("project.json", "history.json", "integrity.json", "Timeline.otio") ||
        mediaName.matches(name) || historyName.matches(name)

    fun export(store: ProjectStore, project: Project, target: File,
        cancelled: () -> Boolean = { false }, progress: (Float) -> Unit = {}) {
        check(!target.exists()) { "Choose a new archive filename" }
        val saved = store.open(project.id)
        val files = linkedMapOf<String, File>()
        val paths = linkedMapOf<String, String>()
        val assetPaths = ProjectAssets.paths(saved)
        for ((i, path) in assetPaths.withIndex()) {
            checkCancelled(cancelled)
            val file = File(path)
            check(file.isFile) { "An original or image is missing: ${file.name}" }
            val suffix = file.extension.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "media"
            val identity = i.toLong().toString(16).padStart(16, '0')
            val digest = hash(file, cancelled)
            val expected = store.trustedHash(project.id, path)
            check(expected == null || digest == expected) { "An original has changed; repair it before exporting" }
            val name = "Media/$digest-$identity.$suffix"
            files[name] = file
            paths[path] = name
            for (side in listOf(".mirror", ".primaries")) {
                val sidecar = File(path + side)
                if (sidecar.isFile) files[name + side] = sidecar
            }
            progress((i + 1f) / assetPaths.size.coerceAtLeast(1) * .1f)
        }
        val portable = ProjectAssets.remap(saved, saved.project.id, saved.project.name) { paths.getValue(it) }
        ArchiveMetadata(target.parentFile!!,METADATA).use { metadata ->
            fun state(p: Project) = store.encode(p).toString().toByteArray(Charsets.UTF_8)
            fun history(items: List<EditRevision>) = JSONArray().apply {
                for (item in items) {
                    val data = state(item.project)
                    val name = "History/${hash(data)}.json"
                    metadata[name] = data
                    put(name)
                }
            }
            fun clips(items: List<Clip>) = store.encode(Project(id = "media", clips = items.toMutableList())).getJSONArray("clips")
            metadata["project.json"] = state(portable.project)
            metadata["history.json"] = JSONObject().apply {
                put("format", "MyRec project"); put("version", 1)
                put("undo", history(portable.undo)); put("redo", history(portable.redo))
                put("captures", clips(portable.captures)); put("originals", clips(portable.originals))
            }.toString().toByteArray(Charsets.UTF_8)
            metadata["Timeline.otio"] = EditorialTimeline.encode(portable.project,portable.originals,store.encode(portable.project)).toString(2).toByteArray(Charsets.UTF_8)
            require(metadata.count+files.size+1<=100000) { "Too many archive entries" }
            val integrity = JSONObject()
            val partial = File(target.parentFile, ".archive-${uid()}.partial")
            val total = files.values.sumOf { it.length() }.coerceAtLeast(1)
            var written = 0L
            try {
                FileOutputStream(partial).use { raw -> ZipOutputStream(raw).use { zip ->
                    fun entry(name: String, size: Long, write: (MessageDigest) -> Unit): String {
                        checkCancelled(cancelled)
                        zip.putNextEntry(ZipEntry(name))
                        val digest = MessageDigest.getInstance("SHA-256")
                        write(digest)
                        zip.closeEntry()
                        val sum=hex(digest.digest())
                        integrity.put(name,JSONObject().put("bytes",size).put("sha256",sum));return sum
                    }
                    for ((name, staged) in metadata) {
                        val actual=entry(name,staged.file.length()) { digest ->
                            staged.file.inputStream().use { input ->
                                val buffer=ByteArray(131072)
                                while (true) { checkCancelled(cancelled);val n=input.read(buffer);if (n<0) break;zip.write(buffer,0,n);digest.update(buffer,0,n) }
                            }
                        }
                        check(actual==staged.hash) { "Staged archive metadata changed" }
                    }
                    for ((name, file) in files) {
                        val size = file.length()
                        entry(name, size) { digest ->
                            var count = 0L
                            file.inputStream().use { input ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    checkCancelled(cancelled)
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    zip.write(buffer, 0, n); digest.update(buffer, 0, n)
                                    written += n; count += n
                                    progress(.1f + .9f * written / total)
                                }
                            }
                            check(count == size) { "A source changed during export" }
                        }
                        if (!name.endsWith(".mirror") && !name.endsWith(".primaries"))
                            check(integrity.getJSONObject(name).getString("sha256") == name.substringAfter("Media/").substringBefore('.').substringBefore('-')) {
                                "A source changed during export"
                            }
                    }
                    val receipt=JSONObject().put("version",1).put("files",integrity).toString().toByteArray()
                    require(metadata.bytes+receipt.size<=METADATA) { "Editing history exceeds the archive metadata limit" }
                    zip.putNextEntry(ZipEntry("integrity.json"))
                    zip.write(receipt)
                    zip.closeEntry(); zip.finish(); raw.fd.sync()
                } }
                check(partial.renameTo(target)) { "Cannot finish project archive" }
            } finally { partial.delete() }
        }
    }

    data class Restored(val project: Project, val missing: List<String>)

    fun restore(store: ProjectStore, archive: File, cancelled: () -> Boolean = { false },
        progress: (Float) -> Unit = {}): Restored {
        if (YouRecArchive.native(archive)) return YouRecArchive.restore(store,archive,cancelled,progress)
        val id = uid()
        val working = File(store.media, ".restore-$id")
        val destination = File(store.media, id)
        check(!working.exists() && working.mkdirs()) { "Cannot prepare project restore" }
        ProjectAssets.begin(working, destination)
        var published = false
        val missing = mutableListOf<String>()
        try {
            FileOutputStream(File(working, ".myrec-restore")).use { it.write(id.toByteArray()); it.fd.sync() }
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size <= 100000) { "Too many archive entries" }
                var metadataBytes = 0L
                for (entry in entries.filter { !mediaName.matches(it.name) }) {
                    require(entry.size in 1..METADATA && entry.size <= METADATA - metadataBytes) {
                        "This archive's editing history exceeds the metadata limit"
                    }
                    metadataBytes += entry.size
                }
                val names = mutableSetOf<String>()
                for (entry in entries) require(!entry.isDirectory && allowed(entry.name) &&
                    names.add(entry.name.lowercase(Locale.ROOT))) { "Unexpected or duplicate archive entry" }
                fun read(name: String): ByteArray {
                    val entry = zip.getEntry(name) ?: error("Missing archive metadata: $name")
                    require(entry.size in 1..METADATA) { "Invalid archive metadata size" }
                    return zip.getInputStream(entry).use { input ->
                        val data = ByteArray(entry.size.toInt())
                        var read = 0
                        while (read < data.size) {
                            checkCancelled(cancelled)
                            val n = input.read(data, read, data.size - read)
                            require(n > 0) { "Truncated archive metadata" }
                            read += n
                        }
                        require(input.read() == -1) { "Oversized archive metadata" }
                        data
                    }
                }
                val integrity = JSONObject(String(read("integrity.json"), Charsets.UTF_8))
                require(integrity.getInt("version") == 1) { "Unknown archive integrity version" }
                val checked = integrity.getJSONObject("files")
                val expected = checked.keys().asSequence().toList()
                require(expected.size <= 100000 && expected.all { allowed(it) && it != "integrity.json" }) { "Invalid integrity index" }
                require(entries.all { it.name == "integrity.json" || checked.has(it.name) }) { "Unchecked archive entry" }
                val objects = mutableMapOf<String, JSONObject>()
                fun metadata(name: String): JSONObject {
                    objects[name]?.let { return it }
                    val data = read(name)
                    val spec = checked.getJSONObject(name)
                    require(data.size.toLong() == spec.getLong("bytes") && hash(data) == spec.getString("sha256")) {
                        "Damaged project metadata: $name"
                    }
                    if (historyName.matches(name)) require(hash(data) == name.substringAfter("History/").substringBefore('.'))
                    return JSONObject(String(data, Charsets.UTF_8)).also { objects[name] = it }
                }
                for (name in expected.filter { !mediaName.matches(it) }) metadata(name)
                val p = store.decode(metadata("project.json"))
                val history = metadata("history.json")
                require(history.getString("format") == "MyRec project" && history.getInt("version") == 1) {
                    "Unsupported project archive"
                }
                fun revisions(key: String): List<EditRevision> {
                    val list = history.getJSONArray(key)
                    require(list.length() <= 100000)
                    return (0 until list.length()).map { i ->
                        val name = list.getString(i)
                        require(historyName.matches(name)) { "Invalid archived history reference" }
                        EditRevision(store.decode(metadata(name)))
                    }
                }
                fun clips(key: String) = store.decode(JSONObject().put("id", "media").put("name", "Originals")
                    .put("clips", history.getJSONArray(key))).clips
                val session = EditSession(p, revisions("undo"), revisions("redo"), clips("captures"), clips("originals"))
                require(ProjectAssets.states(session).all { it.id == p.id }) { "Mixed project identities" }
                val paths = ProjectAssets.paths(session)
                require(paths.all { mediaName.matches(it) && checked.has(it) }) { "Invalid archived media reference" }
                val budget = (working.usableSpace - RESERVE).coerceAtLeast(0)
                var total = 0L
                val media = expected.filter { mediaName.matches(it) }
                for ((i, name) in media.withIndex()) {
                    checkCancelled(cancelled)
                    val spec = checked.getJSONObject(name)
                    val size = spec.getLong("bytes")
                    if (!name.endsWith(".mirror") && !name.endsWith(".primaries"))
                        require(spec.getString("sha256") == name.substringAfter("Media/").take(64)) {
                            "An archived source has inconsistent identity checksums"
                        }
                    require(size >= 0 && size <= budget - total) { "Not enough free storage to restore this project" }
                    val entry = zip.getEntry(name)
                    if (entry == null) { missing.add(name); continue }
                    if (entry.size != size) { missing.add(name); continue }
                    val file = File(working, name)
                    file.parentFile!!.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    try {
                        zip.getInputStream(entry).use { input -> FileOutputStream(file).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                checkCancelled(cancelled)
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n; total += n
                                require(count <= size && total <= budget) { "Archive expands beyond its declared size" }
                                output.write(buffer, 0, n); digest.update(buffer, 0, n)
                            }
                            output.fd.sync()
                        } }
                        require(count == size && hex(digest.digest()) == spec.getString("sha256")) { "Damaged original" }
                    } catch (cancel: InterruptedIOException) { throw cancel }
                    catch (e: Exception) {
                        val damaged = File(working, "damaged/${file.name}")
                        damaged.parentFile!!.mkdirs()
                        if (file.exists()) check(file.renameTo(damaged)) { "Cannot retain damaged original" }
                        missing.add(name)
                    }
                    progress((i + 1f) / media.size.coerceAtLeast(1))
                }
                store.publishMedia(working, destination)
                val restored = ProjectAssets.remap(session, id, p.name) { path ->
                    require(mediaName.matches(path))
                    File(destination, path).absolutePath
                }
                store.save(restored, false)
                published = true
                File(destination, ".myrec-restore").delete()
                if (missing.isNotEmpty()) store.problems[id] =
                    "Restored ${p.name} with ${missing.size} missing or damaged media files. Editing history was retained."
                return Restored(restored.project, missing)
            }
        } finally {
            ProjectAssets.end(working, destination)
            if (!published && !File(store.root, "$id.json").exists()) {
                if (File(working, ".myrec-restore").isFile) working.deleteRecursively()
                if (File(destination, ".myrec-restore").isFile) destination.deleteRecursively()
            }
        }
    }
}
