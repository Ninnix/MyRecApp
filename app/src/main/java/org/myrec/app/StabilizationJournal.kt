package org.myrec.app

import java.io.*
import java.security.MessageDigest

/* Only complete, chained blocks are reusable after a killed analysis. */
internal class StabilizationJournal(private val file: File,key: String,width: Int,height: Int,
    private val duration: Double,first: Double): AutoCloseable {
    val samples=ArrayList<StabilizationSample>()
    private val pending=ArrayList<StabilizationSample>(256)
    private val stream: RandomAccessFile
    private var previous: ByteArray
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").apply { update(previous);update(bytes) }.digest()
    init {
        val header=ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(0x53544a31);it.writeUTF(key);it.writeInt(width);it.writeInt(height);it.writeDouble(duration);it.writeDouble(first)
        } }.toByteArray()
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        stream=RandomAccessFile(file,"rw");previous=MessageDigest.getInstance("SHA-256").digest(header)
        try {
            val stored=ByteArray(header.size)
            if (stream.length()<header.size || run { stream.readFully(stored);!stored.contentEquals(header) }) {
                stream.setLength(0);stream.seek(0);stream.write(header);stream.fd.sync()
            }
            var valid=header.size.toLong()
            while (stream.filePointer<stream.length() && samples.size<600000) {
                val block=try {
                    val count=stream.readInt();require(count in 1..256 && count<=600000-samples.size)
                    val bytes=ByteArray(count*44);stream.readFully(bytes);val checksum=ByteArray(32);stream.readFully(checksum)
                    require(MessageDigest.isEqual(checksum,hash(bytes)))
                    val values=DataInputStream(ByteArrayInputStream(bytes)).use { input -> List(count) {
                        StabilizationSample(input.readDouble(),StabilizationPose(input.readDouble(),input.readDouble(),input.readDouble(),input.readDouble()),input.readInt())
                    } }
                    var last=samples.lastOrNull()
                    for (value in values) {
                        require(value.time.isFinite() && value.time in 0.0..duration && value.pose.valid())
                        require(if (last==null) value.time==0.0 && value.segment==0 else value.time>last.time && value.segment in last.segment..last.segment+1)
                        last=value
                    }
                    previous=checksum;values
                } catch (_: EOFException) { break }
                catch (_: IllegalArgumentException) { break }
                samples.addAll(block);valid=stream.filePointer
            }
            stream.setLength(valid);stream.seek(valid)
        } catch (error: Throwable) { stream.close();throw error }
    }
    fun add(sample: StabilizationSample) {
        samples.add(sample);pending.add(sample)
        if (pending.size==256) flush()
    }
    private fun flush() {
        if (pending.isEmpty()) return
        val bytes=ByteArrayOutputStream(pending.size*44).also { buffer -> DataOutputStream(buffer).use { out ->
            for (sample in pending) {
                out.writeDouble(sample.time);out.writeDouble(sample.pose.x);out.writeDouble(sample.pose.y)
                out.writeDouble(sample.pose.angle);out.writeDouble(sample.pose.logScale);out.writeInt(sample.segment)
            }
        } }.toByteArray()
        val checksum=hash(bytes);stream.writeInt(pending.size);stream.write(bytes);stream.write(checksum);stream.fd.sync()
        previous=checksum;pending.clear()
    }
    override fun close() { try { flush() } finally { stream.close() } }
}
