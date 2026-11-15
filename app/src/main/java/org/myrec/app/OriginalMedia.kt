package org.myrec.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.MessageDigest
import org.json.JSONObject

object OriginalMedia {
    fun files(store: ProjectStore, project: String? = null): List<File> {
        val root = store.media.canonicalFile
        val found = linkedMapOf<String,File>()
        fun include(file: File) {
            val path = file.canonicalFile
            if (path.path.startsWith(root.path+File.separator) && path.isFile && path.length() > 0 &&
                !path.name.startsWith('.') && path.extension !in listOf("mirror","primaries","partial")) found[path.path] = path
        }
        fun folder(directory: File) {
            val pending = ArrayDeque<File>(); val visited = hashSetOf<String>(); pending.add(directory)
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst().canonicalFile
                if (next != root && !next.path.startsWith(root.path+File.separator) || !visited.add(next.path)) continue
                for (file in next.listFiles().orEmpty().sortedBy { it.name }) {
                    if (file.name.startsWith('.')) continue
                    if (file.isDirectory) pending.add(file) else include(file)
                }
            }
        }
        if (project == null) folder(root)
        else {
            require(project.matches(Regex("[A-Za-z0-9-]{1,100}")))
            runCatching { store.open(project) }.getOrNull()?.let { saved -> ProjectAssets.paths(saved).forEach { include(File(it)) } }
            fun metadata(file: File) {
                if (!file.isFile || file.length() !in 1..64L*1024*1024) return
                runCatching {
                    val data = JSONObject(file.readText())
                    if (data.optString("id") != project) return@runCatching
                    for (key in listOf("clips","captures","originals")) data.optJSONArray(key)?.let { clips ->
                        for (i in 0 until clips.length().coerceAtMost(100000)) clips.optJSONObject(i)?.optString("path")
                            ?.takeIf { it.isNotEmpty() }?.let { include(File(it)) }
                    }
                }
            }
            /* Read paths without treating damaged edit metadata as a usable timeline. */
            metadata(File(store.root,"$project.json"))
            for (directory in listOf("backups","revisions"))
                File(store.root,"$directory/$project").listFiles().orEmpty().forEach(::metadata)
            runCatching {
                val checksums = File(store.root,"checksums/$project.json")
                if (checksums.isFile && checksums.length() in 1..64L*1024*1024) {
                    val data = JSONObject(checksums.readText())
                    if (data.optString("id") == project) data.optJSONObject("files")?.keys()?.forEach { include(File(it)) }
                }
            }
            for (entry in File(store.root,"pending").listFiles().orEmpty()) runCatching {
                if (entry.length() !in 1..64L*1024*1024) return@runCatching
                val data = JSONObject(entry.readText())
                if (data.optString("project") == project) {
                    val name = data.optString("filename")
                    if (name.isNotEmpty() && File(name).name == name && !name.contains('\\')) include(File(root,name))
                }
            }
            folder(File(root,project))
        }
        return found.values.sortedWith(compareBy<File> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.path })
    }

    fun type(file: File): String = when (file.extension.lowercase(java.util.Locale.ROOT)) {
        "mp4","m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "wav" -> "audio/wav"
        "m4a","aac" -> "audio/mp4"
        "jpg","jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    interface Destination {
        fun create(name: String, type: String): Uri
        fun write(file: Uri): OutputStream
        fun read(file: Uri): InputStream
        fun remove(file: Uri)
    }
    fun documents(resolver: ContentResolver, parent: Uri) = object : Destination {
        override fun create(name: String, type: String) = DocumentsContract.createDocument(resolver,parent,type,name)
            ?: error("Cannot create $name")
        override fun write(file: Uri) = resolver.openOutputStream(file,"wt") ?: error("Cannot write this destination")
        override fun read(file: Uri) = resolver.openInputStream(file) ?: error("Cannot verify this destination")
        override fun remove(file: Uri) { check(DocumentsContract.deleteDocument(resolver,file)) { "Cannot remove an incomplete copy" } }
    }
    fun copy(files: List<File>, destination: Destination, cancelled: () -> Boolean = { false }, progress: (Float) -> Unit = {}): List<Uri> {
        require(files.isNotEmpty() && files.map { it.canonicalPath }.distinct().size == files.size)
        val created = mutableListOf<Uri>(); val names = hashSetOf<String>()
        fun checkCancelled() { if (cancelled()) throw InterruptedIOException("Copy cancelled") }
        fun digest(input: InputStream): ByteArray {
            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(128*1024)
            while (true) { checkCancelled(); val count = input.read(buffer); if (count < 0) break; hash.update(buffer,0,count) }
            return hash.digest()
        }
        try {
            for ((index,file) in files.withIndex()) {
                checkCancelled(); check(file.isFile) { "Original is missing: ${file.name}" }
                val length = file.length(); val stamp = file.lastModified()
                var name = file.name; var suffix = 2
                while (!names.add(name)) name = "${file.nameWithoutExtension} (${suffix++})${file.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""}"
                val target = destination.create(name,type(file)); created.add(target)
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> destination.write(target).use { output ->
                    val buffer = ByteArray(128*1024)
                    while (true) {
                        checkCancelled(); val count = input.read(buffer); if (count < 0) break
                        output.write(buffer,0,count); hash.update(buffer,0,count)
                    }
                } }
                val expected = hash.digest()
                check(destination.read(target).use(::digest).contentEquals(expected)) { "Saved original did not verify: ${file.name}" }
                check(file.length() == length && file.lastModified() == stamp && file.inputStream().use(::digest).contentEquals(expected)) {
                    "Original changed while copying: ${file.name}"
                }
                progress((index+1f)/files.size)
            }
            checkCancelled()
            return created
        } catch (failure: Exception) {
            for (file in created.asReversed()) try { destination.remove(file) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
