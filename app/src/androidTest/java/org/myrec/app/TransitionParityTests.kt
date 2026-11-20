package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

object TransitionParityTests {
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    private fun label(a: MainActivity, name: String) = views(a.root).filterIsInstance<TextView>()
        .last { it.text.toString().trim() == name }

    fun run(i: Instrumentation, a: MainActivity, main: (() -> Unit) -> Unit,
            test: (String, () -> Unit) -> Unit) {
        val out = File(a.getExternalFilesDir(null), "transition-parity").apply { mkdirs() }
        val asset = File(a.cacheDir, "transition-parity.mp4")
        i.context.assets.open("fixture.mp4").use { source -> asset.outputStream().use { source.copyTo(it) } }
        val project = Project(name="Transition fixture",width=320,height=180,clips=mutableListOf(
            Clip(path=asset.path,sourceOut=3.0),Clip(path=asset.path,sourceIn=3.0,sourceOut=6.0,start=3.0)))
        fun turn(landscape: Boolean) {
            main { a.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            val until=SystemClock.uptimeMillis()+6000
            val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            while (SystemClock.uptimeMillis()<until) {
                var ready=false
                main { ready=a.resources.configuration.orientation==expected && (a.root.width>a.root.height)==landscape }
                if (ready) return
                Thread.sleep(70)
            }
            error("Transition workspace did not rotate")
        }
        test("transition style picker keeps its first row visible and all styles reachable") {
            turn(false)
            main {
                a.editor.load(project.duplicate()); a.editor.cursor=3.0; a.panels.transition()
                check((label(a,"Dissolve").parent as View).performClick())
            }
            val violations=mutableListOf<String>()
            for (landscape in listOf(false,true,false)) {
                turn(landscape)
                Thread.sleep(350)
                val name=if (landscape) "landscape" else "portrait"
                main {
                    check(label(a,"Transition style").isShown)
                    val buttons=Effects.transitions.map { label(a,it).parent as View }
                    val scroll=views(a.root).filterIsInstance<ScrollView>().last()
                    val area=Rect(); check(scroll.getGlobalVisibleRect(area))
                    val first=Rect(); val leading=buttons.first()
                    if (!leading.getGlobalVisibleRect(first) || first.height()!=leading.height || first.top<=area.top)
                        violations.add("$name first row $first, viewport $area, height ${leading.height}, scroll ${scroll.scrollY}")
                    for ((index,button) in buttons.withIndex()) {
                        val text=label(a,Effects.transitions[index]); val icon=(button as ViewGroup).getChildAt(0)
                        if (text.layout.lineCount>0 && text.layout.getLineBottom(text.layout.lineCount-1)>text.height || icon.top<0)
                            violations.add("$name ${Effects.transitions[index]} label or icon clips")
                        val tile=Rect()
                        if (landscape && (!button.getGlobalVisibleRect(tile) || tile.height()!=button.height))
                            violations.add("$name ${Effects.transitions[index]} requires scrolling")
                    }
                    scroll.scrollTo(0, scroll.getChildAt(0).height)
                }
                Thread.sleep(220)
                main {
                    val last=label(a,Effects.transitions.last()).parent as View
                    val visible=Rect()
                    if (!last.getGlobalVisibleRect(visible) || visible.height()!=last.height) violations.add("$name last row is clipped")
                    views(a.root).filterIsInstance<ScrollView>().last().scrollTo(0,0)
                }
                Thread.sleep(180)
                i.uiAutomation.takeScreenshot()?.let { image ->
                    try { File(out,"style-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }
                    finally { image.recycle() }
                }
            }
            File(out,"style-measurements.txt").writeText(violations.joinToString("\n",postfix="\n"))
            check(violations.isEmpty()) { violations.joinToString("; ") }
            main { a.panels.close() }
        }
        test("transition style picker supports large text and preserves the draft on back") {
            main {
                val resources = a.resources
                val original = Configuration(resources.configuration)
                try {
                    resources.updateConfiguration(Configuration(original).apply { fontScale = 2f }, resources.displayMetrics)
                    a.editor.load(project.duplicate()); a.editor.cursor=3.0; a.panels.transition()
                    check((label(a,"Dissolve").parent as View).performClick())
                    val buttons = Effects.transitions.map { label(a,it).parent as View }
                    check(buttons.map { it.parent }.distinct().size == buttons.size)
                    check(buttons.all { it.isFocusable && it.contentDescription != null })
                    check(buttons[Effects.transitions.indexOf("Still")].performClick())
                    check(label(a,"Freeze duration").isShown)
                    check((label(a,"Still").parent as View).performClick())
                    a.panels.cancel()
                    check(label(a,"Freeze duration").isShown)
                } finally {
                    resources.updateConfiguration(original, resources.displayMetrics)
                    a.panels.close()
                }
            }
        }
        test("split-screen divider can be dragged in the preview") {
            main {
                a.editor.load(project.duplicate())
                a.panels.transition(Transition(at=3.0,kind="Split screen",controls=TransitionControls()))
            }
            Thread.sleep(450)
            main {
                val view = views(a.root).first { it.contentDescription == "Tool preview" }
                val now = SystemClock.uptimeMillis()
                val x = view.width / 2f
                val y = view.height / 2f
                for ((action, pos) in listOf(MotionEvent.ACTION_DOWN to x, MotionEvent.ACTION_MOVE to x*1.25f, MotionEvent.ACTION_UP to x*1.25f)) {
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, pos, y, 0)
                    try { check(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                check(label(a,"Apply").performClick())
                check(a.editor.project.transitions.single().controls!!.divider > .55)
            }
        }
        test("all nine modern transition styles render their picture endpoints") {
            render(a)
        }
        test("transition fitting retains requested timing and extends closing Still holds") {
            val editor = Editor(project.duplicate())
            editor.putTransition(Transition(at=3.0, duration=2.0,
                controls=TransitionControls(audioOffset=-2.0,audioBlend=5.0)))
            val placed=editor.project.transitions.single()
            check(placed.duration==2.0 && placed.controls?.audioOffset == -2.0 && placed.controls?.audioBlend == 5.0)
            check(abs(placed.timingScale-.6)<.000001) { "${placed.timingScale}" }
            val store = ProjectStore(File(a.cacheDir,"transition-parity-store"))
            val revision = store.decode(store.encode(editor.project))
            val reopened=revision.transitions.single()
            check(reopened == placed && abs(reopened.begin-placed.begin)<.000001)
            editor.removeTransition(placed.id)
            check(abs(editor.project.duration-6.0)<.000001)
            editor.putTransition(Transition(at=6.0,kind="Still",duration=5.0,controls=TransitionControls()))
            check(abs(editor.project.duration-11.0)<.000001)
            editor.undo()
            check(abs(editor.project.duration-6.0)<.000001)
        }
    }

    private fun render(a: MainActivity) {
        fun photo(name: String, width: Int, height: Int, color: Int): String {
            val file = File(a.cacheDir, "transition-$name.png")
            val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            } finally { bitmap.recycle() }
            return file.path
        }
        val red = photo("red",60,120,Color.RED)
        val blue = photo("blue",120,60,Color.BLUE)
        fun edit(kind: String, edge: Int = 0, settings: TransitionControls = TransitionControls()) =
            Editor(Project(width=160,height=90,fps=30,clips=mutableListOf<Clip>().apply {
                if (edge != -1) add(Clip(path=red,sourceOut=3.0,photo=true,audio=false))
                if (edge != 1) add(Clip(path=blue,sourceOut=3.0,start=if (edge == -1) 0.0 else 3.0,photo=true,audio=false))
            })).apply { putTransition(Transition(at=if (edge == -1) 0.0 else 3.0,kind=kind,duration=1.0,controls=settings)) }
        Frames().use { frames ->
            fun pixel(editor: Editor, time: Double, x: Int = 80, y: Int = 45, fps: Int = 30): Int {
                val bitmap = frames.render(editor.project,time,160,90,frameRate=fps)
                return try { bitmap.getPixel(x,y) } finally { bitmap.recycle() }
            }
            for (style in Effects.transitions) {
                val editor = edit(style)
                val t = editor.project.transitions.single()
                check(pixel(editor,t.pictureOrigin) == Color.RED) { "$style opening picture" }
                check(pixel(editor,t.end + .01) == Color.BLUE) { "$style closing picture" }
                pixel(editor,(t.pictureOrigin+t.end)/2)
            }
            val split = edit("Split screen")
            val middle = split.project.transitions.single().cut
            check(pixel(split,middle,20,9)==Color.RED)
            check(pixel(split,middle,138,9)==Color.BLUE)
            val divider=pixel(split,middle,79,45)
            check(Color.green(divider)>90) { "Split divider: ${Integer.toHexString(divider)}" }
            val hitchcock = edit("Hitchcock")
            val reveal = hitchcock.project.transitions.single()
            check(pixel(hitchcock,reveal.cut)==Color.BLUE)
            reveal.amount=0f
            check(pixel(hitchcock,reveal.cut)==Color.RED)
            val settings=TransitionControls(color=0xff00ff00)
            val opening=edit("Fade through color",-1,settings)
            val closing=edit("Fade through color",1,settings)
            check(pixel(opening,0.0)==Color.GREEN)
            val halfway = pixel(opening,.5)
            check(abs(Color.blue(halfway)-Color.green(halfway))<10 && Color.blue(halfway)>130)
            check(pixel(closing,2.0)==Color.RED)
            check(Color.green(pixel(closing,2.99))>245)
            val flash=edit("Flash Cut",settings=settings)
            val active=flash.project.transitions.single()
            for (fps in listOf(24,60)) {
                val flashes=active.controls!!.flashes(TransitionClock(active).videoDuration,fps)
                for (frame in 0 until fps) {
                    val expected=if (frame in flashes) Color.BLUE else Color.RED
                    check(pixel(flash,active.pictureOrigin+frame.toDouble()/fps,fps=fps)==expected) { "$fps fps, flash frame $frame" }
                }
            }
            val fps = 24
            val first = (active.pictureOrigin * fps).roundToInt()
            val flashFrame = first + active.controls!!.flashes(TransitionClock(active).videoDuration,fps).first()
            val movie = File(a.cacheDir,"transition-24fps.mp4")
            Export(a.audioCache).render(flash.project,movie,160,90,ExportColor.SDR,fps,null) {}
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(movie.path)
                for (index in listOf(flashFrame,flashFrame+1)) {
                    val image=checkNotNull(reader.getFrameAtIndex(index))
                    val color=try { image.getPixel(80,45) } finally { image.recycle() }
                    if (index==flashFrame) check(Color.blue(color)>200 && Color.red(color)<40)
                    else check(Color.red(color)>200 && Color.blue(color)<40)
                }
            } finally { reader.release() }
        }
    }
}
