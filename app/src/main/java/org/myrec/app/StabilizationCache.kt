package org.myrec.app

import java.io.*
import java.security.MessageDigest

object StabilizationCache {
    private const val MAX_BYTES = 27*1024*1024
    fun read(file: File, key: String): StabilizationAnalysis? = try {
        require(file.length() in 128..MAX_BYTES.toLong())
        val bytes = file.readBytes(); val count = bytes.size-32
        val hash = MessageDigest.getInstance("SHA-256").apply { update(bytes,0,count) }.digest()
        require(MessageDigest.isEqual(hash,bytes.copyOfRange(count,bytes.size)))
        DataInputStream(ByteArrayInputStream(bytes,0,count)).use { input ->
            require(input.readInt() == 0x53544231 && input.readUTF() == key)
            val width = input.readInt(); val height = input.readInt(); val duration = input.readDouble(); val first = input.readDouble()
            val tracked = input.readInt(); val n = input.readInt()
            require(n in 1..600000 && input.available() == n*44)
            val samples = List(n) { StabilizationSample(input.readDouble(),
                StabilizationPose(input.readDouble(),input.readDouble(),input.readDouble(),input.readDouble()),input.readInt()) }
            StabilizationAnalysis(width,height,duration,samples,tracked,first).also { require(it.valid()) }
        }
    } catch (_: Exception) { null }

    fun write(file: File, key: String, analysis: StabilizationAnalysis) {
        require(key.matches(Regex("[0-9a-f]{64}")) && analysis.valid())
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x53544231); out.writeUTF(key)
            out.writeInt(analysis.width); out.writeInt(analysis.height); out.writeDouble(analysis.duration)
            out.writeDouble(analysis.firstSourceTime)
            out.writeInt(analysis.tracked); out.writeInt(analysis.samples.size)
            for (sample in analysis.samples) {
                out.writeDouble(sample.time); out.writeDouble(sample.pose.x); out.writeDouble(sample.pose.y)
                out.writeDouble(sample.pose.angle); out.writeDouble(sample.pose.logScale); out.writeInt(sample.segment)
            }
        }
        val data = bytes.toByteArray(); require(data.size+32 <= MAX_BYTES)
        val partial = File(file.parentFile,".${file.name}-${uid()}.partial")
        try {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot prepare stabilization cache" }
            FileOutputStream(partial).use { out -> out.write(data); out.write(MessageDigest.getInstance("SHA-256").digest(data)); out.fd.sync() }
            check(partial.renameTo(file)) { "Cannot save stabilization analysis" }
        } finally { partial.delete() }
    }
}
