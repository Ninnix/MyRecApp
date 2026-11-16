package org.myrec.app

import java.io.*
import java.security.MessageDigest

internal object AudioCheckpoint {
    data class State(val read: Long,val written: Long,val prefix: ByteArray,val states: Array<ByteArray>)
    private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
    fun prefix(file: File,size: Long,cancelled: () -> Boolean): MessageDigest {
        val sum=MessageDigest.getInstance("SHA-256");var left=size
        file.inputStream().buffered().use { input ->
            val bytes=ByteArray(131072)
            while (left>0) {
                if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                val n=input.read(bytes,0,minOf(bytes.size.toLong(),left).toInt());require(n>0)
                sum.update(bytes,0,n);left-=n
            }
        }
        return sum
    }
    fun read(file: File,key: String): State? = runCatching {
        require(file.length() in 128..140000)
        val bytes=file.readBytes();val body=bytes.copyOf(bytes.size-32)
        require(MessageDigest.isEqual(digest(body),bytes.copyOfRange(body.size,bytes.size)))
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt()==0x44464e31 && input.readUTF()==key)
            val read=input.readLong();val written=input.readLong();val prefix=ByteArray(32).also(input::readFully)
            val states=Array(2) { ByteArray(input.readInt().also { require(it in 1..65536) }).also(input::readFully) }
            require(input.available()==0);State(read,written,prefix,states)
        }
    }.getOrNull()
    fun write(file: File,key: String,state: State) {
        val body=ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(0x44464e31);out.writeUTF(key);out.writeLong(state.read);out.writeLong(state.written);out.write(state.prefix)
            for (s in state.states) { out.writeInt(s.size);out.write(s) }
        } }.toByteArray()
        val temporary=File(file.parentFile,"${file.name}-${uid()}.partial")
        try {
            FileOutputStream(temporary).use { it.write(body);it.write(digest(body));it.fd.sync() }
            check(temporary.renameTo(file)) { "Cannot save audio checkpoint" }
        } finally { temporary.delete() }
    }
}
