package org.myrec.app

object Noir {
    val definition = FilmColor.Definition(6,"Strong monochrome contrast and black crush. Grain follows the presentation clock; blue shadows add a restrained cold tint.",listOf(
        FilmColor.Control("contrast","Contrast",ParameterKind.Slider(1.0,2.0,.01),EffectValue.Number(1.5)),
        FilmColor.Control("crush","Black crush",ParameterKind.Slider(0.0,.3,.01),EffectValue.Number(.12)),
        FilmColor.Control("grain","Grain",ParameterKind.Toggle,EffectValue.Toggle(true)),
        FilmColor.Control("shadowColor","Blue shadows",ParameterKind.Toggle,EffectValue.Toggle(false))))
    fun modern(effect: Effect) = effect.kind == "Noir" && effect.parameters.isNotEmpty()
    fun parameters() = (listOf(EffectParameter("intensity",ParameterKind.Slider(0.0,1.0,.01),EffectValue.Number(1.0))) +
        definition.controls.map { EffectParameter(it.id,it.kind,it.initial) }).toMutableList()
    fun uniforms(effect: Effect) = floatArrayOf(effect.number("contrast"),effect.number("crush"),
        if ((effect.parameter("grain")?.value as? EffectValue.Toggle)?.value == true) 1f else 0f,
        if ((effect.parameter("shadowColor")?.value as? EffectValue.Toggle)?.value == true) 1f else 0f)
}
