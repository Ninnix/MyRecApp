package org.myrec.app

import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

class FontLibrary(val root: File) {
    data class Face(val id: String,val file: File,val info: FontFile.Face)
    data class Entry(val digest: String,val filename: String,val file: File,val faces: List<Face>)
    data class Reference(val digest: String,val index: Int,val postScript: String) {
        companion object {
            fun parse(id: String): Reference? {
                val parts=id.split(':',limit=4)
                if (parts.size!=4 || parts[0]!="import" || !parts[1].matches(Regex("[0-9a-f]{64}"))) return null
                val index=parts[2].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                if (parts[3].isBlank() || parts[3].length>140) return null
                return Reference(parts[1],index,parts[3])
            }
        }
    }
    private data class Cached(val stamp: String,val faces: List<FontFile.Face>)
    private val metadata=linkedMapOf<String,Cached>()
    companion object { val extensions=setOf("ttf","otf","ttc","otc");const val maximumBytes=256L*1024*1024 }
    @Synchronized private fun read(file: File): List<FontFile.Face> {
        require(file.length() in 12..maximumBytes) { "Choose a font smaller than 256 MB" }
        val stamp="${file.length()}:${file.lastModified()}"
        metadata[file.path]?.takeIf { it.stamp==stamp }?.let { return it.faces }
        val faces=RandomAccessFile(file,"r").use { FontFile.faces(it.channel.map(FileChannel.MapMode.READ_ONLY,0,it.length())) }
        faces.forEach { Typeface.Builder(file).setTtcIndex(it.index).build() ?: error("This font face cannot be loaded") }
        if (metadata.size>=64) metadata.remove(metadata.keys.first())
        metadata[file.path]=Cached(stamp,faces);return faces
    }
    private fun entry(file: File,digest: String): Entry {
        require(SourceAudio.hash(file)==digest) { "The font file changed" }
        return Entry(digest,file.name,file,read(file).map { Face("import:$digest:${it.index}:${it.postScript}",file,it) })
    }
    @Synchronized fun entries(): List<Entry> {
        migrate()
        return root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{64}")) }.mapNotNull { folder ->
            folder.listFiles()?.firstOrNull { it.extension.lowercase() in extensions }?.let { runCatching { entry(it,folder.name) }.getOrNull() }
        }.sortedWith(compareBy({ it.faces.first().info.name },{ it.filename },{ it.digest }))
    }
    @Synchronized fun add(source: File,name: String=source.name): Pair<Entry,Boolean> {
        val filename=File(name).name.take(200)
        require(filename.substringAfterLast('.').lowercase() in extensions) { "Choose a TTF, OTF, TTC, or OTC font file" }
        read(source)
        val digest=SourceAudio.hash(source);val folder=File(root,digest)
        File(root,".removed-$digest").delete()
        folder.listFiles()?.firstOrNull { it.extension.lowercase() in extensions }?.let { return entry(it,digest) to false }
        folder.mkdirs();val target=File(folder,filename);val temp=File(folder,".${uid()}.partial")
        try {
            source.inputStream().use { input -> FileOutputStream(temp).use { output -> input.copyTo(output);output.fd.sync() } }
            check(SourceAudio.hash(temp)==digest && temp.renameTo(target)) { "Cannot save the font" }
        } finally { temp.delete() }
        return entry(target,digest) to true
    }
    @Synchronized private fun migrate() {
        root.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in extensions }.forEach { old -> runCatching { if (!File(root,".removed-${SourceAudio.hash(old)}").exists()) add(old) } }
    }
    @Synchronized fun remove(entry: Entry) {
        val folder=File(root,entry.digest);require(folder.canonicalFile.parentFile==root.canonicalFile)
        check(!folder.exists() || folder.deleteRecursively()) { "Cannot remove the font" }
        File(root,".removed-${entry.digest}").writeText("")
        metadata.clear()
    }
    fun preserve(face: Face,media: File): File {
        val ref=Reference.parse(face.id) ?: error("Invalid font identity")
        val target=File(media,"font-${ref.digest}.sfnt")
        synchronized(this) {
            if (!target.isFile) {
                media.mkdirs();val temp=File(media,"font-${uid()}.partial")
                try {
                    face.file.inputStream().use { input -> FileOutputStream(temp).use { output -> input.copyTo(output);output.fd.sync() } }
                    check(SourceAudio.hash(temp)==ref.digest && temp.renameTo(target)) { "Cannot copy the project font" }
                }
                finally { temp.delete() }
            }
            require(SourceAudio.hash(target)==ref.digest) { "The project font changed" }
        }
        return target
    }
    fun describe(file: File,index: Int): FontFile.Face?=runCatching { read(file).getOrNull(index) }.getOrNull()
}
