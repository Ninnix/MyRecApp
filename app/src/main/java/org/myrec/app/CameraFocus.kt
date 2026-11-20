package org.myrec.app

import android.hardware.camera2.CaptureRequest
import kotlin.math.min

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

    fun meteringArea(point: CameraPoint, crop: CameraRect, streamAspect: Double): CameraRect {
        val width = min(crop.width, crop.height * streamAspect)
        val height = min(crop.height, crop.width / streamAspect)
        val left = crop.center.x - width / 2
        val top = crop.center.y - height / 2
        val side = min(width, height) * .12
        val x = (left + point.x * width - side / 2).coerceIn(left, left + width - side)
        val y = (top + point.y * height - side / 2).coerceIn(top, top + height - side)
        return CameraRect(x, y, x + side, y + side)
    }
}
