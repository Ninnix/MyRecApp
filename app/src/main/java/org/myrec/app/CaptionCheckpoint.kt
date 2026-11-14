package org.myrec.app

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class CaptionCheckpoint(private val file: File, private val identity: String, private val chunks: Int) {
    data class State(val completed: Int, val words: List<SubtitleWord>)

    fun load(): State = try {
        require(file.isFile && file.length() in 1..16_777_216)
        val o = JSONObject(file.readText())
        require(o.getInt("version") == 1 && o.getString("identity") == identity)
        val completed = o.getInt("completed"); require(completed in 0..chunks)
        val array = o.getJSONArray("words"); require(array.length() <= 100000)
        val words = (0 until array.length()).map { i ->
            val w = array.getJSONObject(i)
            SubtitleWord(w.getString("id"), w.getString("text"), w.getDouble("start"), w.getDouble("end"))
        }
        require(words.all { it.valid() } && words.zipWithNext().all { it.first.end <= it.second.start + .000001 })
        require(words.map { it.id }.distinct().size == words.size)
        State(completed, words)
    } catch (_: Exception) { State(0, emptyList()) }

    fun save(state: State) {
        require(state.completed in 0..chunks && state.words.size <= 100000 && state.words.all { it.valid() })
        val bytes = JSONObject().apply {
            put("version", 1); put("identity", identity); put("completed", state.completed)
            put("words", JSONArray().apply { for (word in state.words) put(JSONObject().apply {
                put("id", word.id); put("text", word.text); put("start", word.start); put("end", word.end)
            }) })
        }.toString().toByteArray()
        require(bytes.size <= 16_777_216)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}-${uid()}.partial")
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(file)) { "Cannot save transcription progress" }
        } finally { temporary.delete() }
    }
}
