package org.myrec.app

import kotlin.math.*

// Convert stored controls at the file boundary; renderers only see today's model.
object EffectImport {
    fun convert(effect: Effect) {
        val values = effect.values
        if (effect.parameters.isEmpty()) return
        if (effect.kind == "RGB Offset") {
            val distance = values["distance"]?.toDoubleOrNull() ?: .025
            val angle = (values["angle"]?.toDoubleOrNull() ?: 90.0) * PI / 180
            require(distance.isFinite() && angle.isFinite()) { "Invalid RGB offset" }
            for ((channel, sign) in listOf("red" to 1, "green" to -1, "blue" to 1)) {
                effect.setNumber(channel + "X", (sign * distance * cos(angle) / .12).coerceIn(-1.0, 1.0).toFloat())
                effect.setNumber(channel + "Y", (sign * distance * sin(angle) / .12).coerceIn(-1.0, 1.0).toFloat())
            }
        }
        if (effect.kind == "Kaleidoscope") {
            val divisions = values["segments"]?.toDoubleOrNull() ?: 4.0
            val angle = values["rotation"]?.toDoubleOrNull() ?: 0.0
            require(divisions.isFinite() && angle.isFinite()) { "Invalid kaleidoscope controls" }
            effect.setNumber("divisions", divisions.roundToInt().coerceIn(2, 9).toFloat())
            effect.setNumber("splitAngle", Kaleidoscope.wrapped(angle).toFloat())
        }
        for (p in effect.parameters) {
            val key = if (p.id == "intensity") "strength" else p.id
            val spec = p.kind as? ParameterKind.Slider ?: continue
            values[key]?.let {
                val value = it.toDoubleOrNull()
                require(value != null && value.isFinite()) { "Invalid effect control" }
                p.value = EffectValue.Number(value.coerceIn(spec.min, spec.max))
            }
        }
        values.clear()
    }
}
