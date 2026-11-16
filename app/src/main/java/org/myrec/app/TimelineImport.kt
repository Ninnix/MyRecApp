package org.myrec.app

import java.io.*
import java.net.URI
import java.net.URLDecoder
import org.json.*
import kotlin.math.*

object TimelineImport {
    private fun schema(o: JSONObject): String {
        val parts=o.getString("OTIO_SCHEMA").split('.');require(parts.size==2)
        val version=parts[1].toInt();require(version in 1..if (parts[0] in listOf("Clip","Marker")) 2 else 1) { "This timeline needs a newer reader" }
        return parts[0]
    }
    private fun objects(a: JSONArray)=(0 until a.length()).map { a.getJSONObject(it) }
    private fun seconds(time: JSONObject): Double {
        require(schema(time)=="RationalTime")
        val rate=time.getDouble("rate");val value=time.getDouble("value")
        require(rate.isFinite() && rate>0 && value.isFinite());return (value/rate).also { require(it.isFinite() && abs(it)<=604800) }
    }
    private fun range(o: JSONObject): Pair<Double,Double> {
        require(schema(o)=="TimeRange");val start=seconds(o.getJSONObject("start_time"));val duration=seconds(o.getJSONObject("duration"))
        require(start>=0 && duration>0);return start to duration
    }
    private fun unsupported(name: String): Nothing=error("This timeline contains unsupported $name. Its original file has been kept.")

    fun decode(store: ProjectStore,document: JSONObject,sourceDurations: MutableMap<String,Double>?=null,resolve: (String)->String): Project {
        require(schema(document)=="Timeline") { "Not an OpenTimelineIO timeline" }
        document.optJSONObject("metadata")?.optJSONObject("org.myrec.project")?.let {
            val p=store.decode(it);return ProjectAssets.remap(EditSession(p),p.id,p.name,resolve).project
        }
        val result=Project(name=document.optString("name","Imported timeline"))
        document.optJSONObject("global_start_time")?.let { seconds(it);result.fps=it.getDouble("rate").roundToInt().coerceIn(1,240) }
        val stack=document.getJSONObject("tracks");require(schema(stack)=="Stack")
        val available=mutableMapOf<String,Pair<Double,Double>>()
        if (!stack.optBoolean("enabled",true) || stack.optJSONArray("effects")?.length()?.let { it>0 }==true) unsupported("disabled or processed stacks")
        for ((layer,track) in objects(stack.getJSONArray("children")).withIndex()) {
            require(schema(track)=="Track");val video=when (track.getString("kind")) { "Video" -> true;"Audio" -> false;else -> unsupported("track kind") }
            if (!track.optBoolean("enabled",true) || track.optJSONArray("effects")?.length()?.let { it>0 }==true) unsupported("disabled or processed tracks")
            if (track.optJSONObject("source_range")!=null) unsupported("trimmed tracks")
            var cursor=0.0;var previous: Clip?=null;var pending: JSONObject?=null
            for (item in objects(track.getJSONArray("children"))) when (schema(item)) {
                "Gap" -> { require(pending==null);cursor+=range(item.getJSONObject("source_range")).second;previous=null }
                "Transition" -> {
                    require(previous!=null && pending==null)
                    if (item.optString("transition_type")!="SMPTE_Dissolve") unsupported("transition ${item.optString("transition_type")}")
                    pending=item
                }
                "Clip" -> {
                    if (!item.optBoolean("enabled",true)) unsupported("disabled clips")
                    val ref=item.optJSONObject("media_references")?.let { it.getJSONObject(item.optString("active_media_reference_key","DEFAULT_MEDIA")) }
                        ?: item.getJSONObject("media_reference")
                    if (schema(ref)!="ExternalReference") unsupported("generated or missing media references")
                    val (start,duration)=range(item.getJSONObject("source_range"));var speed=1.0;var hold=false
                    for (fx in objects(item.optJSONArray("effects") ?: JSONArray())) when (schema(fx)) {
                        "LinearTimeWarp" -> { require(speed==1.0 && !hold);speed=fx.getDouble("time_scalar");require(speed.isFinite() && speed in .1..128.0) }
                        "FreezeFrame" -> { require(video && speed==1.0 && !hold);hold=true }
                        else -> unsupported("${fx.optString("name","clip")} effects")
                    }
                    if (item.optJSONObject("metadata")?.optJSONObject("MyRec")?.optJSONArray("processing")?.length()?.let { it>0 }==true) unsupported("processing without a MyRec project payload")
                    val path=resolve(ref.getString("target_url"));val c=Clip(path=path,sourceIn=start,sourceOut=start+if (hold) 1.0/30 else duration*speed,
                        start=cursor,track=layer,rate=speed,video=video,audio=!video,photo=video && File(path).extension.lowercase() in listOf("png","jpg","jpeg","heic"),frozen=if (hold) FrozenFrame(start,duration) else null)
                    item.optJSONObject("metadata")?.optJSONObject("MyRec")?.let { c.gainDb=it.optDouble("gain_db",0.0).toFloat() }
                    if (hold) c.effects.add(Effect.create("Freeze Frame"))
                    pending?.let { join ->
                        require(!hold && previous!!.frozen==null)
                        val a=seconds(join.getJSONObject("in_offset"));val b=seconds(join.getJSONObject("out_offset"));require(a>=0 && b>=0 && a+b>0 && c.sourceIn>=a*speed)
                        val before=previous!!;before.sourceOut+=b*before.rate;c.start-=a;c.sourceIn-=a*speed
                        if (video) result.transitions.add(Transition(at=cursor+(b-a)/2,duration=a+b,leftId=before.id,rightId=c.id,overlap=a+b,controls=TransitionControls()))
                        else { before.fadeOut=AudioEdgeFade(a+b);c.fadeIn=AudioEdgeFade(a+b) }
                        pending=null
                    }
                    ref.optJSONObject("available_range")?.let {
                        val r=range(it);available[c.id]=r
                        sourceDurations?.let { durations -> durations[path]=max(durations[path] ?: 0.0,r.first+r.second) }
                    }
                    result.clips.add(c);previous=c;cursor+=duration;require(cursor<=604800 && result.clips.size<=100000)
                }
                else -> unsupported(schema(item))
            }
            require(pending==null) { "A transition has no following clip" }
        }
        for (clip in result.clips) available[clip.id]?.let { (a,b) ->
            require(clip.sourceIn>=a-.001 && clip.sourceOut<=a+b+.001) { "Timeline exceeds its declared source handles" }
        }
        for (mark in objects(stack.optJSONArray("markers") ?: JSONArray())) {
            val start=seconds(mark.getJSONObject("marked_range").getJSONObject("start_time"))
            if (start>=0 && start<=result.duration) result.bookmarks.add(SourceBookmark(time=start,label=mark.optString("name").take(120)))
        }
        return result
    }

    fun restore(store: ProjectStore,document: File,cancelled: ()->Boolean,progress: (Float)->Unit): ProjectArchive.Restored {
        require(document.length() in 1..64L*1024*1024) { "Timeline file is too large" }
        val json=JSONObject(document.readText());val id=uid();val working=File(store.media,".restore-$id");val destination=File(store.media,id)
        check(working.mkdirs());ProjectAssets.begin(working,destination);var published=false
        fun stop() { if (cancelled()) throw InterruptedIOException("Timeline import cancelled") }
        try {
            FileOutputStream(File(working,".myrec-restore")).use { it.write(id.toByteArray());it.fd.sync() }
            val owned=OriginalMedia.files(store);val names=linkedMapOf<String,String>();val copies=linkedMapOf<String,File>();val missing=mutableListOf<String>()
            fun resolve(url: String): String = names.getOrPut(url) {
                stop();require(url.length<=8192 && '\u0000' !in url && '\\' !in url)
                val scheme=runCatching { URI(url).scheme }.getOrNull();require(scheme==null || scheme=="file") { "Network media must be copied locally before importing" }
                val path=URLDecoder.decode(url.removePrefix("file://").replace("+","%2B"),"UTF-8")
                val extension=File(path).extension.lowercase();require(extension in listOf("mp4","mov","m4v","mkv","webm","wav","m4a","aac","flac","mp3","ogg","aif","aiff","png","jpg","jpeg","heic","sfnt","ttf","otf","ttc","otc","cube","bin","json")) { "Unsupported timeline media type" }
                val exact=owned.firstOrNull { it.canonicalFile.path==File(path).canonicalFile.path }
                val source=exact ?: owned.filter { it.name==File(path).name }.singleOrNull()
                val name="${uid()}.$extension"
                if (source!=null) copies[name]=source else missing.add(File(path).name)
                File(destination,name).path
            }
            val durations=mutableMapOf<String,Double>()
            val original=decode(store,json,durations,::resolve)
            val project=original.copy(id=id,captureCount=0)
            val all=ProjectAssets.paths(EditSession(project));require(all.all { it.startsWith(destination.path+File.separator) })
            val originals=mutableListOf<Clip>()
            for ((path,clips) in project.clips.groupBy { it.path }) copies[File(path).name]?.let { source ->
                val probe=ProjectStore.inspect(source)
                if (!probe.photo) require(clips.all { (it.frozen?.sourceTime ?: it.sourceOut)<=probe.sourceOut+.05 }) { "A timeline exceeds the available original media" }
                originals.add(probe.copy(path=path))
            } ?: originals.add(clips.first().duplicate().apply { sourceIn=0.0;sourceOut=max(durations[path] ?: 0.0,clips.maxOf { it.sourceOut });start=0.0;rate=1.0;frozen=null;effects.clear();censors.clear();fadeIn=null;fadeOut=null })
            val total=copies.values.sumOf { it.length() };require(total+64L*1024*1024<=working.usableSpace) { "Not enough storage to import this timeline" };var done=0L
            for ((name,source) in copies) {
                stop();val expected=SourceAudio.hash(source,cancelled);val target=File(working,name)
                FileOutputStream(target).use { out -> source.inputStream().use { input ->
                    val buffer=ByteArray(131072)
                    while (true) { stop();val n=input.read(buffer);if (n<0) break;out.write(buffer,0,n);done+=n;progress(done.toFloat()/total.coerceAtLeast(1)) }
                };out.fd.sync() }
                require(SourceAudio.hash(target,cancelled)==expected) { "A timeline source changed during import" }
            }
            FileOutputStream(File(working,".timeline.otio")).use { out -> document.inputStream().use { it.copyTo(out) };out.fd.sync() }
            stop();store.publishMedia(working,destination);store.save(EditSession(project,originals=originals),false)
            published=true;File(destination,".myrec-restore").delete()
            if (missing.isNotEmpty()) store.problems[id]="Imported ${project.name} with missing originals. Use the source repair controls to locate them."
            return ProjectArchive.Restored(project,missing)
        } finally {
            ProjectAssets.end(working,destination)
            if (!published && !File(store.root,"$id.json").exists()) {
                if (File(working,".myrec-restore").isFile) working.deleteRecursively()
                if (File(destination,".myrec-restore").isFile) destination.deleteRecursively()
            }
        }
    }
}
