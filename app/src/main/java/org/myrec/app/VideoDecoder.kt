package org.myrec.app

import android.graphics.Bitmap
import android.media.*
import android.view.Surface
import java.io.Closeable
import java.io.File
import kotlin.math.*

class VideoDecoder(path: String, private val rawColor: Boolean = false, private val linear: Boolean = false,
    private val tenBit: Boolean = false, softwareOnly: Boolean = false, private val surface: Surface? = null,
    surfaceAspect: Double = 0.0) : Closeable {
    private val mirrored = SourceMirror.enabled(path)
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val configuredFormat: MediaFormat
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var lastTime = -1L
    private var surfaceTime = 0L
    private var last: Bitmap? = null
    private val rotation: Int
    private var standard = MediaFormat.COLOR_STANDARD_BT709
    private var fullRange = false
    private var transfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    private val sourceHdr: Boolean
    private val primaries =
        try {
            File(path + ".primaries").readText().trim().toInt().takeIf { it in 0..2 } ?: 0
        } catch (_: Exception) {
            0
        }

    init {
        val format = try {
            extractor.setDataSource(path)
            val track =
            (0 until extractor.trackCount).firstOrNull {
                extractor
                    .getTrackFormat(it)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: error("No video track")
            extractor.selectTrack(track)
            extractor.getTrackFormat(track)
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
        rotation =
            if (format.containsKey(MediaFormat.KEY_ROTATION))
                format.getInteger(MediaFormat.KEY_ROTATION)
            else 0
        if (surface == null) {
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
            transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        sourceHdr = transfer in listOf(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084)
        if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD))
            standard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
        if (surface != null) {
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            val aspect = if (rotation % 180 == 0) w.toDouble() / h else h.toDouble() / w
            fun pixelRatio(key: String) = if (format.containsKey(key)) format.getInteger(key) else 1
            if (transfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO || mirrored || primaries != 0 ||
                standard == MediaFormat.COLOR_STANDARD_BT2020 || abs(aspect / surfaceAspect - 1) > .001 ||
                pixelRatio("sar-width") != pixelRatio("sar-height")) {
                extractor.release()
                throw IllegalArgumentException("This video requires composition")
            }
        }
        if (surface == null &&
            (linear || android.os.Build.VERSION.SDK_INT >= 31) &&
                transfer in
                    listOf(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084)
        )
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010,
            )
        configuredFormat = format
        try {
            codec = VideoCodecs.decoder(format, softwareOnly)
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
        try {
            try {
                codec.configure(format, surface, null, 0)
            } catch (e: IllegalArgumentException) {
                if (surface != null) throw e
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
                codec.configure(format, null, null, 0)
            }
            codec.start()
        } catch (e: Exception) {
            codec.release()
            extractor.release()
            throw e
        }
    }

    fun draw(time: Double, cancelled: () -> Boolean): Long? {
        check(surface != null)
        frame(time, 0, 0, cancelled)
        return surfaceTime.takeIf { it != 0L && !cancelled() }
    }

    fun frame(time: Double, width: Int, height: Int, cancelled: () -> Boolean = { false }): Bitmap? {
        val target = (time.coerceAtLeast(0.0) * 1_000_000).toLong()
        if (last != null && target in lastTime - 2..lastTime)
            return last!!.copy(last!!.config!!, true)
        if (surface != null && target in lastTime - 100000..lastTime) return null
        if (lastTime >= 0 && (target < lastTime - 2 && surface == null || target > lastTime + 750000) ||
            surface != null && (lastTime < 0 && target > 750000 || lastTime >= 0 && target < lastTime - 100000)) {
            extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // Some Main10 decoders stop producing images after flush. Restart with the
            // stream headers at the keyframe; sequential playback keeps the same codec.
            if (sourceHdr) {
                codec.stop()
                codec.configure(configuredFormat,null,null,0)
                codec.start()
            } else codec.flush()
            inputDone = false
            outputDone = false
            lastTime = -1
            surfaceTime = 0
        }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!outputDone && !cancelled() && System.nanoTime() < deadline) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(0)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, 1000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                    transfer = f.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                if (f.containsKey(MediaFormat.KEY_COLOR_STANDARD))
                    standard = f.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                if (f.containsKey(MediaFormat.KEY_COLOR_RANGE))
                    fullRange =
                        f.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
            } else if (index >= 0) {
                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val wanted =
                    info.size > 0 && (info.presentationTimeUs >= target - 1000 || outputDone)
                if (surface != null) {
                    if (wanted && !cancelled()) {
                        lastTime = info.presentationTimeUs
                        surfaceTime = System.nanoTime()
                        codec.releaseOutputBuffer(index, surfaceTime)
                        return null
                    }
                    codec.releaseOutputBuffer(index, false)
                    continue
                }
                if (wanted) {
                    val image = codec.getOutputImage(index)
                    if (image != null) {
                        try {
                            val crop = image.cropRect
                            val rotated = rotation % 180 != 0
                            val sourceW = if (rotated) crop.height() else crop.width()
                            val sourceH = if (rotated) crop.width() else crop.height()
                            val scale = min(width.toFloat() / sourceW, height.toFloat() / sourceH)
                            val bitDepth = if (image.format == android.graphics.ImageFormat.YCBCR_P010) 10 else 8
                            if (tenBit && sourceHdr)
                                check(bitDepth == 10 && transfer in listOf(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084)) { "This decoder does not expose 10-bit HDR samples" }
                            val bitmap = if (linear) WorkingColor.bitmap(max(2, (sourceW * scale).toInt()), max(2, (sourceH * scale).toInt()))
                                else Bitmap.createBitmap(max(2, (sourceW * scale).toInt()), max(2, (sourceH * scale).toInt()), Bitmap.Config.ARGB_8888)
                            val planes = image.planes
                            Native.yuv(
                                planes[0].buffer.slice(),
                                planes[1].buffer.slice(),
                                planes[2].buffer.slice(),
                                planes[0].rowStride,
                                planes[1].rowStride,
                                planes[2].rowStride,
                                planes[1].pixelStride,
                                planes[2].pixelStride,
                                crop.left,
                                crop.top,
                                crop.width(),
                                crop.height(),
                                rotation,
                                standard,
                                fullRange,
                                bitmap,
                                bitDepth,
                                if (rawColor) -1 else transfer,
                                primaries,
                            )
                            last?.recycle()
                            last = if (mirrored) SourceMirror.flip(bitmap) else bitmap
                            lastTime = info.presentationTimeUs
                        } finally {
                            image.close()
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false)
                if (wanted) return last?.let { it.copy(it.config!!, true) }
            }
        }
        return last?.let { it.copy(it.config!!, true) }
    }

    override fun close() {
        try {
            codec.stop()
        } finally {
            codec.release()
            extractor.release()
            last?.recycle()
            last = null
        }
    }
}
