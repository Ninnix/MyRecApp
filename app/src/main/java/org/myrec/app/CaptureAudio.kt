package org.myrec.app

import android.media.*
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** One AAC sample clock survives input format negotiation and camera replacement. */
class CaptureAudio(
    private val file: File,
    private val cache: AudioCache,
    private val clock: RecordingClock,
    private val failure: (Throwable) -> Unit,
) : Closeable {
    private data class Block(val pcm: FloatArray, val timestamp: Long)
    private val queue = ArrayBlockingQueue<Block>(64)
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val normalizer = RecordingAudioClock()
    @Volatile private var finishAt = -1L
    private var finishDeadline = Long.MAX_VALUE
    @Volatile private var accepting = true
    @Volatile private var abort = false
    @Volatile private var problem: Throwable? = null
    private var track = -1
    private var started = false
    private var position = 0L
    private var outputDone = false
    private val info = MediaCodec.BufferInfo()
    private var worker: Thread? = null
    val insertedSilence get() = normalizer.insertedSilence
    val clockCorrections get() = normalizer.corrections

    init {
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,48000,2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE,192000)
            },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            worker = Thread({ run() },"capture-audio").apply { start() }
        } catch (e: Throwable) { close(); throw e }
    }
    fun append(pcm: FloatArray, timestamp: Long) {
        if (!accepting || abort || problem != null) return
        val block=Block(pcm,timestamp)
        if (!queue.offer(block)) {
            if (!clock.started) { queue.poll(); if (queue.offer(block)) return }
            val error = IllegalStateException("Microphone encoding could not keep up. Recording stopped safely.")
            problem = error
            failure(error)
        }
    }
    private fun drain(wait: Long = 0) {
        while (!outputDone) {
            val index = codec.dequeueOutputBuffer(info,wait)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
            } else if (index >= 0) {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    check(started)
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    muxer.writeSampleData(track,buffer,info)
                }
                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index,false)
            } else break
        }
    }
    private fun write(pcm: FloatArray, from: Int, frames: Int) {
        var offset = from
        var left = frames
        val deadline = System.nanoTime() + 5_000_000_000
        while (left > 0) {
            check(!abort && System.nanoTime() < deadline) { "Microphone encoder timed out" }
            val index = codec.dequeueInputBuffer(10000)
            if (index < 0) { drain(10000); continue }
            val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
            buffer.clear()
            val count = min(left, min(1024,buffer.capacity()/4))
            check(count > 0)
            for (i in offset*2 until (offset+count)*2) buffer.putShort((pcm[i].coerceIn(-1f,1f)*32767).roundToInt().toShort())
            codec.queueInputBuffer(index,0,count*4,position*1_000_000/48000,0)
            position += count; offset += count; left -= count
            drain()
        }
    }
    private fun run() {
        try {
            val prefix = FloatArray(Mp4Audio.PREFIX*2) { Mp4Audio.prefix[it/2] }
            write(prefix,0,Mp4Audio.PREFIX)
            val pending=java.util.ArrayDeque<Block>()
            var pendingFrames=0
            while (!abort) {
                val block = queue.poll(10,TimeUnit.MILLISECONDS)
                if (block!=null) { pending.addLast(block); pendingFrames+=block.pcm.size/2 }
                // The first picture can arrive with an earlier sensor timestamp.
                if (!clock.started) {
                    while (pendingFrames>24000) pendingFrames-=pending.removeFirst().pcm.size/2
                } else while (pending.isNotEmpty()) {
                    val next=pending.removeFirst(); pendingFrames-=next.pcm.size/2
                    normalizer.process(next.pcm,next.timestamp,clock,if (finishAt>=0) finishAt else Long.MAX_VALUE,::write)
                }
                if (finishAt>=0 && (normalizer.nextFrame>=finishAt || System.nanoTime()>=finishDeadline)) {
                    accepting=false
                    break
                }
            }
            check(!abort)
            normalizer.pad(finishAt,::write)
            write(FloatArray(Mp4Audio.TAIL*2),0,Mp4Audio.TAIL)
            var inputDone = false
            val deadline = System.nanoTime()+5_000_000_000
            while (!outputDone) {
                check(!abort && System.nanoTime()<deadline) { "Microphone encoder did not finish" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        codec.queueInputBuffer(index,0,0,position*1_000_000/48000,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
                drain(10000)
            }
        } catch (e: Throwable) {
            if (!abort) { problem = e; failure(e) }
        } finally {
            if (started) runCatching { muxer.stop() }.onFailure { if (!abort) problem = it }
        }
    }
    fun finish(frames: Long): Int {
        finishDeadline=System.nanoTime()+300_000_000
        finishAt = frames.coerceAtLeast(0)
        worker?.join(10000)
        check(worker?.isAlive != true) { "Microphone encoder timed out" }
        problem?.let { throw it }
        return Mp4Audio.delay(cache.head(file.path,12288))
    }
    override fun close() {
        abort = true
        worker?.join(6000)
        runCatching { codec.stop() }
        codec.release(); muxer.release()
    }
}
