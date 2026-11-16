package org.myrec.app

object FilmColor {
    data class Control(val id: String, val title: String, val kind: ParameterKind, val initial: EffectValue)
    data class Definition(val kernel: Int, val help: String, val controls: List<Control>)
    private fun slider(id: String, title: String, min: Double, max: Double, initial: Double) =
        Control(id, title, ParameterKind.Slider(min, max, .01), EffectValue.Number(initial))
    private fun picker(id: String, title: String, values: List<String>, initial: String) =
        Control(id, title, ParameterKind.Picker(values), EffectValue.Choice(initial))
    private fun toggle(id: String, title: String, initial: Boolean) =
        Control(id, title, ParameterKind.Toggle, EffectValue.Toggle(initial))

    val definitions = linkedMapOf(
        "Silent Tint" to Definition(0, "Tint colors the highlights. Tone colors the shadows while keeping whites neutral.", listOf(
            picker("tint", "Tint", listOf("Night Blue", "Amber", "Fire Red", "Nature Green", "Sepia"), "Amber"),
            picker("mode", "Process", listOf("Tint", "Tone"), "Tint"),
            slider("contrast", "Contrast", .8, 1.4, 1.15))),
        "Three-Strip" to Definition(1, "Rich color and denser reds with protection for skin hues.", listOf(
            slider("saturation", "Saturation", 1.0, 1.8, 1.4),
            slider("redDepth", "Red depth", 0.0, 1.0, .6),
            slider("warmth", "Warmth", -1.0, 1.0, .2))),
        "Bleach Bypass" to Definition(2, "Strength increases contrast and removes color for a dense, metallic look.", listOf(
            slider("strength", "Strength", 0.0, 1.0, .7))),
        "Day for Night" to Definition(3, "Darkness and Blue shape the night look. Moonlight highlights preserve bright accents.", listOf(
            slider("darkness", "Darkness", 0.0, 1.0, .6),
            slider("blue", "Blue", 0.0, 1.0, .7),
            toggle("moonlight", "Moonlight highlights", true))),
        "Expressionism" to Definition(4, "Hard monochrome contrast with a dark vignette. Choose the iris shape and tint.", listOf(
            slider("contrast", "Contrast", 1.5, 3.0, 2.2),
            slider("vignette", "Vignette", 0.0, 1.0, .7),
            picker("iris", "Iris", listOf("Oval", "Circle"), "Oval"),
            toggle("tint", "Tint", false))),
        "Halation" to Definition(5, "Glow softens bright areas, Halation red adds warm fringes around highlights, and Shadow lift raises darker tones. Small amounts retain contrast.", listOf(
            slider("glow", "Glow", 0.0, 1.0, .4),
            slider("red", "Halation red", 0.0, 1.0, .5),
            slider("flash", "Shadow lift", 0.0, 1.0, .2),
            picker("flashColor", "Flash color", listOf("Neutral", "Warm", "Cool"), "Warm"))),
    )

    fun parameters(kind: String) = definitions[kind]?.controls?.map {
        EffectParameter(it.id, it.kind, it.initial)
    }?.toMutableList() ?: mutableListOf()

    fun definition(effect: Effect): Definition? = definitions[effect.kind]
        ?: if (FrameEffects.modern(effect)) FrameEffects.definitions[effect.kind] else if (Noir.modern(effect)) Noir.definition else if (SixteenMm.modern(effect)) SixteenMm.definition else null

    fun uniforms(effect: Effect): FloatArray = definition(effect)!!.controls.map { control ->
        when (val value = effect.parameter(control.id)?.value ?: control.initial) {
            is EffectValue.Number -> value.value.toFloat()
            is EffectValue.Toggle -> if (value.value) 1f else 0f
            is EffectValue.Choice -> (control.kind as ParameterKind.Picker).values.indexOf(value.value).toFloat()
            else -> error("Invalid film color control")
        }
    }.toFloatArray()
}
