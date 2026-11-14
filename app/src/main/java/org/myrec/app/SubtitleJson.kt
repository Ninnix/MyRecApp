package org.myrec.app

import org.json.JSONArray
import org.json.JSONObject

object SubtitleJson {
    fun encode(phrase: SubtitlePhrase) = JSONObject().apply {
        put("id", phrase.id); put("series", phrase.series)
        put("words", JSONArray().apply { for (word in phrase.words) put(JSONObject().apply {
            put("id", word.id); put("text", word.text); put("start", word.start); put("end", word.end)
        }) })
    }
    fun decode(o: JSONObject): SubtitlePhrase {
        val words = o.getJSONArray("words")
        require(words.length() in 1..128) { "Invalid subtitle word count" }
        return SubtitlePhrase(o.getString("series"), o.getString("id"), (0 until words.length()).map {
            val w = words.getJSONObject(it)
            SubtitleWord(w.getString("id"), w.getString("text"), w.getDouble("start"), w.getDouble("end"))
        }).also { require(it.valid()) { "Invalid subtitle words" } }
    }
}
