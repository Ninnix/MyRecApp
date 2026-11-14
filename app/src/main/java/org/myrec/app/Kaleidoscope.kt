package org.myrec.app

import kotlin.math.*

object Kaleidoscope {
    val controls = listOf(
        EffectContracts.Control("sourceAngle", "Source rotation", -180.0, 180.0, 0.0, 1.0),
        EffectContracts.Control("sourcePanX", "Source pan X", -1.0, 1.0, 0.0, .001),
        EffectContracts.Control("sourcePanY", "Source pan Y", -1.0, 1.0, 0.0, .001),
        EffectContracts.Control("splitAngle", "Split rotation", -180.0, 180.0, 0.0, 1.0),
        EffectContracts.Control("centerX", "Split center X", 0.0, 1.0, .5, .001),
        EffectContracts.Control("centerY", "Split center Y", 0.0, 1.0, .5, .001),
    )
    fun parameters() = mutableListOf(EffectParameter("divisions", ParameterKind.Stepper((2..9).map { it.toDouble() }),
        EffectValue.Number(2.0))).apply {
        addAll(controls.map { p -> EffectParameter(p.id,
            ParameterKind.Slider(p.min, p.max, p.step, p.id.endsWith("Angle")), EffectValue.Number(p.initial)) })
    }

    data class Mapping(val a: Double, val b: Double, val c: Double, val d: Double) {
        fun x(x: Double, y: Double) = a * x + c * y
        fun y(x: Double, y: Double) = b * x + d * y
        val determinant get() = a * d - b * c
    }

    fun dragMapping(x: Double, y: Double, width: Double, height: Double, effect: Effect): Mapping {
        val dx = (x - effect.number("centerX", .5f)) * width
        val dy = (y - effect.number("centerY", .5f)) * height
        val undo = -effect.number("sourceAngle") * PI / 180
        if (hypot(dx, dy) <= .001) return Mapping(cos(undo), sin(undo), -sin(undo), cos(undo))
        val count = effect.number("divisions", 2f).toInt().coerceIn(2, 9)
        val axis = (effect.number("splitAngle") - 90) * PI / 180
        val turn = 2 * PI; val span = turn / count
        val relative = atan2(dy, dx) - axis
        val sector = min(count - 1, floor((relative - turn * floor(relative / turn)) / span).toInt())
        if (count % 2 == 0 && sector % 2 != 0) {
            val angle = 2 * axis + (sector + 1) * span + undo
            return Mapping(cos(angle), sin(angle), sin(angle), -cos(angle))
        }
        val angle = -sector * span + undo
        return Mapping(cos(angle), sin(angle), -sin(angle), cos(angle))
    }

    fun wrapped(value: Double) = value - 360 * floor((value + 180) / 360)
}
