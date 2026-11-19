package org.myrec.app

import android.app.Instrumentation
import android.graphics.RectF
import android.view.*
import android.widget.*
import java.io.File
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlin.math.*

object RemainingParityTests {
    fun run(i: Instrumentation,a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        val inputs=File(a.getExternalFilesDir(null),"test-inputs/remaining-parity")
        val folder=File(a.getExternalFilesDir(null),"remaining-parity-checks").apply { mkdirs() }
        test("DeepFilterNet streams stereo through former window joins and drains the exact final sample") {
            val models=File(inputs,"models");DeepFilterModel.prepare(models)
            val source=File(inputs,"stream-input.f32");val expected=File(inputs,"stream-reference.f32")
            val output=File(folder,"stream-wet.f32").apply { delete() }
            SourceAudio.reduce(source,output,models,{ false },{})
            check(output.length()==source.length() && output.length()==expected.length())
            val x=ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val y=ByteBuffer.wrap(expected.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            var maximum=0.0;var square=0.0;var channels=0.0
            for (j in 0 until x.limit()) {
                val error=abs(x[j]-y[j]).toDouble();maximum=max(maximum,error);square+=error*error
                if (j%2==0) channels+=abs(x[j]-x[j+1])
            }
            check(maximum<.0003 && sqrt(square/x.limit())<.00002 && channels>1) { "Streaming error $maximum / ${sqrt(square/x.limit())}; stereo=$channels" }
            File(folder,"streaming.json").writeText(JSONObject().put("samples",x.limit()/2).put("maximum_error",maximum).put("rms_error",sqrt(square/x.limit())).put("runtime",Native.inferenceVersion()).toString(2))
        }
        test("DeepFilterNet cancellation never publishes a partial wet source") {
            val output=File(folder,"cancelled.f32").apply { delete() };var cancelled=false
            val error=runCatching { SourceAudio.reduce(File(inputs,"stream-input.f32"),output,File(inputs,"models"),{ cancelled }) { if (it>.02f) cancelled=true } }.exceptionOrNull()
            check(error is InterruptedIOException && !output.exists())
        }
        test("DeepFilterNet restores both recurrent streams after cancellation without a join") {
            val source=File(inputs,"stream-input.f32");val expected=File(folder,"stream-wet.f32")
            val output=File(folder,"resumed.f32").apply { delete() }
            File(output.path+".stream.state").delete();File(output.path+".stream.partial").delete()
            var cancel=false
            val error=runCatching { SourceAudio.reduce(source,output,File(inputs,"models"),{ cancel }) { if (it>.3f) cancel=true } }.exceptionOrNull()
            check(error is InterruptedIOException && !output.exists() && File(output.path+".stream.state").isFile)
            File(output.path+".stream.partial").appendBytes(ByteArray(80))
            var first: Float?=null
            SourceAudio.reduce(source,output,File(inputs,"models"),{ false }) { if (first==null) first=it }
            check(first!!>.29f && SourceAudio.hash(output)==SourceAudio.hash(expected) && !File(output.path+".stream.state").exists())
        }
        test("prepared source audio reuses split parts and rebuilds a damaged PCM stage") {
            val originalModels=AudioModels.directory("Noise reduction").parentFile!!;val originalCache=Studio.cacheDirectory
            val modelRoot=File(folder,"model-store");val models=File(modelRoot,"deepfilter-v3").apply { mkdirs() }
            try {
                for (name in listOf("enc.onnx","erb_dec.onnx","df_dec.onnx")) File(inputs,"models/$name").copyTo(File(models,name),true)
                File(models,"ready").writeText(AudioModels.version("Noise reduction"))
                AudioModels.configure(i.targetContext,modelRoot);Studio.cacheDirectory=File(folder,"prepared-audio")
                val source=File(folder,"speech.wav");i.context.assets.open("speech.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
                val clip=ProjectStore.inspect(source).apply { effects.add(Effect(kind="Noise reduction")) }
                val cache=AudioCache(File(folder,"source-audio"));val hash=SourceAudio.hash(source)
                val first=Studio.prepare(clip,cache,{ false },{})!!;val digest=SourceAudio.hash(first);val stamp=first.lastModified()
                val part=clip.duplicate().apply { sourceIn=.2;sourceOut=min(sourceOut,1.0);start=10.0 }
                val second=Studio.prepare(part,cache,{ false },{})!!;check(second==first && second.lastModified()==stamp)
                java.io.RandomAccessFile(first,"rw").use { it.seek(8);it.writeInt(12345) };check(first.setLastModified(stamp))
                check(!PcmCache.valid(first) { false })
                val repaired=Studio.prepare(clip,cache,{ false },{})!!
                check(SourceAudio.hash(repaired)==digest && SourceAudio.hash(source)==hash)
            } finally { Studio.cacheDirectory=originalCache;AudioModels.configure(i.targetContext,originalModels) }
        }
        test("thermal pressure yields or stops cooperatively before discarding completed audio") {
            MediaThermalGate { 0 }.cooperate()
            MediaThermalGate { 3 }.cooperate()
            check(runCatching { MediaThermalGate { 4 }.cooperate() }.exceptionOrNull() is InterruptedIOException)
        }
        test("long stereo processing keeps bounded memory and the exact source length") {
            val source=File(inputs,"stream-long.f32");val output=File(folder,"stream-long-wet.f32").apply { delete() }
            check(source.length()>48000L*8*120)
            val resumed=File(output.path+".stream.state").isFile
            val key="${SourceAudio.hash(source)}:${AudioModels.version("Noise reduction")}:${Native.inferenceVersion()}"
            val report=File(folder,"long-stereo.json");val previous=runCatching { JSONObject(report.readText()) }.getOrNull()?.takeIf { it.optString("key")==key }
            System.gc();val runtime=Runtime.getRuntime();val baseline=runtime.totalMemory()-runtime.freeMemory()
            var maximum=baseline;val start=android.os.SystemClock.elapsedRealtime();var first: Float?=null
            SourceAudio.reduce(source,output,File(inputs,"models"),{ false }) { if (first==null) first=it;maximum=max(maximum,runtime.totalMemory()-runtime.freeMemory()) }
            check(output.length()==source.length() && maximum-baseline<64L*1024*1024)
            if (resumed) check(first!!>0)
            val bytes=ByteArray(65536);var difference=0.0
            val sum=java.security.MessageDigest.getInstance("SHA-256")
            output.inputStream().buffered().use { input ->
                while (true) {
                    val n=input.read(bytes);if (n<0) break;check(n%8==0)
                    sum.update(bytes,0,n)
                    val buffer=ByteBuffer.wrap(bytes,0,n).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    while (buffer.hasRemaining()) { val left=buffer.get();val right=buffer.get();check(left.isFinite() && right.isFinite());difference+=abs(left-right) }
                }
            }
            check(difference>1)
            val hash=sum.digest().joinToString("") { "%02x".format(it) };previous?.let { check(it.getString("sha256")==hash) }
            report.writeText(JSONObject().put("key",key).put("sha256",hash).put("resumed",resumed).put("first_progress",first)
                .put("seconds",source.length()/8/48000.0).put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-start)
                .put("baseline_heap",baseline).put("peak_heap",maximum).put("output_bytes",output.length()).toString(2))
            output.delete()
        }
        test("saved undo states stay deferred through reopen and save and remain private on demand") {
            val store=ProjectStore(File(a.cacheDir,"deferred-check-${uid()}"))
            val editor=Editor(Project(clips=MutableList(24) { Clip(path="fixture.mp4",start=it.toDouble(),sourceOut=1.0) }))
            repeat(96) { editor.setGain(if (it%2==0) -3f else -9f) };store.save(editor,false)
            check(editor.session().undo.all { it.deferred })
            val saved=store.open(editor.project.id);check(saved.undo.size==96 && saved.undo.all { it.deferred })
            store.save(saved,false);check(saved.undo.all { it.deferred })
            val first=saved.undo.first().project;first.clips.first().gainDb=-55f
            check(saved.undo.first().project.clips.first().gainDb==0f)
            val reopened=Editor(saved.project).apply { load(saved.project,saved) }
            repeat(96) { reopened.undo() };check(!reopened.canUndo && reopened.project.clips.first().gainDb==0f)
            store.root.deleteRecursively()
        }
        test("PCM receipts reject changed bytes even when size and modification time are preserved") {
            val file=File(folder,"receipt.f32").apply { writeBytes(ByteArray(800) { it.toByte() }) }
            PcmCache.seal(file) { false };check(PcmCache.valid(file) { false });val stamp=file.lastModified()
            java.io.RandomAccessFile(file,"rw").use { it.seek(8);it.writeInt(12345) };check(file.setLastModified(stamp))
            check(!PcmCache.valid(file) { false })
        }
        test("a long project opens deferred history and repairs an old revision beyond the decoded cache") {
            val store=ProjectStore(File(a.cacheDir,"long-project-${uid()}"))
            try {
                val base=Project(name="Long edit",clips=MutableList(100) { Clip(path="take-${it%8}.mp4",sourceOut=600.0,start=it*600.0) })
                val history=List(600) { j -> EditRevision.deferred(base.id,0) { base.duplicate().apply { clips.first().gainDb=-j/600f*23 } } }
                store.save(EditSession(base,undo=history),false)
                val started=android.os.SystemClock.elapsedRealtime();val reopened=ProjectStore(store.root);val saved=reopened.open(base.id)
                val elapsed=android.os.SystemClock.elapsedRealtime()-started
                check(saved.undo.size==600 && saved.undo.all { it.deferred } && saved.project.duration==60000.0)
                val index=JSONObject(File(store.root,"${base.id}.json").readText());val hash=index.getJSONArray("undo").getString(0)
                val file=File(store.root,"revisions/${base.id}/$hash.json");val expected=SourceAudio.hash(file)
                file.writeBytes(ByteArray(0));reopened.save(saved,false)
                check(SourceAudio.hash(file)==expected && saved.undo.all { it.deferred })
                val edit=Editor(saved.project).apply { load(saved.project,saved) };repeat(600) { edit.undo() }
                check(!edit.canUndo && edit.project.clips.first().gainDb==0f)
                File(folder,"long-project.json").writeText(JSONObject().put("clips",100).put("hours",base.duration/3600).put("states",600).put("open_ms",elapsed)
                    .put("heap_limit",Runtime.getRuntime().maxMemory()).put("heap_used",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()).toString(2))
            } finally { store.root.deleteRecursively() }
        }
        test("a broken advisory summary cannot hide an intact project") {
            val store=ProjectStore(File(a.cacheDir,"catalog-check-${uid()}"))
            try {
                val p=Project(name="Summary recovery",clips=mutableListOf(Clip(path="source.mp4",sourceOut=7.0)))
                store.save(p,false);val file=File(store.root,"${p.id}.json");val json=JSONObject(file.readText())
                json.getJSONObject("summary").put("duration",-1);file.writeText(json.toString())
                check(ProjectStore(store.root).catalog().single().duration==7.0)
            } finally { store.root.deleteRecursively() }
        }
        test("independent mirror axes render correctly and retain the existing one-axis behavior") {
            val bitmap=android.graphics.Bitmap.createBitmap(64,32,android.graphics.Bitmap.Config.ARGB_8888)
            for (y in 0 until 32) for (x in 0 until 64) bitmap.setPixel(x,y,when {
                x<32 && y<16 -> android.graphics.Color.RED
                x>=32 && y<16 -> android.graphics.Color.GREEN
                x<32 -> android.graphics.Color.BLUE
                else -> android.graphics.Color.YELLOW
            })
            val file=File(folder,"mirror.png");file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            try {
                for ((values,x,y) in listOf(Triple(mapOf("vertical" to "0"),56,4),Triple(mapOf("horizontal" to "1","vertical" to "1"),56,28),Triple(mapOf("horizontal" to "0","vertical" to "0"),8,4))) {
                    val clip=Clip(path=file.path,sourceOut=1.0,photo=true,audio=false,effects=mutableListOf(Effect(kind="Mirror",values=values.toMutableMap())))
                    Frames().use { frames ->
                        val output=frames.render(Project(clips=mutableListOf(clip)),.5,64,32)
                        try { check(output.getPixel(8,4)==bitmap.getPixel(x,y)) } finally { output.recycle() }
                    }
                }
            } finally { bitmap.recycle() }
        }
        test("project catalog remains small and virtualizes a large library") {
            val saved=a.editor.project.duplicate()
            repeat(160) { j -> a.store.save(Project(name="Library $j",clips=MutableList(16) { Clip(path="missing.mp4",sourceOut=1.0,start=it.toDouble()) }),false) }
            val cards=a.store.catalog();check(cards.size>=160 && cards.all { it.project.clips.isEmpty() })
            check(cards.any { it.project.name=="Library 0" && it.takes==1 && it.duration==16.0 })
            main {
                a.panels.projects()
                fun find(view: View): ListView? {
                    if (view is ListView) return view
                    if (view is ViewGroup) for (j in 0 until view.childCount) find(view.getChildAt(j))?.let { return it }
                    return null
                }
                val list=find(a.root) ?: error("Projects are not virtualized")
                check(list.adapter.count>=160 && list.childCount<20)
                list.setSelection(130)
            }
            Thread.sleep(300)
            main { a.panels.close();a.editor.load(saved) }
        }
        test("stabilization resumes a cancelled source range from checked motion blocks") {
            val source=File(a.store.media,"resume-stabilization.mp4")
            i.context.assets.open("stabilization-shake.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            val clip=ProjectStore.inspect(source).apply { sourceOut=2.0 }
            val fx=Effect.create("Stabilize");StabilizationSettings.bind(fx,clip,null)
            val original=StabilizationRenderer.directory;val directory=File(folder,"motion").apply { deleteRecursively();mkdirs() }
            try {
                StabilizationRenderer.directory=directory;StabilizationRenderer.clear();var stop=false
                VideoFrameIndex.get(clip.path,StabilizationRenderer.identity(clip.path) { false }) {}
                val failure=runCatching { StabilizationRenderer.matrix(clip,fx,0.0,RectF(0f,0f,640f,360f),{ stop }) { if ((it ?: 0f)>.25f) stop=true } }.exceptionOrNull()
                check(failure is InterruptedIOException)
                val checkpoint=directory.listFiles()!!.single { it.extension=="checkpoint" };check(checkpoint.length()>100)
                stop=false;var resumed=0f
                StabilizationRenderer.matrix(clip,fx,0.0,RectF(0f,0f,640f,360f),{ false }) { if (resumed==0f && it!=null && it>0) resumed=it }
                check(resumed>.2f && !checkpoint.exists())
                check(StabilizationRenderer.status(clip,fx)!!.samples.size==120)
            } finally { StabilizationRenderer.directory=original;StabilizationRenderer.clear() }
        }
        test("natural footage keeps crop limits and handles moving foreground and low light") {
            val original=StabilizationRenderer.directory
            val evidence=JSONObject()
            try {
                StabilizationRenderer.directory=File(folder,"natural-motion").apply { mkdirs() };StabilizationRenderer.clear()
                for (name in listOf("natural-people-shake.mp4","natural-camera.mp4")) {
                    val clip=ProjectStore.inspect(File(inputs,name));val fx=Effect.create("Stabilize")
                    StabilizationSettings.bind(fx,clip,null)
                    StabilizationRenderer.matrix(clip,fx,0.0,RectF(0f,0f,640f,360f),{ false }) {}
                    val plan=StabilizationRenderer.status(clip,fx)!!
                    check(plan.samples.size>=90 && plan.zoom in 1.0..1.100001)
                    for (sample in plan.samples) {
                        check(sample.pose.valid() && sample.pose.requiredZoom(plan.width,plan.height)<=plan.zoom+.00001)
                    }
                    val row=JSONObject().put("samples",plan.samples.size).put("tracked",plan.trackingFraction).put("zoom",plan.zoom).put("limited",plan.limited)
                    if (name.contains("people")) {
                        check(plan.trackingFraction>.8) { "Lost the static background: ${plan.trackingFraction}" }
                        val analysis=StabilizationRenderer.directory!!.listFiles()!!.filter { it.extension=="path" }.mapNotNull { StabilizationCache.read(it,it.nameWithoutExtension) }.single { it.width==plan.width && it.height==plan.height }
                        fun energy(poses: List<StabilizationPose>)=(2 until poses.size).sumOf { j ->
                            val x=poses[j].x-2*poses[j-1].x+poses[j-2].x;val y=poses[j].y-2*poses[j-1].y+poses[j-2].y;x*x+y*y
                        }
                        val before=energy(analysis.samples.map { it.pose });val after=energy(plan.samples.zip(analysis.samples).map { (correction,raw) -> correction.pose.applying(raw.pose) })
                        check(after<before*.5) { "Natural shake was not reduced: $before -> $after" }
                        row.put("shake_energy_before",before).put("shake_energy_after",after)
                    }
                    evidence.put(name,row)
                }
                File(folder,"natural-stabilization.json").writeText(evidence.toString(2))
            } finally { StabilizationRenderer.directory=original;StabilizationRenderer.clear() }
        }

    }
}
