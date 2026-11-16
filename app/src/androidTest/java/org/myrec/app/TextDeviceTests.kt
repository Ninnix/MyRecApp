package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TextDeviceTests {
    private fun collection(fonts: List<ByteArray>): ByteArray {
        val start=12+fonts.size*4
        val output=ByteBuffer.allocate(start+fonts.sumOf { (it.size+3)/4*4 }).order(ByteOrder.BIG_ENDIAN)
        output.putInt(0x74746366).putInt(0x10000).putInt(fonts.size)
        var position=start
        fonts.forEachIndexed { index,bytes ->
            val font=ByteBuffer.wrap(bytes.copyOf()).order(ByteOrder.BIG_ENDIAN)
            val count=font.getShort(4).toInt() and 65535
            repeat(count) {
                val entry=12+it*16;val offset=font.getInt(entry+8)
                if (font.getInt(entry)==0x68656164) font.putInt(offset+8,0)
                font.putInt(entry+8,offset+position)
            }
            output.putInt(12+index*4,position);output.position(position);output.put(font.array())
            position+=(bytes.size+3)/4*4
        }
        return output.array()
    }
    private fun find(view: View,name: String): View? {
        if (view is TextView && view.text.toString()==name || view.contentDescription?.toString()==name) return view
        if (view is ViewGroup) for (j in 0 until view.childCount) find(view.getChildAt(j),name)?.let { return it }
        return null
    }
    fun run(i: Instrumentation,a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        val root=File(a.cacheDir,"text-${uid()}").apply { mkdirs() }
        fun asset(name: String)=a.assets.open("title-fonts/$name").use { it.readBytes() }
        val ttc=File(root,"Rome.ttc").apply { writeBytes(collection(listOf(asset("barlowcondensed/BarlowCondensed-Regular.ttf"),asset("barlowcondensed/BarlowCondensed-Bold.ttf")))) }
        val library=FontLibrary(File(root,"fonts"));val store=ProjectStore(File(root,"projects"))
        val source=File(store.media,"fixture.mp4").apply { i.context.assets.open("fixture.mp4").use { input -> outputStream().use { input.copyTo(it) } } }
        fun style(face: FontLibrary.Face,path: String=face.file.path)=Effect(kind="Text",to=.7).apply {
            TitleStyle.initialize(this,true);values.putAll(mapOf("face" to face.id,"fontIndex" to face.info.index.toString(),"path" to path,"text" to "The Colosseum","size" to ".12"))
        }
        fun visible(bitmap: Bitmap): Int {
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            return pixels.count { Color.alpha(it)>0 }
        }
        try {
            test("TTC and OTC collections enumerate and render the actual regular and bold faces") {
                val entry=library.add(ttc).first;check(entry.faces.size==2)
                check(entry.faces.map { it.info.weight }==listOf(400,700))
                val normal=TitleStyle.raster(a,style(entry.faces[0]),40f,600)
                val bold=TitleStyle.raster(a,style(entry.faces[1]),40f,600)
                check(!normal.sameAs(bold) && visible(normal)>100 && visible(bold)>100)
                normal.recycle();bold.recycle()
                val otc=File(root,"Rome.otc").apply { writeBytes(ttc.readBytes()) }
                check(FontLibrary(File(root,"otc")).add(otc).first.faces.size==2)
                val duplicate=File(root,"Other name.ttc").apply { writeBytes(ttc.readBytes()) }
                check(!library.add(duplicate).second && library.entries().size==1)
                val variable=File(root,"Variable.ttf").apply { writeBytes(asset("caveat/Caveat[wght].ttf")) }
                check(library.add(variable).first.faces.single().info.weights.containsAll(listOf(400,500,600,700)))
                val broken=File(root,"Broken.ttf").apply { writeBytes(ttc.readBytes().copyOf(20)) }
                check(runCatching { library.add(broken) }.isFailure && library.entries().size==2)
            }
            test("project fonts survive library removal undo redo duplication and archive with collection identity") {
                val entry=library.entries().first { it.filename==ttc.name };val face=entry.faces[1]
                val owned=library.preserve(face,store.media);val editor=Editor(Project(clips=mutableListOf(ProjectStore.inspect(source))))
                editor.cursor=.2;val mark=editor.addBookmark("Colosseum")!!
                editor.applyEffect(style(face,owned.path));editor.undo();store.save(editor)
                library.remove(entry);check(library.entries().none { it.digest==entry.digest })
                check(owned.isFile && TitleStyle.font(a,style(face,owned.path)).weight==700)
                val archive=File(root,"fonts.myrec");ProjectArchive.export(store,editor.project,archive)
                val restored=ProjectArchive.restore(store,archive);check(restored.missing.isEmpty())
                val reopened=store.editor(restored.project);check(reopened.project.resolvedBookmarks().single().id==mark)
                reopened.redo();val effect=reopened.project.clips.single().effects.single()
                check(effect.values["face"]==face.id && effect.values["fontIndex"]=="1")
                check(File(effect.values.getValue("path")).isFile && effect.values["path"]!=owned.path)
                val raster=TitleStyle.raster(a,effect,40f,600);check(visible(raster)>100);raster.recycle()
                store.save(reopened);val duplicate=store.duplicate(reopened.project)
                check(duplicate.resolvedBookmarks().single().mark.label=="Colosseum")
                check(duplicate.clips.single().effects.single().values["face"]==face.id)
            }
            test("all eight title looks shape multilingual outlines backgrounds and all twelve gradients") {
                for (look in TitleStyle.looks.values) for (alignment in listOf("Left","Center","Right")) {
                    val effect=Effect(kind="Text");TitleStyle.initialize(effect,true);TitleStyle.applyLook(effect,look)
                    effect.values.putAll(mapOf("text" to "Roma • روما\nColosseo 🌍","align" to alignment,"outlined" to "true","plate" to "Each line"))
                    val raster=TitleStyle.raster(a,effect,36f,500);check(visible(raster)>100 && raster.width<=500);raster.recycle()
                }
                for (gradient in TitleStyle.gradients) {
                    val effect=Effect(kind="Text");TitleStyle.initialize(effect,true)
                    effect.values.putAll(mapOf("text" to "Rome","gradientFirst" to gradient.first,"gradientLast" to gradient.last,"gradientAngle" to gradient.angle.toString()))
                    TitleStyle.raster(a,effect,40f,500).recycle()
                }
            }
            test("audio-owned subtitles render above independently placed video and disappear when muted or held") {
                val video=ProjectStore.inspect(source).apply { audio=false }
                val audio=video.duplicate(true).apply { this.video=false;this.audio=true }
                val project=Project(width=320,height=180,clips=mutableListOf(video,audio))
                SubtitlesDraft.attach(project,"render",listOf(SubtitleWord(text="Rome",start=.1,end=.6)),setOf(audio.id))
                Frames().use { frames ->
                    val shown=frames.render(project,.3,320,180);audio.gainDb=-60f
                    val hidden=frames.render(project,.3,320,180);check(!shown.sameAs(hidden));audio.gainDb=0f
                    audio.frozen=FrozenFrame(.3,.7);val held=frames.render(project,.3,320,180);check(hidden.sameAs(held))
                    shown.recycle();hidden.recycle();held.recycle()
                }
            }
            test("old video captions migrate onto matching sound and new caption styling reaches encoded movies") {
                val video=ProjectStore.inspect(source).apply { audio=false }
                val audio=video.duplicate(true,keepOccurrence=true).apply { this.video=false;this.audio=true }
                val old=Effect(kind="Text",to=.6,values=mutableMapOf("text" to "Rome","style" to "Bold"),subtitle=SubtitlePhrase("old",words=listOf(SubtitleWord(text="Rome",start=.1,end=.6))))
                video.effects.add(old)
                val legacy=Project(width=320,height=180,clips=mutableListOf(video,audio))
                val decoded=store.decode(store.encode(legacy).put("version",10))
                check(decoded.clips.first().effects.isEmpty() && decoded.clips.last().effects.single().values["subtitleOwner"]=="audio")
                val effect=decoded.clips.last().effects.single();effect.values.putAll(mapOf("plate" to "One box","backing" to "#ff00ff00","x" to ".5","y" to ".7","size" to ".15"))
                val movie=File(root,"captions.mp4");Export(a.audioCache).run(decoded,movie,320,180) {}
                VideoDecoder(movie.path).use { decoder ->
                    val bitmap=decoder.frame(.3,320,180) ?: error("No encoded subtitle")
                    val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    check(pixels.count { Color.green(it)>150 && Color.red(it)<80 && Color.blue(it)<80 }>100)
                    bitmap.recycle()
                }
            }
            test("title motion clips each wipe direction and retains a visible middle in preview rendering") {
                for (mode in listOf("Fade","Drift","Scale","Wipe")) for (direction in listOf("Left","Right","Up","Down")) {
                    val effect=Effect(kind="Text");TitleStyle.initialize(effect,true)
                    effect.values.putAll(mapOf("text" to "The Colosseum","entrance" to mode,"exit" to mode,"inDirection" to direction,"outDirection" to direction))
                    val start=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);val middle=start.copy(Bitmap.Config.ARGB_8888,true);val end=start.copy(Bitmap.Config.ARGB_8888,true)
                    TitleStyle.draw(Canvas(start),effect,320,180,0.0,1.0);TitleStyle.draw(Canvas(middle),effect,320,180,.5,1.0);TitleStyle.draw(Canvas(end),effect,320,180,1.0,1.0)
                    check(visible(start)==0 && visible(middle)>0 && visible(end)==0) { "$mode $direction did not hide both ends" }
                    start.recycle();middle.recycle();end.recycle()
                }
            }
            test("subtitle appearance and bookmark workspaces remain cancellable in portrait and landscape") {
                for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    main { a.panels.close();a.requestedOrientation=orientation }
                    i.waitForIdleSync();Thread.sleep(350)
                    val clip=ProjectStore.inspect(source)
                    val project=Project(clips=mutableListOf(clip))
                    SubtitlesDraft.attach(project,"ui",listOf(SubtitleWord(text="Rome",start=.1,end=.6)),setOf(clip.id))
                    main {
                        a.editor.load(project);a.editor.cursor=.3;a.panels.editExisting(clip.effects.single().id)
                        check(find(a.root,"Appearance")!!.performClick())
                    }
                    Thread.sleep(200)
                    main {
                        check(a.playback.previewProject!!.clips.single().effects.single().subtitle!=null)
                        for (name in listOf("Looks","Font","Fill","Outline","Shadow","Background","Motion")) check(find(a.root,name)!=null)
                        find(a.root,"Motion")!!.performClick();find(a.root,"Fade")!!.performClick();check(find(a.root,"Preview entrance")!=null)
                        a.panels.close();check(!a.editor.canUndo)
                        a.editor.cursor=.3;a.panels.bookmarks();check(find(a.root,"Add at 0:00.30")!!.performClick())
                        check(a.editor.project.resolvedBookmarks().size==1 && find(a.root,"Rename")!=null)
                        a.panels.close();a.editor.undo();check(a.editor.project.resolvedBookmarks().isEmpty())
                    }
                }
            }
            test("long transcripts recycle visible rows and search without changing caption ownership") {
                val clip=ProjectStore.inspect(source);val project=Project(clips=mutableListOf(clip))
                val step=clip.duration/501
                repeat(500) { index -> SubtitlesDraft.attach(project,"long",listOf(SubtitleWord(text="Caption $index",start=index*step,end=(index+.8)*step)),setOf(clip.id)) }
                main { a.editor.load(project);a.editor.cursor=0.0;a.panels.editExisting(clip.effects.first().id) }
                Thread.sleep(200)
                main {
                    fun list(view: View): android.widget.ListView? {
                        if (view is android.widget.ListView) return view
                        if (view is ViewGroup) for (j in 0 until view.childCount) list(view.getChildAt(j))?.let { return it }
                        return null
                    }
                    val rows=list(a.root) ?: error("No virtual transcript")
                    check(rows.adapter.count==500 && rows.childCount<20)
                    fun search(view: View): android.widget.EditText? {
                        if (view is android.widget.EditText && view.hint=="Search subtitles") return view
                        if (view is ViewGroup) for (j in 0 until view.childCount) search(view.getChildAt(j))?.let { return it }
                        return null
                    }
                    search(a.root)!!.setText("Caption 49");check(rows.adapter.count==11)
                    a.panels.close();check(!a.editor.canUndo && a.editor.project.clips.single().effects.size==500)
                }
            }
            test("font library collection rows expand and select their real collection index") {
                a.fontLibrary.add(ttc)
                var dialog: FontLibraryDialog?=null;var selected: FontLibrary.Face?=null
                main { dialog=FontLibraryDialog(a,InspectorUi(a,1f)) { selected=it }.also { it.show() } }
                try {
                    Thread.sleep(400)
                    main { check(find(dialog!!.window!!.decorView,"Rome.ttc · 2 faces")!!.performClick()) }
                    Thread.sleep(400)
                    main { check(find(dialog!!.window!!.decorView,"Use Barlow Condensed Bold")!!.performClick()) }
                    val deadline=android.os.SystemClock.uptimeMillis()+5000
                    while (selected==null && android.os.SystemClock.uptimeMillis()<deadline) { i.waitForIdleSync();Thread.sleep(40) }
                    check(selected?.info?.index==1 && selected?.file?.parentFile==a.store.media)
                } finally { main { dialog?.dismiss() } }
            }
        } finally {
            main { a.panels.close();a.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_FULL_USER }
            root.deleteRecursively()
        }
    }
}
