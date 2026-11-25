package org.myrec.app

import android.app.Instrumentation
import android.graphics.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.*
import org.json.*
import kotlin.math.*

object InterchangeGeometryTests {
    fun run(i: Instrumentation,test: (String,()->Unit)->Unit) {
        TitleStyle.configure(i.targetContext)
        val root=File(i.targetContext.cacheDir,"interchange-geometry-${uid()}").apply { mkdirs() };val store=ProjectStore(root)
        fun picture(name: String,reverse: Boolean=false): String {
            val file=File(store.media,name);val b=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888)
            for (y in 0 until b.height) for (x in 0 until b.width) b.setPixel(x,y,Color.rgb(if (reverse) 255-x*255/320 else x*255/320,y*255/180,if (x<70 && y<100) 255 else 20))
            file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle();return file.path
        }
        fun roundTrip(p: Project): Project {
            val ids=p.clips.associate { it.path to uid() }
            val header=JSONObject().put("id",p.id).put("title",p.name).put("exportSettings",JSONObject().put("frameRate",p.fps))
                .put("sources",JSONArray(ids.map { (path,id) -> JSONObject().put("id",id).put("filename",path).put("duration",20.0).put("isStillPhoto",p.clips.first { it.path==path }.photo) }))
            val paths=(ids.keys+p.clips.flatMap { it.effects }.mapNotNull { it.values["path"] }).associateWith { it }
            return YouRecDocument.project(YouRecDocument.snapshot(p,ids,0),header,paths).also { store.decode(store.encode(it)) }
        }
        fun same(a: Bitmap,b: Bitmap,tolerance: Double=.08) {
            try {
                var error=0L
                for (y in 0 until a.height) for (x in 0 until a.width) {
                    val l=a.getPixel(x,y);val r=b.getPixel(x,y)
                    for (shift in listOf(0,8,16)) error+=abs(((l shr shift) and 255)-((r shr shift) and 255))
                }
                check(error.toDouble()/(a.width*a.height*3)<tolerance) { "Image difference ${error.toDouble()/(a.width*a.height*3)}" }
            } finally { a.recycle();b.recycle() }
        }
        try {
            val first=picture("first.png");val second=picture("second.png",true)
            test("native crop conversion preserves asymmetric pixels through rotation and both mirror axes") {
                for (h in listOf(false,true)) for (v in listOf(false,true)) for (angle in listOf(-27,0,40)) {
                    val crop=Effect(kind="Crop",values=mutableMapOf("zoom" to "2","rotation" to "$angle","x" to ".03","y" to "-.02"))
                    val mirror=Effect(kind="Mirror",values=mutableMapOf("horizontal" to if (h) "1" else "0","vertical" to if (v) "1" else "0"))
                    val p=Project(width=320,height=180,clips=mutableListOf(Clip(path=first,sourceOut=4.0,photo=true,audio=false,effects=mutableListOf(crop,mirror))))
                    Frames().use { frames -> same(frames.render(p,.4,320,180),frames.render(roundTrip(p),.4,320,180),.5) }
                }
            }
            test("image centers opacity and motion retain pixels in portrait and landscape") {
                for ((w,h) in listOf(320 to 180,180 to 320)) {
                    val fx=Effect(kind="Image",values=mutableMapOf("path" to second,"geometry" to "center","x" to ".4","y" to ".6","size" to ".25","opacity" to ".45"))
                    val a=mapOf("x" to .4,"y" to .6,"size" to .25,"opacity" to .45)
                    fx.motion=EffectMotion(a,a+mapOf("x" to .7,"y" to .3,"size" to .5,"opacity" to .9),duration=4.0)
                    val p=Project(width=w,height=h,clips=mutableListOf(Clip(path=first,sourceOut=4.0,photo=true,audio=false,effects=mutableListOf(fx))))
                    val loaded=roundTrip(p)
                    Frames().use { f -> for (at in listOf(.1,1.3,3.8)) same(f.render(p,at,w,h),f.render(loaded,at,w,h)) }
                }
            }
            test("source crop conversion composes stabilization before rotated mirrored framing") {
                val video=File(store.media,"stabilized.mp4");i.context.assets.open("fixture.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
                val crop=Effect(kind="Crop",values=mutableMapOf("zoom" to "1.8","rotation" to "23","x" to ".02","y" to "-.01"))
                val mirror=Effect(kind="Mirror",values=mutableMapOf("horizontal" to "1","vertical" to "0"))
                val stabilization=Effect.create("Stabilize").apply { values["level"]="Standard" }
                val p=Project(width=320,height=180,clips=mutableListOf(Clip(path=video.path,sourceOut=1.0,audio=false,effects=mutableListOf(crop,mirror,stabilization))))
                val loaded=roundTrip(p)
                Frames().use { f -> for (at in listOf(.2,.6)) same(f.render(p,at,320,180),f.render(loaded,at,320,180),.5) }
            }
            test("placement moves the composed picture and title with independent clipping and layer order") {
                val p=Project(width=320,height=180)
                for ((index,path) in listOf(first,second).withIndex()) {
                    val placement=Effect(kind="Placement",values=mutableMapOf("geometry" to "canvas","x" to if (index==0) ".05" else ".4","y" to ".15","w" to ".6","h" to ".65",
                        "reframe" to "1","contentZoom" to "1.4","frameX" to ".4","frameY" to ".6","left" to ".1","right" to ".9","order" to "${1-index}"))
                    val title=Effect(kind="Text",values=(TitleStyle.defaults+mapOf("text" to "ROME $index")).toMutableMap())
                    p.clips.add(Clip(path=path,sourceOut=4.0,photo=true,audio=false,track=index,effects=mutableListOf(placement,title)))
                }
                val loaded=roundTrip(p)
                Frames().use { f -> same(f.render(p,1.0,320,180),f.render(loaded,1.0,320,180)) }
                check(loaded.clips.flatMap { it.effects }.count { it.kind=="Placement" }==2)
            }
            test("held-frame source offset styling and sound retain independent clocks") {
                val video=File(store.media,"hold.mp4");i.context.assets.open("fixture.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
                val cache=AudioCache(File(root,"pcm"))
                for (mode in listOf("Cut","Continue","Fade out")) {
                    val fx=Effect(kind="Freeze Frame",to=.5,values=mutableMapOf("hold" to "1","pushIn" to ".1","style" to "Print","audio" to mode))
                    val c=Clip(path=video.path,sourceIn=.2,sourceOut=.3,audio=mode!="Cut",frozen=FrozenFrame(.2,.5,.1),effects=mutableListOf(fx))
                    if (mode=="Continue") {
                        val pose=mapOf("x" to .45,"y" to .5,"zoom" to 1.4,"rotation" to 0.0)
                        c.effects.add(Effect(kind="Crop",to=.5,values=pose.mapValues { it.value.toString() }.toMutableMap().apply { put("geometry","source") },
                            motion=EffectMotion(pose,pose+mapOf("x" to .55,"zoom" to 2.0,"rotation" to 15.0),duration=1.0,clock=listOf(EffectTimePoint(.2,0.0),EffectTimePoint(1.2,1.0)))))
                        c.effects.add(Effect(kind="Text",to=.5,values=(TitleStyle.defaults+mapOf("text" to "ROME")).toMutableMap()))
                    }
                    val p=Project(width=320,height=180,clips=mutableListOf(c));val loaded=roundTrip(p)
                    Frames().use { f -> for (at in listOf(.05,.4)) same(f.render(p,at,320,180),f.render(loaded,at,320,180),.2) }
                    fun audio(project: Project)=AudioMixer(project,cache).use { it.prepare();it.seek(.05);it.read(12000) }
                    val a=audio(p);val b=audio(loaded);check(a.indices.all { abs(a[it]-b[it])<.00001 }) { "Held sound changed for $mode" }
                    check(loaded.clips.all { it.frozen?.offset==.1 })
                }
            }
            test("native generated Still transitions retain their frame and sound clocks") {
                val a=Clip(path=first,sourceOut=2.0,photo=true,audio=false);val b=Clip(path=second,sourceOut=2.0,photo=true,audio=false,start=2.0)
                val editor=Editor(Project(width=320,height=180,clips=mutableListOf(a,b)))
                editor.putTransition(Transition(at=2.0,kind="Still",duration=.8,leftId=a.id,rightId=b.id,controls=TransitionControls(stillDissolve=.2)))
                val p=editor.project;val loaded=roundTrip(p)
                Frames().use { f -> for (at in listOf(1.8,2.1,2.5,3.1)) same(f.render(p,at,320,180),f.render(loaded,at,320,180),.2) }
            }
            test("native-only ZIP restores crop and image geometry when a photo is also an overlay asset") {
                val crop=Effect(kind="Crop",values=mutableMapOf("geometry" to "source","x" to ".4","y" to ".6","zoom" to "2","rotation" to "15"))
                val image=Effect(kind="Image",values=mutableMapOf("path" to first,"x" to ".15","y" to ".2","size" to ".3","opacity" to ".55"))
                val p=Project(width=320,height=180,clips=mutableListOf(Clip(path=first,sourceOut=3.0,photo=true,audio=false,effects=mutableListOf(crop,image))))
                store.save(Editor(p),false);val archive=File(root,"geometry.zip");YouRecArchive.export(store,p,archive,{ false },{})
                val entries=linkedMapOf<String,ByteArray>()
                ZipFile(archive).use { zip -> for (entry in zip.entries()) entries[entry.name]=zip.getInputStream(entry).readBytes() }
                val header=JSONObject(String(entries.getValue("project.json")));header.remove("myrecSession");check(header.getJSONArray("sources").length()==1)
                val names=header.getJSONArray("snapshotFiles")
                for (j in 0 until names.length()) {
                    val old="History/${names.getString(j)}";val snapshot=JSONObject(String(entries.remove(old)!!));snapshot.remove("myrec")
                    val bytes=snapshot.toString().toByteArray();val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    names.put(j,"$hash.json");entries["History/$hash.json"]=bytes
                }
                entries["project.json"]=header.toString().toByteArray();val native=File(root,"native-geometry.zip")
                ZipOutputStream(native.outputStream()).use { zip -> for ((name,bytes) in entries) { zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() } }
                val loaded=ProjectArchive.restore(store,native).project;val clip=loaded.clips.single()
                check(clip.path==clip.effects.single { it.kind=="Image" }.values["path"])
                Frames().use { f -> same(f.render(p,.4,320,180),f.render(loaded,.4,320,180)) }
                val document=store.encode(loaded);check(document.getInt("version")==14)
                check(runCatching { store.decode(JSONObject(document.toString()).put("version",17)) }.exceptionOrNull() is NewerProject)
            }
        } finally { root.deleteRecursively() }
    }
}
