package org.yourec.app

import android.media.MediaMetadataRetriever
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class ProjectStore(private val root: File) {
    init {
        root.mkdirs()
    }

    val media = File(root, "media").apply { mkdirs() }

    fun save(p: Project, makeCurrent: Boolean = true) {
        val target = File(root, p.id + ".json")
        val temp = File(root, p.id + ".tmp")
        temp.writeText(encode(p).toString())
        check(temp.renameTo(target)) { "Cannot save project" }
        if (makeCurrent) File(root, "current").writeText(p.id)
    }

    fun delete(project: Project) = File(root, project.id + ".json").delete()

    fun current(): Project =
        try {
            val id = File(root, "current").readText().trim()
            decode(JSONObject(File(root, "$id.json").readText()))
        } catch (_: Exception) {
            Project()
        }

    fun list(): List<Project> =
        root
            .listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull {
                try {
                    decode(JSONObject(it.readText()))
                } catch (_: Exception) {
                    null
                }
            } ?: emptyList()

    fun encode(p: Project): JSONObject =
        JSONObject().apply {
            put("version", 1)
            put("id", p.id)
            put("name", p.name)
            put("width", p.width)
            put("height", p.height)
            put("fps", p.fps)
            put(
                "clips",
                JSONArray().apply {
                    p.clips.forEach { c ->
                        put(
                            JSONObject().apply {
                                put("id", c.id)
                                put("group", c.group)
                                put("path", c.path)
                                put("in", c.sourceIn)
                                put("out", c.sourceOut)
                                put("start", c.start)
                                put("track", c.track)
                                put("rate", c.rate)
                                put("video", c.video)
                                put("audio", c.audio)
                                put("linked", c.linked)
                                put("gain", c.gainDb)
                                put(
                                    "effects",
                                    JSONArray().apply {
                                        c.effects.forEach { e ->
                                            put(
                                                JSONObject().apply {
                                                    put("id", e.id)
                                                    put("kind", e.kind)
                                                    put("from", e.from)
                                                    put("to", e.to)
                                                    put("enabled", e.enabled)
                                                    put("values", JSONObject(e.values.toMap()))
                                                }
                                            )
                                        }
                                    },
                                )
                            }
                        )
                    }
                },
            )
            put(
                "transitions",
                JSONArray().apply {
                    p.transitions.forEach { t ->
                        put(
                            JSONObject().apply {
                                put("id", t.id)
                                put("at", t.at)
                                put("kind", t.kind)
                                put("duration", t.duration)
                                put("amount", t.amount)
                                put("hold", t.hold)
                                put("leftId", t.leftId)
                                put("rightId", t.rightId)
                                put("overlap", t.overlap)
                                put("angle", t.angle)
                                put("soft", t.soft)
                                put("phaseFrom", t.phaseFrom)
                                put("phaseTo", t.phaseTo)
                            }
                        )
                    }
                },
            )
        }

    fun decode(o: JSONObject): Project {
        val p =
            Project(
                o.getString("id"),
                o.getString("name"),
                width = o.optInt("width", 1920),
                height = o.optInt("height", 1080),
                fps = o.optInt("fps", 30),
            )
        val clips = o.getJSONArray("clips")
        for (i in 0 until clips.length()) {
            val c = clips.getJSONObject(i)
            val clip =
                Clip(
                    c.getString("id"),
                    c.getString("path"),
                    c.getDouble("in"),
                    c.getDouble("out"),
                    c.getDouble("start"),
                    c.getInt("track"),
                    c.getDouble("rate"),
                    c.getBoolean("video"),
                    c.getBoolean("audio"),
                    c.getBoolean("linked"),
                    c.getDouble("gain").toFloat(),
                )
            val effects = c.getJSONArray("effects")
            clip.group = c.optString("group", clip.id)
            for (j in 0 until effects.length()) {
                val e = effects.getJSONObject(j)
                val values = e.getJSONObject("values")
                val fx =
                    Effect(
                        e.getString("id"),
                        e.getString("kind"),
                        e.getDouble("from"),
                        e.getDouble("to"),
                        e.getBoolean("enabled"),
                    )
                values.keys().forEach { fx.values[it] = values.getString(it) }
                clip.effects.add(fx)
            }
            p.clips.add(clip)
        }
        val transitions = o.optJSONArray("transitions") ?: JSONArray()
        for (i in 0 until transitions.length()) {
            val t = transitions.getJSONObject(i)
            p.transitions.add(
                Transition(
                    t.getString("id"),
                    t.getDouble("at"),
                    t.getString("kind"),
                    t.getDouble("duration"),
                    t.getDouble("amount").toFloat(),
                    t.optDouble("hold", 0.0),
                    t.optString("leftId").takeIf { it.isNotEmpty() && it != "null" },
                    t.optString("rightId").takeIf { it.isNotEmpty() && it != "null" },
                    t.optDouble("overlap", 0.0),
                    t.optDouble("angle", 0.0).toFloat(),
                    t.optBoolean("soft", true),
                    t.optDouble("phaseFrom", 0.0).toFloat(),
                    t.optDouble("phaseTo", 1.0).toFloat(),
                )
            )
        }
        return p
    }

    companion object {
        fun shape(path: String): Pair<Int, Int> {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(path)
                val w =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 1920
                val h =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 1080
                val rotation =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                return if (rotation % 180 == 0) w to h else h to w
            } finally {
                r.release()
            }
        }

        fun inspect(file: File): Clip {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                val duration =
                    (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toDoubleOrNull() ?: 0.0) / 1000
                require(duration > 0) { "This file has no playable duration" }
                val video =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val audio =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                return Clip(
                    path = file.absolutePath,
                    sourceOut = duration,
                    video = video,
                    audio = audio,
                )
            } finally {
                r.release()
            }
        }
    }
}
