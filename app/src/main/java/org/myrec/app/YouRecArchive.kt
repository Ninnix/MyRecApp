package org.myrec.app

import java.io.*
import java.security.MessageDigest
import java.util.zip.*
import org.json.*

object YouRecArchive {
    private const val LIMIT=256L*1024*1024
    private fun digest(data: ByteArray)=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun integer(o: JSONObject,key: String): Int {
        val n=o.get(key);require(n is Number && n.toDouble().isFinite() && n.toDouble()==n.toInt().toDouble()) { "Invalid archive $key" };return n.toInt()
    }
    private fun stop(cancelled: () -> Boolean) { if (cancelled()) throw InterruptedIOException("Project transfer cancelled") }
    private fun flat(name: String)=name.isNotEmpty() && name.length<=255 && !name.startsWith('.') && name.none { it=='/' || it=='\\' || it=='\u0000' }
    private fun recovery(name: String): Boolean {
        val parts=name.split('/');if (parts.size!=3 || parts[0]!="Recovery" || !flat(parts[2])) return false
        if (!parts[1].matches(Regex("[0-9a-fA-F]{64}")) && runCatching { java.util.UUID.fromString(parts[1]) }.isFailure) return false
        return File(parts[2]).extension.lowercase() in listOf("mov","mp4","m4v","m4a","caf","wav","aac","flac","mp3","aif","aiff") || parts[2].endsWith(".events.json") || parts[2].endsWith(".effects.json")
    }
    private fun read(zip: ZipFile,name: String,limit: Long): ByteArray {
        val entry=zip.getEntry(name) ?: error("Missing archive metadata: $name")
        require(entry.size in 1..limit && entry.size<=Int.MAX_VALUE)
        return zip.getInputStream(entry).use { input -> ByteArray(entry.size.toInt()).also { data ->
            DataInputStream(input).readFully(data);require(input.read()==-1)
        } }
    }
    fun native(file: File): Boolean=ZipFile(file).use { zip ->
        val project=zip.getEntry("project.json") ?: return@use false
        require(project.size in 1..LIMIT)
        JSONObject(String(read(zip,"project.json",LIMIT),Charsets.UTF_8)).has("schemaVersion")
    }
    fun export(store: ProjectStore,project: Project,target: File,cancelled: () -> Boolean,progress: (Float)->Unit) {
        check(!target.exists()) { "Choose a new archive filename" }
        val saved=store.open(project.id);val paths=ProjectAssets.paths(saved)
        require(saved.undo.size+saved.redo.size+1<=100000) { "This project exceeds the compatible archive history limit" }
        val sourceClips=linkedMapOf<String,Clip>()
        for (clip in ProjectAssets.clips(saved)) sourceClips[clip.path]?.let {
            if (clip.sourceOut>it.sourceOut) it.sourceOut=clip.sourceOut
            it.video=it.video || clip.video;it.audio=it.audio || clip.audio
            it.marks.addAll(clip.marks.filter { time -> time !in it.marks })
            it.bookmarks.addAll(clip.bookmarks.filter { mark -> it.bookmarks.none { old -> old.id==mark.id } })
        }
            ?: run { sourceClips[clip.path]=clip.duplicate() }
        val effectPaths=ProjectAssets.clips(saved).flatMap { it.effects.asSequence() }.flatMap { it.assetPaths().asSequence() }.toSet()
        require(sourceClips.keys.none { it in effectPaths }) { "This project uses the same file as a take and an effect asset. Use a MyRec ZIP to preserve both roles." }
        val files=linkedMapOf<String,File>();val names=linkedMapOf<String,String>();val hashes=JSONObject()
        for ((index,path) in paths.withIndex()) {
            stop(cancelled);val file=File(path);check(file.isFile) { "An archived original or asset is missing" }
            val hash=SourceAudio.hash(file,cancelled);val expected=store.trustedHash(project.id,path)
            check(expected==null || expected==hash) { "An archived source has changed" }
            val prefix=if (path in sourceClips) "source" else when (file.extension.lowercase()) { "ttf","otf","ttc","otc" -> "font";"png","jpg","jpeg","heic" -> "image";else -> "effect" }
            val name=if (prefix=="font") "font-$hash.sfnt" else "$prefix-$hash-${index.toString(16)}.${file.extension.ifEmpty { "media" }}"
            require(flat(name));files[name]=file;names[path]=name;hashes.put(name,hash)
            if (SourceMirror.enabled(path) || File(path+".primaries").takeIf { it.isFile }?.readText()?.trim()?.let { it!="0" }==true)
                error("YouRec interchange needs this source's camera color or mirror interpretation applied first. Its original has been kept.")
        }
        for (file in OriginalMedia.files(store,project.id).filter { it.path !in paths }) {
            val name="Recovery/${YouRecDocument.uuid(file.path)}/${file.name}"
            if (!recovery(name)) continue
            stop(cancelled);files[name]=file;hashes.put(name,SourceAudio.hash(file,cancelled))
        }
        val portable=ProjectAssets.remap(saved,saved.project.id,saved.project.name) { names.getValue(it) }
        val sourceIds=sourceClips.keys.associate { names.getValue(it) to YouRecDocument.uuid("source:${names.getValue(it)}") }
        val sources=JSONArray(sourceClips.map { (path,clip) -> JSONObject().put("id",sourceIds.getValue(names.getValue(path))).put("filename",names.getValue(path))
            .put("duration",clip.sourceOut).put("recordedAt",0).put("sha256",hashes.getString(names.getValue(path))).put("audioOnly",!clip.video).put("isStillPhoto",clip.photo).put("originalName",File(path).name.take(255))
            .put("events",JSONArray((clip.bookmarks+clip.marks.map(::recordingBookmark)).filter { !it.local }.distinctBy { it.id }.map { JSONObject().put("id",YouRecDocument.uuid(it.id)).put("time",it.time).put("kind","bookmark").put("detail",it.label) })) })
        ArchiveMetadata(target.parentFile!!,LIMIT).use { metadata ->
            val snapshots=JSONArray();var metadataBytes=0L
            fun snapshot(p: Project) {
                stop(cancelled)
                val materialized=Editor(p).apply { load(p,portable.copy(project=p)) }.project
                val data=YouRecDocument.snapshot(materialized,sourceIds,portable.captures.size).put("myrec",store.encode(p)).toString().toByteArray()
                require(data.size<=32000000) { "An editing state exceeds the YouRec archive limit" }
                metadataBytes+=data.size;require(metadataBytes<=LIMIT) { "Editing history exceeds the archive limit" }
                val name=digest(data)+".json";metadata["History/$name"]=data;snapshots.put(name)
            }
            portable.undo.forEach { snapshot(it.project) };snapshot(portable.project);portable.redo.asReversed().forEach { snapshot(it.project) }
            fun clips(items: List<Clip>)=store.encode(Project(clips=items.toMutableList())).getJSONArray("clips")
            val header=JSONObject().put("schemaVersion",12).put("id",YouRecDocument.uuid(project.id)).put("title",project.name).put("createdAt",0).put("updatedAt",0)
                .put("sources",sources).put("historyCursor",portable.undo.size).put("snapshotFiles",snapshots)
                .put("captureLedger",JSONArray(portable.captures.map { JSONObject().put("clip",YouRecDocument.capture(it,sourceIds.getValue(it.path))).put("insertion",false) }))
                .put("recordingHistoryVersion",1).put("automaticCanvasVersion",1)
                .put("exportSettings",JSONObject().put("codec","H.264").put("resolution","Project size").put("frameRate",project.fps))
                .put("myrecSession",JSONObject().put("version",1).put("captures",clips(portable.captures)).put("originals",clips(portable.originals)))
            val headerData=header.toString().toByteArray();metadata["project.json"]=headerData
            require(headerData.size<=32000000) { "The YouRec archive index is too large" }
            val receipt=JSONObject().put("version",if (files.keys.any(::recovery)) 2 else 1).put("sha256",hashes).toString().toByteArray()
            require(receipt.size<=20000000) { "The archive integrity index is too large" };metadata["integrity.json"]=receipt
            metadata["Timeline.otio"]=EditorialTimeline.encode(portable.project,portable.originals,store.encode(portable.project)).toString().toByteArray()
            require(metadata.count+files.size<=100000) { "Too many archive entries" }
            val partial=File(target.parentFile,".yourec-${uid()}.partial");val total=files.values.sumOf { it.length() }.coerceAtLeast(1);var written=0L
            try {
                FileOutputStream(partial).use { raw -> ZipOutputStream(raw).use { zip ->
                    for ((name,staged) in metadata) {
                        stop(cancelled);zip.putNextEntry(ZipEntry(name));val sum=MessageDigest.getInstance("SHA-256")
                        staged.file.inputStream().use { input ->
                            val buffer=ByteArray(131072)
                            while (true) { stop(cancelled);val n=input.read(buffer);if (n<0) break;zip.write(buffer,0,n);sum.update(buffer,0,n) }
                        }
                        check(sum.digest().joinToString("") { "%02x".format(it) }==staged.hash) { "Staged archive metadata changed" }
                        zip.closeEntry()
                    }
                    for ((name,file) in files) {
                        stop(cancelled);zip.putNextEntry(ZipEntry(name));val sum=MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { input ->
                            val buffer=ByteArray(131072)
                            while (true) { stop(cancelled);val n=input.read(buffer);if (n<0) break;zip.write(buffer,0,n);sum.update(buffer,0,n);written+=n;progress(written.toFloat()/total) }
                        }
                        check(sum.digest().joinToString("") { "%02x".format(it) }==hashes.getString(name)) { "A source changed during archive export" }
                        zip.closeEntry()
                    }
                    zip.finish();raw.fd.sync()
                } }
                stop(cancelled);check(partial.renameTo(target)) { "Cannot publish YouRec archive" }
            } finally { partial.delete() }
        }
    }
    fun restore(store: ProjectStore,archive: File,cancelled: () -> Boolean,progress: (Float)->Unit): ProjectArchive.Restored {
        val id=uid();val working=File(store.media,".restore-$id");val destination=File(store.media,id)
        check(working.mkdirs());ProjectAssets.begin(working,destination);var published=false
        try {
            FileOutputStream(File(working,".myrec-restore")).use { it.write(id.toByteArray());it.fd.sync() }
            return ZipFile(archive).use { zip ->
                val entries=zip.entries().asSequence().toList();require(entries.size<=100000)
                require(entries.map { it.name.lowercase() }.distinct().size==entries.size && entries.none { it.isDirectory })
                val header=JSONObject(String(read(zip,"project.json",LIMIT),Charsets.UTF_8))
                require(integer(header,"schemaVersion") in 1..12) { "This YouRec project needs a newer reader" }
                require(YouRecDocument.uuid(header.getString("id"))==header.getString("id").lowercase())
                val declared=(0 until header.getJSONArray("sources").length()).map { header.getJSONArray("sources").getJSONObject(it) }
                require(declared.map { it.getString("id") }.distinct().size==declared.size && declared.map { it.getString("filename").lowercase() }.distinct().size==declared.size)
                val integrity=JSONObject(String(read(zip,"integrity.json",20000000),Charsets.UTF_8));require(integer(integrity,"version") in 1..2)
                val hashes=integrity.getJSONObject("sha256");val media=hashes.keys().asSequence().toList()
                require(media.all { flat(it) || integrity.getInt("version")==2 && recovery(it) }) { "Invalid archived media path" }
                require(media.map { it.lowercase() }.distinct().size==media.size && media.all { hashes.getString(it).matches(Regex("[0-9a-f]{64}")) })
                require(declared.all { flat(it.getString("filename")) && it.getString("filename") in media && (!it.has("sha256") || it.getString("sha256")==hashes.getString(it.getString("filename"))) })
                val paths=media.associateWith { File(destination,it).path }
                val names=header.optJSONArray("snapshotFiles");val inline=header.optJSONArray("snapshots")
                val count=names?.length() ?: inline?.length() ?: error("Unsupported legacy YouRec history")
                val cursor=integer(header,"historyCursor");require(count in 1..100000 && cursor in 0 until count)
                var bytes=0L
                fun state(index: Int): Project {
                    stop(cancelled)
                    val snapshot=if (names!=null) {
                        val name=names.getString(index);require(name.matches(Regex("[0-9a-f]{64}\\.json")))
                        val data=read(zip,"History/$name",32000000);bytes+=data.size
                        require(digest(data)+".json"==name) { "Damaged YouRec editing history" }
                        JSONObject(String(data,Charsets.UTF_8))
                    } else inline!!.getJSONObject(index)
                    return snapshot.optJSONObject("myrec")?.let { store.decode(it).also { p -> require(YouRecDocument.uuid(p.id)==header.getString("id")) } }
                        ?: YouRecDocument.project(YouRecDocument.materialized(snapshot,header),header,media.associateWith { it })
                }
                val current=state(cursor);val originalId=current.id;val counts=IntArray(count)
                for (j in 0 until count) {
                    val p=if (j==cursor) current else state(j);require(p.id==originalId)
                    counts[j]=p.captureCount
                    require(ProjectAssets.paths(EditSession(p)).all { it in paths }) { "An archived edit points outside its media" }
                }
                require(bytes<=LIMIT) { "Editing history exceeds the archive limit" }
                val allowed=media.toSet()+setOf("project.json","integrity.json","Timeline.otio")+(if (names!=null) (0 until names.length()).map { "History/${names.getString(it)}" } else emptyList())
                require(entries.all { it.name in allowed || flat(it.name) && it.name.endsWith(".otio") }) { "Unexpected YouRec archive entry" }
                fun clips(key: String): List<Clip> {
                    val session=header.optJSONObject("myrecSession") ?: return emptyList()
                    require(session.getInt("version")==1)
                    return store.decode(JSONObject().put("id",originalId).put("name",current.name).put("clips",session.getJSONArray(key))).clips
                }
                val undo=(0 until cursor).map { index -> EditRevision.deferred(originalId,counts[index]) { state(index) } }
                val redo=(cursor+1 until count).reversed().map { index -> EditRevision.deferred(originalId,counts[index]) { state(index) } }
                val captured=clips("captures")
                val originals=clips("originals").ifEmpty { YouRecDocument.originals(header,media.associateWith { it }) }
                val saved=EditSession(current,undo,redo,captured,originals)
                val allPaths=ProjectAssets.paths(saved);require(allPaths.all { it in paths })
                val budget=(working.usableSpace-64L*1024*1024).coerceAtLeast(0);var total=0L
                for (name in media) { val entry=zip.getEntry(name);if (entry!=null) { require(entry.size>=0 && entry.size<=budget-total);total+=entry.size } }
                val missing=mutableListOf<String>();var done=0L
                for (name in media) {
                    stop(cancelled);val entry=zip.getEntry(name)
                    if (entry==null) { missing.add(name);continue }
                    val output=File(working,name);check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
                    val sum=MessageDigest.getInstance("SHA-256");var size=0L
                    zip.getInputStream(entry).use { input -> FileOutputStream(output).use { out ->
                        val buffer=ByteArray(131072)
                        while (true) { stop(cancelled);val n=input.read(buffer);if (n<0) break;size+=n;require(size<=entry.size);out.write(buffer,0,n);sum.update(buffer,0,n);done+=n;progress(done.toFloat()/total.coerceAtLeast(1)) }
                        out.fd.sync()
                    } }
                    require(size==entry.size && sum.digest().joinToString("") { "%02x".format(it) }==hashes.getString(name)) { "Damaged YouRec original: $name" }
                }
                store.publishMedia(working,destination)
                val restored=ProjectAssets.remap(saved,id,current.name) { paths.getValue(it) }
                store.save(restored,false);published=true;File(destination,".myrec-restore").delete()
                if (missing.isNotEmpty()) store.problems[id]="Restored ${current.name} with missing media. Its complete edit was retained."
                ProjectArchive.Restored(restored.project,missing)
            }
        } finally {
            ProjectAssets.end(working,destination)
            if (!published && !File(store.root,"$id.json").exists()) {
                if (File(working,".myrec-restore").isFile) working.deleteRecursively()
                if (File(destination,".myrec-restore").isFile) destination.deleteRecursively()
            }
        }
    }
}
