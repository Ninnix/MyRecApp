package org.myrec.app

import android.graphics.Bitmap
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest

object NightRestore {
    @Volatile var directory: File?=null
        set(value) {
            synchronized(gate) {
                field=value
                value?.walkTopDown()?.filter { it.isFile && (it.extension=="tmp" || it.extension=="sum" && !File(it.path.removeSuffix(".sum")).isFile) }?.forEach { it.delete() }
            }
        }
    private const val backend="local-flow-1-linear-f16"
    private val gate=Any()

    fun apply(bitmap: Bitmap,clip: Clip,effect: Effect,time: Double,rawColor: Boolean,cancelled: ()->Boolean,
        progress: (Float?)->Unit,read: (Double)->Bitmap?) {
        if (cancelled()) throw InterruptedIOException("Night restoration cancelled")
        if (effect.number("intensity",1f)<=0) return
        val mode=ExistingEffects.choice(effect,"mode") ?: "Fast"
        if (mode=="Fast" || clip.photo || clip.frozen!=null) {
            val copy=bitmap.copy(bitmap.config!!,false)
            try { adjust(bitmap,copy,effect,cancelled) } finally { copy.recycle() }
            return
        }
        val index=VideoFrameIndex.get(clip.path) { value ->
            if (cancelled()) throw InterruptedIOException("Night restoration cancelled")
            progress(value)
        };val at=index.last(time)
        val radius=if (mode=="Precise") 4 else 1
        val identity=listOf(backend,StabilizationRenderer.identity(clip.path,cancelled),mode,index.times[at],bitmap.width,bitmap.height,rawColor,
            File(clip.path+".primaries").takeIf { it.isFile }?.readText(),File(clip.path+".mirror").takeIf { it.isFile }?.readText()).joinToString(":")
        val key=MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        val cache=directory?.let { File(it,"${key.take(2)}/$key.f16") }
        val fused=synchronized(gate) { cache?.let { load(it,bitmap.width,bitmap.height) } } ?: bitmap.copy(bitmap.config!!,true).also { out ->
            val mass=FloatArray(bitmap.width*bitmap.height) { 1f }
            try {
                for (i in maxOf(0,at-radius)..minOf(index.times.lastIndex,at+radius)) {
                    if (i==at) continue
                    if (cancelled()) throw InterruptedIOException("Night restoration cancelled")
                    read(index.times[i])?.let { neighbor ->
                        try { Native.nightFuse(bitmap,out,neighbor,mass,i-at,mode=="Precise") { _,_ -> !cancelled() } }
                        finally { neighbor.recycle() }
                    }
                }
                if (cancelled()) throw InterruptedIOException("Night restoration cancelled")
                synchronized(gate) { cache?.let { save(it,out) } }
            } catch (error: Throwable) { out.recycle();throw error }
        }
        try { adjust(bitmap,fused,effect,cancelled) } finally { fused.recycle() }
    }
    private fun adjust(bitmap: Bitmap,fused: Bitmap,effect: Effect,cancelled: ()->Boolean) {
        Native.nightRestore(bitmap,fused,effect.number("intensity",1f),effect.number("brightness",2.5f),effect.number("reduction",1f),effect.number("detail",.5f)) { _,_ -> !cancelled() }
        if (cancelled()) throw InterruptedIOException("Night restoration cancelled")
    }
    private fun load(file: File,width: Int,height: Int): Bitmap? {
        if (!file.isFile) return null
        var bitmap: Bitmap?=null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                check(input.readInt()==0x4e463031 && input.readInt()==width && input.readInt()==height)
                bitmap=WorkingColor.bitmap(width,height)
                val bytes=ByteArray(bitmap!!.byteCount);input.readFully(bytes);check(input.read()==-1)
                val expected=inputHash(bytes);check(file.name.substringBefore('.').length==64)
                val checksum=File(file.path+".sum");check(checksum.readText()==expected)
                bitmap!!.copyPixelsFromBuffer(ByteBuffer.wrap(bytes));file.setLastModified(System.currentTimeMillis());bitmap
            }
        } catch (_: Exception) { bitmap?.recycle();file.delete();File(file.path+".sum").delete();null }
    }
    private fun inputHash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun save(file: File,bitmap: Bitmap) {
        val root=directory ?: return
        val bytes=ByteArray(bitmap.byteCount);bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        try {
            file.parentFile!!.mkdirs();val temp=File(file.path+".tmp")
            DataOutputStream(BufferedOutputStream(temp.outputStream())).use { out ->
                out.writeInt(0x4e463031);out.writeInt(bitmap.width);out.writeInt(bitmap.height);out.write(bytes)
            }
            File(file.path+".sum").writeText(inputHash(bytes));if (!temp.renameTo(file)) throw IOException("Cannot save restoration cache")
            val entries=root.walkTopDown().filter { it.extension=="f16" }.toList().sortedBy { it.lastModified() }
            var size=entries.sumOf { it.length() }
            for (entry in entries) {
                if (size<=512L*1024*1024) break
                size-=entry.length();entry.delete();File(entry.path+".sum").delete()
            }
        } catch (_: IOException) { file.delete();File(file.path+".tmp").delete();File(file.path+".sum").delete() }
    }
}
