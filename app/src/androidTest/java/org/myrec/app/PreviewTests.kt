package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.TextView
import java.io.File
import kotlin.math.abs

object PreviewTests {
    fun run(instrumentation: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit) {
        fun find(view: View, name: String): View? {
            if (view.contentDescription?.toString() == name || view is TextView && view.text.toString() == name) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), name)?.let { return it }
            return null
        }
        val source = File(a.store.media, "preview.mp4")
        instrumentation.context.assets.open("fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        onMain {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            a.editor.load(Project(name = "Preview checks", clips = mutableListOf(ProjectStore.inspect(source))))
        }
        Thread.sleep(800)
        val original = a.store.encode(a.editor.project).toString()
        fun snapshot(name: String) {
            val directory = File(a.getExternalFilesDir(null), "preview-checks").apply { mkdirs() }
            val image = Bitmap.createBitmap(a.root.width, a.root.height, Bitmap.Config.ARGB_8888)
            a.root.draw(Canvas(image))
            a.editorView.controlBounds("projects")?.let { r ->
                var white = 0
                for (y in r.top.toInt().coerceAtLeast(0) until r.bottom.toInt().coerceAtMost(image.height))
                    for (x in r.left.toInt().coerceAtLeast(0) until r.right.toInt().coerceAtMost(image.width)) {
                        val color = image.getPixel(x, y)
                        if (Color.red(color) > 220 && Color.green(color) > 220 && Color.blue(color) > 220) white++
                    }
                check(white > 10) { "$name hid preview header labels" }
            }
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        fun unchanged() = check(a.store.encode(a.editor.project).toString() == original)
        test("editor exposes timeline items and inspection requests a sharper frame") {
            onMain {
                val view = a.editorView
                val canvas = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(canvas)); canvas.recycle()
                val provider = view.accessibilityNodeProvider
                val items = provider.findAccessibilityNodeInfosByText("Video 1", -1).orEmpty()
                check(items.single().actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK })
                check(provider.findAccessibilityNodeInfosByText("Insert video", -1).orEmpty().isNotEmpty())
                check(provider.findAccessibilityNodeInfosByText("Video and audio timeline", -1).orEmpty().single().rangeInfo != null)
                repeat(3) { check(view.performAccessibilityAction(0x02000101, null)) }
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            var edge = 0
            while (edge <= 768 && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(40)
                onMain { a.editorView.bitmap?.let { edge = maxOf(it.width, it.height) } }
            }
            check(edge > 768) { "Inspection still uses a $edge pixel preview" }
            onMain { a.editorView.performAccessibilityAction(0x02000103, null) }
            unchanged()
        }
        fun touch(view: View, action: Int, x: Float, y: Float) {
            val t = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(t, t, action, x, y, 0)
            try { check(view.dispatchTouchEvent(e)) } finally { e.recycle() }
        }
        test("editor preview accepts continuous pinch without changing the edit or toggling playback") {
            onMain {
                val view = a.editorView
                val bounds = view.controlBounds("preview") ?: error("Preview has no bounds")
                val t = SystemClock.uptimeMillis()
                val props = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                fun send(action: Int, count: Int, span: Float, delay: Long) {
                    val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                        x = bounds.centerX() + if (i == 0) -span / 2 else span / 2
                        y = bounds.centerY(); pressure = 1f; size = 1f
                    } }
                    val e = MotionEvent.obtain(t, t + delay, action, count, props, coords, 0, 0, 1f, 1f, 0, 0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { check(view.dispatchTouchEvent(e)) } finally { e.recycle() }
                }
                val start = bounds.width() * .2f
                send(MotionEvent.ACTION_DOWN, 1, start, 0)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, start, 10)
                repeat(12) { i -> send(MotionEvent.ACTION_MOVE, 2, start * (1 + (i + 1) * .07f), 30L + i * 20) }
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, start * 1.84f, 300)
                send(MotionEvent.ACTION_UP, 1, start * 1.84f, 320)
                check(view.inspection.zoom > 1.05f && view.inspection.zoom < 3f) { "Pinch zoom is ${view.inspection.zoom}; bounds=$bounds" }
                val info = view.createAccessibilityNodeInfo()
                check((if (android.os.Build.VERSION.SDK_INT >= 30) info.stateDescription else info.text).toString().contains("preview"))
                check(!a.playback.playing)
                unchanged()
                snapshot("inspection-zoom")
            }
        }
        test("editor preview pans while zoomed and its reset restores the fitted view") {
            Thread.sleep(150)
            onMain {
                val view = a.editorView
                val r = view.controlBounds("preview")!!
                touch(view, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
                touch(view, MotionEvent.ACTION_MOVE, r.centerX() + r.width() * .1f, r.centerY())
                touch(view, MotionEvent.ACTION_UP, r.centerX() + r.width() * .1f, r.centerY())
                check(view.inspection.x > 0 && !a.playback.playing)
            }
            Thread.sleep(100)
            onMain {
                val reset = a.editorView.controlBounds("resetPreviewZoom") ?: error("Zoom reset is absent")
                touch(a.editorView, MotionEvent.ACTION_DOWN, reset.centerX(), reset.centerY())
                touch(a.editorView, MotionEvent.ACTION_UP, reset.centerX(), reset.centerY())
                check(a.editorView.inspection.zoom == 1f && a.editorView.inspection.x == 0f)
                unchanged()
                snapshot("inspection-reset")
            }
        }
        test("tool inspection zoom stays separate from export settings including Crop Inspect") {
            onMain { a.panels.videoEffects(Effect(kind = "RGB Offset")) }
            Thread.sleep(250)
            onMain {
                val view = find(a.root, "Tool preview")!!
                repeat(3) { check(view.performAccessibilityAction(0x02000101, null)) }
                val info = view.createAccessibilityNodeInfo()
                check((if (android.os.Build.VERSION.SDK_INT >= 30) info.stateDescription else info.text).toString().startsWith("3.38"))
                unchanged()
                check(view.performAccessibilityAction(0x02000103, null))
                a.panels.close()
                a.panels.effect("Crop")
            }
            Thread.sleep(250)
            onMain {
                val view = find(a.root, "Tool preview")!!
                check(view.performAccessibilityAction(0x02000104, null))
                check(view.performAccessibilityAction(0x02000101, null))
                unchanged()
                a.panels.close()
            }
        }
        test("preview controls immediately follow pause including cancellation before audio starts") {
            onMain { a.panels.videoEffects() }
            Thread.sleep(200)
            onMain {
                find(a.root, "Play preview")!!.performClick()
                check(a.playback.playing && a.editorView.playing && find(a.root, "Pause preview") != null)
                a.playback.pause()
                check(!a.playback.playing && !a.editorView.playing && find(a.root, "Play preview") != null)
                a.playback.play(); a.playback.pause()
            }
            Thread.sleep(500)
            onMain { check(!a.playback.playing && find(a.root, "Play preview") != null) }
        }
        test("shortened draft preview reaches its actual end and returns to the play state") {
            onMain {
                a.editor.cursor = 0.0
                a.playback.previewProject = a.editor.project.duplicate().apply { clips.single().sourceOut = .35 }
                a.playback.play(6.0)
            }
            val deadline = SystemClock.uptimeMillis() + 8000
            while (a.playback.playing && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            onMain {
                check(!a.playback.playing && !a.editorView.playing && find(a.root, "Play preview") != null)
                check(abs(a.editor.cursor - .35) < .001)
                a.playback.play(6.0)
                check(a.playback.playing && a.editor.cursor == 0.0)
                a.playback.pause(); a.playback.previewProject = null
                a.panels.close()
                unchanged()
            }
        }
    }
}
