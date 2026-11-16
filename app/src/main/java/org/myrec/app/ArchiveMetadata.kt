package org.myrec.app

import java.io.*
import java.security.MessageDigest

/* History is staged one state at a time, independently of the project's length. */
internal class ArchiveMetadata(parent: File,private val limit: Long): AutoCloseable {
    private val folder=File(parent,".archive-metadata-${uid()}")
    data class Entry(val file: File,val hash: String)
    private val files=linkedMapOf<String,Entry>()
    private var size=0L
    val bytes get()=size
    val count get()=files.size
    init { check(folder.mkdirs()) { "Cannot stage archive metadata" } }
    operator fun set(name: String,data: ByteArray) {
        val file=files[name]?.file ?: File(folder,files.size.toString())
        val next=size-(if (file.exists()) file.length() else 0)+data.size
        require(next<=limit && data.size<=64*1024*1024) { "Editing history exceeds the archive metadata limit" }
        file.writeBytes(data);files[name]=Entry(file,MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) });size=next
    }
    operator fun iterator()=files.iterator()
    override fun close() { folder.deleteRecursively() }
}
