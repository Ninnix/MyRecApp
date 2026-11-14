package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import org.json.JSONObject
import kotlin.math.abs

object MotionTests {
    fun run(instrumentation: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
        test: (String, () -> Unit) -> Unit) {
        fun find(root: View, name: String, bar: Boolean = false): View? {
            if ((!bar || root is SeekBar) && (root.contentDescription?.toString() == name || root is TextView && root.text.toString() == name)) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), name, bar)?.let { return it }
            return null
        }
        fun choose(name: String) {
            var clicked = false
            repeat(20) {
                if (!clicked) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val node = root?.findAccessibilityNodeInfosByText(name)?.firstOrNull { it.text?.toString() == name }
                    clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    node?.recycle(); root?.recycle()
                    if (!clicked) Thread.sleep(50)
                }
            }
            check(clicked) { "Missing choice $name" }
            instrumentation.waitForIdleSync()
        }
        val source = File(a.store.media, "motion-source.mp4")
        instrumentation.context.assets.open("fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        fun fixture(): Project {
            val c = ProjectStore.inspect(source)
            c.sourceOut = 2.0
            val second = c.duplicate(true).apply { start = 2.0 }
            return Project(name = "Correction motion", clips = mutableListOf(c, second))
        }
        var saved: Project? = null
        test("correction motion saves source clocks endpoint poses undo redo duplicate and portable archive") {
            val e = Editor(fixture()).apply { selected.addAll(project.clips.map { it.id }) }
            val work = VideoEffectsDraft(e, Effects.video.toSet())
            val fx = Effect(kind = "Color Correction"); work.chain.add(fx)
            work.selectEndpoint(fx, true, 1.0)
            work.editNumber(fx, "exposure", 1.5f, 1.0, false)
            work.timing(fx, EffectTiming(EffectCurve.RiseHoldReturn, .8, .6, EffectCurve.Smooth), 1.0)
            work.commit(e)
            e.undo(); e.redo(); a.store.save(e, false)
            fun motions(p: Project) = p.clips.map { it.effects.single().motion }
            val original = motions(e.project)
            check(motions(a.store.editor(e.project).project) == original)
            check(motions(a.store.editor(a.store.duplicate(e.project)).project) == original)
            val archive = File(a.cacheDir, "motion-${uid()}.zip")
            ProjectArchive.export(a.store, e.project, archive)
            check(motions(a.store.editor(ProjectArchive.restore(a.store, archive).project).project) == original)
            archive.delete()
            saved = e.project.duplicate()
        }
        test("malformed motion clocks poses timing and future curves are rejected") {
            val data = a.store.encode(saved!!)
            fun rejected(change: (JSONObject) -> Unit) {
                val copy = JSONObject(data.toString())
                val motion = copy.getJSONArray("clips").getJSONObject(0).getJSONArray("effects").getJSONObject(0).getJSONObject("motion")
                change(motion)
                check(runCatching { a.store.decode(copy) }.isFailure)
            }
            val malformed = JSONObject(data.toString())
            malformed.getJSONArray("clips").getJSONObject(0).getJSONArray("effects").getJSONObject(0).put("motion", "broken")
            check(runCatching { a.store.decode(malformed) }.isFailure)
            rejected { it.put("duration", 0) }
            rejected { it.put("frame", "0.03") }
            rejected { it.put("rise", -1) }
            rejected { it.put("curve", "Future") }
            rejected { it.put("ramp", "RiseAndHold") }
            rejected { it.getJSONObject("a").remove("exposure") }
            rejected { it.getJSONObject("b").put("exposure", 100) }
            rejected { it.getJSONArray("clock").getJSONObject(1).put("source", 0) }
            rejected { it.getJSONArray("clock").getJSONObject(1).put("elapsed", -1) }
        }
        test("animated frames use the same evaluated primary correction as static frames") {
            val p = saved!!
            Frames().use { frames ->
                for (at in listOf(3.9, 0.0, 1.0, 2.5, .4, 3.9)) {
                    val static = p.duplicate()
                    for (c in static.clips) {
                        val fx = c.effects.single()
                        val sampled = fx.sampled(c.sourceTime(at))
                        c.effects.clear(); c.effects.add(sampled)
                    }
                    val animated = frames.render(p, at, 160, 90)
                    val expected = frames.render(static, at, 160, 90)
                    check(animated.sameAs(expected)) { "Animated render mismatch at $at" }
                    animated.recycle(); expected.recycle()
                }
            }
        }
        test("A B timing and endpoint editing work across two clips in both orientations") {
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                onMain {
                    a.panels.close()
                    a.requestedOrientation = orientation
                    a.editor.load(fixture())
                    a.editor.selected.addAll(a.editor.project.clips.map { it.id })
                    a.editor.applyEffect(Effect(kind = "Color Correction"))
                    a.editor.cursor = .5
                }
                Thread.sleep(600)
                onMain { a.panels.videoEffects(a.editor.project.clips[0].effects.single()) }
                Thread.sleep(200)
                onMain {
                    check(!a.isDestroyed && a.window.decorView.windowToken != null) {
                        "Activity lost during orientation: destroyed=${a.isDestroyed}, finishing=${a.isFinishing}, root=${a.root.isAttachedToWindow}, decor=${a.window.decorView.isAttachedToWindow}"
                    }
                    find(a.root, "Animate")!!.performClick()
                }
                choose("Edit B")
                onMain {
                    check(find(a.root, "Editing endpoint B") != null)
                    find(a.root, "Exposure", true)!!.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                    find(a.root, "Animate")!!.performClick()
                }
                choose("Timing")
                onMain { find(a.root, "Curve · Linear")!!.performClick() }
                choose("Rise, hold & return")
                onMain {
                    check(find(a.root, "Rise", true) != null && find(a.root, "Release", true) != null)
                    find(a.root, "Done")!!.performClick()
                    find(a.root, "Apply")!!.performClick()
                    check(a.editor.project.clips.all { it.effects.single().motion?.timing?.curve == EffectCurve.RiseHoldReturn })
                    check(a.editor.project.clips.all { it.effects.single().motion!!.b.getValue("exposure") > 0 })
                    a.editor.undo()
                    check(a.editor.project.clips.all { it.effects.single().motion == null })
                }
            }
        }
        test("multi clip effect inspector preserves distinct chains when applying or cancelling") {
            onMain {
                a.editor.load(fixture())
                a.editor.selected.addAll(a.editor.project.clips.map { it.id })
                a.editor.project.clips[0].effects.add(Effect(kind = "RGB Offset"))
                a.editor.project.clips[1].effects.add(Effect(kind = "Color Correction"))
                a.editor.cursor = .5
                a.panels.videoEffects(a.editor.project.clips[0].effects.single())
            }
            Thread.sleep(150)
            onMain {
                find(a.root, "Red offset")!!.performAccessibilityAction(0x02000002, null)
                find(a.root, "Apply")!!.performClick()
                check(abs(a.editor.project.clips[0].effects.single().number("redX") - .03f) < .00001)
                check(a.editor.project.clips[1].effects.single().kind == "Color Correction")
                a.editor.cursor = 2.5
                a.panels.videoEffects(a.editor.project.clips[1].effects.single())
            }
            Thread.sleep(150)
            onMain {
                find(a.root, "Exposure", true)!!.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
                a.panels.close()
                check(a.editor.project.clips[1].effects.single().number("exposure") == 0f)
                a.editor.cursor = .5
                a.panels.videoEffects(a.editor.project.clips[0].effects.single())
                check(find(a.root, "‹ Applied · 1") != null)
                a.editor.cursor = 2.5
                a.playback.refresh()
            }
            Thread.sleep(600)
            onMain {
                check(find(a.root, "Applied effects · 1") != null)
                check(find(a.root, "Color Correction") != null && find(a.root, "Red offset") == null)
                a.panels.close()
            }
        }
    }
}
