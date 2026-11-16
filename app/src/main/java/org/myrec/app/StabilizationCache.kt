package org.myrec.app

import java.io.*
import java.security.MessageDigest
import java.security.DigestInputStream
import java.security.DigestOutputStream

object StabilizationCache {
    private const val MAX_BYTES = 27*1024*1024
    internal fun retire(directory: File?,keep: String,budget: Long=512L*1024*1024) {
        require(budget>=0)
        val files=directory?.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}\\.(path|checkpoint)")) }.orEmpty()
        var bytes=files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (bytes<=budget) break
            if (file.name.startsWith(keep)) continue
            val size=file.length();if (file.delete()) bytes-=size
        }
    }
    fun read(file: File, key: String): StabilizationAnalysis? = try {
        require(file.length() in 128..MAX_BYTES.toLong())
        val digest=MessageDigest.getInstance("SHA-256")
        val checked=DigestInputStream(file.inputStream().buffered(),digest)
        DataInputStream(checked).use { input ->
            require(input.readInt() == 0x53544231 && input.readUTF() == key)
            val width = input.readInt(); val height = input.readInt(); val duration = input.readDouble(); val first = input.readDouble()
            val tracked = input.readInt(); val n = input.readInt()
            require(n in 1..600000 && input.available() == n*44+32)
            val runtime=Runtime.getRuntime();require(n*104L+16L*1024*1024<=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory()))
            val samples = List(n) { StabilizationSample(input.readDouble(),
                StabilizationPose(input.readDouble(),input.readDouble(),input.readDouble(),input.readDouble()),input.readInt()) }
            checked.on(false);val checksum=ByteArray(32);input.readFully(checksum)
            require(input.read()==-1 && MessageDigest.isEqual(checksum,digest.digest()))
            StabilizationAnalysis(width,height,duration,samples,tracked,first).also { require(it.valid()) }
        }
    } catch (_: Exception) { null }

    fun write(file: File, key: String, analysis: StabilizationAnalysis) {
        require(key.matches(Regex("[0-9a-f]{64}")) && analysis.valid())
        require(analysis.samples.size*44L+160<=MAX_BYTES)
        val partial = File(file.parentFile,".${file.name}-${uid()}.partial")
        try {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot prepare stabilization cache" }
            FileOutputStream(partial).use { raw ->
                val digest=MessageDigest.getInstance("SHA-256")
                val out=DataOutputStream(DigestOutputStream(raw.buffered(),digest))
                out.writeInt(0x53544231); out.writeUTF(key)
                out.writeInt(analysis.width); out.writeInt(analysis.height); out.writeDouble(analysis.duration)
                out.writeDouble(analysis.firstSourceTime)
                out.writeInt(analysis.tracked); out.writeInt(analysis.samples.size)
                for (sample in analysis.samples) {
                    out.writeDouble(sample.time); out.writeDouble(sample.pose.x); out.writeDouble(sample.pose.y)
                    out.writeDouble(sample.pose.angle); out.writeDouble(sample.pose.logScale); out.writeInt(sample.segment)
                }
                out.flush();raw.write(digest.digest());raw.fd.sync()
            }
            check(partial.renameTo(file)) { "Cannot save stabilization analysis" }
        } finally { partial.delete() }
    }
}
