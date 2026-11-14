package org.myrec.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.*
import android.os.Build
import android.os.Bundle
import android.view.Surface
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

class HdrRecorder(
    context: Context,
    private val file: File,
    width: Int,
    height: Int,
    fps: Int,
    private val orientation: Int,
    microphone: AudioDeviceInfo,
    source: Int,
    channels: Int,
    private val failure: (Throwable) -> Unit,
) : Closeable {
    private val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
    private val audio = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
    private val videoFile = File(file.path + ".video.mp4")
    private val audioFile = File(file.path + ".audio.m4a")
    private var muxer: MediaMuxer? = null
    private var worker: Thread? = null
    private var released = false
    @Volatile private var ended = false
    @Volatile private var epoch = 0L
    @Volatile private var pauseAt = 0L
    @Volatile private var pauseTime = 0L
    @Volatile private var waitKey = false
    private var error: Throwable? = null
    val surface: Surface

    val amplitude
        get() = audio.maxAmplitude

    val configuration
        get() = if (Build.VERSION.SDK_INT >= 29) audio.activeRecordingConfiguration else null

    fun microphone(device: AudioDeviceInfo) = audio.setPreferredDevice(device)

    init {
        try {
            val format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
                    .apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                        )
                        setInteger(
                            MediaFormat.KEY_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                        )
                        setInteger(
                            MediaFormat.KEY_COLOR_STANDARD,
                            MediaFormat.COLOR_STANDARD_BT2020,
                        )
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                        setInteger(MediaFormat.KEY_BIT_RATE, width * height * 8)
                        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    }
            video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = video.createInputSurface()
            muxer = MediaMuxer(videoFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            audio.setAudioSource(source)
            audio.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            audio.setOutputFile(audioFile.path)
            audio.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            audio.setAudioSamplingRate(48000)
            audio.setAudioChannels(channels)
            audio.setAudioEncodingBitRate(192000)
            check(audio.setPreferredDevice(microphone)) { "The selected microphone is unavailable" }
            audio.setOnErrorListener { _, _, _ ->
                failure(IllegalStateException("Microphone recording interrupted"))
            }
            audio.prepare()
            video.start()
            worker = Thread({ drain() }, "hdr-encoder").apply { start() }
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    fun start() {
        epoch = System.nanoTime() / 1000
        audio.start()
        video.setParameters(
            Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
        )
    }

    fun pause() {
        audio.pause()
        pauseAt = System.nanoTime() / 1000
    }

    fun resume() {
        pauseTime += System.nanoTime() / 1000 - pauseAt
        waitKey = true
        pauseAt = 0
        audio.resume()
        video.setParameters(
            Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
        )
    }

    private fun drain() {
        var track = -1
        var muxing = false
        var last = -1L
        val info = MediaCodec.BufferInfo()
        try {
            var idle = 0
            while (true) {
                val index = video.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer!!.addTrack(video.outputFormat)
                    muxer!!.start()
                    muxing = true
                } else if (index >= 0) {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (waitKey && key) waitKey = false
                    if (
                        info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            epoch > 0 &&
                            pauseAt == 0L &&
                            !waitKey
                    ) {
                        if (last < 0 && !key) {
                            video.releaseOutputBuffer(index, false)
                            continue
                        }
                        val time = info.presentationTimeUs - epoch - pauseTime
                        if (time >= 0 && muxing) {
                            info.presentationTimeUs = time.coerceAtLeast(last + 1)
                            last = info.presentationTimeUs
                            muxer!!.writeSampleData(track, video.getOutputBuffer(index)!!, info)
                        }
                    }
                    val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    video.releaseOutputBuffer(index, false)
                    if (done) break
                } else if (ended && ++idle > 500) error("HDR encoder did not finish")
            }
        } catch (e: Throwable) {
            error = e
            if (!ended) failure(e)
        } finally {
            if (muxing)
                try {
                    muxer?.stop()
                } catch (e: Throwable) {
                    error = e
                }
        }
    }

    fun stop() {
        audio.stop()
        ended = true
        video.signalEndOfInputStream()
        worker?.join(10000)
        check(worker?.isAlive != true) { "HDR encoder timed out" }
        error?.let { throw it }
        muxer?.release()
        muxer = null
        merge()
    }

    private fun merge() {
        val output = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = listOf(MediaExtractor(), MediaExtractor())
        var muxing = false
        try {
            output.setOrientationHint(orientation)
            val tracks =
                listOf(videoFile, audioFile).mapIndexed { index, part ->
                    val ex = extractors[index]
                    ex.setDataSource(part.path)
                    ex.selectTrack(0)
                    output.addTrack(ex.getTrackFormat(0))
                }
            output.start()
            muxing = true
            val buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            extractors.forEachIndexed { index, ex ->
                while (true) {
                    buffer.clear()
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(
                        0,
                        size,
                        ex.sampleTime,
                        if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        else 0,
                    )
                    output.writeSampleData(tracks[index], buffer, info)
                    ex.advance()
                }
            }
        } finally {
            if (muxing) output.stop()
            output.release()
            extractors.forEach { it.release() }
        }
    }

    override fun close() {
        if (released) return
        released = true
        ended = true
        try {
            video.signalEndOfInputStream()
        } catch (_: Exception) {}
        worker?.join(6000)
        try {
            video.stop()
        } catch (_: Exception) {}
        video.release()
        runCatching { surface.release() }
        audio.release()
        muxer?.release()
        muxer = null
        videoFile.delete()
        audioFile.delete()
    }

    companion object {
        fun colorSpace(
            session: android.hardware.camera2.params.SessionConfiguration,
            chars: CameraCharacteristics,
        ) {
            if (
                Build.VERSION.SDK_INT >= 34 &&
                    chars
                        .get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
                        ?.getSupportedColorSpacesForDynamicRange(
                            android.graphics.ImageFormat.PRIVATE,
                            DynamicRangeProfiles.HLG10,
                        )
                        ?.contains(android.graphics.ColorSpace.Named.BT2020_HLG) == true
            )
                session.setColorSpace(android.graphics.ColorSpace.Named.BT2020_HLG)
        }

        fun supported(manager: CameraManager, id: String): Boolean {
            if (Build.VERSION.SDK_INT < 33 || id.isEmpty()) return false
            return try {
                val chars = manager.getCameraCharacteristics(id)
                if (
                    chars
                        .get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                        ?.supportedProfiles
                        ?.contains(DynamicRangeProfiles.HLG10) != true
                )
                    return false
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                    codec.isEncoder &&
                        codec.supportedTypes.any {
                            it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true)
                        } &&
                        codec
                            .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                            .profileLevels
                            .any {
                                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                            }
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
