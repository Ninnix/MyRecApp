package org.myrec.app

import java.io.File
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

object EditorialTimeline {
    fun encode(project: Project, originals: List<Clip> = emptyList()): JSONObject {
        val rate = project.fps.toDouble()
        fun time(frames: Double) = JSONObject().put("OTIO_SCHEMA", "RationalTime.1").put("rate", rate).put("value", frames)
        fun range(start: Double, duration: Double) = JSONObject().put("OTIO_SCHEMA", "TimeRange.1")
            .put("start_time", time(start)).put("duration", time(duration))
        fun node(schema: String, name: String) = JSONObject().put("OTIO_SCHEMA", schema).put("name", name)
            .put("metadata", JSONObject()).put("effects", JSONArray()).put("markers", JSONArray())
            .put("enabled", true).put("source_range", JSONObject.NULL)
        fun edge(seconds: Double) = round(seconds * rate)
        val pictures = project.clips.filter { it.video }.flatMap { it.playbackPieces() }.map { it.duplicate() }.toMutableList()
        val incoming = mutableMapOf<String, JSONObject>()
        val native = mutableSetOf<String>()
        for (join in project.transitions.filter { it.kind == "Dissolve" && it.hold == 0.0 && it.phaseFrom == 0f && it.phaseTo == 1f }) {
            val a = pictures.firstOrNull { it.id == join.leftId } ?: continue
            val b = pictures.firstOrNull { it.id == join.rightId } ?: continue
            if (project.clips.any { (it.id == a.id || it.id == b.id) && (it.pauses != null || it.frozen != null) }) continue
            if (a.track != b.track || a.start >= b.start || a.end <= b.start || b.end <= a.end ||
                abs(a.end - b.start - join.duration) > .001) continue
            val first = edge(b.start)
            val last = edge(a.end)
            val cut = floor((first + last) / 2) / rate
            if (cut <= a.start || cut < b.start || cut >= b.end) continue
            val nextIn = b.sourceTime(cut)
            a.sourceOut = a.sourceTime(cut)
            b.sourceIn = nextIn
            b.start = cut
            incoming[b.id] = JSONObject().put("OTIO_SCHEMA", "Transition.1").put("name", "Dissolve")
                .put("metadata", JSONObject()).put("transition_type", "SMPTE_Dissolve")
                .put("in_offset", time(edge(cut) - first)).put("out_offset", time(last - edge(cut)))
            native.add(join.id)
        }
        fun tracks(items: List<Clip>, kind: String): List<JSONObject> {
            val result = mutableListOf<JSONObject>()
            for ((layer, group) in items.groupBy { it.track }.toSortedMap()) {
                val lanes = mutableListOf<MutableList<Clip>>()
                for (item in group.sortedWith(compareBy<Clip> { it.start }.thenBy { it.id })) {
                    val lane = lanes.firstOrNull { it.last().end <= item.start + .000001 }
                        ?: mutableListOf<Clip>().also { lanes.add(it) }
                    lane.add(item)
                }
                for ((index, lane) in lanes.withIndex()) {
                    val children = JSONArray()
                    var cursor = 0.0
                    fun gap(frames: Double) { children.put(node("Gap.1", "").put("source_range", range(0.0, frames))) }
                    for (clip in lane) {
                        val start = edge(clip.start)
                        val end = edge(clip.end)
                        if (end <= start) continue
                        if (start > cursor) gap(start - cursor)
                        if (kind == "Video") incoming[clip.id]?.let { children.put(it) }
                        val effects = JSONArray()
                        if (clip.frozen != null && kind == "Video")
                            effects.put(JSONObject().put("OTIO_SCHEMA", "FreezeFrame.1").put("name", "Freeze Frame")
                                .put("effect_name", "FreezeFrame").put("metadata", JSONObject()).put("time_scalar", 0))
                        else if (abs(clip.effectiveRate - 1) > .000001)
                            effects.put(JSONObject().put("OTIO_SCHEMA", "LinearTimeWarp.1").put("name", "")
                                .put("effect_name", "LinearTimeWarp").put("metadata", JSONObject()).put("time_scalar", clip.effectiveRate))
                        val duration = (originals + project.clips).filter { it.path == clip.path }.maxOfOrNull { it.sourceOut } ?: clip.sourceOut
                        val reference = JSONObject().put("OTIO_SCHEMA", "ExternalReference.1").put("metadata", JSONObject())
                            .put("name", File(clip.path).name).put("target_url", clip.path)
                            .put("available_range", range(0.0, duration * rate))
                        val metadata = JSONObject().put("MyRec", JSONObject().put("clip_id", clip.id).put("gain_db", clip.gainDb)
                            .put("processing", JSONArray(clip.effects.map { it.kind })))
                        children.put(node("Clip.2", File(clip.path).name).put("metadata", metadata)
                            .put("source_range", range(clip.sourceIn * rate, end - start)).put("effects", effects)
                            .put("enabled", kind != "Audio" || clip.gainDb > -60f)
                            .put("active_media_reference_key", "DEFAULT_MEDIA")
                            .put("media_references", JSONObject().put("DEFAULT_MEDIA", reference)))
                        cursor = end
                    }
                    if (edge(project.duration) > cursor) gap(edge(project.duration) - cursor)
                    result.add(node("Track.1", "$kind ${layer + 1}.${index + 1}").put("kind", kind).put("children", children))
                }
            }
            return result
        }
        fun marker(name: String, start: Double, length: Double, color: String) = JSONObject()
            .put("OTIO_SCHEMA", "Marker.2").put("metadata", JSONObject()).put("name", name).put("color", color)
            .put("marked_range", range(start, length)).put("comment", "")
        val markers = JSONArray()
        for (at in project.markers()) markers.put(marker("Bookmark", edge(at), 0.0, "YELLOW"))
        for (join in project.transitions.filter { it.id !in native })
            markers.put(marker(join.kind, edge(max(0.0, join.begin)), edge(join.end - join.begin), "PURPLE"))
        val stack = node("Stack.1", "tracks").put("markers", markers).put("children", JSONArray(
            tracks(project.clips.filter { it.audio }.flatMap { it.playbackPieces() }.map { c ->
                if (c.frozen == null) c else c.duplicate().apply {
                    sourceIn = c.frozen!!.sourceTime + c.frozen!!.offset
                    sourceOut = sourceIn + c.duration * c.effectiveRate; frozen = null
                }
            }, "Audio") + tracks(pictures, "Video")))
        return JSONObject().put("OTIO_SCHEMA", "Timeline.1").put("name", project.name)
            .put("global_start_time", time(0.0)).put("tracks", stack).put("metadata", JSONObject().put("MyRec",
                "Source conform. MyRec grades, overlays, mixes and custom transitions need recreation in the external editor."))
    }
}
