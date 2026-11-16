package org.myrec.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Bundle
import android.provider.DocumentsContract
import android.system.Os
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.SeekBar
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import org.json.JSONObject

object OriginalMediaTests {
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun find(view: View, name: String): View? {
        if (view.contentDescription?.toString() == name || view is TextView && view.text.toString() == name) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i),name)?.let { return it }
        return null
    }
    private class Folder(val root: File, val corrupt: Boolean = false, val refuseCleanup: Boolean = false) : OriginalMedia.Destination {
        val names = mutableListOf<String>()
        override fun create(name: String, type: String): Uri {
            val file = File(root,name); check(file.createNewFile()); names.add(name); return Uri.fromFile(file)
        }
        override fun write(file: Uri): OutputStream = File(file.path!!).outputStream()
        override fun read(file: Uri): InputStream = if (corrupt && names.size == 2) byteArrayOf(1,2,3).inputStream() else File(file.path!!).inputStream()
        override fun remove(file: Uri) { check(!refuseCleanup); check(File(file.path!!).delete()) }
    }
    fun run(i: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit, test: (String, () -> Unit) -> Unit) {
        val workspace = File(a.cacheDir,"original-media-${uid()}").apply { mkdirs() }
        fun store() = ProjectStore(File(workspace,uid()))
        fun media(store: ProjectStore, name: String): File = File(store.media,name).apply {
            parentFile!!.mkdirs(); i.context.assets.open("fixture.mp4").use { input -> outputStream().use { input.copyTo(it) } }
        }.canonicalFile
        test("original files remain accessible when all editing indexes and revisions are unreadable") {
            val store = store(); val id = uid()
            val first = media(store,"flat.mp4"); val second = media(store,"$id/owned.mp4")
            val project = Project(id = id,clips = mutableListOf(Clip(path = first.path,sourceOut = 2.0),Clip(path = second.path,sourceOut = 2.0,start = 2.0)))
            store.save(project); store.indexOriginals(id)
            val hashes = listOf(first,second).associateWith(::hash)
            val metadata = listOf(File(store.root,"$id.json")) + File(store.root,"revisions/$id").listFiles().orEmpty()
            metadata.forEach { it.writeText("broken index") }
            check(runCatching { store.open(id) }.isFailure)
            check(OriginalMedia.files(store,id).toSet() == setOf(first,second))
            File(store.root,"checksums/$id.json").writeText("broken checksums")
            check(OriginalMedia.files(store,id) == listOf(second))
            check(OriginalMedia.files(store).toSet() == setOf(first,second))
            check(metadata.all { it.readText() == "broken index" })
            check(hashes.all { (file,expected) -> hash(file) == expected })
        }
        test("newer project paths can be rescued without loading its timeline or following external paths") {
            val store = store(); val file = media(store,"future.mp4"); val id = uid()
            val outside = File(workspace,"outside.mp4").apply { writeText("outside") }
            val data = JSONObject().put("id",id).put("version",999).put("clips",org.json.JSONArray().put(JSONObject().put("path",file.path)).put(JSONObject().put("path",outside.path)))
            val index = File(store.root,"$id.json"); index.writeText(data.toString()); val bytes = index.readBytes()
            check(runCatching { store.open(id) }.exceptionOrNull() is NewerProject)
            check(OriginalMedia.files(store,id) == listOf(file) && index.readBytes().contentEquals(bytes))
            check(runCatching { OriginalMedia.files(store,"../escape") }.isFailure)
        }
        test("raw original scanning excludes sidecars hidden staging external symlinks and directory cycles") {
            val store = store(); val original = media(store,"nested/original.mp4")
            File(original.path+".mirror").writeText("true")
            File(original.path+".primaries").writeText("709")
            media(store,".copy-staging/incomplete.mp4")
            val outside = File(workspace,"outside-video.mp4").apply { writeText("outside") }
            Os.symlink(outside.path,File(store.media,"escape.mp4").path)
            Os.symlink(store.media.path,File(store.media,"cycle").path)
            check(OriginalMedia.files(store) == listOf(original))
            check(File(store.media,"cycle").delete()); check(File(store.media,"escape.mp4").delete())
        }
        test("a separate recovered project owns verified playable copies and retains unreadable originals") {
            val store = store(); val first = media(store,"first/take.mp4"); val second = media(store,"second/take.mp4")
            val unreadable = File(store.media,"unreadable.mp4").apply { writeText("opaque original") }
            File(first.path+".mirror").writeText("true"); File(first.path+".primaries").writeText("709")
            val previous = Project(clips = mutableListOf(Clip(path = first.path,sourceOut = 2.0,gainDb = -6f)))
            store.save(previous,false)
            val index = File(store.root,"${previous.id}.json"); index.writeText("broken metadata")
            val before = listOf(first,second,unreadable).associateWith(::hash)
            val result = RecoveredProject.create(store,listOf(first,second,unreadable),"Separate recovery")
            check(result.skipped == 1 && result.project.id != previous.id && result.project.clips.size == 2)
            val loaded = store.open(result.project.id)
            check(loaded.undo.isEmpty() && loaded.redo.isEmpty() && loaded.project.clips.all { it.gainDb == 0f && it.effects.isEmpty() })
            check(loaded.project.clips.map { File(it.path).name } == listOf("take.mp4","take (2).mp4"))
            check(loaded.project.clips.all { File(it.path).parentFile == File(store.media,result.project.id) })
            check(hash(File(loaded.project.clips[0].path)) == hash(first) && hash(File(loaded.project.clips[1].path)) == hash(second))
            check(File(loaded.project.clips[0].path+".mirror").readText() == "true")
            check(File(loaded.project.clips[0].path+".primaries").readText() == "709")
            check(index.readText() == "broken metadata" && before.all { (file,value) -> hash(file) == value })
            check(!File(store.media,"${result.project.id}/.myrec-copy").exists())
        }
        test("cancelling a recovered project removes only its new copies and publishes no timeline") {
            val store = store(); val sources = listOf(media(store,"a.mp4"),media(store,"b.mp4"))
            val before = sources.map(::hash); var cancel = false
            val failure = runCatching { RecoveredProject.create(store,sources,cancelled = { cancel }) { if (it > .1f) cancel = true } }.exceptionOrNull()
            check(failure is java.io.InterruptedIOException && store.list().isEmpty())
            check(store.media.listFiles()!!.map { it.canonicalFile }.toSet() == sources.toSet() && sources.map(::hash) == before)
        }
        test("failed recovered-index publication remains marked for checked startup cleanup") {
            val store = store(); val original = media(store,"source.mp4"); val before = hash(original)
            File(store.root,"revisions").writeText("expected publication failure")
            check(runCatching { RecoveredProject.create(store,listOf(original)) }.isFailure)
            val marked = store.media.listFiles()!!.filter { it.isDirectory && File(it,".myrec-copy").isFile }
            check(marked.size == 1 && hash(original) == before && store.list().isEmpty())
            check(store.cleanTransfers() == 1 && !marked.single().exists() && hash(original) == before)
        }
        test("recovery does not fabricate takes from opaque files or copy files outside original storage") {
            val store = store(); val opaque = File(store.media,"opaque.bin").apply { writeText("unreadable original") }
            val before = hash(opaque)
            check(runCatching { RecoveredProject.create(store,listOf(opaque)) }.isFailure)
            check(store.list().isEmpty() && store.media.listFiles()!!.toList() == listOf(opaque) && hash(opaque) == before)
            val external = File(workspace,"not-an-original.bin").apply { writeText("external") }
            check(runCatching { RecoveredProject.create(store,listOf(external)) }.exceptionOrNull() is IllegalArgumentException)
        }
        val first = File(workspace,"first/take.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(300001) { (it*17).toByte() }) }
        val second = File(workspace,"second/take.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(240003) { (it*29).toByte() }) }
        val sources = listOf(first,second); val hashes = sources.map(::hash)
        test("bulk originals preserve bytes resolve duplicate names and verify each saved copy") {
            val root = File(workspace,"copies").apply { mkdirs() }; val destination = Folder(root); val progress = mutableListOf<Float>()
            val result = OriginalMedia.copy(sources,destination,progress = { progress.add(it) })
            check(destination.names == listOf("take.mp4","take (2).mp4") && progress == listOf(.5f,1f))
            check(result.map { hash(File(it.path!!)) } == hashes && sources.map(::hash) == hashes)
        }
        test("failed destination verification removes only the files created by this copy") {
            val root = File(workspace,"corrupt").apply { mkdirs() }; val sentinel = File(root,"keep.txt").apply { writeText("keep") }
            val failure = runCatching { OriginalMedia.copy(sources,Folder(root,corrupt = true)) }.exceptionOrNull()
            check(failure?.message?.contains("did not verify") == true)
            check(root.listFiles()!!.toList() == listOf(sentinel) && sentinel.readText() == "keep" && sources.map(::hash) == hashes)
        }
        test("cancelled originals copy removes its partial work and never changes the sources") {
            val root = File(workspace,"cancelled").apply { mkdirs() }; var cancel = false
            val failure = runCatching { OriginalMedia.copy(sources,Folder(root),{ cancel }) { cancel = true } }.exceptionOrNull()
            check(failure is java.io.InterruptedIOException && root.listFiles()!!.isEmpty() && sources.map(::hash) == hashes)
        }
        test("original copy reports cleanup failures and refuses an existing target without overwriting it") {
            val root = File(workspace,"cleanup-failure").apply { mkdirs() }
            val failure = runCatching { OriginalMedia.copy(sources,Folder(root,corrupt = true,refuseCleanup = true)) }.exceptionOrNull()
            check(failure != null && failure.suppressed.size == 2 && sources.map(::hash) == hashes)
            val keep = File(root,"take.mp4"); val bytes = keep.readBytes()
            check(runCatching { OriginalMedia.copy(listOf(first),Folder(root)) }.isFailure && keep.readBytes().contentEquals(bytes))
        }
        test("originals screens expose bulk saving and metadata-independent recovery in both orientations") {
            val files = listOf(File(a.store.media,"original-ui-a.mp4"),File(a.store.media,"original-ui-b.mp4"))
            files.forEach { file -> i.context.assets.open("fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } } }
            val project = Project(clips = files.mapIndexed { index,file -> Clip(path = file.path,sourceOut = 2.0,start = index*2.0) }.toMutableList())
            a.store.save(project,false); val before = a.editor.project.duplicate()
            val problems = a.store.problems.toMap()
            try {
                for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    onMain { a.panels.close(); a.requestedOrientation = orientation }; Thread.sleep(550)
                    onMain {
                        a.panels.originals(project)
                        check(find(a.root,"Save all originals to Files") != null)
                        check(find(a.root,"Create recovered project") != null)
                        a.store.problems["recovery-check"] = "Expected unreadable project fixture"
                        a.panels.projects()
                    }
                    i.waitForIdleSync()
                    val deadline=android.os.SystemClock.uptimeMillis()+3000;var ready=false
                    while (!ready && android.os.SystemClock.uptimeMillis()<deadline) {
                        onMain { ready=find(a.root,"Browse original files")!=null }
                        if (!ready) Thread.sleep(20)
                    }
                    check(ready)
                    onMain {
                        check(find(a.root,"Browse original files")!!.performClick())
                        check(find(a.root,"original-ui-a.mp4") != null && find(a.root,"Save all originals to Files") != null)
                        check(a.editor.project == before)
                        a.panels.close()
                    }
                }
            } finally { onMain { a.store.problems.clear(); a.store.problems.putAll(problems); a.panels.close() } }
        }
        test("cancelling the bulk folder picker leaves the edit and originals untouched") {
            val before = a.editor.project.duplicate(); val bytes = sources.map(::hash)
            var intent: Intent? = null
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(start: Intent): Instrumentation.ActivityResult? {
                    if (start.action == Intent.ACTION_OPEN_DOCUMENT_TREE) { intent = start; return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null) }
                    return null
                }
            }
            i.addMonitor(monitor)
            try {
                onMain { a.saveOriginals(sources) }; Thread.sleep(250)
                check(intent != null && intent!!.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                check(a.editor.project == before && sources.map(::hash) == bytes)
            } finally { i.removeMonitor(monitor) }
        }
        test("Create recovered project publishes an independent card without changing the active edit") {
            val project = a.store.list().first { p -> p.clips.any { File(it.path).name == "original-ui-a.mp4" } }
            val ids = a.store.list().map { it.id }.toSet(); val before = a.editor.project.duplicate()
            onMain { a.panels.originals(project); check(find(a.root,"Create recovered project")!!.performClick()) }
            val deadline = SystemClock.uptimeMillis()+15000
            var created: Project? = null
            while (SystemClock.uptimeMillis() < deadline) {
                created = a.store.list().firstOrNull { it.id !in ids }
                if (created != null) break
                Thread.sleep(100)
            }
            check(created != null && created.clips.size == 2 && created.clips.all { File(it.path).parentFile!!.name == created.id })
            onMain { check(a.editor.project == before); a.panels.close() }
        }
        test("bulk originals save and verify through the actual Android Files provider") {
            val name = "myrec-test-${uid().take(12)}"
            val path = "/sdcard/Download/$name"
            val documentId = "primary:Download/$name"
            val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents",documentId)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree,documentId)
            fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
            check(shell("mkdir $path").isBlank())
            fun text(node: AccessibilityNodeInfo?): String = if (node == null) "" else buildString {
                append(node.text ?: ""); append(' '); append(node.contentDescription ?: "")
                for (index in 0 until node.childCount) append(text(node.getChild(index)))
            }
            fun control(node: AccessibilityNodeInfo?, select: Boolean): AccessibilityNodeInfo? {
                if (node == null) return null
                val title = node.text?.toString()?.uppercase(java.util.Locale.ROOT)
                val matching = if (select) node.viewIdResourceName?.endsWith(":id/action_menu_select") == true ||
                    title in setOf("USE THIS FOLDER","SELECT","USA QUESTA CARTELLA","SELEZIONA")
                    else title in setOf("ALLOW","CONSENTI")
                if (matching && node.isVisibleToUser && node.isEnabled) return node
                for (index in 0 until node.childCount) control(node.getChild(index),select)?.let { return it }
                return null
            }
            fun children(parent: Uri): List<Uri> {
                val child = DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent))
                return a.contentResolver.query(child,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)!!.use { cursor ->
                    buildList { while (cursor.moveToNext()) add(DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0))) }
                }
            }
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,parent)
                    return null
                }
            }
            i.addMonitor(monitor)
            try {
                onMain { a.panels.close(); a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }; Thread.sleep(600)
                onMain { a.saveOriginals(sources) }
                val deadline = SystemClock.uptimeMillis()+15000
                var selected = false; var allowed = false
                while (SystemClock.uptimeMillis() < deadline && !allowed) {
                    val root = i.uiAutomation.rootInActiveWindow
                    if (text(root).contains(name)) {
                        val button = control(root,!selected)
                        if (button != null) {
                            var target: AccessibilityNodeInfo = button
                            while (!target.isClickable) target = target.parent ?: break
                            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                if (!selected) selected = true else allowed = true
                            }
                        }
                    }
                    if (selected && root?.packageName?.toString() == a.packageName && runCatching { children(parent) }.isSuccess) allowed = true
                    Thread.sleep(200)
                }
                check(selected && allowed) { "Android Files did not confirm the owned test folder: ${text(i.uiAutomation.rootInActiveWindow)}" }
                var saved = emptyList<Uri>()
                var verified = false
                while (SystemClock.uptimeMillis() < deadline) {
                    saved = runCatching { children(parent).flatMap(::children) }.getOrDefault(emptyList())
                    val actual = runCatching { saved.map { file -> a.contentResolver.openInputStream(file)!!.use { input ->
                        MessageDigest.getInstance("SHA-256").digest(input.readBytes()).toList()
                    } }.toSet() }.getOrDefault(emptySet())
                    var finished = false
                    onMain { finished = !a.panels.isOpen }
                    verified = saved.size == sources.size && actual == hashes.toSet() && finished
                    if (verified) break
                    Thread.sleep(150)
                }
                check(verified && sources.map(::hash) == hashes)
            } finally {
                i.removeMonitor(monitor)
                runCatching { DocumentsContract.deleteDocument(a.contentResolver,parent) }
                shell("rmdir $path")
            }
        }
        test("original review keeps source range through rotation and appends it as one undo") {
            val project = a.store.list().first { p -> p.clips.any { File(it.path).name == "original-ui-a.mp4" } }
            val source = a.store.originals(project).first { File(it.path).name == "original-ui-a.mp4" }
            lateinit var before: Project
            onMain { a.panels.close(); a.openProject(project); before = a.editor.project.duplicate(); a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Thread.sleep(600)
            onMain {
                a.reviewOriginal(source,project)
                val root = a.originalReview!!.window!!.decorView
                val from = find(root,"Original start") as SeekBar; val to = find(root,"Original end") as SeekBar
                check(from.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,250f) }))
                check(to.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,750f) }))
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            Thread.sleep(650)
            onMain {
                val root = a.originalReview!!.window!!.decorView
                check((find(root,"Original start") as SeekBar).progress in 249..251)
                check((find(root,"Original end") as SeekBar).progress in 749..751)
                check(find(root,"Add range to end of timeline")!!.performClick())
                check(a.originalReview?.isShowing != true)
                val added = a.editor.project.clips.last()
                check(kotlin.math.abs(added.start-before.duration) < .00001)
                check(kotlin.math.abs(added.sourceIn-source.sourceOut*.25) < .002 && kotlin.math.abs(added.sourceOut-source.sourceOut*.75) < .002)
                check(added.id != source.id && added.occurrence != source.occurrence)
                a.editor.undo(); check(a.editor.project == before)
            }
        }
        test("original review cancellation and raw-file recovery controls do not alter the edit") {
            val source = a.store.originals(a.editor.project).first()
            val before = a.editor.project.duplicate()
            onMain {
                a.reviewOriginal(source,a.editor.project)
                val root = a.originalReview!!.window!!.decorView
                check(find(root,"Done")!!.performClick() && a.originalReview?.isShowing != true && a.editor.project == before)
                a.reviewOriginal(source)
                val raw = a.originalReview!!.window!!.decorView
                check(find(raw,"Original start") == null && find(raw,"Recover this recording into a new project") != null)
                check(find(raw,"Save original to Files") != null)
                find(raw,"Done")!!.performClick(); check(a.editor.project == before)
            }
        }
        test("closing the originals browser cancels a pending raw-file review") {
            val before = a.editor.project.duplicate()
            onMain {
                a.panels.recoveryOriginals()
                check(find(a.root,"Review original")!!.performClick())
                a.panels.close()
            }
            Thread.sleep(700)
            onMain { check(a.originalReview?.isShowing != true && a.editor.project == before) }
        }
        workspace.deleteRecursively()
    }
}
