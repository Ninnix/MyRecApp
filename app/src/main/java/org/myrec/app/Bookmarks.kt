package org.myrec.app

import java.util.UUID
import kotlin.math.*

data class SourceBookmark(val id: String=uid(),val time: Double,val label: String="",val local: Boolean=false) {
    fun valid()=id.isNotBlank() && id.length<=100 && time.isFinite() && time in 0.0..604800.0 && label.codePointCount(0,label.length)<=120
}
data class ResolvedBookmark(val id: String,val mark: SourceBookmark,val clip: Clip?,val time: Double) {
    val sourceTime get()=if (mark.local) clip?.frozen?.sourceTime ?: clip?.sourceIn ?: time else mark.time
}
internal fun recordingBookmark(time: Double)=SourceBookmark(UUID.nameUUIDFromBytes("recording-mark:${time.toBits()}".toByteArray()).toString(),time)
fun Project.resolvedBookmarks(): List<ResolvedBookmark> = (bookmarks.filter { it.time<=duration }.map { ResolvedBookmark(it.id,it,null,it.time) } + clips.sortedByDescending { it.video }.flatMap { clip ->
    (clip.bookmarks+clip.marks.map(::recordingBookmark)).distinctBy { it.id }.mapNotNull { mark ->
        val hold=clip.frozen
        val at=if (mark.local) {
            if (hold==null || mark.time<hold.offset || mark.time>=hold.offset+hold.duration) return@mapNotNull null
            clip.start+mark.time-hold.offset
        } else {
            if (hold!=null || clip.keptIntervals().none { mark.time>=it.start && mark.time<it.end }) return@mapNotNull null
            clip.start+clip.timelineOffset(mark.time)
        }
        ResolvedBookmark("${clip.occurrence}:${mark.id}",mark,clip,at)
    }
}).distinctBy { it.id }.sortedWith(compareBy({ it.time },{ it.id }))

fun Editor.addBookmark(label: String=""): String? {
    val clip=project.active(cursor).lastOrNull()
    if (clip==null) {
        val mark=SourceBookmark(time=cursor.coerceIn(0.0,project.duration),label=label);require(mark.valid())
        change { project.bookmarks.add(mark) };return mark.id
    }
    val mark=SourceBookmark(time=clip.frozen?.let { it.offset+cursor-clip.start } ?: clip.sourceTime(cursor),label=label,local=clip.frozen!=null)
    require(mark.valid())
    change { clip.bookmarks.add(mark) }
    return "${clip.occurrence}:${mark.id}"
}
fun Editor.renameBookmark(id: String,label: String) {
    val resolved=project.resolvedBookmarks().firstOrNull { it.id==id } ?: return
    val bounded=label.substring(0,label.offsetByCodePoints(0,min(120,label.codePointCount(0,label.length))))
    change {
        if (resolved.clip==null) {
            val index=project.bookmarks.indexOfFirst { it.id==id }
            if (index>=0) project.bookmarks[index]=resolved.mark.copy(label=bounded)
        }
        for (clip in project.clips.filter { it.occurrence==resolved.clip?.occurrence }) {
            clip.marks.removeAll { recordingBookmark(it).id==resolved.mark.id }
            val index=clip.bookmarks.indexOfFirst { it.id==resolved.mark.id }
            val value=resolved.mark.copy(label=bounded)
            if (index<0) clip.bookmarks.add(value) else clip.bookmarks[index]=value
        }
    }
}
fun Editor.removeBookmark(id: String) {
    val resolved=project.resolvedBookmarks().firstOrNull { it.id==id } ?: return
    change { project.bookmarks.removeAll { it.id==id };for (clip in project.clips.filter { it.occurrence==resolved.clip?.occurrence }) {
        clip.marks.removeAll { recordingBookmark(it).id==resolved.mark.id };clip.bookmarks.removeAll { it.id==resolved.mark.id }
    } }
}

fun Project.shiftBookmarks(at: Double,delta: Double) {
    for (index in bookmarks.indices) if (bookmarks[index].time>=at-.000001)
        bookmarks[index]=bookmarks[index].copy(time=max(0.0,bookmarks[index].time+delta))
}
