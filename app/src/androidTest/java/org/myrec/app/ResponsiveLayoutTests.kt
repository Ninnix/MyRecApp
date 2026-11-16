package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import java.io.File

object ResponsiveLayoutTests {
    fun run(i: Instrumentation, a: MainActivity, main: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit) {
        val directory = File(a.getExternalFilesDir(null), "layout-checks").apply { mkdirs() }
        val source = File(a.store.media,"layout.mp4")
        i.context.assets.open("fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val project = Project(name="Layout project",clips=mutableListOf(ProjectStore.inspect(source)))
        main {
            a.editor.load(project)
            a.store.save(a.editor)
            a.store.save(project.duplicate().copy(id=uid(),name="Second project"),false)
            a.playback.refresh()
        }
        Thread.sleep(800)
        val original = a.store.encode(a.editor.project).toString()
        fun find(view: View, text: String): View? {
            if (view.contentDescription?.toString() == text || view is TextView && view.text.toString() == text) return view
            if (view is ViewGroup) repeat(view.childCount) { find(view.getChildAt(it),text)?.let { return it } }
            return null
        }
        fun screenshot(name: String): Bitmap = i.uiAutomation.takeScreenshot().also { image ->
            File(directory,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        fun heading(name: String) {
            val boxes = mutableListOf<RectF>()
            main {
                check(a.hasWindowFocus()) { "Layout activity is not in the foreground" }
                check(a.editorView.visibility == View.INVISIBLE)
                for (label in listOf("myrec","PROJECTS","Pick up where you left off.")) {
                    val v = find(a.root,label) ?: error("Missing $label")
                    val at = IntArray(2); v.getLocationOnScreen(at)
                    boxes.add(RectF(at[0].toFloat(),at[1].toFloat(),(at[0]+v.width).toFloat(),(at[1]+v.height).toFloat()))
                }
            }
            val image = screenshot(name)
            try {
                for ((index,r) in boxes.withIndex()) {
                    check(r.top >= 0 && r.bottom <= image.height && r.left >= 0 && r.right <= image.width)
                    var ink = 0
                    for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt()) {
                        val c = image.getPixel(x,y)
                        if (minOf(Color.red(c),Color.green(c),Color.blue(c)) > 70) ink++
                    }
                    check(ink > 20) { "$name: heading $index has no visible glyphs" }
                }
            } finally { image.recycle() }
        }
        for ((name,orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            "portrait-return" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
            main { a.panels.close(); a.requestedOrientation=orientation }
            Thread.sleep(800)
            test("$name editor keeps its header and controls inside the usable screen") {
                main {
                    check(a.editorView.visibility == View.VISIBLE)
                    for (id in listOf("projects","preview","mark","split","tools","export","snap")) {
                        val r = a.editorView.controlBounds(id) ?: error("Missing $id")
                        check(r.left >= -1 && r.top >= -1 && r.right <= a.root.width+1 && r.bottom <= a.root.height+1) {
                            "$name $id outside ${a.root.width} x ${a.root.height}: $r"
                        }
                    }
                    if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                        val safe = SafeArea.insets(a.editorView)
                        val header = a.editorView.controlBounds("projects")!!
                        check(kotlin.math.abs(header.top-safe.top) <= 2) { "Phantom top gap: $header; safe=$safe" }
                    }
                }
                screenshot("editor-$name").recycle()
            }
            main { a.panels.projects() }
            Thread.sleep(400)
            test("$name Projects draws both headings above the project cards") { heading("projects-$name") }
            if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                test("$name Projects headings survive scrolling back from the project list") {
                    var scroll: ListView? = null
                    main {
                        fun visit(v: View) {
                            if (v is ListView) scroll=v
                            if (v is ViewGroup) repeat(v.childCount) { visit(v.getChildAt(it)) }
                        }
                        visit(a.root);scroll!!.setSelection(scroll!!.adapter.count-1)
                    }
                    Thread.sleep(200)
                    main { scroll!!.setSelection(0) }
                    Thread.sleep(200)
                    heading("projects-$name-scrolled")
                }
            }
            main { a.panels.close() }
        }
        test("compact and tall portrait layouts retain every main control without a fixed top margin") {
            main {
                val index = MediaIndex(a.audioCache) {}
                try {
                    val view = EditorView(a,a.editor,index,{}, {})
                    for ((w,h) in listOf(720 to 1280,1080 to 1920,1080 to 2248,1272 to 2772,1440 to 3200)) {
                        view.layout(0,0,w,h)
                        val image = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                        try { view.draw(Canvas(image)) } finally { image.recycle() }
                        for (id in listOf("projects","preview","mark","split","tools","export","snap")) {
                            val r=view.controlBounds(id) ?: error("Missing $id")
                            check(r.left >= -1 && r.top >= -1 && r.right <= w+1 && r.bottom <= h+1) { "$w x $h: $id=$r" }
                        }
                    }
                } finally { index.close() }
            }
        }
        test("layout changes preserve the current project") {
            check(a.store.encode(a.editor.project).toString() == original)
        }
        test("closing Projects preserves an editor already hidden by recording mode") {
            main {
                a.editorView.visibility=View.GONE
                a.panels.projects()
                a.panels.close()
                check(a.editorView.visibility == View.GONE)
                a.editorView.visibility=View.VISIBLE
            }
        }
    }
}
