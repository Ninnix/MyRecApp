package org.myrec.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.math.min

/** Store encoded samples before an MP4 index exists so a killed recorder can still be finalized. */
internal object CaptureSamples {
    private const val MAGIC = 0x4d525331
    private const val MAX_SAMPLE = 32 * 1024 * 1024
    private const val MAX_CONFIG = 64 * 1024
    private const val SAMPLE_HEADER = 20
    val suffixes = listOf(".video.samples", ".audio.samples")
    private val formatKeys = listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT,
        MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE,
        MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT, MediaFormat.KEY_PROFILE)

    fun video(file: File) = File(file.path + suffixes[0])
    fun audio(file: File) = File(file.path + suffixes[1])

    class Writer(file: File, format: MediaFormat, private val orientation: Int = 0, private val fps: Int = 0) : Closeable {
        private val output = FileOutputStream(file)
        private val header = ByteBuffer.allocate(SAMPLE_HEADER)
        private val checksum = CRC32()
        private var lastSync = System.nanoTime()

        init {
            try {
                val config = java.io.ByteArrayOutputStream()
                java.io.DataOutputStream(config).use { data ->
                    data.writeInt(MAGIC)
                    data.writeInt(orientation)
                    data.writeInt(fps)
                    data.writeUTF(format.getString(MediaFormat.KEY_MIME)!!)
                    for (key in formatKeys) data.writeInt(if (format.containsKey(key)) format.getInteger(key) else -1)
                    for (i in 0..2) {
                        val source = format.getByteBuffer("csd-$i")?.duplicate()
                        val size = source?.remaining() ?: 0
                        require(size in 0..MAX_CONFIG)
                        data.writeInt(size)
                        if (source != null) data.write(ByteArray(size).also { source.get(it) })
                    }
                }
                output.write(config.toByteArray())
                output.fd.sync()
                syncDirectory(file.parentFile!!)
            } catch (failure: Throwable) {
                output.close()
                throw failure
            }
        }

        fun append(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            require(info.size in 1..MAX_SAMPLE)
            val sample = buffer.duplicate().apply { limit(info.offset + info.size); position(info.offset) }
            header.clear()
            header.putInt(info.size).putLong(info.presentationTimeUs)
                .putInt(info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
            checksum.reset()
            checksum.update(header.array(), 0, SAMPLE_HEADER - 4)
            checksum.update(sample.duplicate())
            header.putInt(checksum.value.toInt())
            output.write(header.array())
            while (sample.hasRemaining()) output.channel.write(sample)
            val now = System.nanoTime()
            if (now - lastSync >= 1_000_000_000L) {
                output.fd.sync()
                lastSync = now
            }
        }

        override fun close() {
            output.use { it.fd.sync() }
        }
    }

    internal class Reader(file: File) : Closeable {
        private val input = RandomAccessFile(file,"r")
        private val header = ByteArray(SAMPLE_HEADER)
        private val checksum = CRC32()
        private var buffer = ByteBuffer.allocateDirect(0)
        private var previous = -1L
        val info = MediaCodec.BufferInfo()
        val format = MediaFormat()
        val orientation: Int
        val fps: Int

        init {
            try {
                require(input.readInt()==MAGIC) { "Unknown recording sample journal" }
                orientation=input.readInt()
                fps=input.readInt()
                val mime=input.readUTF()
                require(orientation in listOf(0,90,180,270) && fps in 0..120)
                require(mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC,MediaFormat.MIMETYPE_VIDEO_HEVC,MediaFormat.MIMETYPE_AUDIO_AAC))
                format.setString(MediaFormat.KEY_MIME,mime)
                for (key in formatKeys) input.readInt().takeIf { it>=0 }?.let { format.setInteger(key,it) }
                for (i in 0..2) {
                    val size=input.readInt()
                    require(size in 0..MAX_CONFIG)
                    if (size>0) format.setByteBuffer("csd-$i",ByteBuffer.wrap(ByteArray(size).also { input.readFully(it) }))
                }
                require(format.containsKey("csd-0")) { "Missing recording codec configuration" }
            } catch (failure: Throwable) {
                input.close()
                throw failure
            }
        }

        // Stop at the first incomplete or damaged record. Later interframes may depend on it.
        fun next(): ByteBuffer? {
            if (input.length()-input.filePointer<SAMPLE_HEADER) return null
            input.readFully(header)
            val data=ByteBuffer.wrap(header)
            val size=data.int
            val time=data.long
            val flags=data.int
            val expected=data.int
            if (size !in 1..MAX_SAMPLE || size>input.length()-input.filePointer || time<=previous ||
                flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != flags) return null
            if (buffer.capacity()<size) buffer=ByteBuffer.allocateDirect(maxOf(1,Integer.highestOneBit(size-1)*2))
            buffer.clear()
            buffer.limit(size)
            while (buffer.hasRemaining()) if (input.channel.read(buffer)<0) return null
            buffer.flip()
            checksum.reset()
            checksum.update(header,0,SAMPLE_HEADER-4)
            checksum.update(buffer.duplicate())
            if (checksum.value.toInt()!=expected) return null
            info.set(0,size,time,flags)
            previous=time
            return buffer
        }

        override fun close() = input.close()
    }

    fun seal(file: File, cache: AudioCache, expectedDuration: Double? = null) {
        val picture=video(file)
        val sound=audio(file)
        check(file.parentFile!!.usableSpace>=picture.length()+sound.length()) { "Not enough space to finalize the recording" }
        val partial=File(file.path+".finalizing.mp4")
        val sealed=File(file.path+".sealed.mp4")
        try {
            val audio = mux(picture,sound,partial,expectedDuration!=null)
            val duration=audio.videoDuration
            val skip=if (audio.lastAudio>=0) runCatching {
                Mp4Audio.PREFIX+Mp4Audio.delay(cache.head(partial.path,12288))
            }.getOrElse {
                if (expectedDuration!=null) throw it
                0
            } else 0
            if (expectedDuration!=null) {
                check(skip>0 && kotlin.math.abs(expectedDuration-duration)<.001) { "The recording sample journal was interrupted" }
                Mp4Audio.trim(partial,skip,expectedDuration)
            } else if (skip>0 && audio.lastAudio/1_000_000.0+(1024-skip)/48000.0>0) {
                Mp4Audio.trim(partial,skip,min(duration,audio.lastAudio/1_000_000.0+(1024-skip)/48000.0))
            } else if (audio.lastAudio>=0) {
                // Never expose the internal timing probe as part of a recovered take.
                mux(picture,null,partial,false)
            }
            FileOutputStream(partial,true).use { it.fd.sync() }
            Os.rename(partial.path,sealed.path)
            syncDirectory(file.parentFile!!)
            Os.rename(sealed.path,file.path)
            syncDirectory(file.parentFile!!)
        } finally { partial.delete() }
    }

    private data class Timing(val videoDuration: Double, val lastAudio: Long)

    private fun mux(picture: File,sound: File?,partial: File,requireAudio: Boolean): Timing = Reader(picture).use { video ->
        require(video.fps>0 && video.format.getString(MediaFormat.KEY_MIME)!!.startsWith("video/"))
        val audio=if (requireAudio) Reader(checkNotNull(sound))
            else sound?.takeIf { it.length()>0 }?.let { runCatching { Reader(it) }.getOrNull() }
        audio.use {
            var image=video.next()
            check(image!=null && video.info.flags==MediaCodec.BUFFER_FLAG_KEY_FRAME) { "No complete key frame in this recording" }
            var samples=audio?.next()
            val output=MediaMuxer(partial.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var lastAudio=-1L
            var lastVideo=-1L
            try {
                output.setOrientationHint(video.orientation)
                val videoTrack=output.addTrack(video.format)
                val audioTrack=if (samples!=null) output.addTrack(checkNotNull(audio).format) else -1
                output.start()
                while (image!=null || samples!=null) {
                    if (samples!=null && (image==null || audio!!.info.presentationTimeUs<video.info.presentationTimeUs)) {
                        val input=checkNotNull(audio)
                        output.writeSampleData(audioTrack,samples,input.info)
                        lastAudio=input.info.presentationTimeUs
                        samples=input.next()
                    } else {
                        output.writeSampleData(videoTrack,checkNotNull(image),video.info)
                        lastVideo=video.info.presentationTimeUs
                        image=video.next()
                    }
                }
                output.stop()
            } finally { output.release() }
            Timing((lastVideo+1_000_000.0/video.fps)/1_000_000.0,lastAudio)
        }
    }

    fun clean(file: File) {
        video(file).delete()
        audio(file).delete()
    }
}
