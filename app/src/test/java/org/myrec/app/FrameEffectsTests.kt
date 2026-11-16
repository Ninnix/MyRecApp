package org.myrec.app

object FrameEffectsTests {
    fun run(test: (String,()->Unit)->Unit) {
        test("the background library keeps person-preserving blur separate from whole-picture extras") {
            check(Effects.categories["Background"]==listOf("Auto Background","Chroma Key"))
            check("Blur" in Effects.categories.getValue("Extra effects"))
            check("Letterbox" in Effects.categories.getValue("Extra effects"))
            check("Expressionism" in Effects.categories.getValue("Color & style"))
            check("Expressionism" !in Effects.categories.getValue("Presets"))
        }
        test("new framing effects have independent validated controls while old recipes retain their schema") {
            for (kind in FrameEffects.kinds) {
                val old=Effect(kind=kind,values=mutableMapOf("strength" to "0.3"))
                check(old.parameters.isEmpty() && !FrameEffects.modern(old))
                val fx=Effect.create(kind)
                check(FrameEffects.modern(fx) && fx.parameters.all { it.valid() })
                check(fx.number("intensity")==1f)
                fx.setNumber("intensity",0f)
                check(fx.parameters.all { it.valid() })
            }
            val wide=Effect.create("Widescreen 1953")
            check((wide.parameter("ratio")!!.kind as ParameterKind.Picker).values==listOf("2.39:1","2.20:1","1.85:1","1.33:1"))
            check((wide.parameter("bars")!!.kind as ParameterKind.Picker).values==listOf("Black","Blur fill"))
            check((wide.parameter("flareColor")!!.kind as ParameterKind.Picker).values==listOf("Blue","Amber","Neutral"))
        }
        test("explicit legacy blur upgrade commits typed radius as one undo without mutating its source draft") {
            val old=Effect(kind="Blur",values=mutableMapOf("strength" to ".4"))
            val editor=Editor(Project(clips=mutableListOf(Clip(path="source.mp4",sourceOut=2.0,effects=mutableListOf(old)))))
            val draft=VideoEffectsDraft(editor,FrameEffects.kinds)
            FrameEffects.upgrade(draft.chain.single())
            check(editor.project.clips.single().effects.single().parameters.isEmpty())
            draft.commit(editor)
            val fx=editor.project.clips.single().effects.single()
            check(FrameEffects.modern(fx) && fx.number("radius")==12f && fx.number("intensity")==.4f)
            editor.undo();check(editor.project.clips.single().effects.single().parameters.isEmpty())
            editor.redo();check(FrameEffects.modern(editor.project.clips.single().effects.single()))
        }
    }
}
