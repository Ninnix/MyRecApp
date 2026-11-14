package org.myrec.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import kotlin.math.abs

data class CameraLens(val id: String, val name: String, val zoom: Float = 1f)

object CameraLenses {
    fun available(manager: CameraManager): List<CameraLens> {
        val cameras =
            manager.cameraIdList.mapNotNull { id ->
                runCatching {
                        val c = manager.getCameraCharacteristics(id)
                        if (
                            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                                ?.getOutputSizes(MediaRecorder::class.java)
                                .isNullOrEmpty()
                        )
                            null
                        else id to c
                    }
                    .getOrNull()
            }
        fun focal(c: CameraCharacteristics): Float {
            val mm =
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            return if (size != null && size.width > 0) mm * 36f / size.width else mm
        }
        val result = mutableListOf<CameraLens>()
        for ((facing, side) in
            listOf(
                CameraCharacteristics.LENS_FACING_BACK to "Rear",
                CameraCharacteristics.LENS_FACING_FRONT to "Front",
            )) {
            val group =
                cameras.filter { it.second.get(CameraCharacteristics.LENS_FACING) == facing }
            val main =
                group.firstOrNull { (_, c) ->
                    c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.contains(
                            CameraCharacteristics
                                .REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                        ) == true
                } ?: group.firstOrNull() ?: continue
            result.add(CameraLens(main.first, "$side main"))
        }
        for (main in result.toList()) {
            val c = cameras.first { it.first == main.id }.second
            val side = main.name.substringBefore(' ')
            val facing = c.get(CameraCharacteristics.LENS_FACING)
            val base = focal(c)
            if (base <= 0) continue
            val others =
                cameras.filter {
                    it.first != main.id &&
                        it.second.get(CameraCharacteristics.LENS_FACING) == facing
                }
            for ((id, lens) in others.sortedBy { focal(it.second) }) {
                val ratio = focal(lens) / base
                if (ratio < .8f) result.add(CameraLens(id, "$side ultrawide"))
                else if (ratio > 1.4f)
                    result.add(
                        CameraLens(id, "$side telephoto · %.1f×".format(java.util.Locale.US, ratio))
                    )
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: continue
                if (range.lower < 1 && result.none { it.name == "$side ultrawide" })
                    result.add(CameraLens(main.id, "$side ultrawide", range.lower))
                val physical =
                    c.physicalCameraIds.mapNotNull { id ->
                        runCatching { focal(manager.getCameraCharacteristics(id)) / base }
                            .getOrNull()
                    }
                val tele = physical.filter { it > 1.4f && it <= range.upper + .1f }.minOrNull()
                if (tele != null && result.none { it.name.startsWith("$side telephoto") })
                    result.add(CameraLens(main.id, "$side telephoto", tele))
            }
        }
        return result
            .distinctBy { Triple(it.id, it.zoom, it.name) }
            .map { lens ->
                if (result.count { it.name == lens.name } > 1)
                    lens.copy(name = "${lens.name} · ${lens.id}")
                else lens
            }
    }

    fun selected(lenses: List<CameraLens>, id: String, zoom: Float): Int =
        lenses.indices.filter { lenses[it].id == id }.minByOrNull { abs(lenses[it].zoom - zoom) }
            ?: -1
}
