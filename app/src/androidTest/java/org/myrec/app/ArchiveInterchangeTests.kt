package org.myrec.app

import android.app.Instrumentation
import java.io.File
import java.security.MessageDigest
import java.util.zip.*
import org.json.*

object ArchiveInterchangeTests {
    fun run(i: Instrumentation,test: (String,()->Unit)->Unit) {
        InterchangeGeometryTests.run(i,test)
        TimelineImportTests.run(i,test)
        val root=File(i.targetContext.cacheDir,"native-archive-${uid()}");val store=ProjectStore(root)
        fun fixture(name: String): Clip {
            val file=File(store.media,name)
            i.context.assets.open("fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
            return ProjectStore.inspect(file)
        }
        fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun write(file: File,entries: Map<String,ByteArray>)=ZipOutputStream(file.outputStream()).use { zip ->
            for ((name,bytes) in entries) { zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() }
        }
        val editor=Editor(Project(name="YouRec interchange"))
        editor.appendRecording(fixture("one.mp4"));editor.appendRecording(fixture("two.mp4"))
        val title=Effect(kind="Text",values=(TitleStyle.defaults+mapOf("text" to "Rome","y" to ".8","entrance" to "Drift")).toMutableMap())
        editor.applyEffect(title);editor.addBookmark("Colosseum")
        val stabilization=Effect.create("Stabilize");editor.applyEffect(stabilization)
        editor.applyEffect(Effect(kind="Color LUTs",values=mutableMapOf("lut" to "Natural","strength" to ".7")));editor.undo()
        store.save(editor,false);val archive=File(root,"yourec.zip")
        try {
            test("native video keys retain identities and strobe phase across a source trim") {
                val fx=Effect.create("RGB Offset").apply { parameter("redX")!!.setKeyframe(2.0,EffectValue.Number(.2));parameter("redX")!!.setKeyframe(4.0,EffectValue.Number(.4)) }
                val strobe=Effect.create("Negative Strobe").apply { phase=EffectPhase(0.0,.4,listOf(EffectTimePoint(0.0,0.0),EffectTimePoint(4.0,4.0))) }
                val clip=Clip(path="keys.mp4",sourceIn=2.0,sourceOut=4.0,effects=mutableListOf(fx,strobe))
                val p=Project(clips=mutableListOf(clip));val source=uid()
                val header=JSONObject().put("id",p.id).put("title",p.name).put("sources",JSONArray().put(JSONObject().put("id",source).put("filename",clip.path).put("duration",4.0)))
                val snapshot=YouRecDocument.snapshot(p,mapOf(clip.path to source),0)
                val loaded=YouRecDocument.project(snapshot,header,mapOf(clip.path to clip.path)).clips.first { it.video }
                check(loaded.effects.first().parameter("redX")!!.keyframes==fx.parameter("redX")!!.keyframes)
                repeat(100) { j -> val t=2+j/50.0;check(TimeEffects.negative(strobe,t,30.0)==TimeEffects.negative(loaded.effects.last(),t,30.0)) }
            }
            test("OTIO restores independent source tracks cuts gaps retiming and dissolve handles") {
                val left=Clip(path="left.mp4",sourceOut=4.0);val right=Clip(path="right.mp4",sourceOut=4.0,start=4.0)
                val edit=Editor(Project(clips=mutableListOf(left,right)))
                edit.putTransition(Transition(at=4.0,duration=.5,leftId=left.id,rightId=right.id,controls=TransitionControls()))
                val document=EditorialTimeline.encode(edit.project);val loaded=TimelineImport.decode(store,document) { it }
                check(kotlin.math.abs(loaded.duration-edit.project.duration)<1.0/edit.project.fps)
                check(loaded.clips.count { it.video }==2 && loaded.clips.count { it.audio }==2 && loaded.transitions.size==1)
                val first=loaded.clips.filter { it.video }.sortedBy { it.start }
                check(kotlin.math.abs(first[0].sourceOut-4.0)<.00001 && kotlin.math.abs(first[1].sourceIn)<.00001)
                val folder=File(i.targetContext.getExternalFilesDir(null),"remaining-parity-checks").apply { mkdirs() }
                File(folder,"timeline.otio").writeText(document.toString(2))
                File(folder,"timeline-expected.json").writeText(JSONObject().put("duration",edit.project.duration).put("video_clips",2).put("audio_clips",2).toString())
                val rates=Project(clips=mutableListOf(Clip(path="fast.mp4",sourceIn=1.0,sourceOut=4.0,rate=2.0),Clip(path="slow.mp4",sourceOut=2.0,rate=.5,start=3.0)))
                val retimed=TimelineImport.decode(store,EditorialTimeline.encode(rates)) { it }
                val video=retimed.clips.filter { it.video }.sortedBy { it.start }
                check(video.map { it.rate }==listOf(2.0,.5) && video[1].start==3.0 && retimed.duration==7.0)
            }
            test("OTIO MyRec payload retains complex processing and copies owned originals") {
                val p=Project(clips=mutableListOf(fixture("timeline-original.mp4")))
                p.clips.single().effects.add(Effect(kind="Color LUTs",values=mutableMapOf("lut" to "Warm","strength" to ".6")))
                val doc=EditorialTimeline.encode(p,projectData=store.encode(p));val file=File(root,"full.otio").apply { writeText(doc.toString()) }
                val restored=TimelineImport.restore(store,file,{ false },{})
                check(restored.missing.isEmpty() && restored.project.id!=p.id && restored.project.clips.single().path!=p.clips.single().path)
                check(restored.project.clips.single().effects==p.clips.single().effects)
                check(File(restored.project.clips.single().path).readBytes().contentEquals(File(p.clips.single().path).readBytes()))
                val before=store.list().size
                check(runCatching { TimelineImport.restore(store,file,{ true },{}) }.exceptionOrNull() is java.io.InterruptedIOException)
                check(store.list().size==before)
                val bad=JSONObject(doc.toString());bad.getJSONObject("metadata").remove("org.myrec.project")
                check(runCatching { TimelineImport.decode(store,bad) { it } }.isFailure)
            }
            test("native captions keep source words phrase ownership and shared styling") {
                val clip=Clip(path="captions.wav",sourceOut=4.0,video=false)
                val project=Project(clips=mutableListOf(clip))
                SubtitlesDraft.attach(project,uid(),listOf(SubtitleWord(text="Hello",start=.2,end=.8),SubtitleWord(text="Rome",start=.9,end=1.5)),setOf(clip.id))
                val source=uid();val header=JSONObject().put("id",project.id).put("title",project.name).put("sources",JSONArray().put(JSONObject().put("id",source).put("filename",clip.path).put("duration",4.0)))
                val snapshot=YouRecDocument.snapshot(project,mapOf(clip.path to source),0)
                val loaded=YouRecDocument.project(snapshot,header,mapOf(clip.path to clip.path))
                check(loaded.subtitleCues().map { it.text }==project.subtitleCues().map { it.text })
                check(loaded.clips.single().effects.single().subtitle!!.words==clip.effects.single().subtitle!!.words)
                check(loaded.clips.single().effects.single().values["plate"]=="One box")
            }
            test("shared transitions audio fades censors and direct filters retain their clocks") {
                val first=Clip(path="left.mp4",sourceOut=4.0);val second=Clip(path="right.mp4",sourceOut=4.0,start=4.0)
                first.effects.add(AudioFilter.effect("Voice EQ").apply { values["gain"]="3";values["frequency"]="900" })
                first.fadeIn=AudioEdgeFade(.7,.2);first.censors.add(AudioCensorRegion(from=.5,to=.9,phaseOrigin=.3))
                val edit=Editor(Project(clips=mutableListOf(first,second)))
                edit.putTransition(Transition(at=4.0,duration=.5,leftId=first.id,rightId=second.id,kind="Wipe",angle=32f,controls=TransitionControls(audioOffset=-.1,audioBlend=.8)))
                val p=edit.project;val ids=p.clips.associate { it.path to uid() }
                val header=JSONObject().put("id",p.id).put("title",p.name).put("sources",JSONArray(ids.map { (path,id) -> JSONObject().put("id",id).put("filename",path).put("duration",4.0) }))
                val loaded=YouRecDocument.project(YouRecDocument.snapshot(p,ids,0),header,ids.keys.associateWith { it })
                val a=p.transitions.single();val b=loaded.transitions.single()
                check(kotlin.math.abs(a.pictureOrigin-b.pictureOrigin)<.00001 && kotlin.math.abs(a.overlap-b.overlap)<.00001)
                repeat(20) { j -> val t=a.begin+j*(a.end-a.begin)/20;check(kotlin.math.abs(a.progress(t)-b.progress(t))<.00001 && kotlin.math.abs(a.audioGain(t,true)-b.audioGain(t,true))<.00001) }
                val sound=loaded.clips.single { it.audio && it.path=="left.mp4" }
                check(sound.fadeIn==first.fadeIn && sound.censors==first.censors && sound.effects.single().number("gain")==3f)
            }
            test("YouRec transitions retain highlight order and fitted timing at the opening edge") {
                val clip=Clip(path="opening.mp4",sourceOut=2.0)
                val edit=Editor(Project(clips=mutableListOf(clip)))
                edit.putTransition(Transition(at=0.0,kind="Hitchcock",duration=1.0,amount=0f,
                    controls=TransitionControls(audioOffset=-1.0,audioBlend=4.0)))
                val source=uid();val project=edit.project
                val header=JSONObject().put("id",project.id).put("title",project.name).put("sources",
                    JSONArray().put(JSONObject().put("id",source).put("filename",clip.path).put("duration",2.0)))
                val snapshot=YouRecDocument.snapshot(project,mapOf(clip.path to source),0)
                val loaded=YouRecDocument.project(snapshot,header,mapOf(clip.path to clip.path))
                val expected=project.transitions.single();val actual=loaded.transitions.single()
                check(actual.kind=="Hitchcock" && actual.amount==0f && actual.controls==expected.controls)
                check(actual.timingScale<1 && actual.timingScale==expected.timingScale && actual.overlap==0.0)
                val reopened=Editor(loaded)
                reopened.removeTransition(actual.id)
                check(reopened.project.clips.all { it.start==0.0 } && reopened.project.duration==2.0)
            }
            test("YouRec schema-12 archives retain independent originals captures history titles and redo") {
                YouRecArchive.export(store,editor.project,archive,{ false },{})
                ZipFile(archive).use { zip ->
                    val header=JSONObject(zip.getInputStream(zip.getEntry("project.json")).bufferedReader().readText())
                    check(header.getInt("schemaVersion")==12 && header.getJSONArray("sources").length()==2 && header.getJSONArray("captureLedger").length()==2)
                    val files=header.getJSONArray("snapshotFiles")
                    for (j in 0 until files.length()) {
                        val data=zip.getInputStream(zip.getEntry("History/${files.getString(j)}")).readBytes()
                        check(hash(data)+".json"==files.getString(j))
                        check(JSONObject(String(data)).getInt("captureCursor")==2)
                    }
                }
                val restored=ProjectArchive.restore(store,archive);val loaded=store.editor(restored.project)
                check(loaded.session().captures.size==2 && loaded.session().originals.size==2 && loaded.canRedo)
                check(loaded.project.clips.map { it.path }.distinct().size==2)
                check(loaded.project.resolvedBookmarks().any { it.mark.label=="Colosseum" })
                loaded.redo();check(loaded.project.clips.any { c -> c.effects.any { it.kind=="Color LUTs" } })
                while (loaded.canUndo) loaded.undo()
                check(loaded.project.clips.size==2)
            }
            test("native reader restores shared edits without Android metadata") {
                val entries=linkedMapOf<String,ByteArray>()
                ZipFile(archive).use { zip -> for (e in zip.entries()) entries[e.name]=zip.getInputStream(e).readBytes() }
                val header=JSONObject(String(entries.getValue("project.json")));header.remove("myrecSession")
                val names=header.getJSONArray("snapshotFiles")
                for (j in 0 until names.length()) {
                    val old="History/${names.getString(j)}";val snapshot=JSONObject(String(entries.getValue(old)));snapshot.remove("myrec")
                    val data=snapshot.toString().toByteArray();val name=hash(data)+".json";entries.remove(old);entries["History/$name"]=data;names.put(j,name)
                }
                entries["project.json"]=header.toString().toByteArray();val file=File(root,"native-only.zip");write(file,entries)
                val restored=ProjectArchive.restore(store,file);val loaded=store.editor(restored.project)
                check(loaded.project.clips.count { it.video }==2 && loaded.project.clips.count { it.audio }==2)
                check(loaded.project.clips.flatMap { it.effects }.any { it.kind=="Text" && it.values["y"]!!.toDouble() in .799.. .801 && it.values["entrance"]=="Drift" })
                check(loaded.project.resolvedBookmarks().any { it.mark.label=="Colosseum" })
                check(loaded.session().originals.size==2 && loaded.canRedo)
                loaded.redo();check(loaded.project.clips.flatMap { it.effects }.any { it.kind=="Color LUTs" })
            }
            test("independent native snapshots replay later captures and retain unused originals") {
                val id=uid();val source=uid();val owner=uid();val occurrence=uid();val bytes=File(editor.project.clips.first().path).readBytes()
                val duration=editor.project.clips.first().sourceOut
                val body=JSONObject().put("id",owner).put("sourceID",source).put("start",0).put("end",duration)
                val empty=JSONObject("""{"timeline":{"video":[],"audio":[],"links":[],"bookmarks":[],"placements":[],"transitions":[],"generated":[]},"outputSize":[640,360]}""")
                val header=JSONObject().put("schemaVersion",12).put("id",id).put("title","Native fixture").put("historyCursor",0)
                    .put("sources",JSONArray().put(JSONObject().put("id",source).put("filename","take.mp4").put("duration",duration).put("recordedAt",0).put("sha256",hash(bytes))))
                    .put("captureLedger",JSONArray().put(JSONObject().put("clip",body).put("insertion",false)))
                    .put("snapshots",JSONArray().put(JSONObject().put("state",empty).put("captureCursor",0)))
                val file=File(root,"independent.zip");write(file,mapOf("project.json" to header.toString().toByteArray(),"take.mp4" to bytes,
                    "integrity.json" to JSONObject().put("version",1).put("sha256",JSONObject().put("take.mp4",hash(bytes))).toString().toByteArray()))
                val restored=ProjectArchive.restore(store,file);val loaded=store.editor(restored.project)
                check(loaded.project.clips.count { it.video }==1 && loaded.project.clips.count { it.audio }==1 && loaded.session().originals.size==1)
                check(kotlin.math.abs(loaded.project.duration-duration)<.00001)
            }
            test("untranslatable processing leaves the original project and archive target intact") {
                val unsupported=Editor(Project(clips=mutableListOf(fixture("unsupported.mp4"))))
                unsupported.applyEffect(Effect(kind="Image",values=mutableMapOf("path" to File(root,"image.png").apply { writeBytes(byteArrayOf(1)) }.path)))
                store.save(unsupported,false);val target=File(root,"unsupported.zip")
                check(runCatching { YouRecArchive.export(store,unsupported.project,target,{ false },{}) }.isFailure)
                check(!target.exists() && store.open(unsupported.project.id).project.clips.single().effects.single().kind=="Image")
            }
        } finally { root.deleteRecursively() }
    }
}
