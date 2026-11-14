package org.myrec.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

object VideoFrameIndex {
    private val cache = linkedMapOf<String, FrameIndex>()
    @Synchronized fun get(path: String, progress: (Float?) -> Unit): FrameIndex {
        val file = File(path); val key = "$path:${file.length()}:${file.lastModified()}:decoded-pts-1"
        cache[key]?.let { return it }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        progress(0f)
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val format = extractor.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
            extractor.selectTrack(track)
            codec = VideoCodecs.decoder(format)
            codec.configure(format, null, null, 0); codec.start()
            val times = mutableListOf<Double>(); val info = MediaCodec.BufferInfo()
            var inputDone = false; var done = false; var lastOutput = System.nanoTime()
            while (!done) {
                check(System.nanoTime() - lastOutput < 30_000_000_000L) { "Source frame indexing timed out" }
                if (!inputDone) {
                    val input = codec.dequeueInputBuffer(0)
                    if (input >= 0) {
                        val buffer = codec.getInputBuffer(input)!!
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) { codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(input, 0, count, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 1000)
                if (output >= 0) {
                    lastOutput = System.nanoTime()
                    if (info.size > 0 && info.presentationTimeUs >= 0 && info.flags and MediaCodec.BUFFER_FLAG_DECODE_ONLY == 0) {
                        check(times.size < 3000000) { "Too many source frames" }
                        times.add(info.presentationTimeUs / 1_000_000.0)
                    }
                    done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                    if (duration > 0) progress((info.presentationTimeUs.toFloat() / duration).coerceIn(0f, 1f))
                }
            }
            val index = FrameIndex(times.distinct().sorted().toDoubleArray())
            while (cache.isNotEmpty() && (cache.size >= 8 || cache.values.sumOf { it.times.size * 8L } + index.times.size * 8L > 32L * 1024 * 1024))
                cache.remove(cache.keys.first())
            cache[key] = index
            return index
        } finally {
            try { codec?.stop() } finally { codec?.release(); extractor.release(); progress(null) }
        }
    }
}
