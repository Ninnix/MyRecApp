package org.myrec.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.*
import android.view.*
import android.widget.*
import java.io.File
import kotlin.math.*

object ToolWorkspaceTests {
    fun run(instrumentation: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
            test: (String, () -> Unit) -> Unit) {
        val directory = File(a.getExternalFilesDir(null), "tool-workspace-checks").apply { mkdirs() }
        val source = File(a.store.media, "workspace.mp4")
        val scene = File(a.getExternalFilesDir(null), "test-inputs/sicily-8s.mp4")
        if (scene.isFile) scene.copyTo(source, overwrite = true)
        else instrumentation.context.assets.open("fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val before = Project(name = "Tool workspaces", clips = mutableListOf(ProjectStore.inspect(source).apply { sourceOut = 3.5 }), width = 640, height = 360)
        fun find(view: View, label: String): View? {
            if (view.contentDescription?.toString() == label || view is TextView && view.text.toString() == label) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), label)?.let { return it }
            return null
        }
        fun click(label: String) = onMain { val control = find(a.root, label) ?: error("Missing $label"); check(control.isEnabled) { "Disabled $label" }; control.performClick() }
        fun preview(ready: (Project)->Boolean): Project {
            repeat(100) {
                a.playback.previewProject?.let { if (ready(it)) return it }
                Thread.sleep(50)
            }
            error("The tool preview did not finish updating")
        }
        fun title() = a.editor.project.clips.first { it.video }.effects.single { it.kind == "Text" }
        fun snapshot(name: String) {
            instrumentation.waitForIdleSync()
            Thread.sleep(120)
            val image = instrumentation.uiAutomation.takeScreenshot() ?: error("No workspace screenshot")
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        fun slider(name: String): SeekBar {
            fun search(view: View): SeekBar? {
                if (view is SeekBar && view.contentDescription?.toString() == name) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) search(view.getChildAt(i))?.let { return it }
                return null
            }
            return search(a.root) ?: error("No $name slider")
        }
        fun drag(bar: SeekBar, fraction: Float) {
            val now = android.os.SystemClock.uptimeMillis()
            val x = (bar.width * fraction).coerceIn(1f, max(1f, bar.width - 1f)); val y = bar.height / 2f
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now + 30, action, x, y, 0)
                bar.dispatchTouchEvent(event); event.recycle()
            }
        }
        for ((name, orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
            onMain { a.panels.close(); a.editor.load(before.duplicate()); a.editor.cursor = 0.0; a.requestedOrientation = orientation }
            Thread.sleep(650)
            test("$name first action row is Start Split Tools Export") {
                onMain {
                    val image = Bitmap.createBitmap(a.editorView.width, a.editorView.height, Bitmap.Config.ARGB_8888)
                    a.editorView.draw(Canvas(image)); image.recycle()
                    val bounds = listOf("mark", "split", "tools", "export").map { a.editorView.controlBounds(it) ?: error("Missing $it") }
                    check(bounds.zipWithNext().all { (l, r) -> l.centerX() < r.centerX() && abs(l.top - r.top) < 1 })
                }
                snapshot("$name-editor")
            }
            test("$name Text offers duration presets and all seven appearance tabs with a cancellable draft") {
                onMain { a.editor.cursor = 0.0; a.panels.effect("Text"); (find(a.root, "Title text") as EditText).setText("The Sicilian Coast") }
                Thread.sleep(200)
                snapshot("$name-text-looks")
                click("Duration")
                onMain { check(find(a.root, "Duration 15 seconds")?.isEnabled == false) }
                click("Duration 1 seconds")
                click("Duration")
                click("Film title"); click("Font")
                onMain { check(find(a.root, "Source Serif 4")?.isSelected == true) }
                click("Fill"); click("Gradient"); click("Lagoon")
                click("Outline"); click("Letter outline")
                onMain { check(find(a.root, "Outline color") != null && find(a.root, "Width") != null) }
                click("Shadow"); click("Drop shadow")
                click("Background"); click("Each line")
                click("Motion"); click("Fade")
                Thread.sleep(160)
                onMain { a.playback.pause() }
                snapshot("$name-text-motion")
                click("Cancel")
                check(a.editor.project == before && !a.editor.canUndo)
            }
            test("$name Text Apply persists its design and timing as one undo") {
                onMain { a.editor.cursor = 0.0; a.panels.effect("Text"); (find(a.root, "Title text") as EditText).setText("The Sicilian Coast") }
                click("Duration"); click("Duration 2 seconds"); click("Duration")
                click("Lower third"); click("Outline"); click("Letter outline")
                click("Apply")
                val fx = title()
                check(fx.values["face"] == "barlowcondensed" && fx.values["plate"] == "One box" && fx.values["outlined"] == "true")
                check(fx.from == 0.0 && fx.to == 2.0 && fx.phase != null) { "Title bounds ${fx.from}..${fx.to}, phase ${fx.phase}" }
                check(a.store.current() == a.editor.project)
                val edited = a.editor.project.duplicate()
                onMain { a.editor.undo() }; check(a.editor.project == before && !a.editor.canUndo)
                onMain { a.editor.redo() }; check(a.editor.project == edited)
                onMain { a.editor.undo(); a.editor.cursor = 0.0 }
            }
            test("$name Color LUTs has real look thumbnails and Before After without committing") {
                onMain { a.panels.effect("Color LUTs") }
                Thread.sleep(1800)
                onMain {
                    check(find(a.root, "Natural") != null && find(a.root, "Cool shadows") != null)
                    check(!slider("Strength").isEnabled)
                    val tile = find(a.root, "Warm") as ViewGroup
                    check((tile.getChildAt(0) as ImageView).drawable != null) { "No real look preview" }
                }
                snapshot("$name-luts")
                click("Warm"); click("Exposure & white balance")
                onMain { check(slider("Strength").isEnabled); drag(slider("Warmth"), .8f) }
                val updated=preview { p -> p.clips.any { c -> c.effects.any { it.kind=="Color LUTs" && it.values["lut"]=="Warm" && it.number("warmth")>0 } } }
                val grade = updated.clips.first { it.video }.effects.single { it.kind == "Color LUTs" }
                check(grade.values["lut"] == "Warm" && grade.number("warmth") > 0)
                click("Before"); Thread.sleep(180)
                check(a.playback.previewProject!!.clips.none { clip -> clip.effects.any { it.kind == "Color LUTs" } })
                click("After"); Thread.sleep(180)
                check(a.playback.previewProject!!.clips.any { clip -> clip.effects.any { it.kind == "Color LUTs" } })
                click("Favorite look"); click("Favorites")
                onMain { check(find(a.root, "Warm") != null) }
                click("Cancel"); check(a.editor.project == before && !a.editor.canUndo)
                onMain {
                    val prefs = a.toolLibrary
                    val favorites = prefs.getStringSet("color.favorites", emptySet())!!.toMutableSet()
                    favorites.remove("Warm"); prefs.edit().putStringSet("color.favorites", favorites).apply()
                }
            }
        }
        for ((name,orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,"landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
            test("$name imported crop preview edits both animation endpoints and keeps Cancel Apply and Undo") {
                val pose=mapOf("x" to .4,"y" to .5,"zoom" to 2.0,"rotation" to 10.0)
                val crop=Effect(kind="Crop",values=pose.mapValues { it.value.toString() }.toMutableMap().apply { put("geometry","source") },
                    motion=EffectMotion(pose,pose+("zoom" to 3.0),duration=3.5))
                val seed=before.duplicate().apply { clips.single().effects.add(crop) }
                onMain { a.panels.close();a.requestedOrientation=orientation;a.editor.load(seed.duplicate());a.editor.cursor=1.0 }
                Thread.sleep(650)
                fun open() {
                    onMain { a.panels.effect("Crop",a.editor.project.clips.single().effects.single()) }
                    Thread.sleep(200)
                    onMain { drag(slider("Zoom"),.6f) }
                    val fx=preview { p -> p.clips.any { c -> c.effects.any { it.kind=="Crop" && it.number("zoom")>3f } } }.clips.single().effects.single { it.kind=="Crop" }
                    check(fx.values["geometry"]=="source" && fx.motion!!.valid(fx) && fx.number("zoom")>3f)
                    check(abs(fx.motion!!.b.getValue("zoom")/fx.motion!!.a.getValue("zoom")-1.5)<.00001)
                    snapshot("$name-native-crop")
                }
                open();click("Cancel");check(a.editor.project==seed && !a.editor.canUndo)
                open();click("Apply");check(a.editor.canUndo && a.editor.project.clips.single().effects.single().motion!!.a.getValue("zoom")>3)
                onMain { a.editor.undo() };check(a.editor.project==seed)
                onMain { a.editor.load(before.duplicate()) }
            }
        }
        test("open Tools reflows its grid and keeps Settings visible through rotation without editing") {
            onMain { a.panels.close();a.editor.load(before.duplicate());a.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Thread.sleep(650)
            onMain { a.panels.tools() }
            Thread.sleep(100)
            val snap=a.editor.snap;val listening=a.editor.matchLoudness
            fun scroll(view: View): ScrollView? {
                if (view is ScrollView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) scroll(view.getChildAt(i))?.let { return it }
                return null
            }
            onMain { checkNotNull(scroll(a.root)).apply { isSmoothScrollingEnabled=false;fullScroll(View.FOCUS_DOWN) } }
            for ((name,orientation) in listOf("landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,"portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                onMain { a.requestedOrientation=orientation }
                // Window rotation outlasts the view hierarchy's layout change.
                Thread.sleep(800)
                var ready=false
                var detail=""
                val deadline=android.os.SystemClock.elapsedRealtime()+5000
                while (!ready && android.os.SystemClock.elapsedRealtime()<deadline) {
                    onMain {
                        if ((a.root.width>a.root.height)==(name=="landscape")) {
                            val video=IntArray(2);val crop=IntArray(2)
                            find(a.root,"Video effects")?.getLocationInWindow(video)
                            find(a.root,"Crop")?.getLocationInWindow(crop)
                            val visible=Rect()
                            ready=(abs(video[1]-crop[1])<2)==(name=="landscape") &&
                                find(a.root,"Fonts")?.getGlobalVisibleRect(visible)==true
                            val panel=scroll(a.root)
                            detail="videoY=${video[1]} cropY=${crop[1]} fonts=$visible scroll=${panel?.scrollY} height=${panel?.height} content=${panel?.getChildAt(0)?.height}"
                        }
                    }
                    if (!ready) Thread.sleep(50)
                }
                check(ready) { "Tools did not reflow or retain its Settings position in $name: $detail" }
                check(a.editor.project==before && !a.editor.canUndo && a.editor.snap==snap && a.editor.matchLoudness==listening)
                snapshot("$name-tools-settings-rotated")
            }
            onMain { a.panels.close() }
        }
        test("title shaping uses bundled fonts and renders gradient outline background and motion in export") {
            val plain = Effect(kind = "Text", to = 3.5, values = mutableMapOf("text" to "Coast"))
            TitleStyle.initialize(plain, true); plain.values["shadow"] = "false"
            val image = TitleStyle.raster(a, plain, 32f, 400)
            fun opaque(bitmap: Bitmap): Int {
                val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return pixels.count { Color.alpha(it) > 100 }
            }
            val basePixels = opaque(image); image.recycle()
            val outlined = plain.copyEffect().apply { values["outlined"] = "true"; values["borderWidth"] = ".2" }
            val border = TitleStyle.raster(a, outlined, 32f, 400)
            check(opaque(border) > basePixels * 1.1); border.recycle()
            val signatures = mutableSetOf<Int>()
            for (family in TitleStyle.families) {
                val candidate = plain.copyEffect().apply { values["face"] = family.id }
                val sample = TitleStyle.raster(a, candidate, 32f, 400)
                val pixels = IntArray(sample.width * sample.height); sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
                signatures.add(pixels.contentHashCode()); sample.recycle()
            }
            check(signatures.size == TitleStyle.families.size) { "A bundled font fell back: ${signatures.size} distinct fonts" }
            val styled = plain.copyEffect().apply {
                values["gradientFirst"] = "#ff0000"; values["gradientLast"] = "#0000ff"; values["gradientAngle"] = "0"
                values["plate"] = "Each line"; values["backing"] = "#ff00ff00"; values["entrance"] = "Fade"; values["exit"] = "Wipe"
            }
            val p = before.duplicate().apply { clips.first().effects.add(styled) }
            val saved = a.store.decode(a.store.encode(p))
            check(saved == p)
            Frames().use { frames ->
                val first = frames.render(saved, 0.0, 320, 180); val middle = frames.render(saved, 1.0, 320, 180)
                check(!first.sameAs(middle)); first.recycle(); middle.recycle()
            }
            val output = File(directory, "styled-title.mp4")
            Export(a.audioCache).run(saved, output, 320, 180) {}
            VideoDecoder(output.path).use { decoder ->
                val image = decoder.frame(1.0, 320, 180) ?: error("No title export")
                val pixels = IntArray(image.width * image.height); image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                check(pixels.count { Color.green(it) > 140 && Color.red(it) < 100 && Color.blue(it) < 100 } > 50)
                image.recycle()
            }
        }
        test("LUT balance and source-clock animation survive save split archive and undo") {
            val fx = Effect(kind = "Color LUTs", values = mutableMapOf("lut" to "Warm"))
            val initial = GradeModel.pose(fx)
            fx.motion = EffectMotion(initial, initial + ("exposure" to 1.0) + ("tint" to .4), duration = 3.5)
            onMain { a.panels.close(); a.editor.load(before.duplicate()); a.editor.applyEffect(fx); a.editor.cursor = 1.0; a.editor.split() }
            val p = a.editor.project.duplicate()
            check(a.store.current() == p)
            val archive = File(directory, "animated-grade-${uid()}.zip")
            ProjectArchive.export(a.store, p, archive) {}
            val restored = ProjectArchive.restore(a.store, archive).project
            check(restored.clips.flatMap { it.effects }.all { it.motion?.valid(it) == true })
            Frames().use { frames ->
                val image = frames.render(p, 2.0, 320, 180)
                check(image.width == 320); image.recycle()
            }
            onMain { a.editor.undo(); a.editor.undo() }; check(a.editor.project == before && !a.editor.canUndo)
        }
        onMain { a.panels.close(); a.editor.load(before.duplicate()) }
    }
}
