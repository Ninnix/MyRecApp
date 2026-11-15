package org.myrec.app

import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.InputStream
import java.io.OutputStream

object RecoveredProject {
    data class Result(val project: Project, val skipped: Int)

    private fun playable(file: File): Clip? = try {
        val clip = ProjectStore.inspect(file)
        require(clip.video || clip.audio)
        if (clip.video && !clip.photo) {
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(file.path)
                for (time in listOf(0.0,(clip.sourceOut-.001).coerceAtLeast(0.0))) {
                    val frame = reader.getScaledFrameAtTime((time*1000000).toLong(),MediaMetadataRetriever.OPTION_CLOSEST,64,64)
                        ?: error("A source frame is unreadable")
                    frame.recycle()
                }
            } finally { reader.release() }
        }
        clip
    } catch (_: Exception) { null }

    fun create(store: ProjectStore, files: List<File>, title: String = "Recordings · Recovered",
        cancelled: () -> Boolean = { false }, progress: (Float) -> Unit = {}): Result {
        require(files.isNotEmpty() && files.map { it.canonicalPath }.distinct().size == files.size)
        val root = store.media.canonicalPath+File.separator
        require(files.all { it.canonicalPath.startsWith(root) && it.isFile })
        val project = Project(name = title.take(120)); val working = File(store.media,".copy-${project.id}")
        val destination = File(store.media,project.id)
        fun checkCancelled() { if (cancelled()) throw InterruptedIOException("Copy cancelled") }
        var budget = 64L*1024*1024
        for (file in files) {
            budget = Math.addExact(budget,file.length())
            for (suffix in listOf(".mirror",".primaries")) File(file.path+suffix).takeIf { it.isFile }?.let {
                require(it.length() <= 65536) { "An original color or mirror sidecar is too large" }
                budget = Math.addExact(budget,it.length())
            }
        }
        check(budget <= store.media.usableSpace) { "Not enough space for a separate recovered project" }
        check(!working.exists() && !destination.exists() && working.mkdir()) { "Cannot prepare the recovered project" }
        val marker = File(working,".myrec-copy")
        FileOutputStream(marker).use { it.write(project.id.toByteArray()); it.fd.sync() }
        ProjectAssets.begin(working,destination)
        var attempted = false
        try {
            val available = mutableListOf<Pair<File,Clip>>()
            for ((index,file) in files.withIndex()) {
                checkCancelled(); playable(file)?.let { available.add(file to it) }
                progress((index+1f)/files.size*.1f)
            }
            check(available.isNotEmpty()) { "No playable originals were found. The original files remain available." }
            val local = object : OriginalMedia.Destination {
                override fun create(name: String, type: String): Uri {
                    val file = File(working,name); check(file.createNewFile()) { "A recovered filename already exists" }; return Uri.fromFile(file)
                }
                override fun write(file: Uri): OutputStream = object : FileOutputStream(File(file.path!!)) {
                    override fun close() { try { fd.sync() } finally { super.close() } }
                }
                override fun read(file: Uri): InputStream = File(file.path!!).inputStream()
                override fun remove(file: Uri) { check(File(file.path!!).delete()) }
            }
            val copies = OriginalMedia.copy(available.map { it.first },local,cancelled) { progress(.1f+it*.75f) }
            for ((index,pair) in available.withIndex()) {
                checkCancelled()
                val (source,clip) = pair
                val copied = File(copies[index].path!!)
                for (suffix in listOf(".mirror",".primaries")) {
                    val sidecar = File(source.path+suffix)
                    if (!sidecar.isFile) continue
                    require(sidecar.length() <= 65536) { "An original color or mirror sidecar is too large" }
                    val side = object : OriginalMedia.Destination by local {
                        override fun create(name: String, type: String) = local.create(copied.name+suffix,type)
                    }
                    OriginalMedia.copy(listOf(sidecar),side,cancelled)
                }
                val next = clip.duplicate(true).copy(path = File(destination,copied.name).path,start = project.duration)
                project.clips.add(next)
                check(project.duration <= 604800) { "Recovered footage exceeds the project duration limit" }
                if (next.video && project.clips.count { it.video } == 1) ProjectStore.shape(source.path).let { project.width = it.first; project.height = it.second }
                progress(.85f+(index+1f)/available.size*.1f)
            }
            checkCancelled(); store.publishMedia(working,destination)
            for (clip in project.clips) { checkCancelled(); store.registerMedia(project.id,clip.path) }
            checkCancelled(); attempted = true
            store.save(project,false)
            File(destination,".myrec-copy").delete()
            progress(1f)
            return Result(project,files.size-available.size)
        } finally {
            ProjectAssets.end(working,destination)
            /* After index publication starts, startup recovery must decide what is referenced. */
            if (!attempted) {
                if (File(working,".myrec-copy").isFile) working.deleteRecursively()
                if (File(destination,".myrec-copy").isFile) destination.deleteRecursively()
                File(store.root,"checksums/${project.id}.json").delete()
            }
        }
    }
}
