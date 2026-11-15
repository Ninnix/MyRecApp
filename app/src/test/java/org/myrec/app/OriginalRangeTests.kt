package org.myrec.app

import kotlin.math.abs

object OriginalRangeTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        test("original range copies source handles keys marks and independent effect state") {
            val effect = Effect.create("Noir").apply { from = 1.0; to = 8.0; editNumber("crush",.25f,5.0,0.0,true) }
            val source = Clip(path = "original.mp4",sourceOut = 10.0,effects = mutableListOf(effect),marks = mutableListOf(2.0,4.0,9.0))
            val before = source.duplicate()
            val clip = OriginalRange.clip(source,3.0,6.0,30)
            check(source == before && clip.sourceIn == 3.0 && clip.sourceOut == 6.0 && clip.start == 0.0)
            check(clip.id != source.id && clip.group != source.group && clip.occurrence != source.occurrence)
            check(clip.effects.single().from == 0.0 && clip.effects.single().to == 3.0)
            check(clip.effects.single().sampled(5.0).number("crush") == .25f)
            clip.effects.single().parameter("crush")!!.keyframes.clear()
            check(source.effects.single().parameter("crush")!!.keyframes.size == 2)
            check(clip.marks == source.marks)
        }
        test("source range remains exact through rate keys and appends with one undo") {
            val effect = Effect(kind = "Step Printing").apply {
                setNumber("speed",.5f); editNumber("speed",1f,2.0,0.0,true)
            }
            val source = Clip(path = "original.mp4",sourceOut = 6.0,effects = mutableListOf(effect))
            val clip = OriginalRange.clip(source,1.0,4.0,30)
            check(clip.sourceIn == 1.0 && clip.sourceOut == 4.0)
            for (point in listOf(1.0,2.5,3.5)) check(abs(clip.rateAt(point)-source.rateAt(point)) < .000001)
            val editor = Editor(Project(clips = mutableListOf(Clip(path = "existing.mp4",sourceOut = 2.0))))
            val before = editor.project.duplicate()
            editor.cursor = .5; editor.insertBatch(listOf(clip),editor.project.duration)
            check(clip.start == 2.0 && editor.project.clips.size == 2)
            editor.undo(); check(editor.project == before && !editor.canUndo)
        }
        test("original range bounds reject invalid values but retain a whole subframe take") {
            val source = Clip(path = "original.mp4",sourceOut = 2.0)
            for ((start,end) in listOf(-.1 to 1.0,1.0 to 2.1,1.0 to 1.0,Double.NaN to 1.0,0.0 to Double.POSITIVE_INFINITY))
                check(runCatching { OriginalRange.clip(source,start,end,30) }.isFailure)
            check(runCatching { OriginalRange.clip(source,0.0,1.0,0) }.isFailure)
            val tiny = OriginalRange.clip(source.copy(sourceOut = .01),0.0,.01,30)
            check(tiny.sourceIn == 0.0 && tiny.sourceOut == .01)
        }
    }
}
