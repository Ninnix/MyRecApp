package org.yourec.app

import android.hardware.camera2.CaptureRequest

object CameraFocus {
    fun mode(available: IntArray) =
        listOf(
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CaptureRequest.CONTROL_AF_MODE_AUTO,
                CaptureRequest.CONTROL_AF_MODE_MACRO,
                CaptureRequest.CONTROL_AF_MODE_EDOF,
            )
            .firstOrNull { it in available } ?: CaptureRequest.CONTROL_AF_MODE_OFF

    fun canLock(mode: Int) =
        mode != CaptureRequest.CONTROL_AF_MODE_OFF && mode != CaptureRequest.CONTROL_AF_MODE_EDOF
}
