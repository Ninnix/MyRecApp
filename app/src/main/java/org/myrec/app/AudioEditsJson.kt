package org.myrec.app

import org.json.JSONArray
import org.json.JSONObject

object AudioEditsJson {
    private fun number(o: JSONObject, key: String): Double {
        val n = o.get(key)
        require(n is Number && n.toDouble().isFinite()) { "Invalid audio edit value" }
        return n.toDouble()
    }
    private fun fade(f: AudioEdgeFade) = JSONObject().put("seconds", f.seconds).put("elapsed", f.elapsed)
    private fun fade(o: JSONObject) = AudioEdgeFade(number(o, "seconds"), number(o, "elapsed")).also { require(it.valid()) }
    fun encode(c: Clip, o: JSONObject) {
        c.fadeIn?.let { o.put("fadeIn", fade(it)) }; c.fadeOut?.let { o.put("fadeOut", fade(it)) }
        if (c.censors.isNotEmpty()) o.put("censors", JSONArray().apply {
            for (r in c.censors) put(JSONObject().apply {
                put("id", r.id); put("from", r.from); put("to", r.to); put("phase", r.phaseOrigin)
                put("envelopeFrom", r.envelopeFrom); put("envelopeTo", r.envelopeTo)
                put("frequency", r.settings.frequency); put("level", r.settings.levelDb)
                put("waveform", r.settings.waveform.name); put("envelope", r.settings.envelope.name)
            })
        })
    }
    fun decode(c: Clip, o: JSONObject) {
        if (o.has("fadeIn")) c.fadeIn = fade(o.getJSONObject("fadeIn"))
        if (o.has("fadeOut")) c.fadeOut = fade(o.getJSONObject("fadeOut"))
        if (!o.has("censors")) return
        val array = o.getJSONArray("censors")
        require(array.length() <= 10000)
        for (i in 0 until array.length()) {
            val r = array.getJSONObject(i)
            val wave = CensorWaveform.entries.firstOrNull { it.name == r.getString("waveform") } ?: throw NewerProject()
            val envelope = CensorEnvelope.entries.firstOrNull { it.name == r.getString("envelope") } ?: throw NewerProject()
            c.censors.add(AudioCensorRegion(r.getString("id"), number(r, "from"), number(r, "to"), number(r, "phase"),
                AudioCensorSettings(number(r, "frequency"), number(r, "level"), wave, envelope), number(r, "envelopeFrom"), number(r, "envelopeTo")))
        }
    }
}
