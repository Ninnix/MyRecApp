package org.myrec.app

import kotlin.math.*

object BackgroundTests {
    fun run(test: (String,()->Unit)->Unit) {
        test("final movie timestamps rebind accepted masks without altering their captured settings clock") {
            val folder=java.nio.file.Files.createTempDirectory("myrec-matte-clock").toFile()
            try {
                val file=java.io.File(folder,"take.masks")
                BackgroundJournal.Writer(file).use { writer ->
                    for (i in 0..2) {
                        val raw=BackgroundMatte(2,2,FloatArray(7),floatArrayOf(.2f),.013+i*.1,1,1)
                        writer.append(BackgroundJournal.Sample("track","signature",raw,raw.alpha,frame=i))
                    }
                    writer.finish("a".repeat(64),doubleArrayOf(0.0,.09998,.19997))
                }
                BackgroundJournal.Reader(file).use { reader ->
                    check(reader.times("track")==listOf(0.0,.09998,.19997))
                    val sample=reader.at("track",.1)!!
                    check(sample.raw.time==.09998 && abs(sample.captureTime-.113)<1e-10 && sample.frame==1)
                }
                val partial=java.io.File(folder,"partial.masks")
                BackgroundJournal.Writer(partial).use { writer ->
                    val raw=BackgroundMatte(2,2,FloatArray(7),floatArrayOf(.2f),.113,1,1)
                    writer.append(BackgroundJournal.Sample("track","signature",raw,raw.alpha,frame=1))
                }
                BackgroundJournal.Reader(partial).use { reader ->
                    reader.recoverClock(doubleArrayOf(0.0,.09998));check(reader.at("track",.1)!!.captureTime==.113)
                }
            } finally { folder.deleteRecursively() }
        }
        test("recorded reset boundaries survive scene cuts control changes and random seeks") {
            val folder=java.nio.file.Files.createTempDirectory("myrec-matte-resets").toFile()
            try {
                val file=java.io.File(folder,"take.masks")
                BackgroundJournal.Writer(file).use { writer ->
                    for (i in 0..6) {
                        val raw=BackgroundMatte(2,2,FloatArray(7),floatArrayOf(if (i<5) .2f else .8f),i/10.0,1,1)
                        writer.append(BackgroundJournal.Sample("track",if (i<3) "a" else "b",raw,raw.alpha))
                    }
                    writer.finish("a".repeat(64))
                }
                BackgroundJournal.Reader(file).use { reader ->
                    check(reader.preceding("track",.4,7,0.0)==listOf(.3))
                    check(reader.preceding("track",.3,7,0.0).isEmpty())
                    check(reader.preceding("track",.61,7,0.0)==listOf(.6,.5))
                    check(reader.preceding("track",.5,7,0.0).isEmpty())
                    check(reader.preceding("track",.2,7,0.0)==listOf(.1,0.0))
                    check(reader.preceding("track",.4,7,.35).isEmpty())
                }
            } finally { folder.deleteRecursively() }
        }
        test("recorded mattes preserve checked final samples and source clocks through seeks") {
            val folder=java.nio.file.Files.createTempDirectory("myrec-mattes").toFile()
            try {
                val file=java.io.File(folder,"take.masks")
                val alpha=BackgroundJournal.canonical(floatArrayOf(.2f,.6f,1f,0f,0f,1f,0f))
                BackgroundJournal.Writer(file).use { writer ->
                    for (t in listOf(.1,.3,.6)) writer.append(BackgroundJournal.Sample("track","signature",
                        BackgroundMatte(2,2,alpha,floatArrayOf(.25f),t,1,1),alpha))
                    writer.finish("a".repeat(64))
                }
                BackgroundJournal.Reader(file).use { reader ->
                    check(reader.sourceHash=="a".repeat(64) && !reader.incomplete)
                    check(reader.at("track",.09)==null && reader.at("other",1.0)==null)
                    for ((t,held) in listOf(.65 to .6,.2 to .1,.59 to .3,.3 to .3)) {
                        val sample=reader.at("track",t)!!
                        check(sample.raw.time==held && sample.final.contentEquals(alpha) && sample.raw.alpha.contentEquals(alpha))
                    }
                    check(reader.times("track")==listOf(.1,.3,.6))
                }
                val interrupted=java.io.File(folder,"interrupted.masks").apply { writeBytes(file.readBytes().dropLast(20).toByteArray()) }
                BackgroundJournal.Reader(interrupted).use { reader ->
                    check(reader.incomplete && reader.sourceHash==null && reader.at("track",.5)!!.raw.time==.3)
                }
                val damaged=file.readBytes();damaged[80]=(damaged[80].toInt() xor 1).toByte()
                val broken=java.io.File(folder,"broken.masks").apply { writeBytes(damaged) }
                check(runCatching { BackgroundJournal.Reader(broken).use { it.at("track",.1) } }.isFailure)
            } finally { folder.deleteRecursively() }
        }
        test("recorded matte assets survive splits history and path relocation") {
            val fx=Effect.create("Auto Background").apply { values["matteJournal"]="take.masks";values["matteTrack"]="source-track" }
            val e=Editor(Project(clips=mutableListOf(Clip(path="source.mp4",sourceOut=4.0,effects=mutableListOf(fx)))))
            e.cursor=2.0;e.split()
            check(e.project.clips.all { it.effects.single().values["matteTrack"]=="source-track" })
            check(e.project.clips.all { "take.masks" in it.effects.single().assetPaths() })
            val copy=fx.copyEffect(true);copy.remapAssets { "archive/$it" }
            check(copy.values["matteJournal"]=="archive/take.masks" && copy.values["matteTrack"]=="source-track")
            check(fx.values["matteJournal"]=="take.masks")
            e.undo();check(e.project.clips.single().effects.single().values["matteJournal"]=="take.masks")
        }
        test("person motion and occlusion suppress old silhouettes while stable hair keeps temporal support") {
            fun matte(alpha: Float,guide: Float,time: Double)=BackgroundMatte(2,2,FloatArray(7) { if (it<4) alpha else 0f },FloatArray(4) { guide },time)
            val current=matte(.8f,.2f,2.0);val stable=matte(.7f,.2f,1.0)
            val filtered=BackgroundTemporal.apply(listOf(current,stable),false,true)[0]
            check(abs(filtered-(.8f*2/3+.7f/3))<1e-6)
            val uncovered=matte(0f,.2f,2.0);val old=matte(1f,.2f,1.0)
            check(BackgroundTemporal.apply(listOf(uncovered,old),false,true)[0]==0f)
            check(BackgroundTemporal.apply(listOf(uncovered,old),false)[0]>.3f)
        }
        test("matte signatures ignore replacement colour and copied ids but detect controls and upstream edits") {
            val fx=Effect.create("Auto Background");val prefix=Effect.create("Night fix")
            val signature=BackgroundEffect.signature(fx,listOf(prefix))
            val copy=fx.copyEffect(true);copy.values["matteJournal"]="relocated.masks";copy.values["matteTrack"]="track"
            copy.parameter("backgroundColor")!!.value=EffectValue.Color(1.0,0.0,0.0)
            check(signature==BackgroundEffect.signature(copy,listOf(prefix.copyEffect(true))))
            copy.setNumber("grow",2f);check(signature!=BackgroundEffect.signature(copy,listOf(prefix)))
            prefix.setNumber("intensity",.4f);check(signature!=BackgroundEffect.signature(fx,listOf(prefix)))
        }
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
