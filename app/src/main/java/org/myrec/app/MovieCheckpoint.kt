package org.myrec.app

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject

internal class MovieCheckpoint(private val folder: File) {
    fun prepare(settings: JSONObject) {
        for (file in folder.listFiles().orEmpty()) if (file.isFile && abandoned.matches(file.name)) file.delete()
        val target = File(folder, "settings.json")
        val value = settings.toString()
        if (target.isFile && target.readText() == value) return
        val temp = File(folder, ".settings-${uid()}.json")
        try {
            FileOutputStream(temp).use { it.write(value.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(temp.renameTo(target)) { "Cannot save export settings" }
            syncDirectory(folder)
        } finally { temp.delete() }
    }

    fun segment(start: Int, end: Int, check: () -> Unit, render: (File) -> Unit): File =
        save("frames-$start-$end", "mp4", check) { render(it); null }.first

    fun audio(check: () -> Unit, render: (File) -> Double?): Pair<File,Double?> = save("audio", "m4a", check, render)

    private fun hash(file: File, check: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun save(name: String, extension: String, check: () -> Unit, render: (File) -> Double?): Pair<File,Double?> {
        val output = File(folder, "$name.$extension")
        val receipt = File(folder, "$name.sha256")
        if (output.isFile && receipt.isFile && receipt.length() in 64L..128L) {
            val data=receipt.readLines()
            if (data.firstOrNull()==hash(output,check))
                return output to data.getOrNull(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it>=0 }
        }
        val temporary = File(folder, ".$name-${uid()}.$extension")
        val stamp = File(folder, ".$name-${uid()}.sha256")
        try {
            val peak=render(temporary)
            // A section becomes reusable only after the encoder closes it and both files reach disk.
            val checksum = hash(temporary) {}
            FileOutputStream(temporary, true).use { it.fd.sync() }
            val metadata=if (peak==null) checksum else "$checksum\n$peak"
            FileOutputStream(stamp).use { it.write(metadata.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
            kotlin.check(temporary.renameTo(output) && stamp.renameTo(receipt)) { "Cannot save the export checkpoint" }
            syncDirectory(folder)
            check()
            return output to peak
        } finally { temporary.delete(); stamp.delete() }
    }

    private companion object {
        val abandoned = Regex("\\.(settings|audio|frames-[0-9]+-[0-9]+)-[a-f0-9-]{36}\\.(json|m4a|mp4|sha256)")
    }
}
