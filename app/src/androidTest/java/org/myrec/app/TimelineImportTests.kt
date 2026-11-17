package org.myrec.app

import android.app.Instrumentation
import android.provider.DocumentsContract
import java.io.File
import java.io.InterruptedIOException
import org.json.*
import kotlin.math.*

object TimelineImportTests {
    fun run(i: Instrumentation,test: (String,()->Unit)->Unit) {
        val root=File(i.targetContext.cacheDir,"otio-import-${uid()}");val store=ProjectStore(root)
        fun time(v: Double)=JSONObject().put("OTIO_SCHEMA","RationalTime.1").put("rate",30).put("value",v*30)
        fun range(start: Double,duration: Double)=JSONObject().put("OTIO_SCHEMA","TimeRange.1").put("start_time",time(start)).put("duration",time(duration))
        fun container(type: String,vararg children: JSONObject)=JSONObject().put("OTIO_SCHEMA","$type.1").put("kind","Video").put("children",JSONArray(children.toList()))
        fun clip(path: String,start: Double=0.0,duration: Double=4.0,speed: Double=1.0)=JSONObject().put("OTIO_SCHEMA","Clip.2").put("source_range",range(start,duration))
            .put("media_reference",JSONObject().put("OTIO_SCHEMA","ExternalReference.1").put("target_url",path).put("available_range",range(0.0,20.0)))
            .put("effects",if (speed==1.0) JSONArray() else JSONArray().put(JSONObject().put("OTIO_SCHEMA","LinearTimeWarp.1").put("time_scalar",speed)))
        fun timeline(stack: JSONObject)=JSONObject().put("OTIO_SCHEMA","Timeline.1").put("name","Nested Rome").put("tracks",stack)
        fun near(a: Double,b: Double) { check(abs(a-b)<.00001) { "$a != $b" } }
        try {
            test("OTIO 0.18.1 authored nested timeline imports with its declared duration and source range") {
                val document=i.context.assets.open("otio-nested.otio").bufferedReader().use { JSONObject(it.readText()) }
                val p=TimelineImport.decode(store,document) { it }
                near(p.duration,1.0);near(p.clips.single().sourceIn,4.0);near(p.clips.single().sourceOut,6.0)
            }
            test("OTIO nested track and stack trims retain source clocks and independent layers") {
                val track=container("Track",clip("take.mp4",1.0,4.0,2.0)).put("source_range",range(1.0,2.0))
                val nested=container("Stack",track).put("source_range",range(.5,1.0))
                val p=TimelineImport.decode(store,timeline(container("Stack",container("Track",nested)))) { it }
                near(p.duration,1.0);near(p.clips.single().sourceIn,4.0);near(p.clips.single().sourceOut,6.0)
                val lower=container("Track",clip("lower.mp4",duration=5.0))
                val upper=container("Track",JSONObject().put("OTIO_SCHEMA","Gap.1").put("source_range",range(0.0,1.0)),clip("upper.mp4",duration=2.0))
                val group=container("Stack",lower,upper)
                val sequence=container("Track",JSONObject().put("OTIO_SCHEMA","Gap.1").put("source_range",range(0.0,2.0)),group,clip("last.mp4",duration=1.0))
                val layered=TimelineImport.decode(store,timeline(container("Stack",sequence))) { it }
                near(layered.duration,8.0)
                check(layered.clips.map { it.start }==listOf(2.0,3.0,7.0) && layered.clips.map { it.track }==listOf(0,1,0))
            }
            test("trimming inside an OTIO dissolve preserves its picture phase and sound handles") {
                val join=JSONObject().put("OTIO_SCHEMA","Transition.1").put("transition_type","SMPTE_Dissolve").put("in_offset",time(.2)).put("out_offset",time(.4))
                val track=container("Track",clip("left.mp4",duration=4.0),join,clip("right.mp4",1.0,4.0))
                val original=TimelineImport.decode(store,timeline(container("Stack",track))) { it }
                track.put("source_range",range(4.05,.2))
                val p=TimelineImport.decode(store,timeline(container("Stack",track))) { it }
                near(p.duration,.2);check(p.clips.size==2 && p.transitions.size==1)
                for (at in listOf(.01,.1,.19)) near(p.transitions.single().progress(at).toDouble(),original.transitions.single().progress(4.05+at).toDouble())
                near(p.clips.single { it.path=="right.mp4" }.sourceIn,1.05)
            }
            test("nested audio trims preserve the decoded dissolve samples") {
                val source=File(root,"sound.mp4");i.context.assets.open("fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
                val join=JSONObject().put("OTIO_SCHEMA","Transition.1").put("transition_type","SMPTE_Dissolve").put("in_offset",time(.15)).put("out_offset",time(.25))
                val track=container("Track",clip("left.mp4",duration=1.0),join,clip("right.mp4",.2,1.0)).put("kind","Audio")
                val original=TimelineImport.decode(store,timeline(container("Stack",track))) { source.path }
                track.put("source_range",range(.9,.3))
                val p=TimelineImport.decode(store,timeline(container("Stack",track))) { source.path }
                val cache=AudioCache(File(root,"audio"))
                fun samples(project: Project,at: Double)=AudioMixer(project,cache).use { it.prepare();it.seek(at);it.read(9600) }
                val a=samples(original,.9);val b=samples(p,0.0)
                check(a.indices.all { abs(a[it]-b[it])<.00003 }) { "Composition trim shifted the sound fade" }
            }
            test("OTIO imports referenced media through a real DocumentsProvider tree") {
                val tree=DocumentsContract.buildTreeDocumentUri("org.myrec.app.debug.timeline","root")
                try {
                val media=TimelineMedia(i.targetContext.contentResolver,tree) { false }
                val doc=File(root,"provider.otio").apply { writeText(timeline(container("Stack",container("Track",clip("../Media/take.mp4",duration=1.0)))).toString()) }
                val restored=TimelineImport.restore(store,doc,{ false },{},media::resolve)
                check(restored.missing.isEmpty() && File(restored.project.clips.single().path).isFile)
                check(store.open(restored.project.id).originals.single().sourceOut>1.0)
                check(File(File(restored.project.clips.single().path).parentFile,".timeline.otio").readText()==doc.readText())
                check(media.resolve("not-present.mp4")==null)
                check(runCatching { media.matches("take.mp4",listOf("a/take.mp4","b/take.mp4")) }.isFailure)
                check(media.matches("a/take.mp4",listOf("a/take.mp4","b/take.mp4"))=="a/take.mp4")
                doc.writeText(timeline(container("Stack",container("Track",clip("file:///Media/take.mp4",duration=1.0),clip("/Media/./take.mp4",1.0,1.0)))).toString())
                val aliases=TimelineImport.restore(store,doc,{ false },{},media::resolve)
                check(aliases.project.clips.map { it.path }.distinct().size==1 && store.open(aliases.project.id).originals.size==1)
                } finally {
                    runCatching { DocumentsContract.deleteDocument(i.targetContext.contentResolver,DocumentsContract.buildDocumentUriUsingTree(tree,"take")) }
                }
            }
            test("changing or cancelled folder imports publish no project and keep source media") {
                val source=File(root,"source.mp4");i.context.assets.open("fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
                val doc=File(root,"failure.otio").apply { writeText(timeline(container("Stack",container("Track",clip("take.mp4",duration=1.0)))).toString()) }
                val before=store.list().size;var checks=0
                val failed=runCatching { TimelineImport.restore(store,doc,{ false },{}) { TimelineImport.Media(source.length(),{ source.inputStream() },{ ++checks<2 }) } }
                check(failed.isFailure && checks==2 && store.list().size==before && source.isFile)
                check(runCatching { TimelineImport.restore(store,doc,{ true },{}) }.exceptionOrNull() is InterruptedIOException)
                check(store.media.listFiles()!!.none { it.name.startsWith(".restore-") })
            }
            test("unsafe media URLs excessive nesting and unsupported composition processing are refused") {
                for (url in listOf("https://example.com/take.mp4","file://remote/take.mp4","%2e%2e/","take%00.mp4")) check(runCatching { TimelineImport.mediaPath(url) }.isFailure)
                check(TimelineImport.mediaPath("../Media/./take.mp4")=="Media/take.mp4")
                check(TimelineImport.mediaPath("file:///Media/Colosseo%20%2B%20Roma.mp4")=="/Media/Colosseo + Roma.mp4")
                var stack=container("Stack",container("Track",clip("take.mp4")))
                repeat(35) { stack=container("Stack",stack) }
                check(runCatching { TimelineImport.decode(store,timeline(stack)) { it } }.isFailure)
                val processed=container("Track",clip("take.mp4")).put("effects",JSONArray().put(JSONObject().put("OTIO_SCHEMA","Effect.1")))
                check(runCatching { TimelineImport.decode(store,timeline(container("Stack",processed))) { it } }.isFailure)
            }
        } finally { root.deleteRecursively() }
    }
}
