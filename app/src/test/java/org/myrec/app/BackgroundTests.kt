package org.myrec.app

import kotlin.math.*

object BackgroundTests {
    fun run(test: (String,()->Unit)->Unit) {
        test("background contracts validate all defaults and retain typed media and colour animation") {
            for ((kind,count) in listOf("Chroma Key" to 27,"Auto Background" to 18)) {
                val fx=Effect.create(kind)
                check(fx.parameters.size==count && fx.parameters.all { it.valid() })
                fx.parameter("media")!!.setKeyframe(2.0,EffectValue.Media("a.mp4"))
                fx.parameter("media")!!.setKeyframe(4.0,EffectValue.Media("b.mp4"))
                fx.parameter("backgroundColor")!!.apply {
                    setKeyframe(2.0,EffectValue.Color(0.0,0.0,0.0)); setKeyframe(4.0,EffectValue.Color(1.0,.5,0.0))
                }
                check(BackgroundEffect.media(fx.sampled(3.0),"media")=="a.mp4")
                check(fx.sampled(3.0).parameter("backgroundColor")!!.value==EffectValue.Color(.5,.25,0.0))
                val duplicate=fx.copyEffect(); duplicate.parameter("media")!!.keyframes.clear()
                check(fx.parameter("media")!!.keyframes.size==2)
            }
        }
        test("keyer accepts green and blue references but rejects neutral and red surfaces") {
            for (c in listOf(EffectValue.Color(0.0,1.0,0.0),EffectValue.Color(.2,.3,.8))) check(BackgroundEffect.acceptsScreen(c))
            for (c in listOf(EffectValue.Color(.5,.5,.5),EffectValue.Color(1.0,0.0,0.0),EffectValue.Color(0.0,0.0,0.0))) check(!BackgroundEffect.acceptsScreen(c))
        }
        test("background loops advance both heads through seams and handle clips shorter than the overlap") {
            for (duration in listOf(.01,.3,1.0,9.0)) {
                val overlap=min(.2,duration/2); val period=duration-overlap
                val start=BackgroundEffect.loop(0.0,duration); check(start.incoming==0.0 && start.outgoing==null)
                val seam=BackgroundEffect.loop(period+overlap/2,duration)
                check(abs(seam.incoming-overlap/2)<1e-8 && abs(seam.outgoing!!-(period+overlap/2))<1e-8 && abs(seam.blend-.5)<1e-8)
                for (n in 0..500) {
                    val p=BackgroundEffect.loop(n*.013,duration)
                    check(p.incoming>=0 && p.incoming<period && p.blend in 0.0..1.0)
                    check(p.outgoing==null || p.outgoing<duration)
                }
            }
        }
        test("matte history rejects scene cuts and local motion without retaining seek history") {
            fun matte(alpha: Float,guide: Float,time: Double)=BackgroundMatte(2,2,FloatArray(7) { if (it<4) alpha else 0f },FloatArray(4) { guide },time)
            val stable=listOf(matte(1f,.2f,2.0),matte(0f,.2f,1.0))
            check(abs(BackgroundTemporal.apply(stable,true)[0]-.75f)<1e-6)
            check(abs(BackgroundTemporal.apply(stable,false)[0]-2f/3)<1e-6)
            check(BackgroundTemporal.apply(listOf(stable[0],matte(0f,.8f,1.0)),true)[0]==1f)
            check(BackgroundTemporal.apply(listOf(stable[0],matte(0f,.23f,1.0)),true)[0]==1f)
            BackgroundTemporal.apply(stable.reversed(),true)
            check(BackgroundTemporal.apply(stable,true).contentEquals(BackgroundTemporal.apply(stable,true)))
        }
        test("background draft changes are cancellable and loop origin survives cuts and undo") {
            val e=Editor(Project(clips=mutableListOf(Clip(path="source.mp4",sourceIn=3.0,sourceOut=7.0))))
            e.applyEffect(Effect.create("Chroma Key"))
            val fx=e.project.clips[0].effects.single()
            check(fx.values["origin"]=="3.0")
            val draft=VideoEffectsDraft(e,BackgroundEffect.kinds)
            draft.editValue(draft.chain.single(),"media",EffectValue.Media("background.mp4"),0.0,false)
            check(BackgroundEffect.media(fx,"media")==null)
            draft.commit(e)
            check(BackgroundEffect.media(e.project.clips[0].effects.single(),"media")=="background.mp4")
            e.cursor=2.0; e.split()
            check(e.project.clips.size==2 && e.project.clips.all { it.effects.single().values["origin"]=="3.0" })
            e.undo(); check(e.project.clips.size==1)
            e.undo(); check(BackgroundEffect.media(e.project.clips[0].effects.single(),"media")==null)
            val reset=VideoEffectsDraft(e,BackgroundEffect.kinds)
            reset.reset(reset.chain.single(),0.0);reset.commit(e)
            check(e.project.clips.single().effects.single().values["origin"]=="3.0")
        }
    }
}
