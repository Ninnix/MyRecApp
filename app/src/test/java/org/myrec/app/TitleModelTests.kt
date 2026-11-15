package org.myrec.app

import kotlin.math.abs

object TitleModelTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        test("title looks preserve the separately edited outline and leave motion and words intact") {
            val fx = Effect(kind = "Text", values = mutableMapOf("text" to "Coast", "outlined" to "true", "border" to "#ffff00", "borderWidth" to ".1", "entrance" to "Wipe"))
            TitleStyle.applyLook(fx, TitleStyle.looks.getValue("Film title"))
            check(fx.values["face"] == "sourceserif4" && fx.values["weight"] == "500")
            check(fx.values["text"] == "Coast" && fx.values["entrance"] == "Wipe")
            check(fx.values["outlined"] == "true" && fx.values["border"] == "#ffff00" && fx.values["borderWidth"] == ".1")
        }
        test("title entrance and exit fit a short cue while preserving its readable hold") {
            val fx = Effect(kind = "Text", values = mutableMapOf("entrance" to "Fade", "exit" to "Scale", "inSeconds" to "5", "outSeconds" to "5"))
            val (a, b) = TitleMotion.ends(fx, .3)
            check(abs(a + b - .24) < .00001)
            check(TitleMotion.pose(fx, 0.0, .3).opacity == 0f)
            check(TitleMotion.pose(fx, .15, .3) == TitleMotion.Pose())
            check(TitleMotion.pose(fx, .3, .3).opacity == 0f)
            fx.values["entrance"] = "Wipe"; fx.values["inDirection"] = "Left"
            check(TitleMotion.pose(fx, 0.0, .3).left == 1f)
        }
        test("one title spans selected cuts and its source clock survives splitting and undo") {
            val a = Clip(path = "a.mp4", sourceOut = 2.0, audio = false)
            val b = Clip(path = "b.mp4", sourceOut = 2.0, start = 2.0, audio = false)
            val editor = Editor(Project(clips = mutableListOf(a, b)))
            editor.selected.addAll(listOf(a.id, b.id))
            val title = Effect(kind = "Text", to = 3.5, values = mutableMapOf("design" to "1", "text" to "Coast", "entrance" to "Fade"))
            editor.applyEffect(title)
            val first = editor.project.clips[0].effects.single()
            val second = editor.project.clips[1].effects.single()
            check(first.from == 0.0 && first.to == 2.0 && second.from == 0.0 && second.to == 1.5)
            check(second.phase!!.offset == 2.0 && second.values["titleDuration"] == "3.5")
            editor.clearSelection(); editor.cursor = 1.0; editor.split()
            val fragment = editor.project.clips.first { it.start == 1.0 }
            val phase = fragment.effects.single().phase!!
            check(abs(phase.offset + phase.elapsed(fragment.sourceTime(1.25)) - 1.25) < .00001)
            editor.undo(); editor.undo(); check(editor.project.clips.all { it.effects.isEmpty() })
        }
        test("color LUT endpoints sample numeric grade values without losing the selected cube") {
            val clip = Clip(path = "a.mp4", sourceIn = 10.0, sourceOut = 14.0, audio = false)
            val fx = Effect(kind = "Color LUTs", values = mutableMapOf("lut" to "Warm"))
            val a = GradeModel.pose(fx); val b = a + ("exposure" to 2.0) + ("warmth" to .5)
            fx.motion = EffectMotion(a, b, duration = 4.0).anchored(clip, 0.0)
            check(fx.motion!!.valid(fx))
            val sampled = fx.sampled(12.0)
            check(sampled.values["lut"] == "Warm" && sampled.number("exposure") in 1f..1.02f)
            check(sampled.number("warmth") in .25f.. .26f)
            check(!fx.motion!!.copy(b = b + ("exposure" to 9.0)).valid(fx))
        }
    }
}
