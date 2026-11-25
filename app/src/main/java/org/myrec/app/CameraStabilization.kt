package org.myrec.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest

internal enum class CameraStabilization {
    Electronic, Optical, Editor;

    companion object {
        fun mode(c: CameraCharacteristics): CameraStabilization = when {
            c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)==true -> Electronic
            c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)==true -> Optical
            else -> Editor
        }
        fun apply(c: CameraCharacteristics,r: CaptureRequest.Builder,enabled: Boolean) {
            val mode=mode(c)
            if (mode==Electronic) r.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (enabled) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            if (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)==true)
                r.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,if (enabled && mode==Optical) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
    }
}
