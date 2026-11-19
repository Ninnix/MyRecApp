package org.myrec.app

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlin.math.roundToInt

enum class ExportColor(val label: String, val mime: String) {
    SDR("SDR · H.264", "video/avc"), HLG("HDR · HEVC 10-bit HLG", "video/hevc"), SDR_HEVC("SDR · HEVC", "video/hevc")
}

enum class ExportResolution(val label: String, private val longEdge: Int, private val shortEdge: Int) {
    Project("Project size", 0, 0), HD("720p", 1280, 720), FullHD("1080p", 1920, 1080), UHD("4K", 3840, 2160);

    fun size(width: Int, height: Int): Pair<Int, Int> {
        val scale = if (this == Project) 1.0 else minOf(longEdge.toDouble() / maxOf(width, height), shortEdge.toDouble() / minOf(width, height))
        fun even(value: Int) = ((value * scale / 2).roundToInt() * 2).coerceAtLeast(2)
        return even(width) to even(height)
    }
}

data class MovieSettings(val resolution: ExportResolution = ExportResolution.Project, val fps: Int? = null,
                         val color: ExportColor = ExportColor.SDR) {
    companion object { val frameRates = listOf(24, 25, 30, 50, 60) }
}

object HlgEncoder {
    fun name(width: Int, height: Int, fps: Int): String? = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { codec ->
        if (!codec.isEncoder || codec.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }) false
        else try {
            val caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 } &&
                caps.videoCapabilities?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
        } catch (_: Exception) { false }
    }?.name
}
