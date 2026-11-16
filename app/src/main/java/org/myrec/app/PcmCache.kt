package org.myrec.app

import java.io.File
import java.io.FileOutputStream

internal object PcmCache {
    fun valid(file: File,cancelled: ()->Boolean): Boolean {
        if (!file.isFile || file.length()%8!=0L) return false
        val receipt=File(file.path+".sha256")
        if (receipt.length()!=64L) return false
        return receipt.readText()==SourceAudio.identity(file,cancelled)
    }
    fun seal(file: File,cancelled: ()->Boolean) {
        require(file.isFile && file.length()%8==0L)
        val receipt=File(file.path+".sha256");val temporary=File(file.parentFile,"${file.name}-${uid()}.partial")
        try {
            val digest=SourceAudio.identity(file,cancelled)
            FileOutputStream(temporary).use { it.write(digest.toByteArray());it.fd.sync() }
            check(temporary.renameTo(receipt)) { "Cannot save audio integrity receipt" }
        } finally { temporary.delete() }
    }
}
