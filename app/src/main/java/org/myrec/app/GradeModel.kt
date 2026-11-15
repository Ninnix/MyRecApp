package org.myrec.app

object GradeModel {
    val limits = linkedMapOf("strength" to (0.0..1.0), "highlights" to (-1.0..1.0),
        "exposure" to (-4.0..4.0), "warmth" to (-1.0..1.0), "tint" to (-1.0..1.0))
    fun pose(fx: Effect) = limits.keys.associateWith { fx.number(it, if (it == "strength") 1f else 0f).toDouble() }
    fun valid(pose: Map<String, Double>) = pose.keys == limits.keys && pose.all { (key, value) -> value.isFinite() && value in limits.getValue(key) }
}
