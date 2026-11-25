package org.myrec.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

object EditorPerformanceTests {
    fun run(i: Instrumentation, a: MainActivity, main: (() -> Unit) -> Unit, test: (String, () -> Unit) -> Unit) {
        val source = File(a.store.media, "performance.mp4")
        i.context.assets.open("fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        fun until(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8000
            while (SystemClock.uptimeMillis() < deadline) {
                var done = false
                main { done = predicate() }
                if (done) return
                Thread.sleep(20)
            }
            error(message)
        }
        test("overlays reuse full export pixels, downsample previews and reread a replaced image") {
            val file = File(a.cacheDir, "performance-overlay.png")
            fun image(color: Int) {
                val bitmap = Bitmap.createBitmap(2048, 1152, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(color)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
            image(Color.RED)
            val effect = Effect(kind="Image")
            val saved: Bitmap
            OverlayImages().use { cache ->
                val full = cache.get(file.path,effect,768,432,false) ?: error("Overlay did not decode")
                check(full === cache.get(file.path,effect,768,432,false))
                check(full.bitmap.width==2048 && full.bitmap.getPixel(0,0)==Color.RED)
                val preview = cache.get(file.path,effect,768,432,true)!!
                check(preview.width==full.width && preview.height==full.height)
                check(preview.bitmap.allocationByteCount < full.bitmap.allocationByteCount / 4)
                check(preview === cache.get(file.path,effect,768,432,true))
                saved=full.bitmap
                val old=file.lastModified()
                image(Color.BLUE)
                check(file.setLastModified(old+1000))
                check(cache.get(file.path,effect,768,432,false)!!.bitmap.getPixel(0,0)==Color.BLUE)
            }
            check(saved.isRecycled)
        }
        test("source-time filmstrip tiles survive a move and split away from a transition") {
            val clip=ProjectStore.inspect(source).apply { effects.add(Effect(kind="Mirror")) }
            val project=Project(name="Filmstrip",clips=mutableListOf(clip))
            val index=MediaIndex(a.audioCache) {}
            try {
                main { index.editChanged(project) }
                val at=1.5
                var frame: Bitmap?=null
                until("Filmstrip tile did not render") {
                    frame=index.editedFrame(project,clip,at)
                    frame!=null
                }
                main {
                    val before=TimelineLayout.filmstripGrid(clip,.32,30).slot(at)
                    clip.start=2.0
                    project.transitions.add(Transition(at=5.0,duration=.3))
                    index.editChanged(project)
                    check(index.editedFrame(project,clip,at+2)===frame)
                    val right=clip.duplicate(newId=true).apply { sourceIn=1.0;start=3.0 }
                    clip.sourceOut=1.0
                    project.clips.add(right)
                    index.editChanged(project)
                    check(index.editedFrame(project,right,at+2)===frame)
                    check(abs(TimelineLayout.filmstripGrid(right,.32,30).slot(at+2)-2-before)<1e-9)
                }
            } finally { main { index.close() } }
        }
        test("direct SDR draws live frames and hands off to bitmap on pause and edited footage") {
            val clip=ProjectStore.inspect(source)
            main {
                a.editor.load(Project(name="Playback",clips=mutableListOf(clip)))
                a.playback.play()
            }
            until("Plain SDR did not reach the preview surface") { a.editorView.directPreview?.showing==true }
            fun screen(): Set<Int> {
                val image=Bitmap.createBitmap(80,45,Bitmap.Config.ARGB_8888)
                try {
                    val done=CountDownLatch(1)
                    var status=PixelCopy.ERROR_UNKNOWN
                    main {
                        val view=a.editorView
                        val (x,y)=IntArray(2).apply { view.getLocationInWindow(this) }
                        val r=view.controlBounds("preview")!!
                        val box=Rect((x+r.centerX()-r.width()*.25f).roundToInt(),(y+r.centerY()-r.height()*.18f).roundToInt(),
                            (x+r.centerX()+r.width()*.25f).roundToInt(),(y+r.centerY()+r.height()*.18f).roundToInt())
                        PixelCopy.request(a.window,box,image,{ status=it;done.countDown() },Handler(Looper.getMainLooper()))
                    }
                    check(done.await(3,TimeUnit.SECONDS) && status==PixelCopy.SUCCESS)
                    return (4 until image.height step 4).flatMap { y -> (4 until image.width step 4).map { x -> image.getPixel(x,y) } }.toSet()
                } finally { image.recycle() }
            }
            val first=screen()
            check(first.any { it and 0xffffff != 0 }) { "The surface picture is black" }
            Thread.sleep(360)
            check(screen()!=first) { "The surface picture did not advance" }
            main { a.playback.pause() }
            until("Paused frame did not replace the Surface picture") {
                a.editorView.directPreview?.showing==false && a.editorView.bitmap!=null
            }
            main {
                val edited=clip.duplicate().apply { effects.add(Effect(kind="Mirror")) }
                a.editor.load(Project(name="Edited playback",clips=mutableListOf(edited)))
                a.playback.play()
            }
            until("Edited playback did not advance") { a.editor.cursor>.25 }
            main {
                check(a.editorView.directPreview?.showing==false)
                a.playback.pause()
            }
        }
        test("a scrub burst keeps the newest cursor and renders the release position") {
            main {
                a.editor.load(Project(name="Scrubbing",clips=mutableListOf(ProjectStore.inspect(source))))
                a.playback.play(4.5)
                repeat(120) { a.playback.scrub(.25+it*.025) }
                check(!a.playback.playing && abs(a.editor.cursor-3.225)<1e-9)
                a.playback.seek(1.25)
                check(a.playback.playing && a.editor.cursor==1.25)
                a.playback.pause()
            }
            until("The release frame did not render") { a.editorView.bitmap!=null && a.editor.cursor==1.25 }
        }
    }
}
