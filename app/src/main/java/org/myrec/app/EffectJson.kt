package org.myrec.app

import org.json.JSONArray
import org.json.JSONObject

object EffectJson {
    private fun number(o: JSONObject, key: String): Double {
        val value = o.get(key)
        require(value is Number && value.toDouble().isFinite()) { "Invalid numeric effect value" }
        return value.toDouble()
    }

    private fun value(value: EffectValue): JSONObject = JSONObject().apply {
        when (value) {
            is EffectValue.Number -> { put("type", "number"); put("value", value.value) }
            is EffectValue.Color -> { put("type", "color"); put("red", value.red); put("green", value.green); put("blue", value.blue) }
            is EffectValue.Toggle -> { put("type", "toggle"); put("value", value.value) }
            is EffectValue.Choice -> { put("type", "choice"); put("value", value.value) }
            is EffectValue.Media -> { put("type", "media"); put("value", value.path ?: JSONObject.NULL) }
        }
    }

    private fun value(o: JSONObject): EffectValue = when (o.getString("type")) {
        "number" -> EffectValue.Number(number(o, "value"))
        "color" -> EffectValue.Color(number(o, "red"), number(o, "green"), number(o, "blue"))
        "toggle" -> EffectValue.Toggle(o.get("value").also { require(it is Boolean) } as Boolean)
        "choice" -> EffectValue.Choice(o.get("value").also { require(it is String) } as String)
        "media" -> EffectValue.Media(if (o.isNull("value")) null else o.get("value").also { require(it is String) } as String)
        else -> throw NewerProject()
    }

    fun encodeMotion(m: EffectMotion) = JSONObject().apply {
        put("a", JSONObject(m.a)); put("b", JSONObject(m.b))
        put("duration", m.duration); put("frame", m.frame)
        put("curve", m.timing.curve.name); put("rise", m.timing.rise)
        put("release", m.timing.release); put("ramp", m.timing.ramp.name)
        put("clock", JSONArray().apply {
            for (point in m.clock) put(JSONObject().put("source", point.source).put("elapsed", point.elapsed))
        })
    }

    fun decodeMotion(effect: Effect, o: JSONObject?) {
        if (o == null) return
        fun pose(key: String): Map<String, Double> {
            val values = o.getJSONObject(key)
            return values.keys().asSequence().associateWith { number(values, it) }
        }
        fun curve(key: String) = EffectCurve.entries.firstOrNull { it.name == o.getString(key) } ?: throw NewerProject()
        val clock = o.getJSONArray("clock")
        require(clock.length() <= 100000) { "Too many animation points" }
        val m = EffectMotion(pose("a"), pose("b"),
            EffectTiming(curve("curve"), number(o, "rise"), number(o, "release"), curve("ramp")),
            number(o, "duration"), number(o, "frame"), (0 until clock.length()).map {
                val p = clock.getJSONObject(it)
                EffectTimePoint(number(p, "source"), number(p, "elapsed"))
            })
        require(m.valid(effect)) { "Invalid effect animation" }
        effect.motion = m
    }

    fun encode(effect: Effect) = JSONArray().apply {
        for (p in effect.parameters) put(JSONObject().apply {
            put("id", p.id); put("value", value(p.value))
            put("keyframes", JSONArray().apply {
                for (f in p.keyframes) put(JSONObject().put("id", f.id).put("time", f.time).put("value", value(f.value)))
            })
        })
    }

    fun decode(effect: Effect, array: JSONArray?) {
        val expected = effect.parameters.associateBy { it.id }
        if (array == null) { require(expected.isEmpty()) { "Missing effect parameters" }; return }
        require(array.length() == expected.size) { "Invalid effect parameters" }
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val id = o.getString("id")
            require(seen.add(id)) { "Duplicate effect parameter" }
            val p = expected[id] ?: throw NewerProject()
            p.value = value(o.getJSONObject("value"))
            val frames = o.getJSONArray("keyframes")
            require(frames.length() <= 100000) { "Too many effect keyframes" }
            for (j in 0 until frames.length()) {
                val f = frames.getJSONObject(j)
                p.keyframes.add(EffectKeyframe(number(f, "time"), value(f.getJSONObject("value")), f.getString("id")))
            }
            require(p.valid()) { "Invalid effect keyframes or value" }
        }
    }
}
