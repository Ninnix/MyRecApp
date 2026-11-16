package org.myrec.app

import android.media.MediaMetadataRetriever
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

data class CaptureReceipt(val id: String, val project: String, val filename: String,
                          val insertion: Double? = null, val layer: Boolean = false)

class NewerProject : IOException("This project was saved by a newer MyRec. Its files have been kept.")

class ProjectStore(val root: File) {
    init { directory(root) }

    val media = File(root, "media").apply { directory(this) }
    val problems = ConcurrentHashMap<String, String>()
    private val deleted = File(root, "deleted")
    private val knownIndexes = ConcurrentHashMap.newKeySet<String>()
    private val stateCache = RevisionCache()

    private fun id(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Invalid project identity" }
        return value
    }

    private fun integer(o: JSONObject, key: String, fallback: Int): Long {
        val value = if (o.has(key)) o.get(key) else fallback
        require(value is Number && value.toDouble().isFinite() &&
            value.toDouble() == value.toLong().toDouble()) { "Invalid $key" }
        return value.toLong()
    }

    private fun checkVersion(o: JSONObject) {
        val version = integer(o, "version", 1)
        if (version > 11) throw NewerProject()
        require(version in 1L..11L) { "Invalid project version" }
    }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it) }

    private fun sync(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    private fun directory(file: File) {
        if (file.isDirectory) return
        val parent = file.parentFile ?: error("Cannot create project directory")
        directory(parent)
        check(file.mkdir() || file.isDirectory) { "Cannot create ${file.name}" }
        sync(parent)
    }

    private fun write(file: File, data: ByteArray) {
        directory(file.parentFile!!)
        val temp = File(file.parentFile, ".saving-${uid()}")
        try {
            FileOutputStream(temp).use { out -> out.write(data); out.fd.sync() }
            Os.rename(temp.absolutePath, file.absolutePath)
            sync(file.parentFile!!)
        } finally { temp.delete() }
    }

    private fun bytes(file: File): ByteArray {
        require(file.length() in 1..64L * 1024 * 1024) { "Invalid project file size" }
        return file.readBytes()
    }

    private fun revisions(project: String) = File(root, "revisions/${id(project)}")

    private fun revision(p: Project, cached: String? = null): String {
        if (cached != null) {
            val file = File(revisions(p.id), "$cached.json")
            val data = runCatching { bytes(file) }.getOrNull()
            if (data != null && sha(data) == cached) return cached
        }
        val data = encode(p).toString().toByteArray(Charsets.UTF_8)
        val hash = sha(data)
        val file = File(revisions(p.id), "$hash.json")
        val previous = runCatching { bytes(file) }.getOrNull()
        if (previous == null || !previous.contentEquals(data)) {
            if (file.exists()) preserve(file, p.id)
            write(file, data)
        }
        return hash
    }

    private fun clips(items: List<Clip>): JSONArray =
        encode(Project(id = "media", clips = items.toMutableList())).getJSONArray("clips")

    private fun clips(items: JSONArray?): List<Clip> =
        if (items == null) emptyList() else decode(JSONObject().apply {
            put("id", "media"); put("name", "Originals"); put("clips", items)
        }).clips

    private fun state(project: String, hash: String): Project {
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid history reference" }
        val data = bytes(File(revisions(project), "$hash.json"))
        check(sha(data) == hash) { "An editing-history file is damaged" }
        /* Verify disk bytes on every read; only decoding is reusable. */
        return (stateCache[hash] ?: decode(JSONObject(String(data, Charsets.UTF_8))).also {
            check(it.id == project) { "History belongs to another project" }
            stateCache.put(hash,it,data.size)
        }).also {
            check(it.id == project) { "History belongs to another project" }
        }
    }

    private fun read(file: File): EditSession {
        val data = bytes(file)
        val o = JSONObject(String(data, Charsets.UTF_8))
        checkVersion(o)
        val project = id(o.getString("id"))
        val backup = file.parentFile?.parentFile == File(root, "backups")
        check(if (backup) file.parentFile!!.name == project else file.name == "$project.json") {
            "Project index belongs to another folder"
        }
        if (!o.has("state")) return EditSession(decode(o)).also { knownIndexes.add(sha(data)) }
        val captures = clips(o.optJSONArray("captures"))
        fun load(hash: String) = state(project, hash).also {
            check(it.captureCount in 0..captures.size) { "Invalid recording history" }
        }
        fun history(key: String): List<EditRevision> {
            val array = o.optJSONArray(key) ?: return emptyList()
            require(array.length() <= 100000) { "Too many editing states" }
            return (0 until array.length()).map { i ->
                val hash = array.getString(i)
                EditRevision(load(hash), hash)
            }
        }
        val p = load(o.getString("state"))
        p.name = o.getString("name")
        val saved = EditSession(p, history("undo"), history("redo"), captures,
            clips(o.optJSONArray("originals")))
        knownIndexes.add(sha(data))
        return saved
    }

    private fun preserve(file: File, project: String) {
        if (file.exists()) write(File(root, "damaged/${id(project)}/${uid()}-${file.name}"), file.readBytes())
    }

    private fun backupOrder(file: File) = file.name.substringBefore('-', "").toLongOrNull() ?: file.lastModified()

    private fun backups(project: String) = File(root, "backups/${id(project)}")
        .listFiles()?.filter { it.extension == "json" }?.sortedWith(compareByDescending<File> { backupOrder(it) }.thenByDescending { it.name })
        ?: emptyList()

    private fun recovered(file: File): EditSession {
        try { return read(file) }
        catch (e: NewerProject) { throw e }
        catch (e: Exception) {
            val project = file.nameWithoutExtension
            for (backup in backups(project)) {
                try {
                    val saved = read(backup)
                    check(saved.project.id == project)
                    problems[project] = "Recovered ${saved.project.name} from an earlier project index. The damaged files have been kept."
                    return saved
                } catch (future: NewerProject) { throw future }
                catch (_: Exception) {}
            }
            throw IOException("Cannot open project $project: ${e.message}. Its files have been kept.", e)
        }
    }

    private fun backup(file: File) {
        if (!file.exists()) return
        val data = file.readBytes()
        val hash = sha(data)
        if (hash !in knownIndexes) {
            try { read(file) }
            catch (future: NewerProject) { throw future }
            catch (_: Exception) { preserve(file, file.nameWithoutExtension); return }
        }
        val previous = backups(file.nameWithoutExtension)
        val last = previous.maxOfOrNull { backupOrder(it) } ?: 0L
        check(last < Long.MAX_VALUE) { "Invalid recovery-index order" }
        val stamp = maxOf(System.currentTimeMillis(), last + 1)
        // Some Android filesystems truncate modification times. The durable
        // filename orders recovery copies independently of that precision.
        val copy = File(root, "backups/${file.nameWithoutExtension}/$stamp-$hash.json")
        val existing = previous.firstOrNull { it.name == "$hash.json" || it.name.endsWith("-$hash.json") }
        if (existing == null) write(copy, data)
        else { Os.rename(existing.absolutePath, copy.absolutePath); sync(copy.parentFile!!) }
        for (old in backups(file.nameWithoutExtension).drop(5)) check(old.delete()) { "Cannot rotate recovery copies" }
        sync(copy.parentFile!!)
    }

    @Synchronized
    fun save(editor: Editor, makeCurrent: Boolean = true) = save(editor.session(), makeCurrent)

    @Synchronized
    fun save(p: Project, makeCurrent: Boolean = true) =
        save(EditSession(p.duplicate().apply { captureCount = 0 }, originals = p.clips), makeCurrent)

    @Synchronized
    fun save(session: EditSession, makeCurrent: Boolean = true) {
        val saved = resolvedSources(session)
        val p = saved.project
        id(p.id)
        validate(p)
        require((listOf(p) + saved.undo.map { it.project } + saved.redo.map { it.project })
            .all { it.captureCount in 0..saved.captures.size }) { "Invalid recording history" }
        val target = File(root, "${p.id}.json")
        fun history(items: List<EditRevision>) = JSONArray().apply {
            for (item in items) {
                check(item.project.id == p.id) { "History belongs to another project" }
                val hash = revision(item.project, item.file)
                item.file = hash
                put(hash)
            }
        }
        val o = JSONObject().apply {
            put("version", 11); put("id", p.id); put("name", p.name)
            put("state", revision(p))
            put("undo", history(saved.undo)); put("redo", history(saved.redo))
            put("captures", clips(saved.captures)); put("originals", clips(saved.originals))
        }
        val data = o.toString().toByteArray(Charsets.UTF_8)
        if (!target.exists() || !target.readBytes().contentEquals(data)) {
            backup(target)
            write(target, data)
            knownIndexes.add(sha(data))
        }
        if (makeCurrent) {
            val current = File(root, "current")
            if (!current.exists() || current.readText() != p.id)
                write(current, p.id.toByteArray(Charsets.UTF_8))
        }
    }

    fun open(project: String): EditSession {
        val file = File(root, "${id(project)}.json")
        check(file.exists() || !File(deleted, "$project.tombstone").exists()) {
            "This project is in Recently deleted"
        }
        return resolvedSources(recovered(file))
    }

    fun currentSession(): EditSession {
        val pointer = File(root, "current")
        if (pointer.exists()) {
            try {
                val project = id(pointer.readText().trim())
                if (File(root, "$project.json").exists() ||
                    !File(deleted, "$project.tombstone").exists()) return open(project)
            }
            catch (e: Exception) { problems["current"] = e.message ?: "Cannot read the current project" }
        }
        val existing = list()
        return existing.firstOrNull()?.let { open(it.id) } ?: EditSession(Project())
    }

    fun current(): Project = currentSession().project

    fun editor(project: Project): Editor {
        val saved = open(project.id)
        return Editor(saved.project).also { it.load(saved.project, saved) }
    }

    fun rename(project: Project, name: String) {
        val saved = open(project.id)
        saved.project.name = name
        save(saved, false)
        project.name = name
    }

    private fun receiptFile(receipt: CaptureReceipt) = File(root, "pending/${id(receipt.id)}.json")

    private fun recordingFile(receipt: CaptureReceipt): File {
        require(receipt.filename == File(receipt.filename).name &&
            !receipt.filename.contains('\\') && !receipt.filename.startsWith('.')) { "Invalid take filename" }
        return File(media, receipt.filename)
    }

    fun beginCapture(project: Project, file: File, effects: List<Effect>, insertion: Double? = null,
                     layer: Boolean = false): CaptureReceipt {
        require(file.parentFile?.canonicalFile == media.canonicalFile)
        require(insertion == null || insertion.isFinite() && insertion >= 0 && insertion <= 604800)
        if (!File(root, "${id(project.id)}.json").exists()) save(project)
        val receipt = CaptureReceipt(uid(), project.id, file.name, insertion, layer)
        val o = JSONObject().apply {
            put("version", if (insertion == null) 1 else 2); put("id", receipt.id); put("project", receipt.project)
            insertion?.let { put("insertion", it); put("layer", layer) }
            put("filename", receipt.filename); put("complete", false)
            put("recipe", clips(listOf(Clip(id = receipt.id, path = file.absolutePath,
                sourceOut = 1.0, effects = effects.map { it.copyEffect(true) }.toMutableList()))))
        }
        write(receiptFile(receipt), o.toString().toByteArray())
        return receipt
    }

    @Synchronized
    fun checkpoint(receipt: CaptureReceipt, marks: List<Double>, effects: List<Effect>) {
        val file = receiptFile(receipt)
        if (!file.isFile) return
        val o = JSONObject(file.readText())
        if (o.optBoolean("complete")) return
        o.put("recipe", clips(listOf(Clip(id = receipt.id, path = recordingFile(receipt).absolutePath,
            sourceOut = 1.0, marks = marks.toMutableList(),
            effects = effects.map { it.copyEffect() }.toMutableList()))))
        write(file, o.toString().toByteArray())
    }

    @Synchronized
    fun finishCapture(receipt: CaptureReceipt, clip: Clip) {
        require(clip.id == receipt.id && clip.path == recordingFile(receipt).absolutePath)
        val file = receiptFile(receipt)
        val o = JSONObject(file.readText()).put("complete", true).put("recipe", clips(listOf(clip)))
        write(file, o.toString().toByteArray())
    }

    @Synchronized
    fun acknowledgeCapture(receipt: CaptureReceipt): Boolean {
        val saved = open(receipt.project)
        if (saved.originals.none { it.id == receipt.id || it.path == recordingFile(receipt).absolutePath }) return false
        val file = receiptFile(receipt)
        if (file.exists()) { check(file.delete()); sync(file.parentFile!!) }
        return true
    }

    fun abandonCapture(receipt: CaptureReceipt) {
        if (recordingFile(receipt).length() == 0L) {
            receiptFile(receipt).delete()
            sync(receiptFile(receipt).parentFile!!)
        }
    }

    fun recoverCaptures(): Int {
        var recovered = 0
        for (entry in File(root, "pending").listFiles()?.filter { it.extension == "json" } ?: emptyList()) {
            try {
                val o = JSONObject(String(bytes(entry), Charsets.UTF_8))
                require(o.getInt("version") in 1..2) { "Unknown recording receipt version" }
                val receipt = CaptureReceipt(id(o.getString("id")), id(o.getString("project")), o.getString("filename"),
                    if (o.getInt("version") >= 2) o.getDouble("insertion") else null, o.optBoolean("layer", false))
                require(receipt.insertion == null || receipt.insertion.isFinite() && receipt.insertion in 0.0..604800.0)
                require(entry.name == "${receipt.id}.json") { "Invalid recording receipt identity" }
                val file = recordingFile(receipt)
                val saved = open(receipt.project)
                if (saved.originals.any { it.id == receipt.id || it.path == file.absolutePath }) {
                    acknowledgeCapture(receipt)
                    continue
                }
                val source = inspect(file).copy(id = receipt.id, group = receipt.id, occurrence = receipt.id)
                val recipe = clips(o.optJSONArray("recipe")).singleOrNull()
                recipe?.let {
                    if (o.optBoolean("complete")) {
                        source.video = it.video; source.audio = it.audio; source.linked = it.linked
                    }
                    source.effects.addAll(it.effects.map { fx -> fx.copyEffect() })
                    source.marks.addAll(it.marks.filter { mark -> mark < source.sourceOut })
                }
                val editor = Editor(saved.project).apply { load(saved.project, saved) }
                if (receipt.insertion == null) editor.appendRecording(source)
                else editor.insertBatch(listOf(source), receipt.insertion, receipt.layer)
                save(editor, false)
                acknowledgeCapture(receipt)
                recovered++
                problems[receipt.project] = "Recovered an interrupted take in ${editor.project.name}."
            } catch (e: Exception) {
                problems["take:${entry.name}"] = "An unfinished recording was kept for recovery: ${e.message}"
            }
        }
        return recovered
    }

    private fun mediaHash(file: File, cancelled: () -> Boolean = { false }): String {
        check(file.isFile) { "Original media is missing" }
        val length = file.length()
        val stamp = file.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                if (cancelled()) throw java.io.InterruptedIOException("Repair cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(length == file.length() && stamp == file.lastModified()) { "The source changed during verification" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mediaHashes(project: String): JSONObject {
        val file = File(root, "checksums/${id(project)}.json")
        if (!file.exists()) return JSONObject().put("version", 1).put("id", project).put("files", JSONObject())
        return JSONObject(String(bytes(file), Charsets.UTF_8)).also {
            require(it.getInt("version") == 1 && it.getString("id") == project) { "Invalid source-checksum index" }
        }
    }

    @Synchronized
    fun trustedHash(project: String, path: String): String? {
        val known = mediaHashes(project).getJSONObject("files").optString(path).takeIf { it.isNotEmpty() }
        val file = File(path)
        val archived = if (file.canonicalPath.startsWith(media.canonicalPath + File.separator))
            file.name.substringBefore('-').substringBefore('.').takeIf { it.matches(Regex("[0-9a-f]{64}")) } else null
        return (known ?: archived)?.also { require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid source checksum" } }
    }

    @Synchronized
    private fun rememberHash(project: String, path: String, hash: String) {
        val data = mediaHashes(project)
        val files = data.getJSONObject("files")
        val previous = files.optString(path)
        check(previous.isEmpty() || previous == hash) { "This is not the unchanged original media" }
        files.put(path, hash)
        write(File(root, "checksums/${id(project)}.json"), data.toString().toByteArray())
    }

    fun registerMedia(project: String, path: String): String {
        val actual = mediaHash(File(path))
        val expected = trustedHash(project, path)
        check(expected == null || expected == actual) { "The original media has changed" }
        rememberHash(project, path, actual)
        return actual
    }

    fun indexOriginals(project: String) {
        val saved = open(project)
        for (path in ProjectAssets.paths(saved))
            if (File(path).isFile && trustedHash(project, path) == null) registerMedia(project, path)
    }

    private fun resolvedSources(saved: EditSession): EditSession {
        val replacements = mediaHashes(saved.project.id).optJSONObject("replacements") ?: return saved
        fun resolve(path: String): String {
            var value = path
            val seen = mutableSetOf<String>()
            while (replacements.has(value)) {
                check(seen.add(value)) { "Invalid source-repair index" }
                value = replacements.getString(value)
            }
            return value
        }
        if (ProjectAssets.paths(saved).none { replacements.has(it) }) return saved
        return ProjectAssets.remap(saved, saved.project.id, saved.project.name, ::resolve)
    }

    @Synchronized
    private fun rememberRepair(project: String, old: String, replacement: String, hash: String) {
        val data = mediaHashes(project)
        data.getJSONObject("files").put(replacement, hash)
        val paths = data.optJSONObject("replacements") ?: JSONObject().also { data.put("replacements", it) }
        paths.put(old, replacement)
        // Publish the source mapping before the index. A live editor may still
        // save its old paths until the main thread receives the repaired source.
        write(File(root, "checksums/${id(project)}.json"), data.toString().toByteArray())
    }

    @Synchronized
    private fun repairIndex(project: String, replacement: String) {
        val saved = open(project)
        check(replacement in ProjectAssets.paths(saved)) { "The project no longer references this original" }
        save(saved, false)
    }

    fun relink(project: Project, path: String, replacement: File, cancelled: () -> Boolean = { false }): String {
        val expected = trustedHash(project.id, path) ?: error("No checksum was recorded for this missing original")
        check(mediaHash(replacement, cancelled) == expected) { "Choose the unchanged original: its checksum differs" }
        val next = uid()
        val working = File(media, ".repair-$next")
        val destination = File(media, next)
        check(working.mkdirs()) { "Cannot prepare media repair" }
        ProjectAssets.begin(working, destination)
        var attempted = false
        try {
            write(File(working, ".myrec-repair"), project.id.toByteArray())
            val name = "$expected-${uid()}.${File(path).extension.ifEmpty { "media" }}"
            val target = File(working, name)
            replacement.inputStream().use { input -> FileOutputStream(target).use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    if (cancelled()) throw java.io.InterruptedIOException("Repair cancelled")
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(working.usableSpace > 64L * 1024 * 1024) { "Not enough free storage" }
                    output.write(buffer, 0, n)
                }
                output.fd.sync()
            } }
            check(mediaHash(target, cancelled) == expected) { "The replacement changed during copying" }
            for (suffix in listOf(".mirror", ".primaries")) {
                val sidecar = File(path + suffix)
                if (sidecar.isFile) write(File(working, name + suffix), sidecar.readBytes())
            }
            if (cancelled()) throw java.io.InterruptedIOException("Repair cancelled")
            publishMedia(working, destination)
            val published = File(destination, name).absolutePath
            attempted = true
            rememberRepair(project.id, path, published, expected)
            repairIndex(project.id, published)
            File(destination, ".myrec-repair").delete()
            return published
        } finally {
            ProjectAssets.end(working, destination)
            // Once publication was attempted, startup recovery decides whether
            // these files are referenced. Never guess after a durability error.
            if (!attempted) {
                if (File(working, ".myrec-repair").isFile) working.deleteRecursively()
                if (File(destination, ".myrec-repair").isFile) destination.deleteRecursively()
            }
        }
    }

    fun cleanTransfers(): Int {
        var removed = 0
        val markers = listOf(".myrec-copy", ".myrec-restore", ".myrec-repair")
        for (folder in media.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            if (folder.absolutePath in ProjectAssets.transfers) continue
            if (folder.canonicalFile.parentFile != media.canonicalFile || folder.canonicalFile.name != folder.name) continue
            val marker = markers.map { File(folder, it) }.firstOrNull { it.isFile } ?: continue
            try {
                val project = id(marker.readText().trim())
                val index = File(root, "$project.json")
                val trash = File(deleted, "$project.json")
                val saved = when {
                    index.isFile -> open(project)
                    trash.isFile -> recovered(trash)
                    backups(project).isNotEmpty() -> open(project)
                    else -> null
                }
                val prefix = folder.absolutePath + File.separator
                if (saved != null && ProjectAssets.paths(saved).any { it.startsWith(prefix) }) marker.delete()
                else if (folder.deleteRecursively()) removed++
            } catch (e: Exception) {
                problems["transfer:${folder.name}"] = "An interrupted transfer was kept for recovery: ${e.message}"
            }
        }
        return removed
    }

    internal fun publishMedia(working: File, destination: File) {
        require(working.parentFile == media && destination.parentFile == media && !destination.exists())
        for (folder in working.walkBottomUp().filter { it.isDirectory }) sync(folder)
        Os.rename(working.absolutePath, destination.absolutePath)
        sync(media)
    }

    fun originals(project: Project): List<Clip> {
        val saved = open(project.id)
        return (saved.originals + ProjectAssets.clips(saved)).distinctBy { it.path }.map { source ->
            try {
                inspect(File(source.path)).copy(id = source.id, group = source.group,
                    effects = source.effects.map { it.copyEffect() }.toMutableList(), marks = source.marks.toMutableList())
            } catch (_: Exception) { source.duplicate() }
        }
    }

    fun duplicate(project: Project, progress: (Float) -> Unit = {}): Project {
        val saved = open(project.id)
        val next = uid()
        val working = File(media, ".copy-$next")
        val destination = File(media, next)
        check(!working.exists() && working.mkdirs()) { "Cannot prepare project copy" }
        ProjectAssets.begin(working, destination)
        var published = false
        try {
            write(File(working, ".myrec-copy"), next.toByteArray())
            val paths = ProjectAssets.paths(saved)
            val moved = linkedMapOf<String, String>()
            for ((i, path) in paths.withIndex()) {
                val source = File(path)
                check(source.isFile) { "An original or image is missing: ${source.name}" }
                val name = "${uid()}.${source.extension.ifEmpty { "media" }}"
                fun copy(input: File, output: File) {
                    input.inputStream().use { read -> FileOutputStream(output).use { write ->
                        read.copyTo(write); write.fd.sync()
                    } }
                }
                copy(source, File(working, name))
                for (suffix in listOf(".mirror", ".primaries")) {
                    val sidecar = File(path + suffix)
                    if (sidecar.isFile) copy(sidecar, File(working, name + suffix))
                }
                moved[path] = File(destination, name).absolutePath
                progress((i + 1f) / paths.size.coerceAtLeast(1))
            }
            sync(working)
            Os.rename(working.absolutePath, destination.absolutePath)
            sync(media)
            val copy = ProjectAssets.remap(saved, next, "${project.name} copy") { moved[it] ?: it }
            for ((old, path) in moved) {
                val expected = trustedHash(project.id, old) ?: registerMedia(project.id, old)
                check(registerMedia(next, path) == expected) { "An original changed during duplication" }
            }
            save(copy, false)
            published = true
            File(destination, ".myrec-copy").delete()
            return copy.project
        } finally {
            ProjectAssets.end(working, destination)
            if (!published && !File(root, "$next.json").exists()) {
                // Only this operation's explicitly marked files are disposable.
                if (File(working, ".myrec-copy").isFile) working.deleteRecursively()
                if (File(destination, ".myrec-copy").isFile) destination.deleteRecursively()
            }
        }
    }

    fun delete(project: Project) {
        directory(deleted)
        val file = File(root, "${id(project.id)}.json")
        val target = File(deleted, file.name)
        check(!target.exists()) { "This project is already in Recently deleted" }
        write(File(deleted, "${project.id}.tombstone"), project.id.toByteArray())
        Os.rename(file.absolutePath, target.absolutePath)
        sync(root); sync(deleted)
    }

    fun restore(project: Project) {
        val target = File(root, "${id(project.id)}.json")
        check(!target.exists()) { "A project with this identity already exists" }
        Os.rename(File(deleted, target.name).absolutePath, target.absolutePath)
        File(deleted, "${project.id}.tombstone").delete()
        sync(deleted); sync(root)
    }

    fun erase(project: Project) {
        check(File(deleted, "${id(project.id)}.json").delete()) { "Cannot delete project" }
        sync(deleted)
    }

    fun recentlyDeleted(): List<Project> = list(deleted)
    fun list(): List<Project> = list(root)

    private fun list(directory: File): List<Project> = directory.listFiles()
        ?.filter { it.extension == "json" }
        ?.sortedByDescending { it.lastModified() }
        ?.mapNotNull {
            try { recovered(it).project }
            catch (e: Exception) { problems[it.nameWithoutExtension] = e.message ?: "Cannot open project"; null }
        } ?: emptyList()

    private fun validate(p: Project) {
        id(p.id)
        require(p.width in 1..16384 && p.height in 1..16384 && p.fps in 1..240 && p.captureCount >= 0) {
            "Invalid project format"
        }
        require(p.clips.size <= 100000 && p.clips.map { it.id }.distinct().size == p.clips.size) {
            "Invalid clip identities"
        }
        require(p.bookmarks.size<=100000 && p.bookmarks.all { it.valid() && !it.local } && p.bookmarks.map { it.id }.distinct().size==p.bookmarks.size) { "Invalid project bookmarks" }
        for (c in p.clips) {
            id(c.occurrence)
            c.link?.let(::id)
            require(!c.photo || c.video && !c.audio) { "Invalid photo clip" }
            require(c.path.isNotBlank() && c.sourceIn.isFinite() && c.sourceOut.isFinite() &&
                c.start.isFinite() && c.rate.isFinite() && c.gainDb.isFinite() &&
                c.sourceIn >= 0 && c.sourceOut > c.sourceIn && c.start >= 0 &&
                c.rate > 0 && c.rate <= 16 && c.track >= 0 &&
                c.marks.all { it.isFinite() && it >= 0 } && c.bookmarks.size<=100000 && c.bookmarks.all { it.valid() } && c.bookmarks.map { it.id }.distinct().size==c.bookmarks.size) { "Invalid clip timing or format" }
            require(c.pauses?.valid() != false && c.frozen?.let { it.valid() && it.sourceTime >= c.sourceIn && it.sourceTime < c.sourceOut } != false) { "Invalid source timing edits" }
            require(c.fadeIn?.valid() != false && c.fadeOut?.valid() != false && c.censors.size <= 10000 &&
                c.censors.all { it.valid(c.duration) }) { "Invalid audio edits" }
            require(c.effects.all { it.from.isFinite() && it.to.isFinite() &&
                it.from >= 0 && it.to >= it.from }) { "Invalid effect interval" }
            require(c.effects.size <= 10000 && c.effects.map { it.id }.distinct().size == c.effects.size &&
                c.effects.all { it.id.isNotBlank() && it.id.length <= 100 }) { "Invalid effect identities" }
            for (effect in c.effects) {
                if (effect.kind == "Stabilize" && effect.values.containsKey("stabilization"))
                    require(StabilizationSettings.valid(effect,c)) { "Invalid stabilization settings" }
                if (effect.kind in AudioFilter.direct && AudioFilter.modern(effect))
                    require(AudioFilter.valid(effect)) { "Invalid direct audio controls" }
                if (ExistingEffects.modern(effect)) require(effect.parameters.all { ExistingEffects.animatable(effect.kind,it.id) || it.keyframes.isEmpty() }) { "This effect control cannot be animated" }
                val expected = EffectContracts.parameters(effect).associateBy { it.id }
                require(effect.parameters.size == expected.size && effect.parameters.map { it.id }.distinct().size == expected.size &&
                    effect.parameters.all { it.kind == expected[it.id]?.kind && it.valid() }) { "Invalid effect parameters" }
                require(effect.phase?.let { (effect.kind in TimeEffects.kinds || effect.kind == "Text" && effect.values["design"] == "1") && it.valid() } != false) { "Invalid time-effect phase" }
                require(effect.subtitle?.let { effect.kind == "Text" && it.valid() } != false) { "Invalid subtitle words" }
                require(effect.motion?.valid(effect) != false) { "Invalid effect animation" }
            }
        }
        require(p.transitions.all { t ->
            listOf(t.at, t.duration, t.hold, t.overlap, t.amount.toDouble(),
                t.angle.toDouble(), t.phaseFrom.toDouble(), t.phaseTo.toDouble()).all { it.isFinite() } &&
                t.duration >= 0 && t.hold >= 0 && t.duration + t.hold > 0 &&
                t.phaseFrom in 0f..1f && t.phaseTo in t.phaseFrom..1f && t.controls?.valid()!=false && listOfNotNull(t.windowFrom,t.windowTo).all { it.isFinite() && kotlin.math.abs(it)<=604800 } && (t.windowFrom==null && t.windowTo==null || t.windowFrom!=null && t.windowTo!=null && t.windowTo!!>t.windowFrom!!)
        }) { "Invalid transition timing" }
    }

    fun encode(p: Project): JSONObject =
        JSONObject().apply {
            put("version", 11)
            put("captureCount", p.captureCount)
            put("bookmarks",JSONArray().apply { p.bookmarks.forEach { put(JSONObject().put("id",it.id).put("time",it.time).put("label",it.label)) } })
            put("id", p.id)
            put("name", p.name)
            put("width", p.width)
            put("height", p.height)
            put("fps", p.fps)
            put(
                "clips",
                JSONArray().apply {
                    p.clips.forEach { c ->
                        put(
                            JSONObject().apply {
                                put("id", c.id)
                                put("group", c.group)
                                put("occurrence", c.occurrence)
                                c.link?.let { put("link", it) }
                                if (c.photo) put("photo", true)
                                put("path", c.path)
                                put("in", c.sourceIn)
                                put("out", c.sourceOut)
                                put("start", c.start)
                                put("track", c.track)
                                put("rate", c.rate)
                                put("video", c.video)
                                put("audio", c.audio)
                                put("linked", c.linked)
                                put("gain", c.gainDb)
                                put("marks", JSONArray(c.marks))
                                put("bookmarks",JSONArray().apply { c.bookmarks.forEach { mark -> put(JSONObject().put("id",mark.id).put("time",mark.time).put("label",mark.label).put("local",mark.local)) } })
                                AudioEditsJson.encode(c, this)
                                c.pauses?.let { put("pauses", PauseJson.encode(it)) }
                                c.frozen?.let { put("frozen", JSONObject().apply {
                                    put("source", it.sourceTime); put("duration", it.duration); put("offset", it.offset)
                                }) }
                                put(
                                    "effects",
                                    JSONArray().apply {
                                        c.effects.forEach { e ->
                                            put(
                                                JSONObject().apply {
                                                    put("id", e.id)
                                                    put("kind", e.kind)
                                                    put("from", e.from)
                                                    put("to", e.to)
                                                    put("enabled", e.enabled)
                                                    put("values", JSONObject(e.values.toMap()))
                                                    if (e.kind == "Text" && e.values["design"] == "1" || e.kind == "Color LUTs" &&
                                                        (e.motion != null || e.values.containsKey("warmth") || e.values.containsKey("tint"))) put("algorithm", 2)
                                                    if (e.kind in AudioFilter.direct && AudioFilter.modern(e)) put("algorithm", 3)
                                                    if (e.kind == "Halation" || Noir.modern(e) || SixteenMm.modern(e)) put("algorithm", 4)
                                                    if (StabilizationSettings.modern(e)) put("algorithm", 5)
                                                    if (e.kind in BackgroundEffect.kinds) put("algorithm", 6)
                                                    if (FrameEffects.modern(e)) put("algorithm", 7)
                                                    if (ExistingEffects.modern(e)) put("algorithm", 8)
                                                    put("parameters", EffectJson.encode(e))
                                                    e.phase?.let { put("phase", TimeEffectJson.encode(it)) }
                                                    e.subtitle?.let { put("subtitle", SubtitleJson.encode(it)) }
                                                    e.motion?.let { put("motion", EffectJson.encodeMotion(it)) }
                                                }
                                            )
                                        }
                                    },
                                )
                            }
                        )
                    }
                },
            )
            put(
                "transitions",
                JSONArray().apply {
                    p.transitions.forEach { t ->
                        put(
                            JSONObject().apply {
                                put("id", t.id)
                                put("at", t.at)
                                put("kind", t.kind)
                                put("duration", t.duration)
                                put("amount", t.amount)
                                put("hold", t.hold)
                                put("leftId", t.leftId)
                                put("rightId", t.rightId)
                                put("overlap", t.overlap)
                                put("angle", t.angle)
                                put("soft", t.soft)
                                put("phaseFrom", t.phaseFrom)
                                put("phaseTo", t.phaseTo);put("windowFrom",t.windowFrom);put("windowTo",t.windowTo)
                                t.controls?.let { c -> put("controls",JSONObject().apply {
                                    put("vertical",c.vertical);put("divider",c.divider);put("reverse",c.reverse);put("audio",c.audio);put("color",c.color)
                                    put("audioOffset",c.audioOffset);put("audioBlend",c.audioBlend);put("flashCount",c.flashCount);put("flashAcceleration",c.flashAcceleration);put("stillDissolve",c.stillDissolve)
                                    fun framing(f: TransitionFraming)=JSONObject().put("x",f.x).put("y",f.y).put("zoom",f.zoom)
                                    put("first",framing(c.first));put("second",framing(c.second))
                                }) }
                            }
                        )
                    }
                },
            )
        }

    fun decode(o: JSONObject): Project {
        checkVersion(o)
        val p =
            Project(
                o.getString("id"),
                o.getString("name"),
                width = o.optInt("width", 1920),
                height = o.optInt("height", 1080),
                fps = o.optInt("fps", 30),
                captureCount = o.optInt("captureCount", 0),
            )
        o.optJSONArray("bookmarks")?.let { marks ->
            require(marks.length()<=100000)
            for (j in 0 until marks.length()) marks.getJSONObject(j).let { mark -> p.bookmarks.add(SourceBookmark(mark.getString("id"),mark.getDouble("time"),mark.getString("label"))) }
        }
        val clips = o.getJSONArray("clips")
        for (i in 0 until clips.length()) {
            val c = clips.getJSONObject(i)
            val clip =
                Clip(
                    c.getString("id"),
                    c.getString("path"),
                    c.getDouble("in"),
                    c.getDouble("out"),
                    c.getDouble("start"),
                    c.getInt("track"),
                    c.getDouble("rate"),
                    c.getBoolean("video"),
                    c.getBoolean("audio"),
                    c.getBoolean("linked"),
                    c.getDouble("gain").toFloat(),
                )
            if (c.has("pauses")) clip.pauses = PauseJson.decode(c.getJSONObject("pauses"))
            if (c.has("frozen")) c.getJSONObject("frozen").let {
                clip.frozen = FrozenFrame(it.getDouble("source"), it.getDouble("duration"), it.getDouble("offset"))
            }
            AudioEditsJson.decode(clip, c)
            val effects = c.getJSONArray("effects")
            val renderVersion = integer(c, "renderVersion", 0)
            if (renderVersion > 1) throw NewerProject()
            require(renderVersion >= 0) { "Invalid rendering order" }
            clip.group = c.optString("group", clip.id)
            clip.occurrence = c.optString("occurrence", clip.group)
            clip.link = if (c.has("link")) c.getString("link") else null
            clip.photo = c.optBoolean("photo", false)
            c.optJSONArray("marks")?.let { marks ->
                for (j in 0 until marks.length()) clip.marks.add(marks.getDouble(j))
            }
            c.optJSONArray("bookmarks")?.let { marks ->
                require(marks.length()<=100000)
                for (j in 0 until marks.length()) marks.getJSONObject(j).let { mark -> clip.bookmarks.add(SourceBookmark(mark.getString("id"),mark.getDouble("time"),mark.getString("label"),mark.getBoolean("local"))) }
            }
            for (j in 0 until effects.length()) {
                val e = effects.getJSONObject(j)
                val values = e.getJSONObject("values")
                val algorithm = integer(e, "algorithm", 0)
                require(algorithm >= 0) { "Invalid effect algorithm" }
                if (algorithm > 8 || algorithm == 2L && e.getString("kind") !in listOf("Text", "Color LUTs") ||
                    algorithm == 3L && e.getString("kind") !in AudioFilter.direct ||
                    algorithm == 4L && e.getString("kind") !in listOf("Halation","Noir","16mm") ||
                    algorithm == 5L && e.getString("kind") != "Stabilize" ||
                    algorithm == 6L && e.getString("kind") !in BackgroundEffect.kinds ||
                    algorithm == 7L && e.getString("kind") !in FrameEffects.kinds ||
                    algorithm == 8L && e.getString("kind") !in ExistingEffects.kinds) throw NewerProject()
                val fx =
                    Effect(
                        e.getString("id"),
                        e.getString("kind"),
                        e.getDouble("from"),
                        e.getDouble("to"),
                        e.getBoolean("enabled"),
                        parameters = when {
                            algorithm == 8L -> ExistingEffects.parameters(e.getString("kind"))
                            algorithm == 7L -> FrameEffects.parameters(e.getString("kind"))
                            algorithm == 4L && e.getString("kind") == "Noir" -> Noir.parameters()
                            algorithm == 4L && e.getString("kind") == "16mm" -> SixteenMm.parameters()
                            else -> EffectContracts.parameters(e.getString("kind"))
                        },
                    )
                values.keys().forEach { fx.values[it] = values.getString(it) }
                if (algorithm == 5L) require(StabilizationSettings.modern(fx)) { "Invalid stabilization algorithm" }
                if (fx.kind in AudioFilter.direct && fx.values["dsp"]?.let { it != "2" } == true) throw NewerProject()
                if (algorithm == 0L && integer(o, "version", 1) < 4 && !e.has("parameters")) EffectImport.convert(fx)
                else EffectJson.decode(fx, if (e.has("parameters")) e.getJSONArray("parameters") else null)
                EffectJson.decodeMotion(fx, if (e.has("motion") && !e.isNull("motion")) e.getJSONObject("motion") else null)
                if (fx.parameters.isNotEmpty()) {
                    val retained=if (ExistingEffects.modern(fx)) fx.values.filterKeys { it in setOf("origin","clockEnd","release","phaseOffset","captured","rangeStart","rangeEnd") } else emptyMap()
                    for ((key,value) in retained) require(value.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..(if (key=="phaseOffset") 24*604800.0 else 604800.0) }==true) { "Invalid effect clock $key" }
                    val origin=if (fx.kind in BackgroundEffect.kinds) fx.values["origin"] else null
                    if (origin!=null) require(origin.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..604800.0 }==true) { "Invalid background origin" }
                    fx.values.clear()
                    if (origin!=null) fx.values["origin"]=origin
                    fx.values.putAll(retained)
                }
                if (e.has("phase")) fx.phase = TimeEffectJson.decode(e.getJSONObject("phase"))
                if (e.has("subtitle")) fx.subtitle = SubtitleJson.decode(e.getJSONObject("subtitle"))
                clip.effects.add(fx)
            }
            p.clips.add(clip)
        }
        val transitions = o.optJSONArray("transitions") ?: JSONArray()
        for (i in 0 until transitions.length()) {
            val t = transitions.getJSONObject(i)
            p.transitions.add(
                Transition(
                    t.getString("id"),
                    t.getDouble("at"),
                    t.getString("kind"),
                    t.getDouble("duration"),
                    t.getDouble("amount").toFloat(),
                    t.optDouble("hold", 0.0),
                    t.optString("leftId").takeIf { it.isNotEmpty() && it != "null" },
                    t.optString("rightId").takeIf { it.isNotEmpty() && it != "null" },
                    t.optDouble("overlap", 0.0),
                    t.optDouble("angle", 0.0).toFloat(),
                    t.optBoolean("soft", true),
                    t.optDouble("phaseFrom", 0.0).toFloat(),
                    t.optDouble("phaseTo", 1.0).toFloat(),
                    t.optJSONObject("controls")?.let { c ->
                        fun framing(key: String)=c.getJSONObject(key).let { TransitionFraming(it.getDouble("x"),it.getDouble("y"),it.getDouble("zoom")) }
                        TransitionControls(c.getBoolean("vertical"),c.getDouble("divider"),c.getBoolean("reverse"),c.getString("audio"),framing("first"),framing("second"),
                            c.getLong("color"),if (c.has("audioOffset")) c.getDouble("audioOffset") else null,if (c.has("audioBlend")) c.getDouble("audioBlend") else null,
                            c.getInt("flashCount"),c.getDouble("flashAcceleration"),c.getDouble("stillDissolve"))
                    },
                    if (t.has("windowFrom")) t.getDouble("windowFrom") else null,
                    if (t.has("windowTo")) t.getDouble("windowTo") else null,
                )
            )
        }
        if (o.optInt("version",1)<11) p.upgradeSubtitles()
        validate(p)
        return p
    }

    companion object {
        fun shape(path: String): Pair<Int, Int> {
            PhotoSource.shape(path)?.let { return it }
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(path)
                val w =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 1920
                val h =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 1080
                val rotation =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                return if (rotation % 180 == 0) w to h else h to w
            } finally {
                r.release()
            }
        }

        fun inspect(file: File): Clip {
            if (PhotoSource.shape(file.path) != null)
                return Clip(path = file.absolutePath, sourceOut = 3.0, audio = false, linked = false, photo = true)
            PcmWave.duration(file)?.let {
                return Clip(path = file.absolutePath, sourceOut = it, video = false, linked = false)
            }
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                val duration = Mp4Audio.presentedDuration(file) ?:
                    (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toDoubleOrNull() ?: 0.0) / 1000
                require(duration > 0) { "This file has no playable duration" }
                val video =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val audio =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                return Clip(
                    path = file.absolutePath,
                    sourceOut = duration,
                    video = video,
                    audio = audio,
                )
            } finally {
                r.release()
            }
        }
    }
}
