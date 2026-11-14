package org.myrec.app

import java.io.File
import java.io.RandomAccessFile

object PcmWave {
    fun duration(file: File): Double? = try {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 12 || input.readInt() != 0x52494646) return null
            input.skipBytes(4)
            if (input.readInt() != 0x57415645) return null
            var rate = 0L
            var align = 0
            var data = -1L
            var pcm = false
            while (input.filePointer + 8 <= input.length()) {
                val kind = input.readInt()
                val length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                val start = input.filePointer
                if (length > input.length() - start) return null
                if (kind == 0x666d7420 && length >= 16) {
                    val format = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                    input.skipBytes(2)
                    rate = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                    input.skipBytes(4)
                    align = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                    pcm = format == 1 || format == 3
                }
                if (kind == 0x64617461) data = length
                input.seek(start + length + length % 2)
            }
            if (pcm && rate > 0 && align > 0 && data > 0 && data % align == 0L)
                data.toDouble() / align / rate else null
        }
    } catch (_: Exception) { null }
}
