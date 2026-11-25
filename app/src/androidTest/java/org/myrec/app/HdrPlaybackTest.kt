package org.myrec.app

import android.graphics.Bitmap
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

object HdrPlaybackTest {
    fun run(a: MainActivity,main: (()->Unit)->Unit,test: (String,()->Unit)->Unit) {
        val folder=File(a.getExternalFilesDir(null),"hdr-preview-fixtures")
        val files=folder.listFiles().orEmpty().filter { it.extension=="mp4" }.sorted()
        check(files.isNotEmpty()) { "Put a moving HDR clip in hdr-preview-fixtures" }
        val stats=JSONObject()
        fun signature(frame: Bitmap): Int {
            var hash=1
            for (y in 1..12) for (x in 1..12) hash=31*hash+frame.getPixel(x*frame.width/13,y*frame.height/13)
            return hash
        }
        for (file in files) test("HDR take ${file.name} advances in editor playback and seeks") {
            check(WorkingColor.hdr(file.path))
            val clip=ProjectStore.inspect(file)
            val size=ProjectStore.shape(file.path)
            val project=Project(name="HDR motion",width=size.first,height=size.second,clips=mutableListOf(clip))
            main { a.playback.pause(); a.editor.load(project); a.showEditor(); a.playback.seek(0.0) }
            Thread.sleep(700)
            main { a.playback.play() }
            val pictures=mutableSetOf<Int>()
            var last: Bitmap?=null
            val deadline=SystemClock.elapsedRealtime()+3000
            while (SystemClock.elapsedRealtime()<deadline) {
                main {
                    val frame=a.editorView.bitmap
                    if (frame!=null && frame!==last) { pictures.add(signature(frame)); last=frame }
                }
                Thread.sleep(25)
            }
            main { a.playback.pause() }
            val sought=mutableSetOf<Int>()
            for (fraction in listOf(.75,.2,.55)) {
                main { last=a.editorView.bitmap; a.playback.seek(clip.duration*fraction) }
                val until=SystemClock.elapsedRealtime()+3500
                var arrived=false
                while (!arrived && SystemClock.elapsedRealtime()<until) {
                    main {
                        val b=a.editorView.bitmap
                        if (b!=null && b!==last) { sought.add(signature(b)); last=b; arrived=true }
                    }
                    if (!arrived) Thread.sleep(35)
                }
            }
            val result=JSONObject().put("different_playback_pictures_3s",pictures.size).put("different_seek_pictures",sought.size)
            VideoDecoder(file.path).use { decoder ->
                val frame=checkNotNull(decoder.frame(minOf(.8,clip.duration/2),360,640))
                try {
                    val pixels=IntArray(frame.width*frame.height)
                    frame.getPixels(pixels,0,frame.width,0,0,frame.width,frame.height)
                    val luma=pixels.map { ((it shr 16 and 255)*.2126+(it shr 8 and 255)*.7152+(it and 255)*.0722) }.sorted()
                    result.put("display_luma_p95",luma[(luma.lastIndex*.95).toInt()])
                        .put("display_near_white_percent",100.0*luma.count { it>=249 }/luma.size)
                } finally { frame.recycle() }
            }
            stats.put(file.name,result)
            File(folder.parentFile,"hdr-playback.json").writeText(stats.toString(2))
            check(pictures.size>=12 && sought.size==3) { "Preview produced ${pictures.size} moving pictures and ${sought.size} distinct seeks. ${a.lastMessage}" }
        }
    }
}
