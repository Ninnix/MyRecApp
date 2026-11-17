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
        require(start>=0 && duration>0 && start+duration<=604800);return start to duration
    }
    private fun unsupported(name: String): Nothing=error("This timeline contains unsupported $name. Its original file has been kept.")

    fun decode(store: ProjectStore,document: JSONObject,sourceDurations: MutableMap<String,Double>?=null,resolve: (String)->String): Project {
        require(schema(document)=="Timeline") { "Not an OpenTimelineIO timeline" }
        document.optJSONObject("metadata")?.optJSONObject("org.myrec.project")?.let {
            val p=store.decode(it);return ProjectAssets.remap(EditSession(p),p.id,p.name,resolve).project
        }
        val result=Project(name=document.optString("name","Imported timeline"))
        document.optJSONObject("global_start_time")?.let { seconds(it);result.fps=it.getDouble("rate").roundToInt().coerceIn(1,240) }
        val available=mutableMapOf<String,Pair<Double,Double>>()
        data class Part(val project: Project=Project(),val duration: Double=0.0,val lanes: Int=1)
        var nodes=0
        fun markers(o: JSONObject,p: Project,offset: Double=0.0,rate: Double=1.0) {
            for (mark in objects(o.optJSONArray("markers") ?: JSONArray())) {
                require(schema(mark)=="Marker")
                val start=(seconds(mark.getJSONObject("marked_range").getJSONObject("start_time"))-offset)/rate
                if (start>=0 && start<=604800) p.bookmarks.add(SourceBookmark(time=start,label=mark.optString("name").take(120)))
            }
        }
        fun trimmed(part: Part,start: Double,duration: Double): Part {
            require(start+duration<=part.duration+.00001) { "A composition trim exceeds its children" }
            val end=start+duration;val next=part.project.copy(clips=mutableListOf(),transitions=mutableListOf(),bookmarks=mutableListOf())
            for (clip in part.project.clips) {
                val a=max(start,clip.start);val b=min(end,clip.end);if (b<=a) continue
                val c=clip.duplicate();val local=a-clip.start
                c.frozen=clip.frozen?.let { it.copy(duration=b-a,offset=it.offset+local) }
                if (c.frozen==null) { c.sourceIn=clip.sourceTime(a);c.sourceOut=clip.sourceTime(b) }
                c.sliceAudio(local,b-clip.start,clip.duration)
                val effects=c.effects.mapNotNull { fx ->
                    val lo=max(0.0,fx.from-local);val hi=min(b-a,fx.to-local)
                    if (hi<=lo) null else fx.copy(from=lo,to=hi)
                };c.effects.clear();c.effects.addAll(effects);c.start=a-start;next.clips.add(c)
            }
            next.transitions.addAll(part.project.transitions.mapNotNull { it.slice(start,end)?.apply { at-=start } })
            next.bookmarks.addAll(part.project.bookmarks.filter { it.time>=start && it.time<=end }.map { it.copy(time=it.time-start) })
            return Part(next,duration,part.lanes)
        }
        fun read(o: JSONObject,kind: String,depth: Int): Part {
            require(depth<=32 && ++nodes<=100000) { "Timeline nesting or item count exceeds the import limit" }
            if (!o.optBoolean("enabled",true)) unsupported("disabled timeline items")
            val type=schema(o)
            if (type=="Gap") {
                if (o.optJSONArray("effects")?.length()?.let { it>0 }==true) unsupported("processed gaps")
                val (start,duration)=range(o.getJSONObject("source_range"));val p=Project();markers(o,p,start)
                return Part(p,duration)
            }
            if (type=="Clip") {
                val video=when (kind) { "Video" -> true;"Audio" -> false;else -> unsupported("track kind") }
                val ref=o.optJSONObject("media_references")?.let { it.getJSONObject(o.optString("active_media_reference_key","DEFAULT_MEDIA")) } ?: o.getJSONObject("media_reference")
                if (schema(ref)!="ExternalReference") unsupported("generated or missing media references")
                val (start,duration)=range(o.getJSONObject("source_range"));var speed=1.0;var hold=false
                for (fx in objects(o.optJSONArray("effects") ?: JSONArray())) when (schema(fx)) {
                    "LinearTimeWarp" -> { require(speed==1.0 && !hold);speed=fx.getDouble("time_scalar");require(speed.isFinite() && speed in .1..16.0) }
                    "FreezeFrame" -> { require(video && speed==1.0 && !hold && duration<=10.0);hold=true }
                    else -> unsupported("${fx.optString("name","clip")} effects")
                }
                if (o.optJSONObject("metadata")?.optJSONObject("MyRec")?.optJSONArray("processing")?.length()?.let { it>0 }==true) unsupported("processing without a MyRec project payload")
                val path=resolve(ref.getString("target_url"));val c=Clip(path=path,sourceIn=start,sourceOut=start+if (hold) 1.0/result.fps else duration*speed,
                    rate=speed,video=video,audio=!video,photo=video && File(path).extension.lowercase() in listOf("png","jpg","jpeg","heic"),frozen=if (hold) FrozenFrame(start,duration) else null)
                require(c.sourceOut<=604800) { "Timeline source time exceeds the import limit" }
                o.optJSONObject("metadata")?.optJSONObject("MyRec")?.let { c.gainDb=it.optDouble("gain_db",0.0).toFloat() }
                if (hold) c.effects.add(Effect(kind="Freeze Frame",to=duration,values=mutableMapOf("hold" to duration.toString(),"audio" to "Cut","style" to "Clean")))
                ref.optJSONObject("available_range")?.let {
                    val r=range(it);available[c.id]=r
                    sourceDurations?.let { durations -> durations[path]=max(durations[path] ?: 0.0,r.first+r.second) }
                }
                val p=Project(clips=mutableListOf(c));markers(o,p,start,speed)
                return Part(p,duration)
            }
            if (type !in listOf("Stack","Track")) unsupported(type)
            if (o.optJSONArray("effects")?.length()?.let { it>0 }==true) unsupported("processed compositions")
            val parallel=type=="Stack";val childKind=if (parallel) kind else o.getString("kind")
            require(childKind in listOf("Video","Audio")) { "Unknown timeline track kind" }
            val p=Project();var cursor=0.0;var lanes=0;var previous: Part?=null;var pending: JSONObject?=null
            for (child in objects(o.getJSONArray("children"))) {
                if (schema(child)=="Transition") {
                    require(!parallel && previous!=null && pending==null)
                    if (child.optString("transition_type")!="SMPTE_Dissolve") unsupported("transition ${child.optString("transition_type")}")
                    pending=child;continue
                }
                val part=read(child,childKind,depth+1)
                val offset=if (parallel) 0.0 else cursor;val layer=if (parallel) lanes else 0
                for (c in part.project.clips) { c.start+=offset;c.track+=layer }
                for (t in part.project.transitions) t.at+=offset
                for (j in part.project.bookmarks.indices) part.project.bookmarks[j]=part.project.bookmarks[j].let { it.copy(time=it.time+offset) }
                pending?.let { join ->
                    val a=seconds(join.getJSONObject("in_offset"));val b=seconds(join.getJSONObject("out_offset"));require(a>=0 && b>=0 && a+b>0)
                    val left=previous!!.project.clips.filter { abs(it.end-cursor)<.00001 };val right=part.project.clips.filter { abs(it.start-cursor)<.00001 }
                    if (left.size!=1 || right.size!=1 || previous!!.project.transitions.isNotEmpty() || part.project.transitions.isNotEmpty()) unsupported("dissolves between layered compositions")
                    val before=left.single();val after=right.single();require(before.frozen==null && after.frozen==null && before.video==after.video && after.sourceIn>=a*after.rate)
                    before.sourceOut+=b*before.rate;after.start-=a;after.sourceIn-=a*after.rate
                    if (before.video) p.transitions.add(Transition(at=cursor+(b-a)/2,duration=a+b,leftId=before.id,rightId=after.id,overlap=a+b,controls=TransitionControls()))
                    else { before.fadeOut=AudioEdgeFade(a+b);after.fadeIn=AudioEdgeFade(a+b) }
                    pending=null
                }
                p.clips.addAll(part.project.clips);p.transitions.addAll(part.project.transitions);p.bookmarks.addAll(part.project.bookmarks)
                cursor=if (parallel) max(cursor,part.duration) else cursor+part.duration
                lanes=if (parallel) lanes+part.lanes else max(lanes,part.lanes)
                previous=if (schema(child)=="Gap") null else part
                require(cursor<=604800 && p.clips.size<=100000 && lanes<=100000)
            }
            require(pending==null) { "A transition has no following clip" }
            markers(o,p)
            val part=Part(p,cursor,max(1,lanes))
            return o.optJSONObject("source_range")?.let { val (start,duration)=range(it);trimmed(part,start,duration) } ?: part
        }
        val stack=document.getJSONObject("tracks");require(schema(stack)=="Stack")
        val part=read(stack,"Video",0)
        result.clips.addAll(part.project.clips);result.transitions.addAll(part.project.transitions)
        result.bookmarks.addAll(part.project.bookmarks.filter { it.time<=part.duration })
        require(abs(result.duration-part.duration)<.00001 || part.duration==0.0) { "Trailing empty timeline time needs an explicit media item before importing" }
        for (clip in result.clips) available[clip.id]?.let { (a,b) ->
            require(clip.sourceIn>=a-.001 && (clip.frozen?.sourceTime ?: clip.sourceOut)<=a+b+.001) { "Timeline exceeds its declared source handles" }
        }
        return result
    }

    data class Media(val size: Long,val open: ()->InputStream,val unchanged: ()->Boolean={ true },val file: File?=null)
    internal fun lookupPath(path: String): String {
        val parts=mutableListOf<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(part)
        }
        require(parts.isNotEmpty()) { "A timeline media path has no filename" }
        return (if (path.startsWith('/')) "/" else "")+parts.joinToString("/")
    }
    fun mediaPath(url: String): String {
        require(url.length<=8192 && '\u0000' !in url && '\\' !in url)
        val uri=URI(url.replace(" ","%20"));require(uri.scheme==null || uri.scheme.equals("file",true)) { "Network media must be copied locally before importing" }
        require(!uri.isOpaque && uri.rawPath!=null && uri.rawQuery==null && uri.rawFragment==null && (uri.host==null || uri.host.equals("localhost",true))) { "Unsupported timeline media URL" }
        val path=URLDecoder.decode(uri.rawPath.replace("+","%2B"),"UTF-8")
        require(path.isNotEmpty() && '\u0000' !in path && '\\' !in path) { "Invalid timeline media path" }
        return lookupPath(path)
    }
    fun restore(store: ProjectStore,document: File,cancelled: ()->Boolean,progress: (Float)->Unit,external: (String)->Media? = { null }): ProjectArchive.Restored {
        require(document.length() in 1..64L*1024*1024) { "Timeline file is too large" }
        val json=JSONObject(document.readText());val id=uid();val working=File(store.media,".restore-$id");val destination=File(store.media,id)
        check(working.mkdirs());ProjectAssets.begin(working,destination);var published=false
        fun stop() { if (cancelled()) throw InterruptedIOException("Timeline import cancelled") }
        try {
            FileOutputStream(File(working,".myrec-restore")).use { it.write(id.toByteArray());it.fd.sync() }
            val owned=OriginalMedia.files(store);val names=linkedMapOf<String,String>();val copies=linkedMapOf<String,Media>();val missing=mutableListOf<String>()
            fun resolve(url: String): String {
                val path=mediaPath(url)
                return names.getOrPut(path) {
                stop()
                val extension=File(path).extension.lowercase();require(extension in listOf("mp4","mov","m4v","mkv","webm","wav","m4a","aac","flac","mp3","ogg","aif","aiff","png","jpg","jpeg","heic","sfnt","ttf","otf","ttc","otc","cube","bin","json")) { "Unsupported timeline media type" }
                val exact=owned.firstOrNull { it.canonicalFile.path==File(path).canonicalFile.path }
                val source=exact ?: owned.filter { it.name==File(path).name }.singleOrNull()
                val name="${uid()}.$extension"
                val media=external(path) ?: source?.let { Media(it.length(),{ it.inputStream() },file=it) }
                if (media!=null) copies[name]=media else missing.add(File(path).name)
                File(destination,name).path
                }
            }
            val durations=mutableMapOf<String,Double>()
            val original=decode(store,json,durations,::resolve)
            val project=original.copy(id=id,captureCount=0)
            val all=ProjectAssets.paths(EditSession(project));require(all.all { it.startsWith(destination.path+File.separator) })
            val total=copies.values.sumOf { max(0L,it.size) };require(total<=working.usableSpace-64L*1024*1024) { "Not enough storage to import this timeline" };var done=0L
            for ((name,source) in copies) {
                stop();require(source.size>=-1 && source.unchanged()) { "A timeline source changed before import" }
                val expected=source.file?.let { SourceAudio.hash(it,cancelled) };val target=File(working,name)
                val sum=java.security.MessageDigest.getInstance("SHA-256");var size=0L
                FileOutputStream(target).use { out -> source.open().use { input ->
                    val buffer=ByteArray(131072)
                    while (true) {
                        stop();val n=input.read(buffer);if (n<0) break
                        require(working.usableSpace-n>=64L*1024*1024) { "Not enough storage to import this timeline" }
                        size+=n;require(source.size<0 || size<=source.size) { "A timeline source changed during import" }
                        out.write(buffer,0,n);sum.update(buffer,0,n);done+=n;progress((done.toDouble()/total.coerceAtLeast(1)).toFloat().coerceIn(0f,1f))
                    }
                };out.fd.sync() }
                val hash=sum.digest().joinToString("") { "%02x".format(it) }
                require((source.size<0 || size==source.size) && source.unchanged() && (expected==null || expected==hash) &&
                    SourceAudio.hash(target,cancelled)==hash) { "A timeline source changed during import" }
            }
            val originals=mutableListOf<Clip>()
            for ((path,clips) in project.clips.groupBy { it.path }) File(working,File(path).name).takeIf { it.isFile }?.let { source ->
                val probe=ProjectStore.inspect(source)
                if (!probe.photo) require(clips.all { (it.frozen?.sourceTime ?: it.sourceOut)<=probe.sourceOut+.05 }) { "A timeline exceeds the available original media" }
                originals.add(probe.copy(path=path))
            } ?: originals.add(clips.first().duplicate().apply { sourceIn=0.0;sourceOut=max(durations[path] ?: 0.0,clips.maxOf { it.sourceOut });start=0.0;rate=1.0;frozen=null;effects.clear();censors.clear();fadeIn=null;fadeOut=null })
            FileOutputStream(File(working,".timeline.otio")).use { out -> document.inputStream().use { it.copyTo(out) };out.fd.sync() }
            stop();store.publishMedia(working,destination);store.save(EditSession(project,originals=originals),false)
            published=true;File(destination,".myrec-restore").delete()
            if (missing.isNotEmpty()) store.problems[id]="Imported ${project.name} with missing originals. Import again with the folder containing its media to resolve them."
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
