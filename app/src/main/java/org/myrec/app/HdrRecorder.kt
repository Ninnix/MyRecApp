package org.myrec.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.*
import android.os.Build

object HdrRecorder {
    fun colorSpace(
        session: android.hardware.camera2.params.SessionConfiguration,
        chars: CameraCharacteristics,
        format: Int=android.graphics.ImageFormat.PRIVATE,
    ) {
        if (
            Build.VERSION.SDK_INT >= 34 &&
                chars
                    .get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
                    ?.getSupportedColorSpacesForDynamicRange(
                        format,
                        DynamicRangeProfiles.HLG10,
                    )
                    ?.contains(android.graphics.ColorSpace.Named.BT2020_HLG) == true
        )
            session.setColorSpace(android.graphics.ColorSpace.Named.BT2020_HLG)
    }

    fun planarSupported(chars: CameraCharacteristics,width: Int,height: Int): Boolean = android.os.Build.VERSION.SDK_INT>=33 &&
        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(android.graphics.ImageFormat.YCBCR_P010)?.any { it.width==width && it.height==height }==true

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
            if (HdrGraphics.support.planar && chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(android.graphics.ImageFormat.YCBCR_P010).isNullOrEmpty()) return false
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
