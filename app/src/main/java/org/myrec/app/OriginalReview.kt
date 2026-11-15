package org.myrec.app

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import java.io.File
import kotlin.math.min

class OriginalReview(private val a: MainActivity, private val source: Clip, private val project: Project?) :
    Dialog(a,android.R.style.Theme_Material_NoActionBar) {
    private val player = VideoView(a)
    private var start = source.sourceIn
    private var end = source.sourceOut
    private val frame = min(1.0/(project?.fps ?: 30),end-start)
    var onClosed: (() -> Unit)? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window?.setBackgroundDrawableResource(android.R.color.black)
        val width = a.root.width; val height = a.root.height
        val scale = min(width/(if (width > height) 640f else 294f),height/(if (width > height) 294f else 640f))
        val ui = InspectorUi(a,scale)
        val body = ui.column().apply { setPadding(ui.px(12f),ui.px(10f),ui.px(12f),ui.px(12f)) }
        val scroll = ScrollView(a); scroll.addView(body); setContentView(scroll)
        val heading = ui.row()
        heading.addView(ui.text("Review original",14f,bold = true),LinearLayout.LayoutParams(0,ui.px(44f),1f))
        heading.addView(ui.action("Done") { dismiss() },LinearLayout.LayoutParams(ui.px(68f),ui.px(44f)))
        ui.add(body,heading)
        val controls = MediaController(a); player.setMediaController(controls)
        body.addView(player,LinearLayout.LayoutParams(-1,(min(height*.45f,width*9f/16)).toInt()))
        ui.add(body,ui.text(File(source.path).name,11f,bold = true),42f)
        ui.add(body,ui.text("${timeLabel(source.sourceOut-source.sourceIn)} recorded",10f,Panels.MUTED),28f)
        val message = ui.text("",10f,Panels.MUTED); ui.add(body,message)
        if (project != null && source.video && !source.photo) {
            lateinit var from: SeekBar; lateinit var to: SeekBar
            fun progress(value: Double) = ((value-source.sourceIn)/(source.sourceOut-source.sourceIn)*1000).toInt().coerceIn(0,1000)
            from = ui.slider(body,"Start",start.toFloat(),source.sourceIn.toFloat(),source.sourceOut.toFloat(),format = { timeLabel(it.toDouble()) }) {
                start = it.toDouble().coerceIn(source.sourceIn,end-frame)
                from.progress = progress(start); player.seekTo((start*1000).toInt())
            }.apply { contentDescription = "Original start" }
            to = ui.slider(body,"End",end.toFloat(),source.sourceIn.toFloat(),source.sourceOut.toFloat(),format = { timeLabel(it.toDouble()) }) {
                end = it.toDouble().coerceIn(start+frame,source.sourceOut)
                to.progress = progress(end); player.seekTo(((end-frame).coerceAtLeast(start)*1000).toInt())
            }.apply { contentDescription = "Original end" }
            ui.add(body,ui.action("Add range to end of timeline","Add") {
                if (a.insertOriginalRange(project,source,start,end)) dismiss()
            },46f)
        }
        if (project == null) {
            ui.add(body,ui.action("Recover this recording into a new project","Add") {
                dismiss(); a.recoverOriginals(listOf(File(source.path)))
            },52f)
        }
        ui.gap(body)
        ui.add(body,ui.action("Save original to Files","Export") { a.saveDocument(File(source.path),OriginalMedia.type(File(source.path))) },46f)
        player.setOnPreparedListener { player.seekTo((start*1000).toInt()) }
        player.setOnErrorListener { _,_,_ -> message.text = "Cannot play this original. You can save its unchanged file."; true }
        player.setVideoPath(source.path)
        setOnDismissListener { player.stopPlayback(); onClosed?.invoke() }
    }
    fun relayout() {
        window?.setLayout(-1,-1)
        val width = a.root.width; val height = a.root.height
        if (player.parent != null) player.layoutParams = player.layoutParams.apply { this.height = min(height*.45f,width*9f/16).toInt() }
    }
    override fun onStart() { super.onStart(); relayout() }
    fun pause() { if (player.isPlaying) player.pause() }
}
