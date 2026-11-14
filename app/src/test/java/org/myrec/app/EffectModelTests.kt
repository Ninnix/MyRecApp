package org.myrec.app

import kotlin.math.abs

object EffectModelTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        fun near(a: Double, b: Double) = check(abs(a - b) < .00001) { "$a != $b" }
        fun numeric(p: EffectParameter, t: Double) = (p.at(t) as EffectValue.Number).value
        test("film color contracts carry source defaults and independent strength and intensity") {
            for ((kind, definition) in FilmColor.definitions) {
                val fx = Effect(kind = kind)
                check(fx.parameters.size == definition.controls.size + 1)
                check(fx.parameters.all { it.valid() })
                check(FilmColor.uniforms(fx).size == definition.controls.size)
            }
            val bleach = Effect(kind = "Bleach Bypass")
            near(bleach.number("strength").toDouble(), .7)
            near(bleach.strength(0.0, 1.0).toDouble(), 1.0)
            bleach.setNumber("intensity", .25f)
            near(bleach.number("strength").toDouble(), .7)
            near(bleach.strength(0.0, 1.0).toDouble(), .25)
        }
        test("kaleidoscope keeps source rotation wrapped and holds division choices between keys") {
            val fx = Effect(kind = "Kaleidoscope")
            check(fx.parameters.size == 8 && fx.number("divisions") == 2f)
            fx.editNumber("sourceAngle", 170f, 0.0, 0.0, true)
            fx.editNumber("sourceAngle", -170f, 2.0, 0.0, true)
            near(fx.numberAt("sourceAngle", 1.0).toDouble(), -180.0)
            fx.editNumber("divisions", 9f, 2.0, 0.0, true)
            near(fx.numberAt("divisions", 1.0).toDouble(), 2.0)
            near(fx.numberAt("divisions", 2.0).toDouble(), 9.0)
        }
        test("kaleidoscope dragging follows the initial wedge including reflection and source rotation") {
            val fx = Effect(kind = "Kaleidoscope")
            val right = Kaleidoscope.dragMapping(.75, .5, 400.0, 300.0, fx)
            val left = Kaleidoscope.dragMapping(.25, .5, 400.0, 300.0, fx)
            near(right.determinant, 1.0); near(left.determinant, -1.0)
            near(right.x(10.0, 0.0), 10.0); near(left.x(10.0, 0.0), -10.0)
            fx.setNumber("sourceAngle", 90f)
            val rotated = Kaleidoscope.dragMapping(.75, .5, 400.0, 300.0, fx)
            near(rotated.x(10.0, 0.0), 0.0); near(rotated.y(10.0, 0.0), -10.0)
            for (count in listOf(3, 5, 7, 9)) {
                fx.setNumber("divisions", count.toFloat())
                for (x in listOf(.1, .3, .7, .9)) for (y in listOf(.1, .3, .7, .9))
                    near(Kaleidoscope.dragMapping(x, y, 400.0, 300.0, fx).determinant, 1.0)
            }
        }
        test("effect values interpolate numbers and colors but hold categorical values") {
            val p = EffectParameter("x", ParameterKind.Slider(-1.0, 1.0, .001), EffectValue.Number(0.0))
            p.setKeyframe(1.0, EffectValue.Number(-1.0)); p.setKeyframe(3.0, EffectValue.Number(1.0))
            near(numeric(p, 0.0), -1.0); near(numeric(p, 2.0), 0.0); near(numeric(p, 4.0), 1.0)
            val color = EffectParameter("color", ParameterKind.Color, EffectValue.Color(0.0, 0.0, 0.0))
            color.setKeyframe(0.0, EffectValue.Color(0.0, 1.0, 0.0))
            color.setKeyframe(2.0, EffectValue.Color(1.0, 0.0, .5))
            check(color.at(1.0) == EffectValue.Color(.5, .5, .25))
            for ((kind, a, b) in listOf(
                Triple(ParameterKind.Toggle, EffectValue.Toggle(false), EffectValue.Toggle(true)),
                Triple(ParameterKind.Picker(listOf("A", "B")), EffectValue.Choice("A"), EffectValue.Choice("B")),
                Triple(ParameterKind.Media, EffectValue.Media("a.png"), EffectValue.Media("b.png")),
                Triple(ParameterKind.Stepper(listOf(2.0, 4.0)), EffectValue.Number(2.0), EffectValue.Number(4.0)))) {
                val hold = EffectParameter("hold", kind, a)
                hold.setKeyframe(0.0, a); hold.setKeyframe(2.0, b)
                check(hold.at(1.999) == a && hold.at(2.0) == b && hold.valid())
            }
        }
        test("wrapped angles take the short path and duplicate frame times replace one key") {
            val p = EffectParameter("angle", ParameterKind.Slider(-180.0, 180.0, 1.0, true), EffectValue.Number(0.0))
            p.setKeyframe(0.0, EffectValue.Number(170.0)); p.setKeyframe(2.0, EffectValue.Number(-170.0))
            near(numeric(p, 1.0), -180.0)
            p.setKeyframe(2.0 + .000001, EffectValue.Number(-160.0))
            check(p.keyframes.size == 2 && p.valid())
            for (time in listOf(Double.NaN, -1.0, 604801.0))
                check(runCatching { p.setKeyframe(time, EffectValue.Number(0.0)) }.isFailure)
            check(runCatching { p.setKeyframe(1.0, EffectValue.Number(Double.NaN)) }.isFailure)
        }
        test("RGB contracts default to identity and import old distance controls once") {
            val modern = Effect(kind = "RGB Offset")
            check(modern.parameters.size == 7 && modern.parameters.all { it.valid() })
            check(modern.parameters.drop(1).all { it.value == EffectValue.Number(0.0) })
            near(modern.number("strength").toDouble(), 1.0)
            val old = Effect(kind = "RGB Offset", values = mutableMapOf("distance" to ".03", "angle" to "90"))
            EffectImport.convert(old)
            check(old.values.isEmpty() && old.parameters.size == 7)
            near(old.number("redY").toDouble(), .25)
            near(old.number("greenY").toDouble(), -.25)
            check(runCatching { modern.setNumber("redX", 1.1f) }.isFailure)
            modern.setNumber("strength", .4f)
            near(modern.number("strength").toDouble(), .4)
        }
        test("animate seeds the original source pose and later edits preserve existing keys") {
            val fx = Effect(kind = "RGB Offset")
            fx.editNumber("redX", .8f, 8.0, 4.0, true)
            val p = fx.parameter("redX")!!
            check(p.keyframes.map { it.time } == listOf(4.0, 8.0))
            near(fx.numberAt("redX", 6.0).toDouble(), .4)
            fx.editNumber("redX", .6f, 6.0, 4.0, false)
            check(p.keyframes.map { it.time } == listOf(4.0, 6.0, 8.0))
            near(fx.numberAt("redX", 6.0).toDouble(), .6)
            fx.editNumber("redX", .6f, 6.0, 4.0, false)
            check(p.keyframes.size == 3)
        }
        test("source keyframes survive splits speed changes copy undo and random frame order") {
            val fx = Effect(kind = "RGB Offset")
            fx.editNumber("redX", 1f, 10.0, 4.0, true)
            val c = Clip(path = "source.mp4", sourceIn = 4.0, sourceOut = 10.0, effects = mutableListOf(fx))
            val e = Editor(Project(clips = mutableListOf(c)))
            e.cursor = 3.0; e.split()
            val right = e.project.clips.single { it.start == 3.0 }
            e.select(right); e.setRate(.5)
            val source = right.sourceTime(5.0)
            near(source, 8.0)
            near(right.effects.single().sampled(source).number("redX").toDouble(), 2.0 / 3)
            check(right.effects.single().parameter("redX")!!.keyframes.map { it.time } == listOf(4.0, 10.0))
            val copied = right.duplicate(true)
            copied.effects.single().parameter("redX")!!.keyframes.clear()
            check(right.effects.single().parameter("redX")!!.keyframes.size == 2)
            for (t in listOf(9.0, 4.0, 7.0, 5.0, 9.0))
                near(right.effects.single().sampled(t).number("redX").toDouble(), (t - 4) / 6)
            e.undo(); e.redo()
            check(e.project.clips.all { it.effects.single().parameter("redX")!!.keyframes.size == 2 })
        }
        test("asset traversal remaps categorical media keys throughout revision branches") {
            val fx = Effect(kind = "Image", values = mutableMapOf("path" to "old-overlay.png"))
            fx.parameters.add(EffectParameter("media", ParameterKind.Media, EffectValue.Media("base.png")))
            fx.parameters.single().setKeyframe(0.0, EffectValue.Media("a.png"))
            fx.parameters.single().setKeyframe(1.0, EffectValue.Media("b.png"))
            val p = Project(clips = mutableListOf(Clip(path = "source.mp4", sourceOut = 2.0, effects = mutableListOf(fx))))
            val saved = EditSession(p, redo = listOf(EditRevision(p.duplicate())))
            check(ProjectAssets.paths(saved).toSet() == setOf("source.mp4", "old-overlay.png", "base.png", "a.png", "b.png"))
            val mapped = ProjectAssets.remap(saved, p.id, p.name) { "new/$it" }
            check(ProjectAssets.paths(mapped).all { it.startsWith("new/") })
            check(ProjectAssets.paths(saved).none { it.startsWith("new/") })
        }
        test("render families keep stable order with restoration and primary balance first") {
            val names = listOf("Widescreen 1953", "RGB Offset", "Noir", "Kaleidoscope", "Night fix",
                "Film grain", "Chroma Key", "Dolly Zoom", "Quantization", "Color Correction", "Step Printing")
            val effects = names.map { Effect(kind = it) }
            check(EffectOrder.ordered(effects).map { it.kind } == listOf("Night fix", "Chroma Key", "Dolly Zoom",
                "Kaleidoscope", "Color Correction", "RGB Offset", "Noir", "Quantization", "Film grain", "Widescreen 1953", "Step Printing"))
        }
    }
}
