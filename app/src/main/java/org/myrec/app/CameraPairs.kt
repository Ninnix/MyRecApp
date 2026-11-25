package org.myrec.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** A candidate becomes verified only after both cameras deliver a matched pair of frames. */
internal class CameraPairs(manager: CameraManager) {
    val advertised = DualCamera.pairs(manager)
    val candidates = advertised.ifEmpty {
        val ids = manager.cameraIdList.sortedBy {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
        val facing = ids.associateWith { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }
        ids.flatMapIndexed { i, first -> ids.drop(i + 1).map { second -> listOf(first, second) } }
            .sortedBy { facing[it[0]] == facing[it[1]] }
    }
    private val streamed = mutableSetOf<Set<String>>()
    fun confirmed(pair: List<String>) = advertised.any { it.toSet() == pair.toSet() } || pair.toSet() in streamed
    fun observe(pair: List<String>) { streamed.add(pair.toSet()) }
}
