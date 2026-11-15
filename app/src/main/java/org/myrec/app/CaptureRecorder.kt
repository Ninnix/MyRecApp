package org.myrec.app

import android.media.*
import android.os.Bundle
import android.view.Surface
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.*

class CaptureRecorder(
    private val file: File,
    width: Int,
    height: Int,
    private val fps: Int,
    private val orientation: Int,
    hdr: Boolean,
    cache: AudioCache,
    private val failure: (Throwable) -> Unit,
) : Closeable {
    internal val clock = RecordingClock()
    private val video = if (hdr) MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC) else null
    @Suppress("DEPRECATION") private val sdr = if (hdr) null else MediaRecorder()
    private val videoFile = File(file.path+".video.mp4")
    private val audioFile = File(file.path+".audio.m4a")
    private var audio: CaptureAudio? = null
    private var muxer: MediaMuxer? = null
    private var worker: Thread? = null
    @Volatile private var ended = false
    @Volatile private var resumeAfter = Long.MAX_VALUE
    @Volatile private var waitKey = true
    private var problem: Throwable? = null
    private var released = false
    private var completed = false
    @Volatile private var began = false
    @Volatile private var firstVideo = Long.MIN_VALUE
    private var lastVideo = -1L
    internal var insertedAudioSilence=0L
        private set
    internal var audioClockCorrections=0L
        private set
    val surface: Surface

    init {
        try {
            if (sdr!=null) {
                sdr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                sdr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                sdr.setOutputFile(videoFile.path)
                sdr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                sdr.setVideoSize(width,height)
                sdr.setVideoFrameRate(fps)
                sdr.setVideoEncodingBitRate(width*height*8)
                sdr.setOnErrorListener { _,_,_ -> failure(IllegalStateException("Camera recording interrupted")) }
                sdr.prepare()
                surface=sdr.surface
            } else {
                val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC,width,height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD,MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.COLOR_TRANSFER_HLG)
                    setInteger(MediaFormat.KEY_COLOR_RANGE,MediaFormat.COLOR_RANGE_LIMITED)
                    setInteger(MediaFormat.KEY_BIT_RATE,width*height*8)
                    setInteger(MediaFormat.KEY_FRAME_RATE,fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES,0)
                }
                video!!.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface=video.createInputSurface()
                muxer=MediaMuxer(videoFile.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                video.start()
                worker=Thread({ drain() },"camera-encoder-output").apply { start() }
            }
            audio=CaptureAudio(audioFile,cache,clock,failure)
        } catch (e: Throwable) { close(); throw e }
    }
    fun appendAudio(pcm: FloatArray,timestamp: Long) { audio?.append(pcm,timestamp) }
    fun start() {
        began=true
        if (sdr!=null) sdr.start()
        else { resumeAfter=System.nanoTime(); clock.start(resumeAfter); syncFrame() }
    }
    fun videoFrame(timestamp: Long) {
        if (sdr!=null && began && firstVideo==Long.MIN_VALUE) {
            firstVideo=timestamp
            clock.start(timestamp)
        }
    }
    fun pause() { clock.pause(System.nanoTime()); sdr?.pause() }
    fun resume() {
        resumeAfter=System.nanoTime(); clock.resume(resumeAfter)
        if (sdr!=null) sdr.resume() else { waitKey=true; syncFrame() }
    }
    private fun syncFrame() { video?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0) }) }

    private fun drain() {
        var track=-1
        var muxing=false
        val info=MediaCodec.BufferInfo()
        try {
            var idle=0
            while (true) {
                val index=video!!.dequeueOutputBuffer(info,10000)
                if (index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track=muxer!!.addTrack(video.outputFormat); muxer!!.start(); muxing=true
                } else if (index>=0) {
                    idle=0
                    val original=info.presentationTimeUs*1000
                    val time=clock.time(original)?.div(1000)
                    val key=info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME!=0
                    if (key && original>=resumeAfter) waitKey=false
                    if (info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0 && time!=null && !(waitKey && original>=resumeAfter)) {
                        if ((lastVideo>=0 || key) && muxing) {
                            info.presentationTimeUs=time.coerceAtLeast(lastVideo+1)
                            lastVideo=info.presentationTimeUs
                            muxer!!.writeSampleData(track,video.getOutputBuffer(index)!!,info)
                        }
                    }
                    val done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    video.releaseOutputBuffer(index,false)
                    if (done) break
                } else if (ended && ++idle>500) error("Camera encoder did not finish")
            }
        } catch (e: Throwable) { problem=e; if (!ended) failure(e) }
        finally { if (muxing) runCatching { muxer?.stop() }.onFailure { problem=it } }
    }

    fun stop() {
        clock.stop(System.nanoTime())
        ended=true
        val duration = if (sdr!=null) {
            sdr.stop()
            val ex=MediaExtractor()
            try {
                ex.setDataSource(videoFile.path)
                ex.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION)/1_000_000.0
            } finally { ex.release() }
        } else {
            video!!.signalEndOfInputStream()
            worker?.join(10000)
            check(worker?.isAlive!=true) { "Camera encoder timed out" }
            problem?.let { throw it }
            check(lastVideo>=0) { "Recording was too short to save" }
            muxer?.release(); muxer=null
            (lastVideo+1_000_000L/fps)/1_000_000.0
        }
        val priming=audio!!.finish((duration*48000).roundToLong())
        insertedAudioSilence=audio!!.insertedSilence
        audioClockCorrections=audio!!.clockCorrections
        merge()
        Mp4Audio.trim(file,Mp4Audio.PREFIX+priming,duration)
        completed=true
    }
    private fun merge() {
        val output=MediaMuxer(file.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors=listOf(MediaExtractor(),MediaExtractor())
        var muxing=false
        try {
            output.setOrientationHint(orientation)
            val tracks=listOf(videoFile,audioFile).mapIndexed { index,part ->
                val ex=extractors[index]; ex.setDataSource(part.path); ex.selectTrack(0)
                output.addTrack(ex.getTrackFormat(0))
            }
            output.start(); muxing=true
            val buffer=ByteBuffer.allocateDirect(16*1024*1024)
            val info=MediaCodec.BufferInfo()
            extractors.forEachIndexed { index,ex ->
                while (true) {
                    buffer.clear()
                    val size=ex.readSampleData(buffer,0)
                    if (size<0) break
                    info.set(0,size,ex.sampleTime,if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    output.writeSampleData(tracks[index],buffer,info); ex.advance()
                }
            }
        } finally {
            try { if (muxing) output.stop() } finally { output.release(); extractors.forEach { it.release() } }
        }
    }
    override fun close() {
        if (released) return
        released=true; ended=true
        runCatching { video?.signalEndOfInputStream() }
        worker?.join(6000)
        runCatching { video?.stop() }; video?.release(); sdr?.release()
        runCatching { surface.release() }
        audio?.close(); audio=null
        muxer?.release(); muxer=null
        if (completed || !began || videoFile.length()==0L) videoFile.delete()
        if (completed || !began || audioFile.length()==0L) audioFile.delete()
    }
}
