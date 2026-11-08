package org.yourec.app

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

    fun configure(context: Context, directory: File) {
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
        return File(dir, "ready").let { it.isFile && it.readText() == version(name) } &&
            (0 until files.length()).all {
                val entry = files.getJSONObject(it)
                File(dir, entry.getString("name")).length() == entry.getLong("bytes")
            }
    }

    fun bytes(name: String): Long {
        if (kind(name) == 2) return 56235055L
        val files = spec(name).getJSONArray("files")
        return (0 until files.length()).sumOf { files.getJSONObject(it).getLong("bytes") }
    }

    fun download(name: String, progress: (Float) -> Unit) {
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
                    check(partial.length() == size && partial.renameTo(file)) {
                        "Incomplete audio model download"
                    }
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
