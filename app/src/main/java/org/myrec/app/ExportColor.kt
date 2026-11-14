package org.myrec.app

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

enum class ExportColor { SDR, HLG }

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
