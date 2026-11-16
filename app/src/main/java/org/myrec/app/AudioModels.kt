package org.myrec.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.json.JSONObject

object AudioModels {
    val effects = listOf("Noise reduction", "Studio restoration", "Speech detail")
    val neural = effects + "Studio clarity"
    private lateinit var root: File
    private lateinit var manifest: JSONObject
    @Volatile var cancelled = false
    private val verified = mutableSetOf<Int>()

    fun configure(context: Context, directory: File) {
        synchronized(this) { verified.clear() }
        root = directory
        manifest =
            JSONObject(
                context.assets.open("audio-models.json").bufferedReader().use { it.readText() }
            )
    }

    fun kind(name: String) =
        when (name) {
            "Noise reduction" -> 0
            "Studio restoration" -> 1
            else -> 2
        }

    private fun spec(name: String) =
        manifest.getJSONObject(if (kind(name) == 0) "deepfilter" else "voicefixer")

    fun directory(name: String) =
        if (kind(name) == 2) Studio.directory else File(root, spec(name).getString("directory"))

    fun version(name: String) =
        if (kind(name) == 2) "lava-v1-c-1" else spec(name).getString("version")

    fun ready(name: String): Boolean {
        if (kind(name) == 2) return Studio.ready
        if (!::manifest.isInitialized) return false
        val dir = directory(name)
        val files = spec(name).getJSONArray("files")
        return File(dir, "ready").let { it.isFile && (it.readText() == version(name) || kind(name)==0 && it.readText()=="deepfilter-v3-c-1") } &&
            (0 until files.length()).all {
                val entry = files.getJSONObject(it)
                File(dir, entry.getString("name")).length() == entry.getLong("bytes")
            }
    }

    @Synchronized fun verify(name: String, cancelled: () -> Boolean) {
        if (kind(name) == 2) { Studio.verify(cancelled); return }
        if (kind(name) in verified) return
        check(ready(name)) { "Download $name first" }
        val files = spec(name).getJSONArray("files")
        for (i in 0 until files.length()) {
            val entry = files.getJSONObject(i)
            check(SourceAudio.hash(File(directory(name), entry.getString("name")), cancelled) == entry.getString("sha256")) {
                "$name model did not verify; download it again"
            }
        }
        if (kind(name)==0) DeepFilterModel.prepare(directory(name))
        verified.add(kind(name))
    }

    fun bytes(name: String): Long {
        if (kind(name) == 2) return 56235055L
        val files = spec(name).getJSONArray("files")
        return (0 until files.length()).sumOf { files.getJSONObject(it).getLong("bytes") }
    }

    private fun publish(partial: File, file: File, spec: JSONObject) {
        try {
            val expected = spec.getString("sha256")
            val actual = SourceAudio.hash(partial) { cancelled }
            if (actual != expected) {
                check(actual == spec.optString("downloadSha256", expected)) { "Audio model download did not verify" }
                if (spec.has("contextOffset")) {
                    // The pinned ONNX export has a fixed input dimension. Only
                    // that two-byte protobuf varint changes; weights are untouched.
                    java.io.RandomAccessFile(partial, "rw").use { data ->
                        val offset = spec.getLong("contextOffset")
                        require(offset >= 0 && offset + 2 <= data.length())
                        data.seek(offset); check(data.readUnsignedShort() == 0xf503) { "Invalid model context" }
                        data.seek(offset); data.writeByte(0x80); data.writeByte(0x02)
                    }
                }
                check(SourceAudio.hash(partial) { cancelled } == expected) { "Adapted audio model did not verify" }
            }
            check(partial.renameTo(file)) { "Cannot publish the verified audio model" }
        } catch (error: Exception) {
            if (error !is InterruptedIOException) partial.delete()
            throw error
        }
    }

    fun download(name: String, progress: (Float) -> Unit) {
        synchronized(this) { verified.remove(kind(name)) }
        val files = spec(name).getJSONArray("files")
        val dir = directory(name).apply { mkdirs() }
        val total = bytes(name)
        var complete = 0L
        for (i in 0 until files.length()) {
            val spec = files.getJSONObject(i)
            val size = spec.getLong("bytes")
            val file = File(dir, spec.getString("name"))
            val partial = File(dir, file.name + ".partial")
            if (cancelled) throw InterruptedIOException("Download cancelled")
            if (file.length() != size) {
                if (partial.length() > size) partial.delete()
                if (partial.length() == size) {
                    publish(partial, file, spec)
                    complete += size; progress(complete.toFloat() / total)
                    continue
                }
                val connection =
                    URI(spec.getString("url")).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                if (partial.length() > 0)
                    connection.setRequestProperty("Range", "bytes=${partial.length()}-")
                try {
                    check(connection.responseCode in 200..299) {
                        "Audio model download failed (${connection.responseCode})"
                    }
                    val append = connection.responseCode == 206 && partial.length() > 0
                    var written = if (append) partial.length() else 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(partial, append).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                if (cancelled) throw InterruptedIOException("Download cancelled")
                                val count = input.read(buffer)
                                if (count < 0) break
                                written += count
                                check(written <= size) { "Invalid audio model download" }
                                output.write(buffer, 0, count)
                                progress((complete + written).toFloat() / total)
                            }
                        }
                    }
                    check(partial.length() == size) { "Incomplete audio model download" }
                    publish(partial, file, spec)
                } finally {
                    connection.disconnect()
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (
                digest.digest().joinToString("") { "%02x".format(it) } != spec.getString("sha256")
            ) {
                file.delete()
                error("Audio model did not verify; please retry")
            }
            complete += size
            progress(complete.toFloat() / total)
        }
        File(dir, "ready").writeText(version(name))
    }
}
