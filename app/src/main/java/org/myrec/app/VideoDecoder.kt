package org.myrec.app

import android.graphics.Bitmap
import android.media.*
import java.io.Closeable
import java.io.File
import kotlin.math.*

class VideoDecoder(path: String) : Closeable {
    private val mirrored = SourceMirror.enabled(path)
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var lastTime = -1L
    private var last: Bitmap? = null
    private val rotation: Int
    private var standard = MediaFormat.COLOR_STANDARD_BT709
    private var fullRange = false
    private var transfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    private val primaries =
        try {
            File(path + ".primaries").readText().trim().toInt().takeIf { it in 0..2 } ?: 0
        } catch (_: Exception) {
            0
        }

    init {
        extractor.setDataSource(path)
        val track =
            (0 until extractor.trackCount).firstOrNull {
                extractor
                    .getTrackFormat(it)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: error("No video track")
        val format = extractor.getTrackFormat(track)
        rotation =
            if (format.containsKey(MediaFormat.KEY_ROTATION))
                format.getInteger(MediaFormat.KEY_ROTATION)
            else 0
        format.setInteger(MediaFormat.KEY_ROTATION, 0)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
            transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD))
            standard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
        if (
            android.os.Build.VERSION.SDK_INT >= 31 &&
                transfer in
                    listOf(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084)
        )
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010,
            )
        extractor.selectTrack(track)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            try {
                codec.configure(format, null, null, 0)
            } catch (e: IllegalArgumentException) {
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

    fun frame(time: Double, width: Int, height: Int): Bitmap? {
        val target = (time.coerceAtLeast(0.0) * 1_000_000).toLong()
        if (last != null && target in lastTime - 20000..lastTime)
            return last!!.copy(Bitmap.Config.ARGB_8888, true)
        if (lastTime >= 0 && (target < lastTime - 20000 || target > lastTime + 750000)) {
            extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush()
            inputDone = false
            outputDone = false
            lastTime = -1
        }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!outputDone && System.nanoTime() < deadline) {
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
                if (wanted) {
                    val image = codec.getOutputImage(index)
                    if (image != null) {
                        try {
                            val crop = image.cropRect
                            val rotated = rotation % 180 != 0
                            val sourceW = if (rotated) crop.height() else crop.width()
                            val sourceH = if (rotated) crop.width() else crop.height()
                            val scale = min(width.toFloat() / sourceW, height.toFloat() / sourceH)
                            val bitmap =
                                Bitmap.createBitmap(
                                    max(2, (sourceW * scale).toInt()),
                                    max(2, (sourceH * scale).toInt()),
                                    Bitmap.Config.ARGB_8888,
                                )
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
                                if (
                                    android.os.Build.VERSION.SDK_INT >= 31 &&
                                        image.format == android.graphics.ImageFormat.YCBCR_P010
                                )
                                    10
                                else 8,
                                transfer,
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
                if (wanted) return last?.copy(Bitmap.Config.ARGB_8888, true)
            }
        }
        return last?.copy(Bitmap.Config.ARGB_8888, true)
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
