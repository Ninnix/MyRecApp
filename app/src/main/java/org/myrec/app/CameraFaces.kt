package org.myrec.app

import android.hardware.camera2.*
import android.os.SystemClock
import kotlin.math.*

object CameraFaces {
    fun zoom(chars: CameraCharacteristics,result: TotalCaptureResult): Double {
        if (android.os.Build.VERSION.SDK_INT>=30) result.get(CaptureResult.CONTROL_ZOOM_RATIO)?.let { return it.toDouble() }
        val active=chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val crop=result.get(CaptureResult.SCALER_CROP_REGION)
        return if (active!=null && crop!=null && crop.width()>0) active.width().toDouble()/crop.width() else 1.0
    }
    fun mode(chars: CameraCharacteristics): Int {
        val modes=chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: intArrayOf()
        return if (CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE in modes) CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
        else if (CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL in modes) CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
        else CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
    }
    fun time(chars: CameraCharacteristics,result: TotalCaptureResult): Double {
        val stamp=result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return System.nanoTime()/1e9
        return if (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
            (stamp-(SystemClock.elapsedRealtimeNanos()-System.nanoTime()))/1e9
        else System.nanoTime()/1e9
    }
    fun boxes(chars: CameraCharacteristics,result: TotalCaptureResult,width: Int,height: Int): List<CameraRect> {
        val crop=result.get(CaptureResult.SCALER_CROP_REGION)
            ?: chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return emptyList()
        val cx=crop.exactCenterX().toDouble(); val cy=crop.exactCenterY().toDouble()
        val aspect=width.toDouble()/height
        val w=min(crop.width().toDouble(),crop.height()*aspect)
        val h=min(crop.height().toDouble(),crop.width()/aspect)
        if (w<=0 || h<=0) return emptyList()
        return result.get(CaptureResult.STATISTICS_FACES).orEmpty().filter { it.score>=50 }.map {
            val b=it.bounds
            CameraRect((b.left-cx)/w+.5,(b.top-cy)/h+.5,(b.right-cx)/w+.5,(b.bottom-cy)/h+.5)
        }
    }
}
