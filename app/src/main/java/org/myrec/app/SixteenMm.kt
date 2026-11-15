package org.myrec.app

object SixteenMm {
    val definition = FilmColor.Definition(7,"Grain and softness shape the texture. Gate weave shifts the picture slightly; Frame edge adds rounded corners. Grain follows the presentation frame.",listOf(
        FilmColor.Control("grain","Grain",ParameterKind.Slider(0.0,1.0,.01),EffectValue.Number(.5)),
        FilmColor.Control("grainSize","Grain size",ParameterKind.Picker(listOf("Fine","Medium","Coarse")),EffectValue.Choice("Medium")),
        FilmColor.Control("softness","Softness",ParameterKind.Slider(0.0,1.0,.01),EffectValue.Number(.3)),
        FilmColor.Control("weave","Gate weave",ParameterKind.Slider(0.0,1.0,.01),EffectValue.Number(.2)),
        FilmColor.Control("frameEdge","Frame edge",ParameterKind.Toggle,EffectValue.Toggle(false)),
        FilmColor.Control("color","Color",ParameterKind.Picker(listOf("Color","B&W")),EffectValue.Choice("Color"))))
    fun modern(effect: Effect) = effect.kind == "16mm" && effect.parameters.isNotEmpty()
    fun parameters() = (listOf(EffectParameter("intensity",ParameterKind.Slider(0.0,1.0,.01),EffectValue.Number(1.0))) +
        definition.controls.map { EffectParameter(it.id,it.kind,it.initial) }).toMutableList()
}
