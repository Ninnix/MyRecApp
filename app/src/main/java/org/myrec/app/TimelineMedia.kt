package org.myrec.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/* A timeline URL is only a lookup key inside the directory the user selected.
 * It never becomes a filesystem path or an arbitrary content-provider request. */
class TimelineMedia(private val resolver: ContentResolver,private val tree: Uri,private val cancelled: ()->Boolean) {
    private data class Entry(val path: String,val uri: Uri,val size: Long,val modified: Long)
    private val entries=mutableListOf<Entry>()
    private var indexed=false
    private fun stop() { if (cancelled()) throw java.io.InterruptedIOException("Timeline import cancelled") }
    private fun index() {
        if (indexed) return
        val seen=mutableSetOf<String>();var count=0
        fun visit(id: String,path: String,depth: Int) {
            stop();require(depth<=32 && seen.add(id)) { "The selected media folder contains a directory cycle" }
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id)
            val fields=arrayOf("document_id","_display_name","mime_type","_size","last_modified")
            resolver.query(children,fields,null,null,null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    stop();require(++count<=100000) { "The selected media folder has too many entries" }
                    val child=cursor.getString(0);val name=cursor.getString(1)
                    require(!name.isNullOrEmpty() && name !in listOf(".","..") && name.none { it=='/' || it=='\\' || it=='\u0000' }) { "Invalid media filename" }
                    val relative=if (path.isEmpty()) name else "$path/$name"
                    if (cursor.getString(2)==DocumentsContract.Document.MIME_TYPE_DIR) visit(child,relative,depth+1)
                    else entries.add(Entry(relative,DocumentsContract.buildDocumentUriUsingTree(tree,child),if (cursor.isNull(3)) -1 else cursor.getLong(3),if (cursor.isNull(4)) 0 else cursor.getLong(4)))
                }
            } ?: error("Cannot read the selected media folder")
        }
        visit(DocumentsContract.getTreeDocumentId(tree),"",0);indexed=true
    }
    internal fun matches(path: String,candidates: List<String>): String? {
        val pieces=TimelineImport.lookupPath(path).split('/').filter { it.isNotEmpty() }
        val relative=pieces.joinToString("/")
        candidates.singleOrNull { it==relative }?.let { return it }
        val suffix=candidates.filter { relative.endsWith("/$it") || it.endsWith("/$relative") }
        if (suffix.isNotEmpty()) {
            val length=suffix.maxOf { it.length };val best=suffix.filter { it.length==length }
            require(best.size==1) { "Timeline media is ambiguous: ${pieces.lastOrNull()}" };return best.single()
        }
        val names=candidates.filter { it.substringAfterLast('/')==pieces.lastOrNull() }
        require(names.size<=1) { "More than one media file is named ${pieces.lastOrNull()}. Select a narrower folder." }
        return names.singleOrNull()
    }
    fun resolve(path: String): TimelineImport.Media? {
        stop();index()
        val match=matches(path,entries.map { it.path }) ?: return null
        val entry=entries.single { it.path==match }
        fun stamp(): Pair<Long,Long> = resolver.query(entry.uri,arrayOf("_size","last_modified"),null,null,null)?.use {
            check(it.moveToFirst());(if (it.isNull(0)) -1L else it.getLong(0)) to (if (it.isNull(1)) 0L else it.getLong(1))
        } ?: error("Cannot verify timeline media")
        return TimelineImport.Media(entry.size,{ resolver.openInputStream(entry.uri) ?: error("Cannot open timeline media") },
            { stop();stamp()==(entry.size to entry.modified) })
    }
}
