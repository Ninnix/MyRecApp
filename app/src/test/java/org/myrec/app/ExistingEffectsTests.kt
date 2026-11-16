package org.myrec.app

import kotlin.math.*

object ExistingEffectsTests {
    fun run(test: (String,()->Unit)->Unit) {
        test("reference existing-effect controls are typed and legacy recipes keep their defaults") {
            for (kind in ExistingEffects.kinds) {
                val fx=Effect.create(kind)
                check(ExistingEffects.modern(fx) && fx.parameters.all { it.valid() })
                check(!ExistingEffects.modern(Effect(kind=kind)))
                check(EffectContracts.parameters(fx)==fx.parameters)
                check(fx.strength(0.0,3.0)==1f)
            }
            check(Effect.create("Night fix").number("brightness")==2.5f)
            check(Effect.create("Undercranking").number("speed")==1.25f)
        }
        test("fractional film cadence integrates changing frame rate without resetting at keys or cuts") {
            val fx=Effect.create("Super 8").apply { from=2.0;to=6.0
                parameter("fps")!!.setKeyframe(2.0,EffectValue.Number(18.0))
                parameter("fps")!!.setKeyframe(3.0,EffectValue.Number(24.0)) }
            val clock=EffectCadence(listOf(fx))
            check(clock.hold(1.99)==null && clock.hold(6.0)==null)
            check(abs(clock.hold(2.11)!!.boundary-(2+1.0/18))<1e-6)
            check(clock.hold(3.01)!!.index==18L && abs(clock.hold(3.01)!!.boundary-3)<1e-6)
            val clip=Clip(path="source",sourceOut=6.0,effects=mutableListOf(fx.copyEffect().apply { values["origin"]="2.0";values["clockEnd"]="6.0" }))
            val split=clip.duplicate().apply { sourceIn=2.13 }
            for (time in listOf(2.14,2.7,3.14,4.8)) check(ExistingEffects.cadence(clip).hold(time)==ExistingEffects.cadence(split).hold(time))
        }
        test("a continuous ramp has a stable source subdivision through trims and pause removal") {
            val fx=Effect.create("Undercranking").apply {
                to=8.0;values["origin"]="0.0";values["clockEnd"]="8.0"
                parameter("speed")!!.setKeyframe(0.0,EffectValue.Number(1.0))
                parameter("speed")!!.setKeyframe(8.0,EffectValue.Number(1.5))
            }
            val clip=Clip(path="source",sourceOut=8.0,effects=mutableListOf(fx))
            val expected=16*ln(1.5)
            check(abs(clip.duration-expected)<.001)
            val plan=SpeedRamp.forClip(clip)!!
            val trimmed=clip.duplicate().apply { sourceIn=2.137;sourceOut=6.213 }
            check(abs(trimmed.duration-(plan.outputTime(trimmed.sourceOut)-plan.outputTime(trimmed.sourceIn)))<1e-7)
            check(SpeedRamp.forClip(trimmed)!!.native.contentEquals(plan.native))
            for (t in listOf(.1,1.8,3.33,clip.duration-.01)) check(abs(clip.timelineOffset(clip.sourceTime(t))-t)<1e-6)
            val pieces=clip.playbackPieces();check(pieces.size>10 && abs(pieces.sumOf { it.duration }-clip.duration)<1e-7)
            check(pieces.all { abs(it.effectiveRate-it.rate)<1e-6 })
        }
        test("crash push overshoot hold and release follow the reference curve") {
            val fx=Effect.create("Crash Zoom")
            val push=fx.number("speed").toDouble();val zoom=fx.number("zoom").toDouble()
            check(ExistingEffects.zoom(fx,0.0).first==1.0)
            check(abs(ExistingEffects.zoom(fx,push/2).first-(1+(zoom-1)/32))<1e-6)
            check(abs(ExistingEffects.zoom(fx,push+.04).first-zoom*1.03)<1e-6)
            check(ExistingEffects.zoom(fx,10.0).first==zoom)
            fx.values["release"]="2.0"
            check(abs(ExistingEffects.zoom(fx,2+push).first-1)<1e-6)
        }
        test("dolly follower compensates measured subject size using actual lens zoom and brakes after loss") {
            val follower=DollyZoomFollower(.2,2.0,1.0,4.0,0.0)
            for (i in 1..90) {
                val t=i/30.0
                follower.observe(.15*follower.zoom,follower.zoom,t);follower.value(t)
            }
            check(abs(follower.zoom-4.0/3)<.02)
            val before=follower.zoom
            for (i in 91..150) follower.value(i/30.0)
            check(follower.zoom in 1.0..4.0 && abs(follower.zoom-before)<.03)
            val stopped=follower.zoom;follower.value(6.0);check(follower.zoom==stopped)
        }
        test("freeze print and fade audio survive resizing undo and splitting") {
            val editor=Editor(Project(clips=mutableListOf(Clip(path="source",sourceOut=8.0,audio=true))))
            editor.cursor=2.0
            val id=editor.insertFreeze(3.0,.05f,true,"Print","Fade out")!!
            val hold=editor.project.clips.first { it.id==id }
            check(hold.audio && hold.effects.single { it.kind=="Freeze Frame" }.values["style"]=="Print")
            editor.resizeFreeze(id,4.0,.1f,true,"Print","Fade out")
            check(editor.project.clips.first { it.id==id }.duration==4.0)
            editor.undo();check(editor.project.clips.first { it.id==id }.duration==3.0)
        }
        test("modern transitions fit the union of picture and sound and preserve a sliced clock") {
            fun editor()=Editor(Project(clips=mutableListOf(Clip(path="a",sourceOut=5.0),Clip(path="b",sourceOut=5.0,start=5.0))))
            for (kind in listOf("Dissolve","Fade through color","Still")) {
                val editor=editor();val before=editor.project.clips.first();val after=editor.project.clips.last()
                editor.putTransition(Transition(at=5.0,kind=kind,duration=1.0,hold=if (kind=="Still") 0.0 else 1.0,controls=TransitionControls()))
                val t=editor.project.transitions.single();val clock=TransitionClock(t)
                check(abs(before.end-(t.pictureOrigin+clock.leftEnd))<1e-6)
                check(abs(after.start-(t.pictureOrigin+clock.rightStart))<1e-6)
                val a=t.begin+(t.end-t.begin)*.2;val b=t.begin+(t.end-t.begin)*.8
                val sliced=t.slice(a,b)!!
                check(abs(sliced.begin-a)<1e-6 && abs(sliced.end-b)<1e-6)
                for (i in 0..10) {
                    val time=a+(b-a)*i/10
                    check(abs(sliced.progress(time)-t.progress(time))<1e-6)
                    check(abs(sliced.audioGain(time,true)-t.audioGain(time,true))<1e-6)
                }
                val expected=if (kind=="Dissolve") 8.0 else 11.0
                check(abs(editor.project.duration-expected)<1e-6) { "$kind duration ${editor.project.duration}" }
                editor.removeTransition(t.id);check(abs(editor.project.duration-10.0)<1e-6)
            }
            val editor=editor()
            editor.putTransition(Transition(at=5.0,duration=1.0,controls=TransitionControls(audioOffset=-.5,audioBlend=2.0)))
            val transition=editor.project.transitions.single();val clock=TransitionClock(transition)
            check(clock.lower==-.5 && clock.upper==1.5 && abs(editor.project.duration-8)<1e-6)
        }
        test("transition flash frames remain separate and audio honors split choice and solid hold") {
            val settings=TransitionControls(flashCount=20)
            val flashes=settings.flashes(.5,30)
            check(flashes.zipWithNext().all { it.second-it.first>=2 } && flashes.all { it in 1..13 })
            val split=Transition(at=1.0,kind="Split screen",duration=1.0,controls=TransitionControls(audio="First clip"))
            check(split.audioGain(split.begin+.5,true)==1.0 && split.audioGain(split.begin+.5,false)==0.0)
            val fade=Transition(at=1.0,kind="Fade through color",duration=1.0,hold=1.0,controls=TransitionControls())
            check(fade.audioGain(fade.begin+1.0,true)==0.0 && fade.audioGain(fade.begin+1.0,false)==0.0)
            check(!TransitionControls(first=TransitionFraming(zoom=5.0)).valid())
        }
    }
}
