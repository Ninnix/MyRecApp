package org.myrec.app

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal class MovieCheckpoint(private val folder: File) {
    fun segment(start: Int, end: Int, check: () -> Unit, render: (File) -> Unit): File =
        save("frames-$start-$end", "mp4", check, render)

    fun audio(check: () -> Unit, render: (File) -> Unit): File = save("audio", "m4a", check, render)

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

    private fun save(name: String, extension: String, check: () -> Unit, render: (File) -> Unit): File {
        val output = File(folder, "$name.$extension")
        val receipt = File(folder, "$name.sha256")
        if (output.isFile && receipt.isFile && receipt.length() == 64L && receipt.readText() == hash(output, check)) return output
        val temporary = File(folder, ".$name-${uid()}.$extension")
        val stamp = File(folder, ".$name-${uid()}.sha256")
        try {
            render(temporary)
            // A section becomes reusable only after the encoder closes it and both files reach disk.
            val checksum = hash(temporary) {}
            FileOutputStream(temporary, true).use { it.fd.sync() }
            FileOutputStream(stamp).use { it.write(checksum.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
            kotlin.check(temporary.renameTo(output) && stamp.renameTo(receipt)) { "Cannot save the export checkpoint" }
            check()
            return output
        } finally { temporary.delete(); stamp.delete() }
    }
}
