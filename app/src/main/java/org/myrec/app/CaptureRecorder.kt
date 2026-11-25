package org.myrec.app

import android.media.*
import android.os.Bundle
import android.view.Surface
import java.io.Closeable
import java.io.File
import kotlin.math.*

class CaptureRecorder(
    private val file: File,
    width: Int,
    height: Int,
    private val fps: Int,
    private val orientation: Int,
    hdr: Boolean,
    private val cache: AudioCache,
    private val failure: (Throwable) -> Unit,
) : Closeable {
    @Volatile var onVideoAccepted: ((Long,Double,Int)->Unit)?=null
    @Volatile var onVideoRejected: ((Long)->Unit)?=null
    private var acceptedFrames=0
    internal val clock = RecordingClock()
    private val video=if (hdr) MediaCodec.createByCodecName(HlgEncoder.name(width,height,fps)
        ?: error("No ten-bit HEVC encoder supports this size and frame rate")) else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val videoFile = CaptureSamples.video(file)
    private val audioFile = CaptureSamples.audio(file)
    internal val storedBytes get() = videoFile.length() + audioFile.length()
    private var audio: CaptureAudio? = null
    private var worker: Thread? = null
    @Volatile private var ended = false
    @Volatile private var resumeAfter = Long.MAX_VALUE
    @Volatile private var waitKey = true
    private var problem: Throwable? = null
    private var released = false
    private var completed = false
    @Volatile private var began = false
    @Volatile private var syncAt=Long.MAX_VALUE
    private var lastVideo = -1L
    @Volatile var elapsedSeconds=0.0
        private set
    internal var insertedAudioSilence=0L
        private set
    internal var audioClockCorrections=0L
        private set
    val surface: Surface

    init {
        try {
            val format=MediaFormat.createVideoFormat(if (hdr) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                if (hdr) setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD,if (hdr) MediaFormat.COLOR_STANDARD_BT2020 else MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER,if (hdr) MediaFormat.COLOR_TRANSFER_HLG else MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE,MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_BIT_RATE,width*height*8)
                setInteger(MediaFormat.KEY_FRAME_RATE,fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES,0)
            }
            video.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface=video.createInputSurface()
            video.start()
            worker=Thread({ drain() },"camera-encoder-output").apply { start() }
            audio=CaptureAudio(audioFile,clock,failure)
        } catch (e: Throwable) { close(); throw e }
    }
    fun appendAudio(pcm: FloatArray,timestamp: Long) { audio?.append(pcm,timestamp) }
    fun start() {
        began=true; resumeAfter=System.nanoTime(); clock.start(resumeAfter)
        syncAt=resumeAfter; syncFrame()
    }
    fun videoFrame(timestamp: Long) {
        if (timestamp>=syncAt) { syncAt=Long.MAX_VALUE; syncFrame() }
        if (began) clock.time(timestamp)?.let { elapsedSeconds=max(elapsedSeconds,it/1e9+1.0/fps) }
    }
    val pausedSeconds get()=clock.closedTime()/1e9
    fun pause() { clock.pause(System.nanoTime()) }
    fun resume() {
        resumeAfter=System.nanoTime(); clock.resume(resumeAfter)
        waitKey=true; syncAt=resumeAfter; syncFrame()
    }
    private fun syncFrame() { video.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0) }) }

    private fun drain() {
        var samples: CaptureSamples.Writer?=null
        val info=MediaCodec.BufferInfo()
        try {
            var idle=0
            while (true) {
                val index=video.dequeueOutputBuffer(info,10000)
                if (index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(samples==null) { "Camera encoder changed its output format during a take" }
                    samples=CaptureSamples.Writer(videoFile,video.outputFormat,orientation,fps)
                } else if (index>=0) {
                    idle=0
                    val original=info.presentationTimeUs*1000
                    val time=clock.time(original)?.div(1000)
                    val key=info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME!=0
                    var accepted=false
                    if (key && original>=resumeAfter) waitKey=false
                    if (info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0 && time!=null && !(waitKey && original>=resumeAfter)) {
                        if ((lastVideo>=0 || key) && samples!=null) {
                            info.presentationTimeUs=time.coerceAtLeast(lastVideo+1)
                            lastVideo=info.presentationTimeUs
                            samples.append(video.getOutputBuffer(index)!!,info)
                            onVideoAccepted?.invoke(original/1000,info.presentationTimeUs/1_000_000.0,acceptedFrames++)
                            accepted=true
                        }
                    }
                    if (!accepted) onVideoRejected?.invoke(original/1000)
                    val done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    video.releaseOutputBuffer(index,false)
                    if (done) break
                } else if (ended && ++idle>500) error("Camera encoder did not finish")
            }
        } catch (e: Throwable) { problem=e; if (!ended) failure(e) }
        finally { runCatching { samples?.close() }.onFailure { problem=it } }
    }

    fun stop() {
        clock.stop(System.nanoTime())
        ended=true
        video.signalEndOfInputStream()
        worker?.join(10000)
        check(worker?.isAlive!=true) { "Camera encoder timed out" }
        problem?.let { throw it }
        check(lastVideo>=0) { "Recording was too short to save" }
        val duration=(lastVideo+1_000_000L/fps)/1_000_000.0
        audio!!.finish((duration*48000).roundToLong())
        insertedAudioSilence=audio!!.insertedSilence
        audioClockCorrections=audio!!.clockCorrections
        CaptureSamples.seal(file,cache,duration)
        completed=true
    }
    override fun close() {
        if (released) return
        released=true; ended=true
        runCatching { video.signalEndOfInputStream() }
        worker?.join(6000)
        runCatching { video.stop() }; video.release()
        runCatching { surface.release() }
        audio?.close(); audio=null
        if (completed || !began || videoFile.length()==0L) videoFile.delete()
        if (completed || !began || audioFile.length()==0L) audioFile.delete()
    }
}
