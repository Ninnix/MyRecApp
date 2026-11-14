package org.myrec.app

import kotlin.math.*

sealed interface EffectValue {
    data class Number(val value: Double) : EffectValue
    data class Color(val red: Double, val green: Double, val blue: Double) : EffectValue
    data class Toggle(val value: Boolean) : EffectValue
    data class Choice(val value: String) : EffectValue
    data class Media(val path: String?) : EffectValue
}

sealed interface ParameterKind {
    data class Slider(val min: Double, val max: Double, val step: Double, val wrapped: Boolean = false) : ParameterKind
    data class Stepper(val values: List<Double>) : ParameterKind
    data class Picker(val values: List<String>) : ParameterKind
    data object Color : ParameterKind
    data object Toggle : ParameterKind
    data object Media : ParameterKind

    fun accepts(value: EffectValue): Boolean = when (this) {
        is Slider -> value is EffectValue.Number && value.value.isFinite() && value.value in min..max
        is Stepper -> value is EffectValue.Number && value.value in values
        is Picker -> value is EffectValue.Choice && value.value in values
        Color -> value is EffectValue.Color && listOf(value.red, value.green, value.blue).all { it.isFinite() && it in 0.0..1.0 }
        Toggle -> value is EffectValue.Toggle
        Media -> value is EffectValue.Media && (value.path == null || value.path.isNotBlank())
    }
}

data class EffectKeyframe(val time: Double, val value: EffectValue, val id: String = uid())

data class EffectParameter(
    val id: String,
    val kind: ParameterKind,
    var value: EffectValue,
    val keyframes: MutableList<EffectKeyframe> = mutableListOf(),
) {
    fun duplicate() = copy(keyframes = keyframes.toMutableList())

    fun valid() = kind.accepts(value) && keyframes.size <= 100000 &&
        keyframes.map { it.id }.distinct().size == keyframes.size &&
        keyframes.all { it.id.isNotBlank() && it.id.length <= 100 && it.time.isFinite() && it.time in 0.0..604800.0 && kind.accepts(it.value) } &&
        keyframes.zipWithNext().all { (a, b) -> a.time < b.time }

    fun setKeyframe(time: Double, next: EffectValue) {
        require(time.isFinite() && time in 0.0..604800.0 && kind.accepts(next))
        keyframes.removeAll { abs(it.time - time) < 1.0 / 60000 }
        keyframes.add(EffectKeyframe(time, next))
        keyframes.sortBy { it.time }
    }

    fun at(time: Double): EffectValue {
        require(time.isFinite()) { "Invalid effect source time" }
        if (keyframes.isEmpty()) return value
        if (time <= keyframes.first().time) return keyframes.first().value
        if (time >= keyframes.last().time) return keyframes.last().value
        var lo = 0
        var hi = keyframes.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (keyframes[mid].time <= time) lo = mid else hi = mid
        }
        val a = keyframes[lo]
        val b = keyframes[hi]
        val t = (time - a.time) / (b.time - a.time)
        return when {
            kind is ParameterKind.Slider && a.value is EffectValue.Number && b.value is EffectValue.Number -> {
                var distance = b.value.value - a.value.value
                if (kind.wrapped && abs(distance) > 180) distance -= sign(distance) * 360
                var result = a.value.value + distance * t
                if (kind.wrapped) result -= 360 * floor((result + 180) / 360)
                EffectValue.Number(result)
            }
            kind == ParameterKind.Color && a.value is EffectValue.Color && b.value is EffectValue.Color ->
                EffectValue.Color(a.value.red + (b.value.red - a.value.red) * t,
                    a.value.green + (b.value.green - a.value.green) * t,
                    a.value.blue + (b.value.blue - a.value.blue) * t)
            else -> a.value
        }
    }
}

object EffectContracts {
    data class Control(val id: String, val title: String, val min: Double, val max: Double,
        val initial: Double, val step: Double)

    val correction = listOf(
        Control("exposure", "Exposure", -4.0, 4.0, 0.0, .05),
        Control("contrast", "Contrast", .25, 2.0, 1.0, .01),
        Control("shadows", "Shadows", -1.0, 1.0, 0.0, .01),
        Control("highlights", "Highlights", -1.0, 1.0, 0.0, .01),
        Control("blacks", "Blacks", -1.0, 1.0, 0.0, .01),
        Control("whites", "Whites", -1.0, 1.0, 0.0, .01),
        Control("pivot", "Contrast pivot", .1, .9, .5, .01),
        Control("temperature", "Temperature", -1.0, 1.0, 0.0, .01),
        Control("tint", "Tint", -1.0, 1.0, 0.0, .01),
        Control("saturation", "Saturation", 0.0, 2.0, 1.0, .01),
        Control("vibrance", "Vibrance", -1.0, 1.0, 0.0, .01),
        Control("hue", "Hue", -180.0, 180.0, 0.0, 1.0),
    )

    fun display(id: String, value: Float): String = when (id) {
        "exposure" -> "%+.2f EV".format(java.util.Locale.US, value)
        "contrast", "saturation", "intensity", "pivot" -> "%.0f%%".format(java.util.Locale.US, value * 100)
        "hue" -> if (value.roundToInt() == 0) "0°" else "%+.0f°".format(java.util.Locale.US, value)
        else -> if ((value * 100).roundToInt() == 0) "0" else "%+.0f".format(java.util.Locale.US, value * 100)
    }

    fun help(id: String) = when (id) {
        "exposure" -> "Adjusts picture brightness in stops. +1 EV doubles linear light. Highlights roll off gently. This changes the grade, not the camera exposure."
        "contrast" -> "Separates light and dark tones around the pivot while preserving color proportions. 100% is unchanged."
        "shadows" -> "Raises or lowers darker tones while keeping the black endpoint. Use Blacks for the darkest areas."
        "highlights" -> "Raises or lowers brighter tones while keeping the white endpoint. Use Whites for the brightest areas."
        "blacks" -> "Adjusts the darkest endpoint. Positive values soften black; negative values deepen it with a gentle toe."
        "whites" -> "Adjusts the brightest endpoint. Positive values brighten whites with a gentle roll-off."
        "pivot" -> "The tone that stays fixed as contrast changes. 50% is a middle display tone. It is inactive at 100% contrast."
        "temperature" -> "Relative white balance: negative is cooler, positive is warmer. Zero preserves the recorded balance. This is not a Kelvin setting."
        "tint" -> "Relative white balance: negative adds green, positive adds magenta."
        "saturation" -> "Adjusts color intensity. 100% is unchanged; 0% is black and white."
        "vibrance" -> "Adjusts muted colors more than vivid ones. Positive values are gentler on common skin hues."
        "hue" -> "Rotates colors around the color wheel while preserving luminance. Zero is unchanged."
        else -> "Blends the correction with its input in linear light. 100% applies the full correction; 0% bypasses it."
    }

    fun parameters(kind: String): MutableList<EffectParameter> {
        if (kind !in listOf("RGB Offset", "Color Correction") && kind !in FilmColor.definitions && kind != "Kaleidoscope" && kind !in TimeEffects.kinds) return mutableListOf()
        if (kind == "Step Printing") return TimeEffects.parameters(kind)
        val result = mutableListOf(EffectParameter("intensity", ParameterKind.Slider(0.0, 1.0, .01),
            EffectValue.Number(1.0)))
        if (kind == "RGB Offset") {
            for (channel in listOf("red", "green", "blue")) for (axis in listOf("X", "Y")) {
                val key = channel + axis
                result.add(EffectParameter(key, ParameterKind.Slider(-1.0, 1.0, .001),
                    EffectValue.Number(0.0)))
            }
        } else if (kind in TimeEffects.kinds) result.addAll(TimeEffects.parameters(kind))
        else if (kind == "Kaleidoscope") result.addAll(Kaleidoscope.parameters())
        else if (kind in FilmColor.definitions) result.addAll(FilmColor.parameters(kind))
        else for (p in correction) result.add(EffectParameter(p.id, ParameterKind.Slider(p.min, p.max, p.step),
            EffectValue.Number(p.initial)))
        return result
    }
}

fun Effect.assetPaths(): List<String> {
    val result = mutableListOf<String>()
    values["path"]?.let { result.add(it) }
    for (p in parameters) for (value in listOf(p.value) + p.keyframes.map { it.value })
        if (value is EffectValue.Media) value.path?.let { result.add(it) }
    return result.distinct()
}

fun Effect.remapAssets(path: (String) -> String) {
    values["path"]?.let { values["path"] = path(it) }
    fun value(v: EffectValue) = if (v is EffectValue.Media) EffectValue.Media(v.path?.let(path)) else v
    for (p in parameters) {
        p.value = value(p.value)
        for (i in p.keyframes.indices) p.keyframes[i] = p.keyframes[i].let { it.copy(value = value(it.value)) }
    }
}
