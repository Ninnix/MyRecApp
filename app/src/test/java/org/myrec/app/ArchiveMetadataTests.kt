package org.myrec.app

import java.io.File
import java.security.MessageDigest

object ArchiveMetadataTests {
    fun run(test: (String,()->Unit)->Unit) {
        test("archive staging bounds unique metadata verifies bytes and cleans only its own files") {
            val folder=kotlin.io.path.createTempDirectory("archive-staging").toFile()
            try {
                val original=File(folder,"original.mp4").apply { writeBytes(byteArrayOf(7,8,9)) }
                ArchiveMetadata(folder,4).use { staged ->
                    staged["history"]=byteArrayOf(1,2);staged["history"]=byteArrayOf(1,2,3)
                    check(runCatching { staged["extra"]=byteArrayOf(4,5) }.isFailure)
                    val entry=staged.iterator().asSequence().single().value
                    fun hash()=MessageDigest.getInstance("SHA-256").digest(entry.file.readBytes()).joinToString("") { "%02x".format(it) }
                    check(entry.file.length()==3L && hash()==entry.hash)
                    entry.file.writeBytes(byteArrayOf(3,2,1));check(hash()!=entry.hash)
                }
                check(folder.listFiles()!!.toList()==listOf(original) && original.readBytes().contentEquals(byteArrayOf(7,8,9)))
            } finally { folder.deleteRecursively() }
        }
    }
}
