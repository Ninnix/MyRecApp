package org.myrec.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

// A discarded prefix measures this encoder's priming instead of guessing a
// device-specific delay. The MP4 edit keeps it out of every player’s timeline.
object Mp4Audio {
    const val PREFIX = 8192
    const val TAIL = 4096
    private const val MARK = 2048
    private const val LENGTH = 1024
    val prefix = FloatArray(PREFIX).apply {
        for (i in 0 until LENGTH) {
            var hash = i * 374761393 + 668265263
            hash = (hash xor (hash ushr 13)) * 1274126177
            hash = hash xor (hash ushr 16)
            this[MARK + i] = ((hash ushr 8) / 8388608f - 1) * .6f * sin(PI * i / (LENGTH - 1)).pow(2).toFloat()
        }
    }
    fun delay(pcm: FloatArray): Int {
        check(pcm.size >= (MARK + 4096 + LENGTH) * 2) { "AAC encoder timing probe is incomplete" }
        var best = -1.0; var offset = 0
        for (delay in 0..4096) {
            var dot = 0.0; var aa = 0.0; var bb = 0.0
            for (i in 0 until LENGTH) {
                val a = prefix[MARK + i].toDouble(); val b = pcm[(MARK + delay + i) * 2].toDouble()
                dot += a * b; aa += a * a; bb += b * b
            }
            val score = dot / sqrt(max(1e-20, aa * bb))
            if (score > best) { best = score; offset = delay }
        }
        check(best > .2) { "Cannot determine AAC encoder timing" }
        return offset
    }
    private data class Box(val type: String, val bytes: ByteArray)
    private fun children(data: ByteArray, from: Int = 0): List<Box> {
        val result = mutableListOf<Box>(); var at = from
        while (at < data.size) {
            require(at + 8 <= data.size)
            val size = ByteBuffer.wrap(data, at, 4).int
            require(size >= 8 && size <= data.size - at)
            result.add(Box(String(data, at + 4, 4, Charsets.US_ASCII), data.copyOfRange(at, at + size)))
            at += size
        }
        return result
    }
    private fun box(type: String, data: ByteArray) = ByteBuffer.allocate(data.size + 8).order(ByteOrder.BIG_ENDIAN)
        .putInt(data.size + 8).put(type.toByteArray(Charsets.US_ASCII)).put(data).array()
    private fun join(boxes: List<Box>) = boxes.fold(java.io.ByteArrayOutputStream()) { output, b -> output.apply { write(b.bytes) } }.toByteArray()
    private fun scale(box: Box): Long {
        val version = box.bytes[8].toInt()
        return ByteBuffer.wrap(box.bytes).getInt(if (version == 1) 28 else 20).toLong() and 0xffffffffL
    }
    private fun duration(box: Box): Long {
        val version = box.bytes[8].toInt()
        val at = if (box.type == "tkhd") { if (version == 1) 36 else 28 } else { if (version == 1) 32 else 24 }
        val data = ByteBuffer.wrap(box.bytes)
        return if (version == 1) data.getLong(at) else data.getInt(at).toLong() and 0xffffffffL
    }
    private fun duration(box: Box, value: Long): Box {
        val bytes = box.bytes.copyOf(); val version = bytes[8].toInt()
        val at = if (box.type == "tkhd") { if (version == 1) 36 else 28 } else { if (version == 1) 32 else 24 }
        if (version == 1) ByteBuffer.wrap(bytes).putLong(at, value)
        else { require(value <= 0xffffffffL); ByteBuffer.wrap(bytes).putInt(at, value.toInt()) }
        return Box(box.type, bytes)
    }
    private fun movie(io: RandomAccessFile): Pair<Long, ByteArray> {
        var at = 0L
        while (at + 8 <= io.length()) {
            io.seek(at)
            val short = io.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also { io.readFully(it) }.toString(Charsets.US_ASCII)
            val size = if (short == 1L) io.readLong() else if (short == 0L) io.length() - at else short
            require(size >= (if (short == 1L) 16 else 8) && size <= io.length() - at)
            if (type == "moov") {
                require(short != 1L && size <= 64 * 1024 * 1024)
                val data = ByteArray(size.toInt()); io.seek(at); io.readFully(data)
                return at to data
            }
            at += size
        }
        error("Missing movie timing")
    }
    fun presentedDuration(file: File): Double? = runCatching {
        RandomAccessFile(file, "r").use { io ->
            val header = children(movie(io).second, 8).single { it.type == "mvhd" }
            val ticks = scale(header)
            require(ticks > 0)
            (duration(header).toDouble() / ticks).takeIf { it.isFinite() && it > 0 }
        }
    }.getOrNull()
    fun trim(file: File, skipFrames: Int, duration: Double) {
        RandomAccessFile(file, "rw").use { io ->
            val (location, original) = movie(io)
            val movie = children(original, 8)
            val movieScale = scale(movie.single { it.type == "mvhd" })
            var edited = false
            val ticks = (duration * movieScale).roundToLong()
            var movieDuration = ticks
            val next = movie.map { track ->
                if (track.type != "trak") return@map track
                val parts = children(track.bytes, 8)
                val media = children(parts.single { it.type == "mdia" }.bytes, 8)
                val handler = media.single { it.type == "hdlr" }.bytes
                if (String(handler, 16, 4, Charsets.US_ASCII) != "soun") {
                    movieDuration = max(movieDuration, duration(parts.single { it.type == "tkhd" }))
                    return@map track
                }
                val mediaScale = scale(media.single { it.type == "mdhd" })
                val edit = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
                    .putInt(0x01000000).putInt(1).putLong((duration * movieScale).roundToLong())
                    .putLong((skipFrames.toDouble() * mediaScale / 48000).roundToLong()).putShort(1).putShort(0).array()
                val edts = Box("edts", box("edts", box("elst", edit)))
                edited = true
                Box("trak", box("trak", join(parts.filter { it.type != "edts" }.map { if (it.type == "tkhd") duration(it, ticks) else it } + edts)))
            }
            check(edited) { "Missing movie audio" }
            val rewritten = box("moov", join(next.map { if (it.type == "mvhd") duration(it, movieDuration) else it }))
            // Appending leaves all existing media offsets valid, including a
            // fast-start moov. The old index becomes a harmless free box.
            io.seek(location + 4); io.write("free".toByteArray(Charsets.US_ASCII))
            io.seek(io.length()); io.write(rewritten); io.fd.sync()
        }
    }
}
