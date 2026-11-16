package org.myrec.app

import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView

class FontLibraryDialog(private val a: MainActivity,private val ui: InspectorUi,
    private val use: ((FontLibrary.Face)->Unit)?=null): Dialog(a) {
    private val content=ui.column()
    private var preparing=false
    private var generation=0
    private val expanded=mutableSetOf<String>()
    init {
        setTitle("Fonts")
        val body=ui.column().apply { setPadding(ui.px(12f),ui.px(10f),ui.px(12f),ui.px(10f));setBackgroundColor(Panels.BG) }
        val scroll=ScrollView(a).apply { addView(content) }
        body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        ui.add(body,ui.action("Done") { dismiss() })
        setContentView(body);setOnShowListener { resize();render() }
    }
    private fun resize() { window?.setLayout((a.root.width*.94).toInt(),(a.root.height*.88).toInt()) }
    private fun render() {
        content.removeAllViews()
        ui.add(content,ui.action("Import fonts…","Add") { a.importFonts { if (isShowing) render() } })
        ui.add(content,ui.text("TrueType (.ttf), OpenType (.otf), and font collections (.ttc, .otc)",9f,Panels.MUTED))
        val request=++generation
        ui.add(content,ui.text("Loading fonts…",9f,Panels.MUTED))
        Thread {
            val result=runCatching { a.fontLibrary.entries() }
            val previews=result.getOrNull().orEmpty().flatMap { it.faces }.associate { face ->
                val effect=Effect(kind="Text",values=mutableMapOf("face" to face.id,"fontIndex" to face.info.index.toString(),"path" to face.file.path,"weight" to face.info.weight.toString()))
                face.id to TitleStyle.font(a,effect)
            }
            a.runOnUiThread {
                if (isShowing && generation==request) {
                    content.removeViewAt(content.childCount-1)
                    result.fold({ populate(it,previews) },{ ui.add(content,ui.text(it.message ?: "Cannot read fonts")) })
                }
            }
        }.start()
    }
    private fun populate(entries: List<FontLibrary.Entry>,previews: Map<String,android.graphics.Typeface>) {
        val names=entries.flatMap { it.faces }.groupingBy { it.info.name }.eachCount()
        for (entry in entries) {
            ui.gap(content)
            val header=ui.row()
            header.addView(ui.action(if (entry.faces.size==1) entry.faces.first().info.name else "${entry.filename} · ${entry.faces.size} faces",color=Color.WHITE) {
                if (entry.faces.size==1) select(entry.faces.first()) else {
                    if (!expanded.add(entry.digest)) expanded.remove(entry.digest);render()
                }
            },LinearLayout.LayoutParams(0,-2,1f))
            header.addView(ui.icon("Remove ${entry.filename}","Delete") {
                a.choose("Remove ${entry.filename} from the library? Projects keep their copies.",listOf("Keep font","Remove font")) {
                    if (it==1) try { a.fontLibrary.remove(entry);render() } catch (error: Exception) { a.message(error.message ?: "Cannot remove font") }
                }
            },LinearLayout.LayoutParams(ui.px(40f),ui.px(44f)))
            ui.add(content,header)
            ui.add(content,ui.text(entry.filename,8f,Panels.MUTED))
            if (entry.faces.size==1 || entry.digest in expanded) for (face in entry.faces) {
                val row=ui.row().apply { gravity=Gravity.CENTER_VERTICAL;contentDescription="Use ${face.info.name}" }
                row.addView(ui.text("Aa",20f).apply { typeface=previews[face.id] },LinearLayout.LayoutParams(ui.px(44f),ui.px(44f)))
                row.addView(ui.text(face.info.name+if ((names[face.info.name] ?: 0)>1) " · ${entry.filename}" else "",10f),LinearLayout.LayoutParams(0,-2,1f))
                if (use!=null) row.setOnClickListener { select(face) }
                ui.add(content,row)
            }
        }
        ui.gap(content)
        ui.add(content,ui.text("Projects keep a copy of each font they use. Removing a library font leaves those projects unchanged.",9f,Panels.MUTED))
    }
    private fun select(face: FontLibrary.Face) {
        val selected=use ?: return
        if (preparing) return
        preparing=true;content.alpha=.5f
        val projectId=a.editor.project.id
        Thread {
            val result=runCatching {
                val owned=a.fontLibrary.preserve(face,a.store.media)
                a.store.registerMedia(projectId,owned.path);a.fontLibrary.describe(owned,face.info.index)
                val effect=Effect(kind="Text",values=mutableMapOf("face" to face.id,"path" to owned.path,"fontIndex" to face.info.index.toString(),"weight" to face.info.weight.toString(),"italic" to face.info.italic.toString()))
                TitleStyle.font(a,effect)
                face.copy(file=owned)
            }
            a.runOnUiThread {
                preparing=false;content.alpha=1f
                if (isShowing && a.editor.project.id==projectId) result.fold({ selected(it);dismiss() },{ a.message(it.message ?: "Cannot use font") })
            }
        }.start()
    }
}
