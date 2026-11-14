package org.myrec.app

import org.json.JSONArray
import org.json.JSONObject

object TimeEffectJson {
    fun encode(phase: EffectPhase) = JSONObject().apply {
        put("origin", phase.origin); put("offset", phase.offset)
        put("clock", JSONArray().apply { phase.clock.forEach { put(JSONObject().put("source", it.source).put("elapsed", it.elapsed)) } })
    }
    fun decode(o: JSONObject): EffectPhase {
        val clock = o.getJSONArray("clock")
        require(clock.length() <= 100000)
        return EffectPhase(o.getDouble("origin"), o.getDouble("offset"), (0 until clock.length()).map {
            val point = clock.getJSONObject(it); EffectTimePoint(point.getDouble("source"), point.getDouble("elapsed"))
        }).also { require(it.valid()) { "Invalid source-time phase" } }
    }
}
