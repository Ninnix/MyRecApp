package org.yourec.app

object CameraOrientation {
    fun recording(sensor: Int, display: Int, front: Boolean) =
        ((sensor + if (front) display else -display) % 360 + 360) % 360

    fun correction(recording: Int, camera: Int) = (camera - recording + 360) % 360
}
