package org.myrec.app

import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.*

object SourceAudio {
    private val identities=LinkedHashMap<String,Pair<String,String>>(64,.75f,true)
    fun identity(file: File,cancelled: () -> Boolean): String {
        fun stamp()=android.system.Os.stat(file.path).let { "${it.st_dev}:${it.st_ino}:${it.st_size}:${it.st_mtim.tv_sec}:${it.st_mtim.tv_nsec}:${it.st_ctim.tv_sec}:${it.st_ctim.tv_nsec}" }
        val before=stamp()
        synchronized(identities) { identities[file.path]?.takeIf { it.first==before }?.let { return it.second } }
        val hash=hash(file,cancelled);check(before==stamp()) { "Source changed during audio preparation" }
        synchronized(identities) { identities[file.path]=before to hash;while (identities.size>64) identities.remove(identities.keys.first()) }
        return hash
    }
    data class Window(val frames: Int, val margin: Int, val stride: Int = frames - 2 * margin) {
        val overlap get() = frames - 2 * margin - stride
        init { require(frames > 0 && margin >= 0 && stride > 0 && frames - 2 * margin - stride >= 0) }
    }
    val detail = Window(192000, 16384)
    val restoration = Window(255 * 441, 64 * 441, 120 * 441)
    val reduction = Window(480000, 48000)

    @Synchronized
    fun reduce(input: File,output: File,models: File,cancelled: () -> Boolean,progress: (Float) -> Unit) {
        require(input.length()>0 && input.length()%8==0L && !output.exists() && input.canonicalFile!=output.canonicalFile)
        val handles=LongArray(2);val partial=File(output.parentFile,"${output.name}.stream.partial")
        val checkpoint=File(output.parentFile,"${output.name}.stream.state")
        val key=hashText("dfn-stream-state-1:${Native.inferenceVersion()}:${android.os.Build.FINGERPRINT}:${identity(input,cancelled)}:"+
            listOf("enc.onnx","erb_dec.onnx","df_dec.onnx").joinToString(":") { identity(File(models,it),cancelled) })
        val total=input.length()/8;var read=0L;var written=0L
        var digest=MessageDigest.getInstance("SHA-256")
        fun open() { for (ch in 0..1) handles[ch]=Native.neuralLoad(models.path,0,2).also { check(it!=0L) } }
        fun close() { handles.indices.forEach { if (handles[it]!=0L) Native.neuralFree(handles[it]);handles[it]=0 } }
        try {
            open()
            val resumed=runCatching {
                val saved=AudioCheckpoint.read(checkpoint,key) ?: return@runCatching false
                require(saved.read in 480 until total && saved.read%480==0L && saved.written==max(0L,saved.read-1440) && partial.length()>=saved.written*8)
                digest=AudioCheckpoint.prefix(partial,saved.written*8,cancelled)
                require(MessageDigest.isEqual((digest.clone() as MessageDigest).digest(),saved.prefix))
                for (ch in 0..1) Native.neuralState(handles[ch],saved.states[ch])
                read=saved.read;written=saved.written;true
            }.getOrElse { if (it is InterruptedIOException) throw it;false }
            if (!resumed) { close();open();read=0;written=0;digest=MessageDigest.getInstance("SHA-256");checkpoint.delete() }
            check(output.parentFile!!.usableSpace>=input.length()-written*8+64L*1024*1024) { "Not enough storage to prepare this source. Completed processing is kept." }
            RandomAccessFile(input,"r").use { source -> RandomAccessFile(partial,"rw").use { target ->
                source.seek(read*8);target.setLength(written*8);target.seek(written*8)
                fun save() {
                    if (read==0L || read>=total || read%480!=0L) return
                    target.fd.sync()
                    val prefix=runCatching { (digest.clone() as MessageDigest).digest() }.getOrElse { AudioCheckpoint.prefix(partial,written*8,cancelled).digest() }
                    AudioCheckpoint.write(checkpoint,key,AudioCheckpoint.State(read,written,prefix,Array(2) { Native.neuralState(handles[it],null) }))
                }
                val bytes=ByteArray(480*8);val zero=ByteArray(480*8);val outputBytes=ByteArray(1440*8)
                val dry=ByteBuffer.allocateDirect(480*8).order(ByteOrder.LITTLE_ENDIAN)
                val wet=ByteBuffer.allocateDirect(1440*8).order(ByteOrder.LITTLE_ENDIAN)
                progress(read.toFloat()/total)
                val thermal=MediaThermalGate()
                var reported=System.nanoTime()
                try {
                    while (read<total || written<total) {
                        thermal.cooperate()
                        if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio preparation cancelled")
                        val count=min(480L,total-read).toInt()
                        if (count<480) zero.copyInto(bytes)
                        if (count>0) source.readFully(bytes,0,count*8)
                        dry.clear();dry.put(bytes);wet.clear()
                        val produced=Native.neuralStereo(handles[0],handles[1],dry,count==0,wet)
                        check(produced in 0..1440) { "Invalid streaming audio" }
                        val valid=min(produced.toLong(),total-written).toInt()
                        check(count>0 || valid>0) { "Noise reduction did not drain its delay" }
                        wet.get(outputBytes,0,valid*8);target.write(outputBytes,0,valid*8);digest.update(outputBytes,0,valid*8)
                        read+=count;written+=valid
                        if (read%480000==0L) save()
                        val now=System.nanoTime()
                        if (now-reported>=100000000 || read==total && written==total) { progress(read.toFloat()/total);reported=now }
                    }
                    target.fd.sync()
                } catch (error: InterruptedIOException) { save();throw error }
            } }
            if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
            check(partial.length()==input.length() && partial.renameTo(output)) { "Cannot save noise reduction" }
            checkpoint.delete()
        } finally { close();if (!checkpoint.isFile) partial.delete() }
    }
    private fun hashText(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun hash(file: File, cancelled: () -> Boolean = { false }): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(131072)
            while (true) {
                if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun reflected(index: Long, length: Long): Long {
        require(length > 0)
        if (length == 1L) return 0
        val period = 2 * (length - 1)
        val i = (index % period + period) % period
        return if (i < length) i else period - i
    }

    fun resample(input: File, output: File, from: Int, to: Int, frames: Long = 0,
        cancelled: () -> Boolean, progress: (Float) -> Unit) {
        val count = if (frames > 0) frames else (input.length() / 8.0 * to / from).roundToLong()
        if (output.isFile && output.length() == count * 8) return
        val partial = File(output.parentFile, "${output.name}-${uid()}.partial")
        try {
            val written = Native.audioResample(input.path, partial.path, from, to, count) { done, total ->
                progress(if (total > 0) done.toFloat() / total else 1f); !cancelled()
            }
            if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
            check(written == count && partial.length() == count * 8 && partial.renameTo(output)) { "Cannot resample source audio" }
        } finally { partial.delete() }
    }

    fun denoise(input: File, output: File, cancelled: () -> Boolean, progress: (Float) -> Unit) {
        require(input.length() > 0 && input.length() % 8 == 0L && !output.exists())
        val handles = longArrayOf(Native.noiseCreate(), Native.noiseCreate())
        val partial = File(output.parentFile, "${output.name}-${uid()}.partial")
        try {
            check(handles.all { it != 0L }) { "Cannot prepare noise reduction" }
            val total = input.length() / 8
            RandomAccessFile(input, "r").use { source -> partial.outputStream().buffered().use { target ->
                var position = 0L; var previous = 0
                while (position < total || previous > 0) {
                    if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                    val count = min(480L, total - position).toInt()
                    val bytes = ByteArray(480 * 8)
                    if (count > 0) source.readFully(bytes, 0, count * 8)
                    val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val wet = Array(2) { ch ->
                        Native.noiseProcess(handles[ch], FloatArray(480) { pcm[it * 2 + ch] })
                            ?.also { check(it.all { v -> v.isFinite() }) { "Invalid denoised audio" } }
                            ?: error("Cannot denoise this source")
                    }
                    val buffer = ByteBuffer.allocate(previous * 8).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until previous) { buffer.putFloat(wet[0][i]); buffer.putFloat(wet[1][i]) }
                    target.write(buffer.array()); previous = count; position += count
                    progress(position.toFloat() / total)
                }
            } }
            if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
            check(partial.length() == input.length() && partial.renameTo(output)) { "Cannot save noise reduction" }
        } finally {
            handles.forEach { if (it != 0L) Native.noiseFree(it) }
            partial.delete()
        }
    }

    fun render(input: File, output: File, checkpoints: File, window: Window,
        cancelled: () -> Boolean, progress: (Float) -> Unit, infer: (FloatArray) -> FloatArray) {
        require(input.length() > 0 && input.length() % 8 == 0L && input.canonicalFile != output.canonicalFile)
        require(!output.exists())
        val count = input.length() / 8
        val thermal=MediaThermalGate()
        checkpoints.mkdirs()
        val partial = File(output.parentFile, "${output.name}-${uid()}.partial")
        try {
            RandomAccessFile(input, "r").use { source -> partial.outputStream().buffered().use { target ->
                var position = 0L
                val tail = FloatArray(window.overlap * 2)
                while (position < count) {
                    thermal.cooperate()
                    if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                    val size = min(window.stride.toLong(), count - position).toInt()
                    val valid = min((window.stride + window.overlap).toLong(), count - position).toInt()
                    val chunk = File(checkpoints, "$position.f32")
                    val checksum = File(checkpoints, "$position.sha256")
                    val reusable = chunk.isFile && chunk.length() == valid * 8L && checksum.isFile &&
                        checksum.length() == 64L && checksum.readText() == hash(chunk, cancelled)
                    if (!reusable) {
                        val indices = LongArray(window.frames) { reflected(position - window.margin + it, count) }
                        val from = indices.min(); val to = indices.max() + 1
                        val bytes = ByteArray(((to - from) * 8).toInt())
                        source.seek(from * 8); source.readFully(bytes)
                        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        val left = FloatArray(window.frames) { pcm[((indices[it] - from) * 2).toInt()] }
                        val right = FloatArray(window.frames) { pcm[((indices[it] - from) * 2 + 1).toInt()] }
                        fun predict(samples: FloatArray): FloatArray {
                            check(samples.all { it.isFinite() }) { "Invalid source audio" }
                            if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                            if (samples.all { abs(it) < 1e-8f }) return samples
                            return infer(samples).also { result ->
                                check(result.size == samples.size && result.all { it.isFinite() }) { "Invalid neural audio output" }
                            }
                        }
                        val a = predict(left)
                        val b = if (left.contentEquals(right)) a else predict(right)
                        if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
                        val buffer = ByteBuffer.allocate(valid * 8).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until valid) { buffer.putFloat(a[window.margin + i]); buffer.putFloat(b[window.margin + i]) }
                        val temp = File(checkpoints, "$position-${uid()}.partial")
                        try {
                            temp.writeBytes(buffer.array()); check(temp.renameTo(chunk)) { "Cannot save audio checkpoint" }
                            temp.writeText(hash(chunk, cancelled))
                            check(temp.renameTo(checksum)) { "Cannot save audio checkpoint checksum" }
                        } finally { temp.delete() }
                    }
                    if (window.overlap == 0) chunk.inputStream().use { it.copyTo(target) }
                    else {
                        val raw = ByteBuffer.wrap(chunk.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        val buffer = ByteBuffer.allocate(size * 8).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until size) {
                            val weight = if (position > 0 && i < window.overlap) (i + 1f) / (window.overlap + 1) else 1f
                            for (ch in 0..1) buffer.putFloat(raw[i * 2 + ch] * weight +
                                (if (i < window.overlap) tail[i * 2 + ch] else 0f) * (1 - weight))
                        }
                        for (i in 0 until window.overlap) for (ch in 0..1)
                            tail[i * 2 + ch] = if (size + i < valid) raw[(size + i) * 2 + ch] else 0f
                        target.write(buffer.array())
                    }
                    position += size
                    progress(position.toFloat() / count)
                }
            } }
            if (cancelled()) throw InterruptedIOException("Audio preparation cancelled")
            check(partial.length() == count * 8 && partial.renameTo(output)) { "Cannot save source audio" }
        } finally { partial.delete() }
    }
}
